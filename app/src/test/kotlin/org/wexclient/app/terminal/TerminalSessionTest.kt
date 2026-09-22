package org.wexclient.app.terminal

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TerminalSessionTest {
    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    @Test
    fun `bash session echoes, reports cwd and resizes`() {
        val dir = Files.createTempDirectory("wex-term")
        val s = TerminalSession(1, dir, shell = "/bin/bash", initialColumns = 80, initialRows = 24)
        s.start()
        try {
            assertEquals(null, s.startError)
            assertTrue(waitFor(5000) { s.isAlive })
            s.sendText("export PS1='$ '; echo marker-\$((40+2))\r")
            assertTrue(waitFor(5000) { s.allText().contains("marker-42") }, s.allText())
            assertEquals(dir.toRealPath(), s.currentDirectory()?.toRealPath())
            s.resize(120, 30)
            // Размер применяется асинхронно — ждём, пока оболочка его увидит
            assertTrue(waitFor(5000) {
                s.sendText("echo size=\$(tput cols)x\$(tput lines)\r")
                Thread.sleep(300)
                s.allText().contains("size=120x30")
            }, s.allText())
            // Занятость: во время sleep оболочка не свободна
            s.sendText("sleep 2\r")
            assertTrue(waitFor(2000) { s.isBusy() })
            assertTrue(waitFor(4000) { !s.isBusy() })
            val sub = dir.resolve("sub dir").also { Files.createDirectories(it) }
            assertTrue(s.changeDirectory(sub))
            assertTrue(waitFor(3000) { s.currentDirectory()?.toRealPath() == sub.toRealPath() })
            // Цвета и стили доходят до буфера
            s.sendText("printf '\\033[1;31mRED\\033[0m\\n'\r")
            assertTrue(waitFor(3000) {
                s.buffer.lock()
                try {
                    val screen = s.buffer.getScreenLines()
                    val row = screen.lines().indexOfFirst { it.startsWith("RED") }
                    row >= 0 && s.buffer.getStyleAt(0, row)?.foreground != null
                } finally {
                    s.buffer.unlock()
                }
            }, s.allText())
            s.sendText("exit 3\r")
            assertTrue(waitFor(5000) { s.exitCode != null })
            assertEquals(3, s.exitCode)
        } finally {
            s.close()
            org.wexclient.app.util.FileTree.delete(dir)
        }
    }

    @Test
    fun `login shell starts and prints prompt`() {
        val shell = ShellLocator.loginShell()
        val dir = Files.createTempDirectory("wex-term2")
        val s = TerminalSession(2, dir, shell = shell)
        s.start()
        try {
            assertNotNull(s.pid)
            val started = System.currentTimeMillis()
            assertTrue(waitFor(8000) { s.allText().isNotBlank() }, "no output from $shell")
            println("SHELL $shell first output after ${System.currentTimeMillis() - started} ms: " + s.allText().take(200))
            s.sendText("echo wex-ok\r")
            assertTrue(waitFor(8000) { s.allText().contains("wex-ok") }, s.allText())
            println("available shells: " + ShellLocator.available())
        } finally {
            s.close()
            org.wexclient.app.util.FileTree.delete(dir)
        }
    }
}
