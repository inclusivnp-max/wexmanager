package org.wexclient.app.vfs

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrashAndTransfersTest {
    private lateinit var dir: Path
    private val vfs = VfsManager()
    private val home: Path get() = Paths.get(System.getProperty("user.home"))

    @BeforeTest
    fun setUp() {
        Files.createDirectories(home)
        // На том же разделе, что и домашняя корзина теста
        dir = Files.createTempDirectory(home, "wex_trash")
        Trash.empty()
    }

    @AfterTest
    fun tearDown() {
        Trash.empty()
        vfs.closeAll()
        dir.toFile().deleteRecursively()
    }

    private fun file(name: String, text: String = name): Path =
        dir.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    @Test
    fun `restore to another filesystem copies files and skips pipes`() {
        val folder = Files.createDirectories(dir.resolve("with-pipe"))
        Files.writeString(folder.resolve("data.txt"), "data")
        val mk = ProcessBuilder("mkfifo", folder.resolve("pipe").toString()).start()
        if (mk.waitFor() != 0) return
        Files.createSymbolicLink(folder.resolve("link"), Paths.get("data.txt"))
        val item = Trash.trash(folder)
        // /tmp — другая ФС (tmpfs): атомарный перенос не выйдет, будет копирование
        val elsewhere = Files.createTempDirectory("wex_restore_other_fs")
        try {
            val target = elsewhere.resolve("restored")
            val done = java.util.concurrent.atomic.AtomicReference<Result<Path>>()
            val worker = Thread { done.set(runCatching { Trash.restore(item, target) }) }.apply { isDaemon = true; start() }
            worker.join(10_000)
            assertTrue(!worker.isAlive, "восстановление зависло на канале")
            assertEquals(target, done.get().getOrThrow())
            assertEquals("data", Files.readString(target.resolve("data.txt")))
            assertTrue(Files.isSymbolicLink(target.resolve("link")))
            assertTrue(Trash.list().none { it.name == item.name })
        } finally {
            org.wexclient.app.util.FileTree.delete(elsewhere)
        }
    }

    @Test
    fun `trash, list and restore like freedesktop trash`() {
        assertEquals(home.resolve(".local/share/Trash"), Trash.homeTrash())
        val report = file("Отчёт за май.txt", "данные")
        val item = Trash.trash(report)
        assertFalse(Files.exists(report))
        assertEquals("Отчёт за май.txt", item.name)
        val info = Files.readString(item.info)
        assertTrue(info.startsWith("[Trash Info]\nPath=/"), info)
        assertTrue("%D0%9E%D1%82%D1%87%D1%91%D1%82%20%D0%B7%D0%B0%20%D0%BC%D0%B0%D0%B9.txt" in info, info)
        assertTrue(Regex("DeletionDate=\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d").containsMatchIn(info))

        // Второй файл с тем же именем — своё имя в корзине
        Files.writeString(report, "новые данные")
        val second = Trash.trash(report)
        assertEquals("Отчёт за май (2).txt", second.name)

        // Папка целиком
        val folder = dir.resolve("проект")
        file("проект/src/Main.kt", "fun main() {}")
        val folderItem = Trash.trash(folder)
        assertTrue(folderItem.isDirectory)
        assertEquals(13L, Trash.sizeOf(folderItem))

        val listed = Trash.list()
        assertEquals(3, listed.size)
        assertEquals(3, Trash.count())
        assertEquals(setOf(report, folder), listed.map { it.originalPath }.toSet())

        // Восстановление: занятое место не затирается
        Files.writeString(report, "занято")
        assertFailsWith<FileAlreadyExistsException> { Trash.restore(listed.first { it.name == "Отчёт за май.txt" }) }
        val renamed = Trash.restore(listed.first { it.name == "Отчёт за май.txt" }, Trash.freeRestoreTarget(listed.first { it.name == "Отчёт за май.txt" }))
        assertEquals("Отчёт за май (восстановлено).txt", renamed.fileName.toString())
        assertEquals("данные", Files.readString(renamed))
        assertEquals("fun main() {}", Files.readString(Trash.restore(folderItem).resolve("src/Main.kt")))

        // Удалить навсегда и очистить
        assertEquals(1, Trash.list().size)
        assertEquals(1, Trash.empty())
        assertEquals(0, Trash.count())
        assertFalse(Files.list(Trash.homeTrash().resolve("files")).use { it.findAny().isPresent })
    }

    @Test
    fun `trashinfo paths are url encoded`() {
        val raw = "/home/u/Мои файлы/a b%c#d.txt"
        val encoded = Trash.encodePath(raw)
        assertEquals("/home/u/%D0%9C%D0%BE%D0%B8%20%D1%84%D0%B0%D0%B9%D0%BB%D1%8B/a%20b%25c%23d.txt", encoded)
        assertEquals(raw, Trash.decodePath(encoded))
        assertEquals("/a+b", Trash.decodePath("/a+b"), "плюс — не пробел")
        val parsed = assertNotNull(Trash.parseInfo("[Trash Info]\nPath=/tmp/x%20y\nDeletionDate=2026-09-17T01:02:03\n"))
        assertEquals("/tmp/x y", parsed.first)
        assertEquals(3, parsed.second!!.second)
        assertNull(Trash.parseInfo("[Other]\nPath=/x\n"))
        assertEquals("архив.tar (3).gz", Trash.numbered("архив.tar.gz", 3))

        // Корзина раздела: только относительные пути внутри раздела
        val top = Paths.get("/run/media/u/USB")
        assertEquals(top.resolve("docs/a.txt"), Trash.originalPathOf(Paths.get("docs/a.txt"), top, "a.txt"))
        assertEquals(top.resolve("x.desktop"), Trash.originalPathOf(Paths.get("../../../home/u/.config/autostart/x.desktop"), top, "x.desktop"))
        assertEquals(top.resolve("x.desktop"), Trash.originalPathOf(Paths.get("/home/u/.config/autostart/x.desktop"), top, "x.desktop"))
        assertEquals(top.resolve("old/b.txt"), Trash.originalPathOf(Paths.get("/run/media/u/USB/old/b.txt"), top, "b.txt"))
        assertEquals(top.resolve("c.txt"), Trash.originalPathOf(Paths.get("/run/media/u/USB/../../../etc/c.txt"), top, "c.txt"))
        // Домашняя корзина: абсолютные пути
        assertEquals(Paths.get("/home/u/a.txt"), Trash.originalPathOf(Paths.get("/home/u/./a.txt"), null, "a.txt"))
        assertNull(Trash.originalPathOf(Paths.get("rel/a.txt"), null, "a.txt"))

        // Папка на пути внутри раздела — ссылка наружу (флешка со ссылкой на ~/.config/autostart)
        val usb = Files.createDirectories(dir.resolve("usb"))
        val outside = Files.createDirectories(dir.resolve("autostart"))
        Files.createSymbolicLink(usb.resolve("link"), outside)
        assertFailsWith<java.io.IOException> { Trash.ensureInsideTop(usb.resolve("link/evil.desktop"), usb) }
        assertFailsWith<java.io.IOException> { Trash.ensureInsideTop(usb.resolve("link/new/deeper/evil.desktop"), usb) }
        Trash.ensureInsideTop(usb.resolve("docs/new/a.txt"), usb)
        Trash.ensureInsideTop(usb.resolve("a.txt"), usb)
        assertEquals(".bashrc (2)", Trash.numbered(".bashrc", 2))
    }

    @Test
    fun `copy and move with conflicts, undo`() {
        val src = Files.createDirectory(dir.resolve("src"))
        val dst = Files.createDirectory(dir.resolve("dst"))
        val a = file("src/a.txt", "новый a")
        val b = file("src/b.txt", "b")
        file("dst/a.txt", "старый a")
        val link = Files.createSymbolicLink(src.resolve("link"), Paths.get("a.txt"))

        assertEquals(listOf(a), FileTransfers.conflicts(listOf(a, b), dst))
        assertEquals(emptyList(), FileTransfers.conflicts(listOf(a), src), "дубль в той же папке — не конфликт")

        // Оставить оба
        val both = FileTransfers.transfer(vfs, listOf(a, b), dst, move = false, policy = ConflictPolicy.KEEP_BOTH)
        assertEquals(listOf(dst.resolve("a (копия).txt"), dst.resolve("b.txt")), both.done.map { it.second })
        assertEquals("старый a", Files.readString(dst.resolve("a.txt")))
        val undo = FileUndoStack()
        undo.push(FileOp.Copied(both.done.map { it.second }, both.replaced))
        assertTrue(undo.undo(vfs)!!.startsWith("Копии убраны"))
        assertFalse(Files.exists(dst.resolve("a (копия).txt")) || Files.exists(dst.resolve("b.txt")))

        // Заменить: прежний файл уходит в корзину и возвращается отменой
        val replace = FileTransfers.transfer(vfs, listOf(a), dst, move = false, policy = ConflictPolicy.REPLACE)
        assertEquals("новый a", Files.readString(dst.resolve("a.txt")))
        assertEquals(1, replace.replaced.size)
        undo.push(FileOp.Copied(replace.done.map { it.second }, replace.replaced))
        undo.undo(vfs)
        assertEquals("старый a", Files.readString(dst.resolve("a.txt")))

        // Пропустить
        val skip = FileTransfers.transfer(vfs, listOf(a, b), dst, move = true, policy = ConflictPolicy.SKIP)
        assertEquals(1, skip.skipped)
        assertFalse(Files.exists(b))
        assertTrue(Files.exists(a))
        undo.push(FileOp.Moved(skip.done))
        assertEquals("Перемещение отменено: 1", undo.undo(vfs))
        assertTrue(Files.exists(b) && !Files.exists(dst.resolve("b.txt")))

        // Дубль в той же папке и ссылки внутри папок — ссылками
        val dup = FileTransfers.transfer(vfs, listOf(a, a), src, move = false)
        assertEquals(listOf("a (копия).txt", "a (копия 2).txt"), dup.done.map { it.second.fileName.toString() })
        val tree = FileTransfers.transfer(vfs, listOf(src), dst, move = false).done.single().second
        assertTrue(Files.isSymbolicLink(tree.resolve("link")))
        assertEquals(Paths.get("a.txt"), Files.readSymbolicLink(tree.resolve("link")))
        assertTrue(Files.isSymbolicLink(link))
        // Вырезать и вставить туда же — ничего
        assertEquals(1, FileTransfers.transfer(vfs, listOf(b), src, move = true).skipped)
        assertFailsWith<IllegalArgumentException> { vfs.copyTo(src, src.resolve("inner")) }

        // Отмена переименования и создания
        val renamed = vfs.renameEntry(b, "c.txt")
        undo.push(FileOp.Renamed(b, renamed))
        assertEquals("Имя снова «b.txt»", undo.undo(vfs))
        assertTrue(Files.exists(b))
        val created = vfs.createFile(src, "new.txt")
        undo.push(FileOp.Created(created))
        undo.undo(vfs)
        assertFalse(Files.exists(created))
        assertNull(undo.undo(vfs))
    }

    @Test
    fun `clipboard keeps files, cut state and reads other formats`() {
        val clipboard = Clipboard("test")
        FileClipboard.clipboardProvider = { clipboard }
        val a = file("a.txt")
        val b = file("b.txt")

        FileClipboard.copy(listOf(a, b))
        assertEquals(ClipContent.FileList(listOf(a, b), cut = false), FileClipboard.read())
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(a.toFile(), b.toFile()), clipboard.getData(DataFlavor.javaFileListFlavor) as List<java.io.File>)
        assertEquals("$a\n$b", clipboard.getData(DataFlavor.stringFlavor))

        FileClipboard.cut(listOf(a))
        assertEquals(setOf(a), FileClipboard.cutPaths)
        assertEquals(ClipContent.FileList(listOf(a), cut = true), FileClipboard.read())
        FileClipboard.clearCut()
        assertEquals(emptySet(), FileClipboard.cutPaths)

        // Формат GNOME: «cut» и адреса file://
        val (cut, paths) = FileClipboard.parseGnome("cut\n${FileClipboard.pathToUri(a)}\nfile:///tmp/%D1%8F%20b")
        assertTrue(cut)
        assertEquals(listOf(a, Paths.get("/tmp/я b")), paths)

        // Чужой буфер: пути текстом, обычный текст, картинка
        clipboard.setContents(StringSelection("$a\n$b"), null)
        assertEquals(ClipContent.FileList(listOf(a, b), cut = false), FileClipboard.read())
        clipboard.setContents(StringSelection("просто текст"), null)
        assertEquals(ClipContent.PlainText("просто текст"), FileClipboard.read())
        val img = BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB)
        clipboard.setContents(object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = img
        }, null)
        val picture = assertIs<ClipContent.Picture>(FileClipboard.read())
        assertEquals(4, picture.image.width)

        // Вырезано в другой программе: перенесли всё — буфер пуст, только часть — список остаётся
        val gnome = DataFlavor("x-special/gnome-copied-files;class=java.io.InputStream")
        clipboard.setContents(object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(gnome)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == gnome
            override fun getTransferData(flavor: DataFlavor): Any = java.io.ByteArrayInputStream(
                "cut\n${FileClipboard.pathToUri(a)}\n${FileClipboard.pathToUri(b)}".toByteArray()
            )
        }, null)
        assertEquals(ClipContent.FileList(listOf(a, b), cut = true), FileClipboard.read())
        FileClipboard.clearCut(listOf(a))
        assertEquals(ClipContent.FileList(listOf(a, b), cut = true), FileClipboard.read())
        FileClipboard.clearCut(listOf(b, a))
        assertNull(FileClipboard.read())
    }
}
