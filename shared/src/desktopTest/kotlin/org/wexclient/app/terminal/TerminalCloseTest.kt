package org.wexclient.app.terminal

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.wexclient.app.util.ProcessRunner
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Закрытие консоли — как закрытие настоящего терминала: PTY «вешает трубку», оболочка получает SIGHUP
 * и передаёт его своим заданиям. Раньше bash убивался SIGKILL, и запущенное в фоне жило дальше без консоли.
 * Всё здесь — на настоящем PTY и настоящих процессах; ищем их по уникальному аргументу sleep.
 */
class TerminalCloseTest {
    private val unix = File.separatorChar == '/'

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    /** Живые процессы с аргументом [arg]. У зомби строки запуска нет — они сюда не попадают. */
    private fun withArg(arg: String): List<ProcessHandle> =
        ProcessHandle.allProcesses().filter { h ->
            h.info().arguments().map { args -> arg in args }.orElse(false)
        }.toList()

    private fun killAll(vararg args: String) {
        for (a in args) withArg(a).forEach { it.destroyForcibly() }
    }

    /** Уникальный аргумент sleep: дробные секунды GNU sleep понимает, а с чужими процессами он не совпадёт. */
    private fun marker(): String = "${Random.nextInt(20_000, 90_000)}.${Random.nextInt(100, 999)}"

    private fun hangupKillsJobs(shell: String, id: Int, stuckPaste: Boolean = false) {
        val dir: Path = Files.createTempDirectory("wex-term-close")
        val bg = marker()
        val fg = marker()
        val kept = marker()
        val s = TerminalSession(id, dir, shell = shell)
        s.start()
        try {
            assertTrue(waitFor(8000) { s.allText().isNotBlank() }, "$shell не показал приглашение")
            s.sendText("sleep $bg &\r")
            assertTrue(waitFor(5000) { withArg(bg).isNotEmpty() }, s.allText())
            s.sendText("nohup sleep $kept >/dev/null 2>&1 &\r")
            assertTrue(waitFor(5000) { withArg(kept).isNotEmpty() }, s.allText())
            s.sendText("sleep $fg\r")
            assertTrue(waitFor(5000) { withArg(fg).isNotEmpty() && s.isBusy() }, s.allText())
            if (stuckPaste) {
                // sleep ввод не читает: очередь ввода PTY переполняется, и поток записи встаёт в write().
                // Строки — комментарии: если что-то пойдёт не так, оболочка их просто пропустит
                s.paste(("#" + "x".repeat(70) + "\n").repeat(4000))
                Thread.sleep(500)
            }

            s.close()
            assertTrue(
                waitFor(3000) { withArg(bg).isEmpty() && withArg(fg).isEmpty() },
                "$shell: после закрытия живы фоновый ${withArg(bg).map { it.pid() }} и передний ${withArg(fg).map { it.pid() }}"
            )
            // nohup SIGHUP не слушает — живёт, как после закрытия обычного терминала
            assertTrue(withArg(kept).isNotEmpty(), "$shell: задание под nohup не должно было умереть")
            assertFalse(s.isAlive, "$shell: оболочка жива после закрытия")
        } finally {
            s.close()
            killAll(bg, fg, kept)
            org.wexclient.app.util.FileTree.delete(dir)
        }
    }

    @Test
    fun `closing bash hangs up background and foreground jobs`() {
        assumeTrue(unix, "только Unix: PTY и SIGHUP")
        val bash = ProcessRunner.which("bash")
        assumeTrue(bash != null, "нет bash")
        hangupKillsJobs(bash!!, 41)
    }

    @Test
    fun `closing fish hangs up background and foreground jobs`() {
        assumeTrue(unix, "только Unix: PTY и SIGHUP")
        val fish = ProcessRunner.which("fish")
        assumeTrue(fish != null, "нет fish")
        hangupKillsJobs(fish!!, 42)
    }

    /**
     * Вставка застряла (программа на переднем плане ввод не читает) — поток записи JediTerm стоит в write().
     * Раньше обрыв PTY ждал в очереди за ним, bash получал SIGKILL и задания переживали вкладку.
     */
    @Test
    fun `closing bash with a stuck paste still hangs up jobs`() {
        assumeTrue(unix, "только Unix: PTY и SIGHUP")
        val bash = ProcessRunner.which("bash")
        assumeTrue(bash != null, "нет bash")
        hangupKillsJobs(bash!!, 44, stuckPaste = true)
    }

    @Test
    fun `closing fish with a stuck paste still hangs up jobs`() {
        assumeTrue(unix, "только Unix: PTY и SIGHUP")
        val fish = ProcessRunner.which("fish")
        assumeTrue(fish != null, "нет fish")
        hangupKillsJobs(fish!!, 45, stuckPaste = true)
    }

    @Test
    fun `closed session does not start`() {
        assumeTrue(unix, "только Unix")
        val dir = Files.createTempDirectory("wex-term-close")
        val m = marker()
        val s = TerminalSession(43, dir, command = listOf("sleep", m))
        try {
            s.close()
            s.start()
            assertFalse(s.started, "закрытая вкладка запустила процесс")
            Thread.sleep(200)
            assertTrue(withArg(m).isEmpty(), "процесс закрытой вкладки жив")
        } finally {
            killAll(m)
            org.wexclient.app.util.FileTree.delete(dir)
        }
    }

    /**
     * Закрытие прямо во время запуска: close() ещё не видит процесса, а start() его уже запускает.
     * Задержки разные — часть попыток попадает внутрь PtyProcessBuilder.start().
     */
    @Test
    fun `close during start leaves no process`() {
        assumeTrue(unix, "только Unix")
        val dir = Files.createTempDirectory("wex-term-close")
        val markers = ArrayList<String>()
        try {
            repeat(24) { i ->
                val m = marker()
                markers += m
                val s = TerminalSession(100 + i, dir, command = listOf("sleep", m))
                s.sendWhenReady("echo pending\r")
                val starter = Thread { s.start() }
                starter.start()
                if (i % 4 != 0) Thread.sleep((i % 6).toLong())
                s.close()
                starter.join(10_000)
            }
            for (m in markers) {
                assertTrue(waitFor(3000) { withArg(m).isEmpty() }, "после закрытия во время запуска жив sleep $m")
            }
        } finally {
            killAll(*markers.toTypedArray())
            org.wexclient.app.util.FileTree.delete(dir)
        }
    }
}
