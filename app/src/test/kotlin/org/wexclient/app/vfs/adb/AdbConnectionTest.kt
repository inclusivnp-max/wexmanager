package org.wexclient.app.vfs.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Разбор ответа toybox find на телефоне — без настоящего телефона. */
class AdbConnectionTest {

    private val tab = '\t'
    private val nul = '\u0000'

    private fun record(vararg fields: String): String = fields.joinToString(tab.toString()) + nul

    @Test
    fun `ответ find разбирается в записи`() {
        val raw = record("E", "drwxrws---", "4096", "1788915152.700000000", "2770", "u0_a189", "media_rw", "Download") +
            record("E", "-rw-rw----", "1234", "1700000100.000000000", "660", "u0_a189", "media_rw", "photo.jpg") +
            record("E", "lrwxrwxrwx", "21", "1737652868.000000000", "777", "root", "root", "sdcard") +
            record("L", "drwxrws---", "Download") +
            record("L", "-rw-rw----", "photo.jpg") +
            record("L", "drwxrws---", "sdcard")
        val entries = AdbConnection.parseListing(raw)

        assertEquals(listOf("Download", "photo.jpg", "sdcard"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertEquals(0x5F8, entries[0].mode) // 2770 восьмеричное: setgid и rwxrws---
        assertTrue(entries[1].isRegular)
        assertEquals(1234L, entries[1].size)
        assertEquals(1_700_000_100_000L, entries[1].modifiedMillis)
        assertEquals("u0_a189", entries[1].owner)
        // Ссылка на папку ведёт себя как папка, но помнит, что она ссылка
        assertTrue(entries[2].isDirectory && entries[2].isLink)
    }

    @Test
    fun `битая и закрытая ссылки остаются ссылками`() {
        val raw = record("E", "lrwxrwxrwx", "7", "1700000000.0", "777", "root", "root", "broken") +
            record("E", "lrwxrwxrwx", "17", "1700000000.0", "777", "root", "root", "d") +
            // find -L показывает битую ссылку как ссылку, а закрытую не показывает совсем
            record("L", "lrwxrwxrwx", "broken")
        val (broken, closed) = AdbConnection.parseListing(raw)
        assertEquals('l', broken.targetType)
        assertEquals('l', closed.targetType)
        assertFalse(broken.isDirectory || broken.isRegular)
        assertFalse(closed.isDirectory || closed.isRegular)
    }

    @Test
    fun `особые файлы и табуляция в имени не ломают разбор`() {
        val raw = record("E", "srw-rw----", "0", "1700000000.0", "660", "system", "system", "socket") +
            record("E", "-rw-r--r--", "5", "1700000000.0", "644", "shell", "shell", "мой файл${tab}с табом.txt") +
            record("L", "-rw-r--r--", "мой файл${tab}с табом.txt")
        val entries = AdbConnection.parseListing(raw)
        assertEquals('s', entries[0].type)
        assertEquals("мой файл${tab}с табом.txt", entries[1].name)
        assertTrue(entries[1].isRegular)
    }

    @Test
    fun `консоль телефона открывается в папке панели`() {
        val cmd = AdbConnection("emulator-5554", "/usr/bin/adb").shellCommand("/sdcard/it's here")
        assertEquals(listOf("/usr/bin/adb", "-s", "emulator-5554", "shell", "-t"), cmd.take(5))
        // Команда — одним словом: путь в кавычках, не выполнится как команда
        assertEquals("cd -- '/sdcard/it'\\''s here'; exec sh", cmd.last())
    }
}
