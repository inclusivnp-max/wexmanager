package org.wexclient.app.vfs.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Разбор адреса сервера и ответа find — без настоящего сервера. */
class SshConnectionTest {

    private val tab = '\t'
    private val nul = '\u0000'

    @Test
    fun `адрес сервера разбирается во всех видах`() {
        assertEquals(SshTarget("ubuntu", "185.126.114.208", 22), SshTarget.parse("ubuntu@185.126.114.208"))
        assertEquals(SshTarget("root", "example.com", 2222), SshTarget.parse("ssh://root@example.com:2222/"))
        assertEquals(SshTarget(null, "myserver", 22), SshTarget.parse("myserver"))
        assertEquals("root@example.com:2222", SshTarget("root", "example.com", 2222).label)
        assertEquals("ubuntu@host", SshTarget("ubuntu", "host").label)
    }

    @Test
    fun `мусор не принимается за адрес`() {
        assertNull(SshTarget.parse(""))
        assertNull(SshTarget.parse("два слова"))
        // Имя, похожее на ключ ssh, — не адрес: иначе можно было бы подсунуть параметр
        assertNull(SshTarget.parse("-oProxyCommand=evil"))
        assertNull(SshTarget.parse("-oProxyCommand=evil@host"))
    }

    private fun record(vararg fields: String): String = fields.joinToString(tab.toString()) + nul

    @Test
    fun `ответ find разбирается в записи`() {
        val raw = record("d", "d", "4096", "1700000000.5", "755", "ubuntu", "ubuntu", "./src") +
            record("f", "f", "1234", "1700000100.0", "644", "ubuntu", "ubuntu", "./README.md") +
            record("l", "d", "12", "1700000200.0", "777", "root", "root", "./current")
        val entries = SshConnection.parseListing(raw)

        assertEquals(listOf("src", "README.md", "current"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertEquals(1234L, entries[1].size)
        assertEquals(1_700_000_100_000L, entries[1].modifiedMillis)
        assertEquals(0x1A4, entries[1].mode) // 644 восьмеричное
        // Ссылка на папку ведёт себя как папка, но помнит, что она ссылка
        assertTrue(entries[2].isDirectory && entries[2].isLink)
    }

    @Test
    fun `табуляция в имени файла не ломает разбор`() {
        val raw = record("f", "f", "5", "1700000000.0", "644", "u", "g", "./мой файл${tab}с табом.txt")
        assertEquals("мой файл${tab}с табом.txt", SshConnection.parseListing(raw).single().name)
    }

    @Test
    fun `консоль сервера открывается в папке панели`() {
        val cmd = SshConnection(SshTarget("ubuntu", "example.org", 2222)).shellCommand("/srv/it's here")
        assertEquals("ssh", cmd.first())
        assertTrue("-t" in cmd)
        // Порт не обычный — передаётся; адрес — перед «--», команда — одним словом после
        assertEquals("2222", cmd[cmd.indexOf("-p") + 1])
        // «--» перед адресом: ни адрес, ни команда не станут параметрами ssh
        assertEquals(listOf("--", "ubuntu@example.org", "cd -- '/srv/it'\\''s here'; exec \$SHELL -l"), cmd.takeLast(3))
    }

    @Test
    fun `кавычки защищают путь от оболочки`() {
        assertEquals("'/home/u/it'\\''s; rm -rf /'", SshConnection.quote("/home/u/it's; rm -rf /"))
        // Обратная косая черта — вне кавычек: в fish внутри них она экранирует кавычку
        assertEquals("'a'\\\\'b'", SshConnection.quote("a\\b"))
        assertEquals(org.wexclient.app.terminal.ScriptRunner.quote("x\\';echo 1;#"), SshConnection.quote("x\\';echo 1;#"))
    }
}
