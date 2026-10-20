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

    /**
     * Размер меняется, пока оболочка запускается (выезжает панель, догружается шрифт): раньше его получал только
     * буфер эмулятора, оболочка оставалась при старом — и fish, рисуя строку шире экрана, при наборе уводил каждую
     * букву на новую строку. Теперь оболочка в итоге знает тот же размер, что и экран, при любом порядке.
     */
    @Test
    fun `resize while the shell is starting reaches the shell`() {
        repeat(5) { round ->
            val dir = Files.createTempDirectory("wex-term-race")
            val s = TerminalSession(10 + round, dir, shell = "/bin/bash", initialColumns = 80, initialRows = 24)
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            // Дёргаем размер, пока запуск не закончится; последний — каждый раз свой
            val resizer = Thread {
                var i = 0
                while (!stop.get()) {
                    s.resize(if (i % 2 == 0) 97 else 113, if (i % 2 == 0) 21 else 33)
                    i++
                }
            }.apply { start() }
            try {
                s.start()
                stop.set(true)
                resizer.join()
                assertEquals(null, s.startError)
                // Последние размеры применяются асинхронно — сравниваем с тем, что на экране сейчас
                fun screen() = "${s.columns}x${s.rows}"
                assertTrue(waitFor(3000) { s.ptySize?.let { "${it.columns}x${it.rows}" } == screen() },
                    "оболочка знает ${s.ptySize}, экран ${screen()}")
                s.sendText("export PS1='$ '; echo size=\$(tput cols)x\$(tput lines)\r")
                assertTrue(waitFor(5000) { s.allText().contains("size=${screen()}") }, "экран ${screen()}\n" + s.allText())
            } finally {
                stop.set(true)
                s.close()
            }
        }
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
