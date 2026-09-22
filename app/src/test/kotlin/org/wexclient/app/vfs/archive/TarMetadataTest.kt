package org.wexclient.app.vfs.archive

import org.wexclient.app.vfs.VfsManager
import java.io.ByteArrayOutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TarMetadataTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("wex_tar_meta")
    }

    @AfterTest
    fun tearDown() {
        vfs.closeAll()
        dir.toFile().deleteRecursively()
    }

    private fun header(name: ByteArray, type: Char, size: Int): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, len: Int, b: ByteArray) = System.arraycopy(b, 0, h, off, minOf(b.size, len))
        fun oct(off: Int, len: Int, v: Long) = put(off, len - 1, v.toString(8).padStart(len - 1, '0').toByteArray())
        put(0, 100, name)
        oct(100, 8, if (type == '5') 493 else 420)
        oct(108, 8, 1000)
        oct(116, 8, 1000)
        oct(124, 12, size.toLong())
        oct(136, 12, 1_700_000_000)
        h[156] = type.code.toByte()
        put(257, 6, "ustar".toByteArray() + 0)
        put(263, 2, "00".toByteArray())
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        put(148, 6, h.sumOf { it.toInt() and 0xff }.toString(8).padStart(6, '0').toByteArray())
        h[154] = 0
        h[155] = ' '.code.toByte()
        return h
    }

    private fun ByteArrayOutputStream.data(bytes: ByteArray) {
        write(bytes)
        val rem = bytes.size % 512
        if (rem != 0) write(ByteArray(512 - rem))
    }

    private fun paxRecord(key: String, value: ByteArray): ByteArray {
        val body = " $key=".toByteArray() + value + '\n'.code.toByte()
        var len = body.size + 1
        while ((len.toString().length + body.size) != len) len = len.toString().length + body.size
        return len.toString().toByteArray() + body
    }

    @Test
    fun `xattrs and exact times survive a rewrite`() {
        val out = ByteArrayOutputStream()
        // Двоичное значение xattr (security.capability и т.п.) — байты должны вернуться те же
        val cap = byteArrayOf(1, 0, 0, 2, 0x20, 0, 0, 0, 0xff.toByte(), 0, 0, 0)
        val pax = paxRecord("SCHILY.xattr.security.capability", cap) +
            paxRecord("SCHILY.xattr.user.note", "привет".toByteArray()) +
            paxRecord("mtime", "1700000000.123456789".toByteArray()) +
            paxRecord("atime", "1700000001.5".toByteArray())
        out.write(header("PaxHeaders/ping".toByteArray(), 'x', pax.size))
        out.data(pax)
        val body = "ping".toByteArray()
        out.write(header("bin/ping".toByteArray(), '0', body.size))
        out.data(body)
        out.write(header("bin/other".toByteArray(), '0', 1))
        out.data("o".toByteArray())
        out.write(ByteArray(1024))
        val tar = Files.write(dir.resolve("meta.tar"), out.toByteArray())

        val root = vfs.openArchive(tar)
        assertFalse(vfs.isReadOnlyArchive(root))
        // Правка другого файла — архив переписывается целиком
        vfs.writeBytes(root.resolve("bin/other"), "changed".toByteArray())
        val bytes = Files.readAllBytes(tar)
        assertTrue(indexOf(bytes, " SCHILY.xattr.security.capability=".toByteArray() + cap + '\n'.code.toByte()) >= 0, "двоичный xattr")
        assertTrue(indexOf(bytes, "SCHILY.xattr.user.note=привет\n".toByteArray()) >= 0, "текстовый xattr")
        assertTrue(indexOf(bytes, " mtime=1700000000.123456789\n".toByteArray()) >= 0, "точное время")
        assertTrue(indexOf(bytes, " atime=1700000001.5\n".toByteArray()) >= 0)

        // Системный tar читает результат
        val list = ProcessBuilder("tar", "-tf", tar.toString()).redirectErrorStream(true).start()
        val names = list.inputStream.readAllBytes().decodeToString()
        assertEquals(0, list.waitFor(), names)
        assertTrue("bin/ping" in names && "bin/other" in names, names)

        // Время файла поменяли — старое точное время не пишем
        val live = vfs.live(root)
        Files.setLastModifiedTime(live.resolve("bin/ping"), FileTime.fromMillis(1_600_000_000_000))
        vfs.commitArchive(live)
        val again = Files.readAllBytes(tar)
        assertTrue(indexOf(again, " mtime=1700000000.123456789\n".toByteArray()) < 0)
        assertTrue(indexOf(again, "SCHILY.xattr.user.note=привет\n".toByteArray()) >= 0)
    }

    @Test
    fun `names that are not utf8 make the archive read only`() {
        val out = ByteArrayOutputStream()
        val latin1 = "caf".toByteArray() + 0xE9.toByte() + ".txt".toByteArray()
        out.write(header(latin1, '0', 3))
        out.data("abc".toByteArray())
        out.write(ByteArray(1024))
        val tar = Files.write(dir.resolve("latin1.tar"), out.toByteArray())
        val original = Files.readAllBytes(tar)

        val root = vfs.openArchive(tar)
        assertTrue(vfs.isReadOnlyArchive(root))
        val entry = Files.list(root).use { it.toList() }.single()
        assertContentEquals("abc".toByteArray(), Files.readAllBytes(entry))
        assertFailsWith<AccessDeniedException> { vfs.writeBytes(root.resolve("new.txt"), byteArrayOf(1)) }
        vfs.closeAll()
        assertContentEquals(original, Files.readAllBytes(tar))
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
