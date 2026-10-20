package org.wexclient.app.terminal

import org.wexclient.app.vfs.archive.ArchiveFormats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptRunnerQuoteTest {
    private val bs = Char(92).toString()
    private val emoji = String(Character.toChars(0x1F600))

    private val names = listOf(
        "простой.sh",
        "with space.sh",
        "it's.sh",
        "back${bs}slash.sh",
        "x$bs';echo INJECTED;#",
        "x$bs$bs';echo INJECTED;#'",
        "$" + "(echo INJECTED)",
        "`echo INJECTED`",
        "a;b|c&d>e<f",
        "*?[a]{b,c}~#%^",
        "-rf",
        "эмодзи $emoji.sh",
        "'",
        bs,
        ""
    )

    private fun run(shell: String, script: String): String {
        // Без файлов настроек пользователя: тесту нужны правила разбора, а не чужой config.fish/.zshrc
        val noConfig = when (shell) {
            "fish" -> listOf("--no-config")
            "zsh" -> listOf("-f")
            "bash" -> listOf("--norc", "--noprofile")
            else -> emptyList()
        }
        val p = ProcessBuilder(listOf(shell) + noConfig + listOf("-c", script)).redirectErrorStream(true).start()
        val out = p.inputStream.readAllBytes().decodeToString()
        p.waitFor()
        return out
    }

    @Test
    fun `quoted names reach every shell as one unchanged word`() {
        val shells = listOf("bash", "zsh", "fish").filter { ArchiveFormats.toolAvailable(it) }
        assertTrue(shells.isNotEmpty())
        for (shell in shells) {
            for (name in names) {
                val out = run(shell, "printf '[%s]" + bs + "n' " + ScriptRunner.quote(name))
                assertEquals("[$name]\n", out, "$shell: $name")
                assertFalse(out.lines().any { it == "INJECTED" }, "$shell: $name")
                val word = ScriptRunner.word(name)
                assertEquals("[$name]\n", run(shell, "printf '[%s]" + bs + "n' " + word), "$shell word: $name")
            }
        }
    }

    @Test
    fun `plain words stay readable and control characters are refused`() {
        assertEquals("./build.sh", ScriptRunner.word("./build.sh"))
        assertEquals("/usr/bin/env", ScriptRunner.word("/usr/bin/env"))
        assertEquals("-e", ScriptRunner.word("-e"))
        assertEquals("'./my script.sh'", ScriptRunner.word("./my script.sh"))
        assertEquals("'it'$bs''s'", ScriptRunner.quote("it's"))
        for (code in listOf(3, 10, 13, 27, 127, 155)) {
            assertTrue(ScriptRunner.hasControlChars("a" + Char(code) + "b"), "code $code")
        }
        assertFalse(ScriptRunner.hasControlChars("обычное имя (1).sh"))
    }
}
