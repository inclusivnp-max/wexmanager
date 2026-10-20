package org.wexclient.app.vfs.adb

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверка на живом телефоне или эмуляторе. Запускается, только если задан WEX_ADB_SERIAL —
 * серийный номер из adb devices; WEX_ADB_DIR — рабочая папка на телефоне (по умолчанию
 * /data/local/tmp). Тест создаёт там свою папку и удаляет её. Без WEX_ADB_SERIAL молча пропускается.
 */
class AdbFileSystemIT {

    private val serial = System.getenv("WEX_ADB_SERIAL")
    private val dir = System.getenv("WEX_ADB_DIR") ?: "/data/local/tmp"

    @Test
    fun `папка на телефоне работает как обычная`() {
        if (serial == null) return
        AdbFileSystemProvider.instance.open(serial, serial).use { check(it) }
    }

    private fun check(fs: AdbFileSystem) {
        val sub = fs.getPath(dir).resolve("wex-проверка-${System.nanoTime()}")
        Files.createDirectory(sub)
        try {
            // Запись и чтение байт в байт: нули, все значения байта, размер больше буфера
            val bytes = ByteArray(700_000) { (it * 31 % 256).toByte() }
            val file = sub.resolve("файл с пробелом.bin")
            Files.write(file, bytes)
            assertContentEquals(bytes, Files.readAllBytes(file))
            // Тот же путь, что у Ctrl+S в редакторе
            val vfs = org.wexclient.app.vfs.VfsManager()
            val text = sub.resolve("заметка.txt")
            vfs.writeBytes(text, "из редактора".toByteArray())
            assertEquals("из редактора", Files.readString(text))

            // Имя с апострофом и «\» в конце: экранирование для оболочки телефона
            val tricky = sub.resolve("it's \\")
            Files.writeString(tricky, "хитрое имя")
            assertEquals("хитрое имя", Files.readString(tricky))
            Files.delete(tricky)
            assertFalse(Files.exists(tricky))

            // Список и сведения
            val names = Files.list(sub).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
            assertEquals(listOf("заметка.txt", "файл с пробелом.bin"), names)
            assertEquals(bytes.size.toLong(), Files.size(file))
            assertTrue(Files.isDirectory(sub))
            assertTrue(Files.isRegularFile(file))

            // Переименование и копирование на телефоне
            val renamed = sub.resolve("новое имя.bin")
            Files.move(file, renamed)
            assertFalse(Files.exists(file))
            Files.copy(renamed, sub.resolve("копия.bin"))
            assertContentEquals(bytes, Files.readAllBytes(sub.resolve("копия.bin")))

            // Папка с компьютера на телефон и обратно — как F5 в панели
            val local = Files.createTempDirectory("wex-adb")
            Files.createDirectories(local.resolve("проект/src"))
            Files.writeString(local.resolve("проект/src/main.kt"), "fun main() {}")
            vfs.copyTo(local.resolve("проект"), sub.resolve("проект"))
            assertEquals("fun main() {}", Files.readString(sub.resolve("проект/src/main.kt")))
            vfs.copyTo(sub.resolve("проект"), local.resolve("обратно"))
            assertEquals("fun main() {}", Files.readString(local.resolve("обратно/src/main.kt")))
            local.toFile().deleteRecursively()

            // Файл, появившийся на телефоне после чтения папки, копия не затирает
            Files.list(sub).use { it.count() }
            Files.writeString(sub.resolve("появился.txt"), "чужой")
            val src = Files.createTempFile("wex-adb-src", ".txt")
            Files.writeString(src, "наш")
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { Files.copy(src, sub.resolve("появился.txt")) }
            Files.copy(src, sub.resolve("появился.txt"), StandardCopyOption.REPLACE_EXISTING)
            assertEquals("наш", Files.readString(sub.resolve("появился.txt")))
            Files.delete(src)

            // Нет такого файла: ошибка, а не пустое содержимое
            assertFailsWith<java.io.IOException> { Files.newInputStream(sub.resolve("нет такого")).use { it.readBytes() } }

            // Место на разделе телефона
            val space = org.wexclient.app.vfs.DiskSpace.of(sub)
            assertTrue(space != null && space.total > 0 && space.free in 0..space.total)

            // Удаление папки целиком одной командой
            vfs.deleteEntry(sub.resolve("проект"))
            assertFalse(Files.exists(sub.resolve("проект")))
            vfs.closeAll()
        } finally {
            fs.deleteTree(sub)
        }
        assertFalse(fs.existsNow(sub))
    }
}
