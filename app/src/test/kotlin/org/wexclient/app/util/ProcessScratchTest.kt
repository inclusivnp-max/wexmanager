package org.wexclient.app.util

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProcessScratchTest {
    private val root = Files.createTempDirectory("wex_scratch_test")

    @BeforeTest
    fun setUp() {
        Files.createDirectories(root)
    }

    @AfterTest
    fun tearDown() {
        FileTree.delete(root)
    }

    @Test
    fun `leftovers of finished processes go, live ones and foreign files stay`() {
        // Завершившийся процесс: такого pid с этим временем старта нет
        val dead = Files.createDirectories(root.resolve("999999999-12345"))
        Files.writeString(dead.resolve("big.jar"), "x")
        // Другая работающая копия — здесь её изображает родительский процесс сборки
        val parent = ProcessHandle.current().parent().orElseThrow()
        val live = Files.createDirectories(root.resolve(ProcessScratch.processKey(parent)))
        // Тот же pid, но процесс другой (pid переиспользован): чужая папка — остаток
        val reused = Files.createDirectories(root.resolve("${parent.pid()}-1"))
        val foreign = Files.writeString(root.resolve("notes.txt"), "не наше")

        val scratch = ProcessScratch { root }
        val mine = scratch.dir
        assertEquals(ProcessScratch.processKey(ProcessHandle.current()), mine.fileName.toString())
        assertFalse(Files.exists(dead))
        assertFalse(Files.exists(reused))
        assertTrue(Files.isDirectory(live))
        assertTrue(Files.exists(foreign))

        val a = scratch.newDir("cfr-")
        val b = scratch.newFile("disasm-", ".so")
        assertNotEquals(a, b)
        assertEquals(mine, a.parent)
        assertEquals(mine, b.parent)
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(mine)))
    }

    @Test
    fun `old work dirs of previous versions are removed from tmp`() {
        val tmp = Files.createDirectories(root.resolve("tmp"))
        val dayAgo = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 30L * 3600_000)
        fun aged(p: java.nio.file.Path) = p.also {
            Files.getFileAttributeView(it, java.nio.file.attribute.BasicFileAttributeView::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                .setTimes(dayAgo, null, null)
        }
        val oldCfr = Files.createDirectories(tmp.resolve("wex_cfr_123"))
        Files.writeString(oldCfr.resolve("classes_0.jar"), "x")
        aged(oldCfr)
        val oldFile = aged(Files.writeString(tmp.resolve("wex_sign_1.apk"), "x"))
        val fresh = Files.createDirectories(tmp.resolve("wex_dexjar_9"))
        val notOurs = aged(Files.createDirectories(tmp.resolve("dex_other")))
        // Ссылка с нашим именем — не идём по ней и не трогаем цель
        val target = Files.createDirectories(root.resolve("precious"))
        Files.writeString(target.resolve("keep.txt"), "keep")
        val link = aged(Files.createSymbolicLink(tmp.resolve("wex-key-evil"), target))

        ProcessScratch.removeLegacyTemp(tmp)

        assertFalse(Files.exists(oldCfr))
        assertFalse(Files.exists(oldFile))
        assertTrue(Files.isDirectory(fresh))
        assertTrue(Files.isDirectory(notOurs))
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("keep", Files.readString(target.resolve("keep.txt")))
    }
}
