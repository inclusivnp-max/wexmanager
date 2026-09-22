package org.wexclient.app.signing

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeyImportTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_import")
        org.wexclient.app.TestIsolation.clearTestKeys()
        SigningKeys.reload()
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
        org.wexclient.app.TestIsolation.clearTestKeys()
        SigningKeys.reload()
    }

    private fun keytool(vararg args: String) {
        val tool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val p = ProcessBuilder(listOf(tool) + args).redirectErrorStream(true).start()
        val out = p.inputStream.readAllBytes().decodeToString()
        assertEquals(0, p.waitFor(), out)
    }

    @Test
    fun `keystore is inspected before import`() {
        // JKS с отдельным паролем ключа, как у старых релизных ключей
        val jks = dir.resolve("release.jks").toFile()
        keytool(
            "-genkeypair", "-noprompt", "-keystore", jks.path, "-storetype", "JKS",
            "-storepass", "store123", "-keypass", "key12345", "-alias", "upload",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-dname", "CN=Uploader, O=WEX"
        )
        assertEquals(listOf("upload"), SigningKeys.aliases(jks, "store123"))
        val wrongStore = assertFailsWith<IOException> { SigningKeys.aliases(jks, "nope") }
        assertTrue(wrongStore.message!!.contains("пароль", ignoreCase = true), wrongStore.message)

        // Без пароля ключа (берётся пароль хранилища) — понятная ошибка про пароль ключа
        val wrongKey = assertFailsWith<IOException> { SigningKeys.inspectKeystore(jks, "store123", "upload", "") }
        assertTrue(wrongKey.message!!.contains("пароль ключа", ignoreCase = true), wrongKey.message)
        assertFailsWith<IOException> { SigningKeys.inspectKeystore(jks, "store123", "missing", "key12345") }

        val info = SigningKeys.inspectKeystore(jks, "store123", "upload", "key12345")
        assertEquals("Uploader", info.commonName)
        assertEquals(null, SigningKeys.findBySha256(info.sha256))

        val key = SigningKeys.importKeystore(jks, "Загрузочный", "store123", "upload", "key12345")
        assertEquals(key.id, SigningKeys.findBySha256(info.sha256)?.id, "после импорта ключ находится по отпечатку")
        assertTrue(SigningKeys.keys.any { it.id == key.id }, "ключ есть в списке для меню «Подписать»")
        assertEquals(info.sha256, SigningKeys.info(key)?.sha256)
    }

    @Test
    fun `pk8 pair must match its certificate`() {
        val pk8 = dir.resolve("testkey.pk8").toFile()
        val pem = dir.resolve("testkey.x509.pem").toFile()
        javaClass.getResourceAsStream("/signing/testkey.pk8")!!.use { pk8.writeBytes(it.readBytes()) }
        javaClass.getResourceAsStream("/signing/testkey.x509.pem")!!.use { pem.writeBytes(it.readBytes()) }
        assertEquals(SigningKeys.info(SigningKeys.testKey)!!.sha256, SigningKeys.inspectPk8(pk8, pem).sha256)

        // Сертификат от другого ключа
        val other = dir.resolve("other.p12").toFile()
        keytool(
            "-genkeypair", "-noprompt", "-keystore", other.path, "-storetype", "PKCS12",
            "-storepass", "secret1", "-alias", "o", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "30", "-dname", "CN=Other"
        )
        val otherCert = dir.resolve("other.x509.pem").toFile()
        KeyExporter.export(
            SigningKey("t-other", "t", KeyKind.KEYSTORE, storeFile = other, alias = "o", storePassword = "secret1", keyPassword = "secret1"),
            KeyExportRequest(KeyExportFormat.CERTIFICATE, otherCert)
        )
        val mismatch = assertFailsWith<IOException> { SigningKeys.inspectPk8(pk8, otherCert) }
        assertEquals("Закрытый ключ не подходит к сертификату", mismatch.message)
        assertFailsWith<IOException> { SigningKeys.importPk8(pk8, otherCert, "плохая пара") }
        assertTrue(SigningKeys.keys.none { it.title == "плохая пара" })
    }
}
