package org.wexclient.app.signing

import com.android.apksig.ApkVerifier
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SigningKeysTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_keys")
        org.wexclient.app.TestIsolation.clearTestKeys()
        SigningKeys.reload()
    }

    @AfterTest
    fun tearDown() {
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        org.wexclient.app.TestIsolation.clearTestKeys()
        SigningKeys.reload()
    }

    private fun apk(): File {
        val f = dir.resolve("app-${System.nanoTime()}.apk").toFile()
        val base = javaClass.getResourceAsStream("/fixtures/minimal-unsigned.apk")!!.use { it.readBytes() }
        f.writeBytes(base)
        return f
    }

    private fun signerSha256(apk: File, v4: File? = null): String {
        val b = ApkVerifier.Builder(apk)
        if (v4 != null) b.setV4SignatureFile(v4)
        val r = b.build().verify()
        assertTrue(r.isVerified, "подпись не прошла: ${r.allErrors}")
        return SigningKeys.infoOf(r.signerCertificates.first()).sha256
    }

    @Test
    fun `builtin aosp testkey matches MT Manager signature`() {
        val info = assertNotNull(SigningKeys.info(SigningKeys.testKey))
        assertEquals("A4:0D:A8:0A:59:D1:70:CA:A9:50:CF:15:C1:8C:45:4D:47:A3:9B:26:98:9D:8B:64:0E:CD:74:5B:A7:1B:F5:DC", info.sha256)
        assertEquals("RSA 2048", info.algorithm)
        val out = dir.resolve("signed.apk").toFile()
        val res = ApkSignerEngine.sign(apk(), out, SigningKeys.testKey, SignSchemes())
        assertEquals(null, res.verifyProblem)
        assertEquals(info.sha256, signerSha256(out))
        assertEquals(SigningKeys.testKey, SigningKeys.findBySha256(info.sha256))
    }

    @Test
    fun `schemes are applied including v4 idsig`() {
        val v1only = dir.resolve("v1.apk").toFile()
        ApkSignerEngine.sign(apk(), v1only, SigningKeys.testKey, SignSchemes(v1 = true, v2 = false, v3 = false))
        val r1 = ApkVerifier.Builder(v1only).setMinCheckedPlatformVersion(21).build().verify()
        assertTrue(r1.isVerifiedUsingV1Scheme)
        assertFalse(r1.isVerifiedUsingV2Scheme)

        val v4 = dir.resolve("v4.apk").toFile()
        val res = ApkSignerEngine.sign(apk(), v4, SigningKeys.testKey, SignSchemes(v1 = true, v2 = true, v3 = true, v4 = true))
        val idsig = assertNotNull(res.v4File)
        assertTrue(idsig.isFile && idsig.length() > 0)
        assertTrue(res.message.contains("V1+V2+V3+V4"))

        // На месте: .idsig ложится рядом с APK
        val inPlace = apk()
        val r = ApkSignerEngine.signInPlace(inPlace, SigningKeys.testKey, SignSchemes(v4 = true))
        assertEquals(File(inPlace.path + ".idsig"), r.v4File)
        assertTrue(File(inPlace.path + ".idsig").isFile)
        assertTrue(dir.toFile().list()!!.none { it.startsWith(".wex-") }, "временные файлы остались")

        assertFailsWith<IllegalArgumentException> {
            ApkSignerEngine.sign(apk(), dir.resolve("bad.apk").toFile(), SigningKeys.testKey, SignSchemes(v1 = true, v2 = false, v3 = false, v4 = true))
        }
    }

    @Test
    fun `created key signs, is persisted, renamed and deleted`() {
        val key = SigningKeys.create(SigningKeys.NewKeyRequest(
            title = "Мой ключ", alias = "wex", password = "", commonName = "Tester, \"Inc\"",
            organization = "WEX", country = "ru", validityYears = 5, keySize = 2048
        ))
        assertTrue(key in SigningKeys.keys)
        val info = assertNotNull(SigningKeys.info(key))
        assertTrue(info.subject.contains("WEX"), info.subject)
        assertEquals("RSA 2048", info.algorithm)

        val out = dir.resolve("mine.apk").toFile()
        ApkSignerEngine.sign(apk(), out, key, SignSchemes())
        assertEquals(info.sha256, signerSha256(out))
        ZipFile(out).use { zip -> assertNotNull(zip.getEntry("META-INF/WEX.RSA"), "имя блока v1 из псевдонима") }

        SigningKeys.reload()
        val reloaded = assertNotNull(SigningKeys.byId(key.id))
        assertEquals("Мой ключ", reloaded.title)
        SigningKeys.rename(reloaded, "Переименован")
        assertEquals("Переименован", SigningKeys.byId(key.id)?.title)

        SigningPreferences.updateDefaultKey(key.id)
        assertEquals(key.id, SigningPreferences.defaultKey.id)
        SigningKeys.delete(SigningKeys.byId(key.id)!!)
        assertEquals(null, SigningKeys.byId(key.id))
        assertEquals(SigningKeys.TESTKEY_ID, SigningPreferences.defaultKey.id, "удалённый ключ — откат на testkey")
        SigningPreferences.updateDefaultKey(SigningKeys.TESTKEY_ID)
    }

    @Test
    fun `keystore and pk8 imports`() {
        // Хранилище с двумя ключами через keytool
        val ks = dir.resolve("release.jks").toFile()
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        for (alias in listOf("first", "second")) {
            val p = ProcessBuilder(
                keytool, "-genkeypair", "-noprompt", "-keystore", ks.path, "-storetype", "PKCS12",
                "-storepass", "secret123", "-keypass", "secret123", "-alias", alias,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-dname", "CN=$alias"
            ).redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            assertEquals(0, p.waitFor())
        }
        assertEquals(setOf("first", "second"), SigningKeys.aliases(ks, "secret123").toSet())
        assertFailsWith<java.io.IOException> { SigningKeys.aliases(ks, "wrong") }
        val imported = SigningKeys.importKeystore(ks, "Релиз", "secret123", "second", "")
        assertEquals("CN=second", SigningKeys.info(imported)?.subject)
        assertTrue(SigningKeys.info(SigningKeys.testKey)!!.subject.contains("EMAILADDRESS=android@android.com"))
        val out = dir.resolve("rel.apk").toFile()
        ApkSignerEngine.sign(apk(), out, imported, SignSchemes())
        assertEquals(SigningKeys.info(imported)!!.sha256, signerSha256(out))

        // Пара .pk8 + .x509.pem (сам AOSP testkey)
        val pk8 = dir.resolve("platform.pk8").toFile()
        val pem = dir.resolve("platform.x509.pem").toFile()
        javaClass.getResourceAsStream("/signing/testkey.pk8")!!.use { pk8.writeBytes(it.readBytes()) }
        javaClass.getResourceAsStream("/signing/testkey.x509.pem")!!.use { pem.writeBytes(it.readBytes()) }
        val pair = SigningKeys.importPk8(pk8, pem, "Платформа")
        assertEquals(KeyKind.PK8, pair.kind)
        assertEquals(SigningKeys.info(SigningKeys.testKey)!!.sha256, SigningKeys.info(pair)!!.sha256)
        // Пароли и ключи — только для владельца
        val perms = Files.getPosixFilePermissions(pair.storeFile!!.toPath())
        assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), perms)
    }

    @Test
    fun `apk without readable manifest is still signed`() {
        val f = dir.resolve("nomanifest.apk").toFile()
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(byteArrayOf(1, 2, 3, 4)); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(ByteArray(64)); z.closeEntry()
        }
        val res = ApkSignerEngine.signInPlace(f, SigningKeys.testKey, SignSchemes())
        assertEquals(null, res.verifyProblem, res.message)
    }
}
