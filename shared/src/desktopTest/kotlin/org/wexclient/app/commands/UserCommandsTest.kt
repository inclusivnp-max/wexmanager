package org.wexclient.app.commands

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Подстановки и фильтры своих команд. */
class UserCommandsTest {

    private val file = Path.of("/home/u/Проекты/app release.apk")
    private val other = Path.of("/home/u/Загрузки/b.apk")

    @Test
    fun `подставляет путь, имя и папку`() {
        assertEquals(
            "adb install '/home/u/Проекты/app release.apk'",
            UserCommands.expand("adb install %f", listOf(file), null)
        )
        assertEquals("echo 'app release.apk'", UserCommands.expand("echo %n", listOf(file), null))
        assertEquals("echo 'app release'", UserCommands.expand("echo %b", listOf(file), null))
        assertEquals("cd '/home/u/Проекты'", UserCommands.expand("cd %d", listOf(file), null))
    }

    @Test
    fun `все выделенные идут через пробел`() {
        assertEquals(
            "sha256sum '/home/u/Проекты/app release.apk' '/home/u/Загрузки/b.apk'",
            UserCommands.expand("sha256sum %F", listOf(file, other), null)
        )
    }

    @Test
    fun `папка соседней панели`() {
        assertEquals(
            "cp %F → '/srv/backup'".replace("%F", "'/home/u/Загрузки/b.apk'"),
            UserCommands.expand("cp %F → %D", listOf(other), Path.of("/srv/backup"))
        )
    }

    @Test
    fun `процент и неизвестные подстановки остаются как есть`() {
        assertEquals("printf '100%' %x", UserCommands.expand("printf '100%%' %x", listOf(file), null))
    }

    @Test
    fun `апостроф в имени не ломает команду`() {
        assertEquals("'it'\\''s here'", UserCommands.quote("it's here"))
    }

    @Test
    fun `фильтр по расширениям и папкам`() {
        val apkOnly = UserCommand("Установить", "adb install %f", filter = "apk xapk")
        assertTrue(apkOnly.accepts(listOf(file)) { false })
        assertFalse(apkOnly.accepts(listOf(Path.of("/tmp/a.txt"))) { false })
        assertFalse(apkOnly.accepts(listOf(Path.of("/tmp/dir"))) { true })

        val dirsOnly = UserCommand("Открыть в редакторе", "code %f", filter = "/")
        assertTrue(dirsOnly.accepts(listOf(Path.of("/tmp/dir"))) { true })
        assertFalse(dirsOnly.accepts(listOf(file)) { false })

        val any = UserCommand("Что угодно", "ls %F")
        assertTrue(any.accepts(listOf(file, Path.of("/tmp/dir"))) { it.toString() == "/tmp/dir" })
    }

    @Test
    fun `смешанное выделение подходит, только если подходит всё`() {
        val apkOnly = UserCommand("Установить", "adb install %f", filter = ".apk")
        assertFalse(apkOnly.accepts(listOf(file, Path.of("/tmp/a.txt"))) { false })
        assertTrue(apkOnly.accepts(listOf(file, other)) { false })
    }
}
