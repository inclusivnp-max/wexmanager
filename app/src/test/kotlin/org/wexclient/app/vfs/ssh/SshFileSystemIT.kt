package org.wexclient.app.vfs.ssh

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверка на живом sshd. Запускается, только если задан тестовый сервер:
 * WEX_SSH_CONFIG — файл настроек ssh, WEX_SSH_HOST — имя из него, WEX_SSH_DIR — рабочая папка.
 * Без них тест молча пропускается.
 */
class SshFileSystemIT {

    private val host = System.getenv("WEX_SSH_HOST")
    private val dir = System.getenv("WEX_SSH_DIR")
    private val enabled = System.getenv("WEX_SSH_CONFIG") != null && host != null && dir != null

    @Test
    fun `папка на сервере работает как обычная`() {
        if (!enabled) return
        val fs = SshFileSystemProvider.instance.open(SshTarget.parse(host!!)!!)
        // Фоновое соединение закрываем сами, иначе оно живёт ещё 10 минут после тестов
        fs.use { check(it) }
    }

    /**
     * Ссылки, ошибки чтения, перенос между серверами. Сервер тестовый и на этом же компьютере:
     * результат проверяется и прямо на диске. WEX_SSH_HOST2 — второе имя того же сервера
     * (другое подключение — «другой сервер»).
     */
    @Test
    fun `ссылки не раскрываются при удалении, ошибка чтения не выглядит пустым файлом`() {
        if (!enabled) return
        val fs = SshFileSystemProvider.instance.open(SshTarget.parse(host!!)!!)
        fs.use { links(it) }
    }

    private fun links(fs: SshFileSystem) {
        val local = java.nio.file.Paths.get(dir!!).resolve("ссылки")
        if (Files.exists(local)) Files.walk(local).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        Files.createDirectories(local.resolve("shared/storage"))
        Files.writeString(local.resolve("shared/storage/keep.txt"), "данные сайта")
        Files.createDirectories(local.resolve("releases/5"))
        Files.createSymbolicLink(local.resolve("releases/5/storage"), java.nio.file.Paths.get("../../shared/storage"))
        Files.createSymbolicLink(local.resolve("current"), local.resolve("releases/5"))

        val vfs = org.wexclient.app.vfs.VfsManager()
        val remote = fs.getPath(local.toString())
        val link = remote.resolve("current")

        // Сведения о ссылке — как на диске
        assertTrue(Files.isSymbolicLink(link))
        assertTrue(Files.isDirectory(link))
        assertFalse(Files.isDirectory(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals(local.resolve("releases/5").toString(), Files.readSymbolicLink(link).toString())

        // Копия с сервера на диск: ссылка остаётся ссылкой
        val copyDir = Files.createTempDirectory("wex-ssh-links")
        vfs.copyTo(remote.resolve("releases"), copyDir.resolve("releases"))
        assertTrue(Files.isSymbolicLink(copyDir.resolve("releases/5/storage")))
        copyDir.toFile().deleteRecursively()

        // Удаление ссылки и папки со ссылкой внутри не трогает то, на что они указывают
        vfs.deleteEntry(link)
        assertFalse(Files.exists(local.resolve("current"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        vfs.deleteEntry(remote.resolve("releases"))
        assertFalse(Files.exists(local.resolve("releases")))
        assertEquals("данные сайта", Files.readString(local.resolve("shared/storage/keep.txt")))

        // Нечитаемый файл: ошибка, а не пустое содержимое (перенос иначе удалил бы источник)
        val locked = local.resolve("закрытый.txt")
        Files.writeString(locked, "секрет")
        Files.setPosixFilePermissions(locked, emptySet())
        try {
            assertFailsWith<java.io.IOException> { Files.newInputStream(remote.resolve("закрытый.txt")).use { it.readBytes() } }
            val dest = Files.createTempDirectory("wex-ssh-locked")
            assertFailsWith<Exception> { vfs.moveTo(remote.resolve("закрытый.txt"), dest.resolve("закрытый.txt")) }
            assertTrue(Files.exists(locked), "источник на месте")
            dest.toFile().deleteRecursively()
        } finally {
            Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }

        // Файл, появившийся на сервере после чтения папки, копия не затирает
        Files.list(remote).use { it.count() }
        Files.writeString(local.resolve("появился.txt"), "чужой")
        val src = Files.createTempFile("wex-ssh-src", ".txt")
        Files.writeString(src, "наш")
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { Files.copy(src, remote.resolve("появился.txt")) }
        assertEquals("чужой", Files.readString(local.resolve("появился.txt")))
        Files.delete(src)

        // Перенос папки на «другой сервер» и отмена (обратный перенос): ничего не теряется
        val host2 = System.getenv("WEX_SSH_HOST2")
        if (host2 != null) {
            val fs2 = SshFileSystemProvider.instance.open(SshTarget.parse(host2)!!)
            fs2.use {
                Files.createDirectories(local.resolve("проект/src"))
                Files.writeString(local.resolve("проект/src/main.kt"), "fun main() {}")
                val there = it.getPath(local.resolve("перенесённый").toString())
                vfs.moveTo(remote.resolve("проект"), there)
                assertEquals("fun main() {}", Files.readString(local.resolve("перенесённый/src/main.kt")))
                assertFalse(Files.exists(local.resolve("проект")))
                vfs.moveTo(there, remote.resolve("проект"))
                assertEquals("fun main() {}", Files.readString(local.resolve("проект/src/main.kt")))
                assertFalse(Files.exists(local.resolve("перенесённый")))
            }
        }

        vfs.closeAll()
        Files.walk(local).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
    }

    private fun check(fs: SshFileSystem) {
        val root = fs.getPath(dir!!)

        // Создание, запись, чтение
        val sub = root.resolve("проверка")
        if (Files.exists(sub)) Files.walk(sub).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        Files.createDirectory(sub)
        val file = sub.resolve("файл с пробелом.txt")
        Files.writeString(file, "привет с сервера")
        assertEquals("привет с сервера", Files.readString(file))
        // Перезапись существующего файла — так сохраняет редактор
        Files.write(file, "первая версия".toByteArray())
        assertEquals("первая версия", Files.readString(file))
        Files.write(file, "привет с сервера".toByteArray())
        assertEquals("привет с сервера", Files.readString(file))
        // Тот же путь, что у Ctrl+S в редакторе
        val vfs = org.wexclient.app.vfs.VfsManager()
        vfs.writeBytes(file, "из редактора".toByteArray())
        assertEquals("из редактора", Files.readString(file))
        Files.write(file, "привет с сервера".toByteArray())

        // Имя с апострофом и «\» в конце: у оболочки входа fish своё экранирование в кавычках
        val tricky = sub.resolve("it's \\")
        Files.writeString(tricky, "хитрое имя")
        assertEquals("хитрое имя", Files.readString(tricky))
        assertTrue(Files.list(sub).use { s -> s.anyMatch { it.fileName.toString() == "it's \\" } })
        Files.delete(tricky)
        assertFalse(Files.exists(tricky))

        // Список и сведения
        val names = Files.list(sub).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("файл с пробелом.txt"), names)
        assertEquals("привет с сервера".toByteArray().size.toLong(), Files.size(file))
        assertTrue(Files.isDirectory(sub))
        assertFalse(Files.isDirectory(file))

        // Переименование и копирование на сервере
        val renamed = sub.resolve("новое имя.txt")
        Files.move(file, renamed)
        assertFalse(Files.exists(file))
        Files.copy(renamed, sub.resolve("копия.txt"))
        assertEquals("привет с сервера", Files.readString(sub.resolve("копия.txt")))

        // Копирование между компьютером и сервером
        val local = Files.createTempFile("wex-ssh", ".txt")
        Files.writeString(local, "с компьютера")
        Files.copy(local, sub.resolve("с диска.txt"), StandardCopyOption.REPLACE_EXISTING)
        assertEquals("с компьютера", Files.readString(sub.resolve("с диска.txt")))
        val back = Files.createTempFile("wex-ssh-back", ".txt")
        Files.copy(sub.resolve("копия.txt"), back, StandardCopyOption.REPLACE_EXISTING)
        assertEquals("привет с сервера", Files.readString(back))

        // Удаление снизу вверх
        Files.walk(sub).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        assertFalse(Files.exists(sub))
    }
}
