package org.wexclient.app.terminal

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Большая вставка уходит в оболочку кусками: без потерь, без разрезанных символов, с отменой по Ctrl+C. */
class PasteChunksTest {
    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    private fun screen(s: TerminalSession): String {
        s.buffer.lock()
        try {
            return s.buffer.getScreenLines()
        } finally {
            s.buffer.unlock()
        }
    }

    @Test
    fun `chunk ends never split a UTF-8 character`() {
        val bytes = "аб😀в".repeat(3000).toByteArray(Charsets.UTF_8)
        for (max in listOf(4, 5, 7, 4096)) {
            var from = 0
            val parts = ArrayList<ByteArray>()
            while (from < bytes.size) {
                val to = TerminalSession.pasteChunkEnd(bytes, from, max)
                assertTrue(to > from && to - from <= max)
                // Каждый кусок сам по себе — целые символы
                val part = bytes.copyOfRange(from, to)
                assertTrue(part.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8).contentEquals(part), "max=$max from=$from")
                parts += part
                from = to
            }
            assertEquals(bytes.size, parts.sumOf { it.size })
        }
    }

    @Test
    fun `large paste arrives whole`() {
        val dir = Files.createTempDirectory("wex-paste")
        val s = TerminalSession(801, dir, shell = "/bin/bash", initialColumns = 100, initialRows = 30)
        s.start()
        try {
            assertTrue(waitFor(5000) { s.isAlive })
            s.sendText("export PS1='$ '; stty -echo; cat > out.txt\r")
            // cat запущен — оболочка выключила bracketed paste, и текст идёт в канал как есть
            assertTrue(waitFor(5000) { !s.bracketedPaste && s.isBusy() })
            val lines = (1..4000).map { "строка $it 😀 abcdefghijklmnopqrstuvwxyz ёжик" }
            s.paste(lines.joinToString("\n") + "\n")
            s.sendBytes(byteArrayOf(4))
            val out = dir.resolve("out.txt")
            assertTrue(waitFor(20_000) { !s.isBusy() }, "cat не закончил")
            assertEquals(lines, Files.readAllLines(out))
        } finally {
            s.close()
            org.wexclient.app.util.FileTree.delete(dir)
        }
    }

    @Test
    fun `ctrl+c cuts a large paste short`() {
        val dir = Files.createTempDirectory("wex-paste-cancel")
        val s = TerminalSession(802, dir, shell = "/bin/bash", initialColumns = 100, initialRows = 30)
        s.start()
        try {
            assertTrue(waitFor(5000) { s.isAlive })
            s.sendText("export PS1='$ '\r")
            assertTrue(waitFor(5000) { s.bracketedPaste })
            // Шесть мегабайт readline разбирает секунд двадцать; без отмены ответ ждал бы их все
            val text = (1..100_000).joinToString("\n") { "echo line $it abcdefghijklmnopqrstuvwxyz 0123456789 ABCDEFGHIJ" }
            val started = System.currentTimeMillis()
            s.paste(text)
            Thread.sleep(200)
            s.sendBytes(byteArrayOf(3))
            s.sendText("echo done-\$((6*7))\r")
            assertTrue(waitFor(8000) { screen(s).contains("done-42") }, screen(s))
            println("оболочка ответила через ${System.currentTimeMillis() - started} мс после вставки")
        } finally {
            s.close()
            org.wexclient.app.util.FileTree.delete(dir)
        }
    }
}
