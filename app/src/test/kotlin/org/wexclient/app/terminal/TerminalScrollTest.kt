package org.wexclient.app.terminal

import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.emulator.JediEmulator
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Просмотр истории: пока пользователь смотрит старые строки, вывод программы не должен сдвигать вид.
 * Процесс не запускается — байты подаются прямо в эмулятор.
 */
class TerminalScrollTest {

    private fun session() = TerminalSession(1, Paths.get("."), "/bin/sh", initialColumns = 30, initialRows = 6)

    private fun feed(s: TerminalSession, text: String) {
        val emulator = JediEmulator(ArrayTerminalDataStream(text.toCharArray()), s.terminal)
        while (emulator.hasNext()) emulator.next()
    }

    /** Текст верхней видимой строки при текущей прокрутке. */
    private fun topLine(s: TerminalSession): String {
        s.buffer.lock()
        try {
            val y = s.scrollOrigin
            val line = if (y >= 0) s.buffer.getLine(y) else s.buffer.historyLinesStorage.get(s.buffer.historyLinesStorage.size + y)
            return line.text.trim()
        } finally {
            s.buffer.unlock()
        }
    }

    private fun history(s: TerminalSession) = (1..60).joinToString("\r\n") { "line $it" }.also { feed(s, it) }

    @Test
    fun `новые строки уходят в историю, а вид остаётся на месте`() {
        val s = session()
        history(s)
        s.scrollOrigin = -20
        val before = topLine(s)
        feed(s, (1..15).joinToString("") { "\r\nmore $it" })
        assertEquals(before, topLine(s))
    }

    @Test
    fun `сдвиг экрана вниз не тянет вид к концу`() {
        val s = session()
        history(s)
        s.scrollOrigin = -20
        val before = topLine(s)
        // Обратный перевод строки в верхней строке: содержимое экрана съезжает вниз, история не меняется
        repeat(5) { feed(s, "7[1;1HM8") }
        assertEquals(before, topLine(s))
        assertEquals(-20, s.scrollOrigin)
    }

    @Test
    fun `прокрутка части экрана вид не двигает`() {
        val s = session()
        history(s)
        s.scrollOrigin = -20
        val before = topLine(s)
        // Область прокрутки 2..4, как у программ со строкой состояния
        feed(s, "7[2;4r[4;1H" + (1..8).joinToString("") { "\nregion $it" } + "[r8")
        assertEquals(before, topLine(s))
        assertEquals(-20, s.scrollOrigin)
    }

    @Test
    fun `у самого начала истории вид держится за ту же строку`() {
        val s = session()
        history(s)
        s.buffer.lock()
        val hist = try { s.buffer.historyLinesCount } finally { s.buffer.unlock() }
        s.scrollOrigin = -hist
        val before = topLine(s)
        feed(s, (1..5).joinToString("") { "\r\nnew $it" })
        assertEquals(before, topLine(s))
    }
}
