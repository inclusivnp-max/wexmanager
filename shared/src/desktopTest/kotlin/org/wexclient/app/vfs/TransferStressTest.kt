package org.wexclient.app.vfs

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Случайные копирования и переносы с конфликтами и отменой: ничего не теряется,
 * а после Ctrl+Z обе папки — ровно как были.
 */
class TransferStressTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()
    private val home: Path get() = Paths.get(System.getProperty("user.home"))

    @BeforeTest
    fun setUp() {
        Files.createDirectories(home)
        dir = Files.createTempDirectory(home, "wex_stress")
        Trash.empty()
    }

    @AfterTest
    fun tearDown() {
        Trash.empty()
        vfs.closeAll()
        org.wexclient.app.util.FileTree.delete(dir)
    }

    private val names = listOf("a.txt", "b", "Папка", "with space", "dup.log", "x\ny", "ÿ.bin", ".hidden", "b (2)", "c.tar")

    private fun randomTree(root: Path, rnd: Random, depth: Int, links: Boolean = true) {
        Files.createDirectories(root)
        repeat(1 + rnd.nextInt(4)) {
            val name = names[rnd.nextInt(names.size)]
            val p = root.resolve(name)
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return@repeat
            when (rnd.nextInt(if (depth > 0) 4 else 3)) {
                0, 1 -> Files.writeString(p, "content-$name-${rnd.nextInt(1000)}")
                2 -> if (links) Files.createSymbolicLink(p, Paths.get("../target-${rnd.nextInt(3)}")) else Files.writeString(p, "plain-$name")
                else -> randomTree(p, rnd, depth - 1, links)
            }
        }
    }

    /** Снимок дерева: путь → содержимое / ссылка / папка. */
    private fun snapshot(root: Path): Map<String, String> {
        if (!Files.exists(root)) return emptyMap()
        val out = sortedMapOf<String, String>()
        Files.walk(root).use { s ->
            s.forEach { p ->
                if (p == root) return@forEach
                val rel = root.relativize(p).toString()
                out[rel.trimEnd('/')] = when {
                    Files.isSymbolicLink(p) -> "link:" + Files.readSymbolicLink(p)
                    Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> "dir"
                    else -> "file:" + Files.readString(p)
                }
            }
        }
        return out
    }

    @Test
    fun `random transfers are undone exactly`() {
        val rnd = Random(System.getenv("WEX_STRESS_SEED")?.toLongOrNull() ?: 20260917)
        // Отмена копирования убирает копии в корзину (так задумано) — только они там и должны остаться
        val undoneCopies = HashSet<Path>()
        repeat(System.getenv("WEX_STRESS_ROUNDS")?.toIntOrNull() ?: 60) { round ->
            val base = Files.createDirectories(dir.resolve("r$round"))
            val src = base.resolve("src")
            val dst = base.resolve("dst")
            randomTree(src, rnd, 2)
            randomTree(dst, rnd, 1)
            // Иногда цель — внутри источника (перенос туда должен быть отклонён целиком)
            val sources = Files.list(src).use { it.toList() }.sortedBy { it.toString() }.filter { rnd.nextInt(3) != 0 }
            if (sources.isEmpty()) return@repeat
            val move = rnd.nextBoolean()
            val policy = ConflictPolicy.entries[rnd.nextInt(ConflictPolicy.entries.size)]
            val srcBefore = snapshot(src)
            val dstBefore = snapshot(dst)

            val r = FileTransfers.transfer(vfs, sources, dst, move, policy)
            assertNull(r.error, "раунд $round: ${r.error}")
            // Всё, что уехало или скопировано, лежит в цели с тем же содержимым
            for ((from, to) in r.done) {
                val expected = srcBefore[src.relativize(from).toString()]
                val actual = snapshot(to.parent).let { snap -> snap[to.fileName.toString()] }
                assertEquals(expected, actual, "раунд $round: $from → $to")
            }
            if (!move) assertEquals(srcBefore, snapshot(src), "раунд $round: копирование не трогает источник")

            val op = if (move) FileOp.Moved(r.done, r.replaced, r.archiveReplaced)
            else FileOp.Copied(r.done.map { it.second }, r.replaced, r.archiveReplaced)
            if (!move) undoneCopies += r.done.map { it.second.toAbsolutePath().normalize() }
            FileUndo.revert(op, vfs)
            assertEquals(srcBefore, snapshot(src), "раунд $round (move=$move, $policy): источник после отмены")
            assertEquals(dstBefore, snapshot(dst), "раунд $round (move=$move, $policy): цель после отмены")
        }
        // Всё заменённое вернулось на место: в корзине — только убранные копии
        val leftovers = Trash.list().map { it.originalPath.toAbsolutePath().normalize() }.filter { it !in undoneCopies }
        assertTrue(leftovers.isEmpty(), "в корзине остались не копии: $leftovers")
    }

    @Test
    fun `random transfers into a zip are undone exactly`() {
        val rnd = Random(System.getenv("WEX_STRESS_SEED")?.toLongOrNull() ?: 99)
        repeat((System.getenv("WEX_STRESS_ROUNDS")?.toIntOrNull() ?: 60) / 3) { round ->
            val base = Files.createDirectories(dir.resolve("z$round"))
            val src = base.resolve("src")
            randomTree(src, rnd, 2, links = false)
            // Архив с похожим содержимым — чтобы были конфликты
            val seed = base.resolve("seed")
            randomTree(seed, rnd, 1, links = false)
            val zip = base.resolve("box.zip")
            java.util.zip.ZipOutputStream(Files.newOutputStream(zip)).use { }
            val root = vfs.openArchive(zip)
            FileTransfers.transfer(vfs, Files.list(seed).use { it.toList() }, root, move = false).error?.let { throw it }
            val sources = Files.list(src).use { it.toList() }.sortedBy { it.toString() }.filter { rnd.nextInt(3) != 0 }
            if (sources.isEmpty()) return@repeat
            val move = rnd.nextBoolean()
            val policy = ConflictPolicy.entries[rnd.nextInt(ConflictPolicy.entries.size)]
            val srcBefore = snapshot(src)
            val zipBefore = snapshot(vfs.live(root))

            val r = FileTransfers.transfer(vfs, sources, vfs.live(root), move, policy)
            assertNull(r.error, "раунд $round: ${r.error}")
            if (!move) assertEquals(srcBefore, snapshot(src))
            val op = if (move) FileOp.Moved(r.done, r.replaced, r.archiveReplaced)
            else FileOp.Copied(r.done.map { it.second }, r.replaced, r.archiveReplaced)
            FileUndo.revert(op, vfs)
            assertEquals(srcBefore, snapshot(src), "раунд $round (move=$move, $policy): источник после отмены")
            assertEquals(zipBefore, snapshot(vfs.live(root)), "раунд $round (move=$move, $policy): архив после отмены")
        }
    }

    private fun rounds(default: Int) = (System.getenv("WEX_STRESS_ROUNDS")?.toIntOrNull() ?: default * 3) / 3

    @Test
    fun `random moves out of archives and into tar are undone exactly`() {
        val rnd = Random(System.getenv("WEX_STRESS_SEED")?.toLongOrNull() ?: 5)
        repeat(rounds(60)) { round ->
            val base = Files.createDirectories(dir.resolve("t$round"))
            val seed = base.resolve("seed")
            randomTree(seed, rnd, 2, links = false)
            val useTar = rnd.nextBoolean()
            val archive = base.resolve(if (useTar) "box.tar" else "box.zip")
            if (useTar) {
                val code = ProcessBuilder("tar", "-cf", archive.toString(), "--files-from", "/dev/null").start().waitFor()
                assertEquals(0, code)
            } else {
                java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { }
            }
            val root = vfs.openArchive(archive)
            FileTransfers.transfer(vfs, Files.list(seed).use { it.toList() }, root, move = false).error?.let { throw it }
            val disk = base.resolve("disk")
            randomTree(disk, rnd, 1, links = false)

            // Из архива на диск или с диска в архив
            val outOf = rnd.nextBoolean()
            val from = if (outOf) vfs.live(root) else disk
            val to = if (outOf) disk else vfs.live(root)
            val sources = Files.list(from).use { it.toList() }.sortedBy { it.toString() }.filter { rnd.nextInt(3) != 0 }
            if (sources.isEmpty()) return@repeat
            val move = rnd.nextBoolean()
            val policy = ConflictPolicy.entries[rnd.nextInt(ConflictPolicy.entries.size)]
            val diskBefore = snapshot(disk)
            val archiveBefore = snapshot(vfs.live(root))

            val r = FileTransfers.transfer(vfs, sources, to, move, policy)
            assertNull(r.error, "раунд $round ($archive, outOf=$outOf): ${r.error}")
            val op = if (move) FileOp.Moved(r.done, r.replaced, r.archiveReplaced)
            else FileOp.Copied(r.done.map { it.second }, r.replaced, r.archiveReplaced)
            FileUndo.revert(op, vfs)
            val where = "раунд $round (${archive.fileName}, outOf=$outOf, move=$move, $policy)"
            assertEquals(diskBefore, snapshot(disk), "$where: диск после отмены")
            assertEquals(archiveBefore, snapshot(vfs.live(root)), "$where: архив после отмены")
        }
    }

    @Test
    fun `random renames and trash are undone exactly`() {
        val rnd = Random(System.getenv("WEX_STRESS_SEED")?.toLongOrNull() ?: 17)
        repeat(rounds(60)) { round ->
            val base = Files.createDirectories(dir.resolve("n$round"))
            randomTree(base, rnd, 1)
            val before = snapshot(base)
            val items = Files.list(base).use { it.toList() }.sortedBy { it.toString() }
            if (items.size < 2) return@repeat
            // Перестановка имён (обмены и циклы) — пакетное переименование
            val names = items.map { it.fileName.toString() }.shuffled(rnd)
            val plan = items.zip(names).filter { (p, n) -> p.fileName.toString() != n }
            if (plan.isNotEmpty()) {
                assertNull(BatchRenamer.problem(plan), "раунд $round")
                val renamed = BatchRenamer.apply(vfs, plan)
                FileUndo.revert(FileOp.BatchRenamed(renamed), vfs)
                assertEquals(before, snapshot(base), "раунд $round: имена после отмены")
            }
            // В корзину и обратно
            val victims = items.filter { rnd.nextBoolean() }
            if (victims.isNotEmpty()) {
                val trashed = victims.map { Trash.trash(it) }
                FileUndo.revert(FileOp.Trashed(trashed), vfs)
                assertEquals(before, snapshot(base), "раунд $round: после возврата из корзины")
            }
        }
        assertTrue(Trash.list().isEmpty())
    }

    @Test
    fun `random transfers between nested and different archives are undone exactly`() {
        val rnd = Random(System.getenv("WEX_STRESS_SEED")?.toLongOrNull() ?: 31)
        repeat(rounds(45)) { round ->
            val base = Files.createDirectories(dir.resolve("m$round"))
            // outer.zip с inner.zip внутри и отдельный box.tar
            val outer = base.resolve("outer.zip")
            java.util.zip.ZipOutputStream(Files.newOutputStream(outer)).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("keep.txt"))
                z.write("outer keeps".toByteArray())
                z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("inner.zip"))
                val innerBytes = java.io.ByteArrayOutputStream().also { java.util.zip.ZipOutputStream(it).use { } }.toByteArray()
                z.write(innerBytes)
                z.closeEntry()
            }
            val tar = base.resolve("box.tar")
            assertEquals(0, ProcessBuilder("tar", "-cf", tar.toString(), "--files-from", "/dev/null").start().waitFor())
            val outerRoot = vfs.openArchive(outer)
            val innerRoot = vfs.openArchive(outerRoot.resolve("inner.zip"))
            val tarRoot = vfs.openArchive(tar)
            val seedA = base.resolve("seedA").also { randomTree(it, rnd, 1, links = false) }
            val seedB = base.resolve("seedB").also { randomTree(it, rnd, 1, links = false) }
            FileTransfers.transfer(vfs, Files.list(seedA).use { it.toList() }, vfs.live(innerRoot), move = false).error?.let { throw it }
            FileTransfers.transfer(vfs, Files.list(seedB).use { it.toList() }, vfs.live(tarRoot), move = false).error?.let { throw it }

            val toInner = rnd.nextBoolean()
            val from = vfs.live(if (toInner) tarRoot else innerRoot)
            val to = vfs.live(if (toInner) innerRoot else tarRoot)
            val sources = Files.list(from).use { it.toList() }.sortedBy { it.toString() }.filter { rnd.nextInt(3) != 0 }
            if (sources.isEmpty()) return@repeat
            val move = rnd.nextBoolean()
            val policy = ConflictPolicy.entries[rnd.nextInt(ConflictPolicy.entries.size)]
            val innerBefore = snapshot(vfs.live(innerRoot))
            val tarBefore = snapshot(vfs.live(tarRoot))

            val r = FileTransfers.transfer(vfs, sources, to, move, policy)
            assertNull(r.error, "раунд $round (toInner=$toInner): ${r.error}")
            val op = if (move) FileOp.Moved(r.done, r.replaced, r.archiveReplaced)
            else FileOp.Copied(r.done.map { it.second }, r.replaced, r.archiveReplaced)
            FileUndo.revert(op, vfs)
            val where = "раунд $round (toInner=$toInner, move=$move, $policy)"
            assertEquals(innerBefore, snapshot(vfs.live(innerRoot)), "$where: вложенный архив после отмены")
            assertEquals(tarBefore, snapshot(vfs.live(tarRoot)), "$where: tar после отмены")
            assertEquals("outer keeps", Files.readString(vfs.live(outerRoot).resolve("keep.txt")), where)
        }
    }

    @Test
    fun `random extractions are undone exactly`() {
        val rnd = Random(System.getenv("WEX_STRESS_SEED")?.toLongOrNull() ?: 53)
        repeat(rounds(45)) { round ->
            val base = Files.createDirectories(dir.resolve("e$round"))
            val content = base.resolve("content").also { randomTree(it, rnd, 2, links = false) }
            val useTar = rnd.nextBoolean()
            val archive = base.resolve(if (useTar) "pack.tar.gz" else "pack.zip")
            if (useTar) {
                val names = Files.list(content).use { it.map { p -> p.fileName.toString() }.toList() }
                if (names.isEmpty()) return@repeat
                val cmd = listOf("tar", "-czf", archive.toString(), "-C", content.toString(), "--") + names
                assertEquals(0, ProcessBuilder(cmd).start().waitFor())
            } else {
                vfs.compressToNewFile(Files.list(content).use { it.toList() }, archive, "zip")
            }
            // Назначение — папка на диске или внутри другого архива
            val intoArchive = rnd.nextBoolean()
            val dest = if (intoArchive) {
                val box = base.resolve("box.zip")
                java.util.zip.ZipOutputStream(Files.newOutputStream(box)).use { }
                val root = vfs.openArchive(box)
                val seed = base.resolve("seed").also { randomTree(it, rnd, 1, links = false) }
                FileTransfers.transfer(vfs, Files.list(seed).use { it.toList() }, root, move = false).error?.let { throw it }
                root
            } else {
                base.resolve("dest").also { randomTree(it, rnd, 1, links = false) }
            }
            val before = snapshot(vfs.live(dest))
            val policy = ConflictPolicy.entries[rnd.nextInt(ConflictPolicy.entries.size)]
            val prepared = SafeExtract.prepare(vfs, archive, vfs.live(dest))
            val r = SafeExtract.finish(vfs, prepared, policy)
            FileUndo.revert(FileOp.Copied(r.created, r.replaced, r.archiveReplaced), vfs)
            assertEquals(before, snapshot(vfs.live(dest)), "раунд $round (${archive.fileName}, intoArchive=$intoArchive, $policy)")
        }
    }
}
