package org.wexclient.app.signing

import com.android.apksig.ApkVerifier
import org.wexclient.app.apk.ApkAnalyzer
import org.wexclient.app.vfs.VfsManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInPlaceTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_sign_inplace")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /**
     * Неподписанный APK из aapt2 (fixtures/minimal-unsigned.apk) + dex/so/raw;
     * resources.arsc пишется заданным методом.
     */
    private fun makeApk(arscMethod: Int): File {
        val base = javaClass.getResourceAsStream("/fixtures/minimal-unsigned.apk")!!.use { it.readBytes() }
        val baseDir = dir.resolve("base.apk").toFile().apply { writeBytes(base) }
        val f = dir.resolve("app.apk").toFile()
        ZipOutputStream(f.outputStream()).use { zos ->
            fun put(name: String, data: ByteArray, method: Int) {
                val e = ZipEntry(name)
                e.method = method
                if (method == ZipEntry.STORED) {
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                zos.putNextEntry(e)
                zos.write(data)
                zos.closeEntry()
            }
            ZipFile(baseDir).use { zip ->
                for (e in zip.entries()) {
                    val data = zip.getInputStream(e).readBytes()
                    val method = if (e.name == "resources.arsc") arscMethod else e.method
                    put(e.name, data, method)
                }
            }
            put("classes.dex", ByteArray(4096) { (it % 7).toByte() }, ZipEntry.DEFLATED)
            put("lib/arm64-v8a/libx.so", ByteArray(1000) { 1 }, ZipEntry.STORED)
            put("res/raw/data.txt", "hello".toByteArray(), ZipEntry.DEFLATED)
        }
        baseDir.delete()
        return f
    }

    private fun verify(f: File): ApkVerifier.Result = ApkVerifier.Builder(f).build().verify()

    @Test
    fun `apk is signed in place and compressed arsc becomes stored`() {
        val apk = makeApk(ZipEntry.DEFLATED)
        Files.setPosixFilePermissions(apk.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        assertNotNull(ApkAnalyzer.verifySignature(apk), "до подписи проверка должна падать")

        val msg = ApkSignerEngine.signInPlace(apk).message
        assertTrue(msg.contains("resources.arsc"), msg)

        assertTrue(verify(apk).isVerified, "после подписи APK валиден")
        assertNull(ApkAnalyzer.verifySignature(apk))
        ZipFile(apk).use { zip ->
            assertEquals(ZipEntry.STORED, zip.getEntry("resources.arsc").method)
            assertEquals(ZipEntry.STORED, zip.getEntry("lib/arm64-v8a/libx.so").method)
            assertEquals("hello", zip.getInputStream(zip.getEntry("res/raw/data.txt")).readBytes().decodeToString())
        }
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(apk.toPath())))
        // Временные файлы не остаются
        assertEquals(listOf("app.apk"), dir.toFile().list()!!.toList())
    }

    @Test
    fun `editing inside the apk breaks the signature and fires modified event`() {
        val apk = makeApk(ZipEntry.STORED)
        ApkSignerEngine.signInPlace(apk)
        assertNull(ApkAnalyzer.verifySignature(apk))

        val vfs = VfsManager()
        val events = mutableListOf<Path>()
        vfs.addArchiveModifiedListener { events.add(it) }
        try {
            val root = vfs.openArchive(apk.toPath())
            val inner = root.resolve("res/raw/data.txt")
            assertEquals(apk.toPath().toAbsolutePath().normalize(), vfs.topArchiveFile(inner)?.toAbsolutePath()?.normalize())
            assertNull(vfs.topArchiveFile(dir))

            vfs.writeText(inner, "patched")
            assertEquals(1, events.size)
            assertEquals(apk.toPath().toAbsolutePath().normalize(), events[0].toAbsolutePath().normalize())

            // Правка resources.arsc через zipfs (метод может смениться на сжатый — подпись это исправит)
            val arsc = vfs.openArchive(apk.toPath()).resolve("resources.arsc")
            vfs.writeBytes(arsc, vfs.readBytes(arsc))
            assertEquals(2, events.size)
        } finally {
            vfs.closeAll()
        }

        val problem = ApkAnalyzer.verifySignature(apk)
        assertNotNull(problem)
        assertTrue(problem.contains("изменён") || problem.contains("изменено"), problem)

        // Подпись на месте снова делает APK валидным, resources.arsc — без сжатия
        ApkSignerEngine.signInPlace(apk)
        assertTrue(verify(apk).isVerified)
        ZipFile(apk).use { zip ->
            assertEquals(ZipEntry.STORED, zip.getEntry("resources.arsc").method)
            assertEquals("patched", zip.getInputStream(zip.getEntry("res/raw/data.txt")).readBytes().decodeToString())
        }
    }
}
