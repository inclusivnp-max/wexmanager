package org.wexclient.app.signing

import com.android.apksig.ApkVerifier
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyExporterTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_export")
        org.wexclient.app.TestIsolation.clearTestKeys()
        SigningKeys.reload()
    }

    @AfterTest
    fun tearDown() {
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        org.wexclient.app.TestIsolation.clearTestKeys()
        SigningKeys.reload()
    }

    private val testSha: String get() = assertNotNull(SigningKeys.info(SigningKeys.testKey)).sha256

    private fun perms(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    private fun signedSha(key: SigningKey): String {
        val apk = dir.resolve("app-${System.nanoTime()}.apk").toFile()
        javaClass.getResourceAsStream("/fixtures/minimal-unsigned.apk")!!.use { apk.writeBytes(it.readBytes()) }
        ApkSignerEngine.signInPlace(apk, key, SignSchemes())
        val r = ApkVerifier.Builder(apk).build().verify()
        assertTrue(r.isVerified, "подпись не прошла: ${r.allErrors}")
        return SigningKeys.infoOf(r.signerCertificates.first()).sha256
    }

    // Ключ из экспортированного хранилища — без импорта в настройки
    private fun keystoreKey(file: File, alias: String, storePassword: String, keyPassword: String) = SigningKey(
        id = "t-" + UUID.randomUUID(), title = "t", kind = KeyKind.KEYSTORE,
        storeFile = file, alias = alias, storePassword = storePassword, keyPassword = keyPassword
    )

    @Test
    fun `jks export keeps separate key password and signs identically`() {
        val target = dir.resolve("release").toFile()
        val files = KeyExporter.export(
            SigningKeys.testKey,
            KeyExportRequest(KeyExportFormat.JKS, target, alias = "rel", storePassword = "storepw1", keyPassword = "keypw123")
        )
        val jks = dir.resolve("release.jks").toFile()
        assertEquals(listOf(jks), files)
        assertEquals("rw-------", perms(jks))
        assertEquals("JKS", KeyStore.getInstance(jks, "storepw1".toCharArray()).type)
        assertEquals(listOf("rel"), SigningKeys.aliases(jks, "storepw1"))
        assertFailsWith<java.io.IOException> { SigningKeys.load(keystoreKey(jks, "rel", "storepw1", "storepw1")) }

        val key = keystoreKey(jks, "rel", "storepw1", "keypw123")
        assertEquals(testSha, SigningKeys.info(key)?.sha256)
        assertEquals(testSha, signedSha(key))
        assertTrue(dir.toFile().list()!!.none { it.startsWith(".wex-export-") }, "временные файлы остались")
    }

    @Test
    fun `pkcs12 export of created key with generated password`() {
        val created = SigningKeys.create(SigningKeys.NewKeyRequest(title = "Мой", alias = "mine", password = "", commonName = "Me"))
        val sha = assertNotNull(SigningKeys.info(created)).sha256
        val p12 = dir.resolve("Мой ключ.p12").toFile()
        // Пароль ключа для PKCS12 игнорируется — берётся пароль хранилища
        KeyExporter.export(created, KeyExportRequest(KeyExportFormat.PKCS12, p12, "upload", "newpass1", keyPassword = "другой1"))
        assertEquals("PKCS12", KeyStore.getInstance(p12, "newpass1".toCharArray()).type)
        val imported = SigningKeys.importKeystore(p12, "Снова", "newpass1", "upload", "")
        assertEquals(sha, SigningKeys.info(imported)?.sha256)
        assertEquals(sha, signedSha(imported))
    }

    @Test
    fun `pk8 pair, single pem and certificate`() {
        val pk8 = dir.resolve("platform.pk8").toFile()
        val pair = KeyExporter.export(SigningKeys.testKey, KeyExportRequest(KeyExportFormat.PK8_PEM, pk8))
        val pem = dir.resolve("platform.x509.pem").toFile()
        assertEquals(listOf(pk8, pem), pair)
        assertEquals("rw-------", perms(pk8))
        assertEquals("rw-r--r--", perms(pem))
        assertEquals(testSha, SigningKeys.info(SigningKeys.importPk8(pk8, pem, "Пара"))?.sha256)

        val single = dir.resolve("key.pem").toFile()
        KeyExporter.export(SigningKeys.testKey, KeyExportRequest(KeyExportFormat.PEM, single))
        val text = single.readText()
        assertTrue(text.startsWith("-----BEGIN PRIVATE KEY-----") && text.contains("-----BEGIN CERTIFICATE-----"))
        assertTrue(SigningKeys.pemHasCertificate(single))
        assertFalse(SigningKeys.pemHasCertificate(pk8))
        val fromSingle = SigningKeys.importPk8(single, single, "")
        assertEquals("key", fromSingle.title)
        assertEquals(testSha, SigningKeys.info(fromSingle)?.sha256)
        // В настройках — нормализованная пара: DER PKCS#8 + только сертификаты
        assertFalse(fromSingle.storeFile!!.readBytes().decodeToString().contains("BEGIN"))
        assertFalse(fromSingle.certFile!!.readText().contains("PRIVATE KEY"))
        assertEquals(testSha, signedSha(fromSingle))

        val cert = dir.resolve("only").toFile()
        val certFiles = KeyExporter.export(SigningKeys.testKey, KeyExportRequest(KeyExportFormat.CERTIFICATE, cert))
        assertEquals(listOf(dir.resolve("only.x509.pem").toFile()), certFiles)
        val certText = certFiles[0].readText()
        assertTrue(certText.contains("BEGIN CERTIFICATE") && !certText.contains("PRIVATE"))
        assertEquals("rw-r--r--", perms(certFiles[0]))
    }

    @Test
    fun `rsa pkcs1 pem is imported`() {
        // PKCS#1 из PKCS#8 testkey: SEQUENCE { INTEGER 0, SEQUENCE alg, OCTET STRING pkcs1 }
        val pkcs8 = javaClass.getResourceAsStream("/signing/testkey.pk8")!!.use { it.readBytes() }
        val pkcs1 = extractOctetString(pkcs8)
        assertTrue(SigningKeys.rsaPkcs1ToPkcs8(pkcs1).contentEquals(pkcs8), "обёртка PKCS#1 → PKCS#8 должна дать тот же DER")
        val keyFile = dir.resolve("legacy.key").toFile()
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs1)
        keyFile.writeText("Bag Attributes\n    friendlyName: legacy\n-----BEGIN RSA PRIVATE KEY-----\n$b64\n-----END RSA PRIVATE KEY-----\n")
        val cert = dir.resolve("legacy.x509.pem").toFile()
        javaClass.getResourceAsStream("/signing/testkey.x509.pem")!!.use { cert.writeBytes(it.readBytes()) }
        assertEquals(testSha, SigningKeys.info(SigningKeys.importPk8(keyFile, cert, "legacy"))?.sha256)

        val encrypted = dir.resolve("enc.pem").toFile()
        encrypted.writeText("-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n")
        val e = assertFailsWith<java.io.IOException> { SigningKeys.importPk8(encrypted, cert, "enc") }
        assertTrue(e.message!!.contains("Зашифрованный"), e.message)
    }

    private fun extractOctetString(der: ByteArray): ByteArray {
        var i = 0
        fun readLen(): Int {
            val b = der[i++].toInt() and 0xff
            if (b < 0x80) return b
            var n = 0
            repeat(b and 0x7f) { n = (n shl 8) or (der[i++].toInt() and 0xff) }
            return n
        }
        fun skip(tag: Int) {
            check(der[i++] == tag.toByte())
            val len = readLen() // до «i +=»: readLen сдвигает i
            i += len
        }
        check(der[i++] == 0x30.toByte()); readLen() // PrivateKeyInfo
        skip(0x02) // version
        skip(0x30) // algorithm
        check(der[i++] == 0x04.toByte())
        val len = readLen()
        return der.copyOfRange(i, i + len)
    }

    @Test
    fun `targets, validation and gradle snippet`() {
        val base = dir.toFile()
        assertEquals(File(base, "a.jks"), KeyExporter.normalizeTarget(KeyExportFormat.JKS, File(base, "a")))
        assertEquals(File(base, "a.keystore"), KeyExporter.normalizeTarget(KeyExportFormat.JKS, File(base, "a.keystore")))
        assertEquals(File(base, "a.KEYSTORE"), KeyExporter.normalizeTarget(KeyExportFormat.PKCS12, File(base, "a.KEYSTORE")))
        assertEquals(File(base, "a.x509.pem"), KeyExporter.normalizeTarget(KeyExportFormat.CERTIFICATE, File(base, "a")))
        assertEquals(File(base, ".jks.jks"), KeyExporter.normalizeTarget(KeyExportFormat.JKS, File(base, ".jks")))
        assertEquals(
            listOf(File(base, "p.PK8"), File(base, "p.x509.pem")),
            KeyExporter.outputs(KeyExportFormat.PK8_PEM, File(base, "p.PK8"))
        )
        assertEquals("testkey.jks", KeyExporter.defaultFileName(SigningKeys.testKey, KeyExportFormat.JKS))
        assertEquals("debug.x509.pem", KeyExporter.defaultFileName(SigningKeys.debugKey, KeyExportFormat.CERTIFICATE))
        assertEquals(
            "Релиз_ v2.p12",
            KeyExporter.defaultFileName(SigningKeys.testKey.copy(kind = KeyKind.KEYSTORE, title = "Релиз/ v2"), KeyExportFormat.PKCS12)
        )

        val ok = KeyExportRequest(KeyExportFormat.JKS, File(base, "x.jks"), "a", "123456", "654321")
        assertNull(KeyExporter.validate(ok))
        assertEquals("Укажите псевдоним ключа", KeyExporter.validate(ok.copy(alias = " ")))
        assertTrue(KeyExporter.validate(ok.copy(storePassword = "12345"))!!.contains("хранилища"))
        assertTrue(KeyExporter.validate(ok.copy(keyPassword = "1"))!!.contains("ключа"))
        assertNull(KeyExporter.validate(ok.copy(format = KeyExportFormat.PKCS12, keyPassword = "1")), "у PKCS12 пароль ключа не используется")
        assertNull(KeyExporter.validate(KeyExportRequest(KeyExportFormat.PEM, File(base, "k.pem"))))
        assertTrue(KeyExporter.validate(KeyExportRequest(KeyExportFormat.PEM, File(base, "нет/k.pem")))!!.startsWith("Папка не существует"))
        assertFailsWith<IllegalArgumentException> {
            KeyExporter.export(SigningKeys.testKey, ok.copy(storePassword = "short"))
        }
        assertFalse(File(base, "x.jks").exists())

        val snippet = KeyExporter.gradleSigningConfig(ok.copy(storePassword = "p\"a\$s\\1", target = File(base, "x")))
        assertTrue(snippet.contains("storeFile = file(\"${File(base, "x.jks").absolutePath}\")"), snippet)
        assertTrue(snippet.contains("storePassword = \"p\\\"a\\\$s\\\\1\""), snippet)
        assertTrue(snippet.contains("keyAlias = \"a\"") && snippet.contains("keyPassword = \"654321\""), snippet)
    }

    @Test
    fun `schemes preference rejects invalid combination`() {
        val before = SigningPreferences.schemes
        try {
            assertFalse(SigningPreferences.updateSchemes(SignSchemes(v1 = false, v2 = false, v3 = false)))
            assertFalse(SigningPreferences.updateSchemes(SignSchemes(v1 = true, v2 = false, v3 = false, v4 = true)))
            assertEquals(before, SigningPreferences.schemes)
            assertTrue(SigningPreferences.updateSchemes(SignSchemes(v1 = true, v2 = true, v3 = false, v4 = true)))
            assertEquals("V1+V2+V4", SigningPreferences.schemes.label)
        } finally {
            SigningPreferences.updateSchemes(before)
        }
    }
}
