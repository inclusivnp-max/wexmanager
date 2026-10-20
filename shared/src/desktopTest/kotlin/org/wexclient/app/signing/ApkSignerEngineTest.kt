package org.wexclient.app.signing

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ApkSignerEngineTest {

    @Test
    fun `output equal to input is rejected before anything is written`() {
        val dir = Files.createTempDirectory("wex_sign_test")
        try {
            val apk = dir.resolve("app.apk")
            val content = ByteArray(64) { it.toByte() }
            Files.write(apk, content)
            val link = Files.createSymbolicLink(dir.resolve("link.apk"), apk)

            assertFailsWith<IllegalArgumentException> { ApkSignerEngine.signApk(apk.toFile(), apk.toFile()) }
            assertFailsWith<IllegalArgumentException> { ApkSignerEngine.signApk(apk.toFile(), dir.resolve("./app.apk").toFile()) }
            assertFailsWith<IllegalArgumentException> { ApkSignerEngine.signApk(apk.toFile(), link.toFile()) }
            assertContentEquals(content, Files.readAllBytes(apk))
        } finally {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun `failed signing leaves an existing output file untouched`() {
        val dir = Files.createTempDirectory("wex_sign_fail")
        try {
            val broken = dir.resolve("broken.apk")
            Files.write(broken, "not a zip".toByteArray())
            val output = dir.resolve("signed.apk")
            val previous = ByteArray(128) { (it * 3).toByte() }
            Files.write(output, previous)
            assertFailsWith<Exception> {
                ApkSignerEngine.sign(broken.toFile(), output.toFile(), SigningKeys.debugKey, SignSchemes(true, true, true, v4 = true))
            }
            assertContentEquals(previous, Files.readAllBytes(output))
            // Временные файлы не остаются
            val left = Files.list(dir).use { it.map { p -> p.fileName.toString() }.toList() }.sorted()
            kotlin.test.assertEquals(listOf("broken.apk", "signed.apk"), left)
        } finally {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun `new key password must be ascii`() {
        val e = assertFailsWith<IllegalArgumentException> {
            SigningKeys.create(SigningKeys.NewKeyRequest(title = "t", alias = "a", password = "пароль123", commonName = "T"))
        }
        kotlin.test.assertTrue(e.message!!.contains("латинские"))
    }
}
