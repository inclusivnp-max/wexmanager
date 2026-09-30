package org.wexclient.app.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.core.typeahead.TerminalTypeAheadManager
import com.jediterm.core.typeahead.TypeAheadTerminalModel
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.TerminalExecutorServiceManager
import com.jediterm.terminal.TerminalMode
import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import com.jediterm.core.Color as JColor
import org.wexclient.app.util.ChildEnv

/** Оболочки системы: из /etc/passwd, $SHELL и /etc/shells; на Windows — PowerShell и cmd. */
object ShellLocator {
    private val isWindows = File.separatorChar == '\\'

    fun loginShell(): String {
        if (isWindows) return windowsShells().first()
        val user = System.getProperty("user.name")
        val fromPasswd = runCatching {
            File("/etc/passwd").useLines { lines ->
                lines.map { it.split(':') }.firstOrNull { it.size >= 7 && it[0] == user }?.get(6)
            }
        }.getOrNull()
        return listOfNotNull(fromPasswd, System.getenv("SHELL"), "/bin/bash", "/bin/sh")
            .firstOrNull { it.isNotBlank() && File(it).canExecute() } ?: "/bin/sh"
    }

    /** Установленные оболочки без дублей (/bin и /usr/bin — одно и то же). */
    fun available(): List<String> {
        if (isWindows) return windowsShells()
        val listed = runCatching { File("/etc/shells").readLines() }.getOrDefault(emptyList())
            .map { it.trim() }.filter { it.startsWith("/") && File(it).canExecute() }
        val byName = LinkedHashMap<String, String>()
        for (s in listOf(loginShell()) + listed) {
            val name = s.substringAfterLast('/')
            if (name in setOf("nologin", "false", "git-shell", "rbash", "systemd-home-fallback-shell", "tmux", "screen")) continue
            byName.putIfAbsent(name, s)
        }
        return byName.values.toList()
    }

    /** PowerShell 7 (если стоит), встроенный PowerShell и cmd — полными путями: настройка проверяет файл. */
    private fun windowsShells(): List<String> {
        val root = System.getenv("SystemRoot") ?: "C:\\Windows"
        return listOfNotNull(
            org.wexclient.app.util.ProcessRunner.which("pwsh"),
            "$root\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
            System.getenv("ComSpec") ?: "$root\\System32\\cmd.exe"
        ).filter { File(it).isFile }.distinct().ifEmpty { listOf("cmd.exe") }
    }

    /** Имя оболочки для заголовка вкладки: fish, powershell.exe. */
    fun displayName(shell: String): String = shell.substringAfterLast('/').substringAfterLast('\\')
}

/**
 * Палитра ANSI (16 цветов). Берётся из выбранной темы: вывод программ в консоли
 * раскрашивается так же, как само окно, а не всегда одной зашитой схемой.
 */
internal class WexPalette : com.jediterm.terminal.emulator.ColorPalette() {

    override fun getForegroundByColorIndex(colorIndex: Int): JColor = current[colorIndex.coerceIn(0, 15)]
    override fun getBackgroundByColorIndex(colorIndex: Int): JColor = current[colorIndex.coerceIn(0, 15)]

    companion object {
        val INSTANCE = WexPalette()

        // Цвета готовим заранее: рисование зовёт палитру для каждого отрезка текста
        @Volatile
        private var current: Array<JColor> = build(org.wexclient.app.theme.TerminalPalettes.of(org.wexclient.app.theme.AppTheme.NOCTALIA))

        private fun build(rgb: IntArray): Array<JColor> = Array(16) { JColor(rgb[it]) }

        /** Сменилась тема окна — пересобираем цвета ANSI. */
        fun apply(theme: org.wexclient.app.theme.AppTheme) {
            current = build(org.wexclient.app.theme.TerminalPalettes.of(theme))
        }
    }
}

private class DaemonExecutors(name: String) : TerminalExecutorServiceManager {
    private val counter = AtomicInteger()
    private val single: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "$name-io").apply { isDaemon = true }
    }
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "$name-pool-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    override fun getSingleThreadScheduledExecutor(): ScheduledExecutorService = single
    override fun getUnboundedExecutorService() = pool
    override fun shutdownWhenAllExecuted() {
        single.shutdown()
        pool.shutdown()
    }

    /** Сеанс закрыт: очередь записи в оболочку больше некому читать — недописанное бросаем. */
    fun shutdownNow() {
        single.shutdownNow()
        pool.shutdownNow()
    }
}

/** Предугадывание ввода (type-ahead) выключено: локальный терминал отвечает мгновенно. */
private object NoTypeAhead : TypeAheadTerminalModel {
    override fun insertCharacter(ch: Char, index: Int) = Unit
    override fun removeCharacters(from: Int, count: Int) = Unit
    override fun moveCursor(index: Int) = Unit
    override fun forceRedraw() = Unit
    override fun clearPredictions() = Unit
    override fun lock() = Unit
    override fun unlock() = Unit
    override fun isUsingAlternateBuffer() = false
    override fun getCurrentLineWithCursor() = TypeAheadTerminalModel.LineWithCursorX(StringBuffer(), 0)
    override fun getTerminalWidth() = 80
    override fun isTypeAheadEnabled() = false
    override fun getLatencyThreshold() = 0L
    override fun getShellType() = TypeAheadTerminalModel.ShellType.Unknown
}

private class PtyConnector(val process: PtyProcess, cmd: List<String>) :
    ProcessTtyConnector(process, Charsets.UTF_8, cmd) {
    override fun getName(): String = "wex-pty"

    override fun resize(termSize: TermSize) {
        if (process.isAlive) runCatching { process.winSize = WinSize(termSize.columns, termSize.rows) }
    }

    override fun isConnected(): Boolean = process.isAlive
}

/**
 * Сеанс встроенной консоли: процесс оболочки в PTY + модель экрана JediTerm.
 * Поля-состояния читаются интерфейсом; поток эмулятора только увеличивает [version].
 */
class TerminalSession(
    val id: Int,
    val startDir: Path,
    val shell: String = ShellLocator.loginShell(),
    /** Своя команда вместо оболочки — ssh на сервер или adb shell на телефон; null — оболочка [shell]. */
    val command: List<String>? = null,
    /** Где идёт сеанс («ssh user@host», «adb Pixel 9»): консоль панели на сервере или телефоне. */
    val remote: String? = null,
    initialColumns: Int = 100,
    initialRows: Int = 30,
    /** Цвета окна для ответов на запросы OSC 10/11 (приложения узнают, тёмная ли тема). */
    var windowForeground: JColor = JColor(0xEDE0DE),
    var windowBackground: JColor = JColor(0x181211)
) {
    /** Растёт при каждом изменении экрана — ключ перерисовки. */
    var version by mutableLongStateOf(0L)
        private set
    var title by mutableStateOf(remote ?: ShellLocator.displayName(shell))
        private set
    var exitCode by mutableStateOf<Int?>(null)
        private set
    var startError by mutableStateOf<String?>(null)
        private set
    var bellAt by mutableLongStateOf(0L)
        private set

    /** Прокрутка в историю: 0 — низ, отрицательные значения — строки истории. */
    var scrollOrigin by mutableIntStateOf(0)
    var selection by mutableStateOf<TerminalSelection?>(null)

    @Volatile var cursorX = 0
        private set
    @Volatile var cursorY = 1
        private set
    @Volatile var cursorVisible = true
        private set
    @Volatile var cursorShape: CursorShape? = null
        private set
    @Volatile var mouseMode: MouseMode = MouseMode.MOUSE_REPORTING_NONE
        private set
    @Volatile var bracketedPaste = false
        private set
    @Volatile var alternateScreen = false
        private set

    val styleState = StyleState()
    val buffer = TerminalTextBuffer(initialColumns, initialRows, styleState, HISTORY_LINES)
    private val display = Display()
    val terminal = JediTerminal(display, buffer, styleState)
    private val executors = DaemonExecutors("wex-term-$id")
    private var connector: PtyConnector? = null
    private var starter: TerminalStarter? = null

    val columns: Int get() = buffer.width
    val rows: Int get() = buffer.height
    val pid: Long? get() = connector?.process?.pid()
    val isAlive: Boolean get() = connector?.process?.isAlive == true

    private inner class Display : TerminalDisplay {
        override fun setCursor(x: Int, y: Int) {
            cursorX = x
            cursorY = y
        }

        override fun setCursorShape(shape: CursorShape?) {
            cursorShape = shape
        }

        override fun beep() {
            bellAt = System.currentTimeMillis()
        }

        override fun onResize(newTermSize: TermSize, origin: RequestOrigin) = Unit

        override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) {
            // Пользователь смотрит историю — держим ту же строку перед глазами. В историю строки уходят,
            // только когда экран прокручивается вверх с первой строки (так устроен TerminalTextBuffer).
            // Прокрутка вниз (обратный перевод строки, вставка строк) и прокрутка части экрана историю
            // не меняют: сдвигать вид на них нельзя — иначе просмотр истории уезжал вниз, пока программа
            // перерисовывала экран
            if (dy >= 0 || scrollRegionTop != 1) return
            val origin = scrollOrigin
            if (origin < 0) {
                // Вызов приходит до переноса строк: история станет длиннее на -dy, но не больше предела
                val grown = (buffer.historyLinesCount - dy).coerceAtMost(HISTORY_LINES)
                scrollOrigin = (origin + dy).coerceIn(-grown, 0)
            }
        }

        override fun setCursorVisible(visible: Boolean) {
            cursorVisible = visible
        }

        override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) {
            alternateScreen = useAlternateScreenBuffer
            scrollOrigin = 0
        }

        override fun getWindowTitle(): String = this@TerminalSession.title

        override fun setWindowTitle(windowTitle: String) {
            title = windowTitle
        }

        override fun getSelection(): TerminalSelection? = this@TerminalSession.selection

        override fun terminalMouseModeSet(mode: MouseMode) {
            mouseMode = mode
        }

        override fun setMouseFormat(format: MouseFormat) = Unit

        override fun ambiguousCharsAreDoubleWidth(): Boolean = false

        override fun setBracketedPasteMode(enabled: Boolean) {
            bracketedPaste = enabled
        }

        override fun getWindowForeground(): JColor = this@TerminalSession.windowForeground
        override fun getWindowBackground(): JColor = this@TerminalSession.windowBackground
    }

    // Перерисовка не чаще кадра (~60 в секунду), даже если вывод идёт потоком. Первое изменение
    // после паузы рисуется сразу: эхо набранной буквы не ждёт лишние 16 мс, как раньше
    private val redrawScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastRedrawNanos = 0L

    private fun requestRedraw() {
        if (!redrawScheduled.compareAndSet(false, true)) return
        val wait = FRAME_NANOS - (System.nanoTime() - lastRedrawNanos)
        val redraw = Runnable {
            redrawScheduled.set(false)
            lastRedrawNanos = System.nanoTime()
            version++
        }
        runCatching {
            if (wait <= 0) redrawTimer.execute(redraw)
            else redrawTimer.schedule(redraw, wait, java.util.concurrent.TimeUnit.NANOSECONDS)
        }.onFailure {
            redrawScheduled.set(false)
            version++
        }
    }

    /** Перерисовать после изменений интерфейса (выделение, прокрутка). */
    fun invalidate() {
        version++
    }

    init {
        buffer.addModelListener { requestRedraw() }
        terminal.setModeEnabled(TerminalMode.AltSendsEscape, true)
    }

    /** Оболочка уже запущена (или хотя бы канал с ней открыт). */
    val started: Boolean get() = connector != null

    @Volatile
    private var starting = false

    /**
     * Запустить оболочку один раз и не зависеть от того, кто попросил: раньше запуск жил в корутине
     * окна, и переключение вкладки в этот момент могло оставить консоль пустой — без процесса.
     */
    fun ensureStarted(cols: Int, rowsCount: Int) {
        synchronized(this) {
            if (connector != null || starting || exitCode != null) return
            starting = true
        }
        runCatching { resize(cols, rowsCount) }
        Thread({
            try {
                start()
            } finally {
                starting = false
            }
        }, "wex-term-$id-start").apply { isDaemon = true }.start()
    }

    /** Запуск оболочки. Ошибка запуска — в [startError]. */
    fun start() {
        if (connector != null) return
        val cmd = command ?: listOf(shell)
        try {
            // Окружение пользователя: JAVA_HOME и JAVA_OPTS лаунчера нужны только самому WEX
            val env = ChildEnv.userEnv()
            env["TERM"] = "xterm-256color"
            env["COLORTERM"] = "truecolor"
            env["TERM_PROGRAM"] = "WEXDesktop"
            env.remove("JAVA_TOOL_OPTIONS")
            if (System.getProperty("wex.isolated") == "true") {
                // Тесты: своя домашняя папка и без истории — команды тестов не попадают в историю пользователя
                val home = System.getProperty("user.home")
                env["HOME"] = home
                env["XDG_CONFIG_HOME"] = "$home/.config"
                env["XDG_DATA_HOME"] = "$home/.local/share"
                env["HISTFILE"] = "/dev/null"
                env["fish_history"] = ""
            }
            val dir = startDir.takeIf { Files.isDirectory(it) } ?: Paths.get(System.getProperty("user.home"))
            val process = PtyProcessBuilder(cmd.toTypedArray())
                .setEnvironment(env)
                .setDirectory(dir.toString())
                .setInitialColumns(buffer.width)
                .setInitialRows(buffer.height)
                .setConsole(false)
                .setRedirectErrorStream(true)
                .start()
            val c = PtyConnector(process, cmd)
            connector = c
            val typeAhead = TerminalTypeAheadManager(NoTypeAhead)
            val s = TerminalStarter(terminal, c, TtyBasedArrayDataStream(c), typeAhead, executors)
            synchronized(pendingInput) {
                starter = s
                // Команды, отправленные до запуска (кнопка «Запуск»): оболочка прочтёт их после старта
                if (pendingInput.isNotEmpty()) {
                    s.sendString(pendingInput.toString(), false)
                    pendingInput.setLength(0)
                }
            }
            Thread({
                runCatching { s.start() }
            }, "wex-term-$id-emulator").apply { isDaemon = true }.start()
            Thread({
                val code = runCatching { process.waitFor() }.getOrDefault(-1)
                // Дочитать вывод перед сообщением о завершении
                Thread.sleep(80)
                exitCode = code
                version++
            }, "wex-term-$id-wait").apply { isDaemon = true }.start()
        } catch (e: Throwable) {
            startError = e.message ?: e.javaClass.simpleName
            exitCode = -1
        }
    }

    fun resize(cols: Int, rowsCount: Int) {
        val c = cols.coerceAtLeast(5)
        val r = rowsCount.coerceAtLeast(2)
        if (c == buffer.width && r == buffer.height) return
        val s = starter
        if (s != null) s.postResize(TermSize(c, r), RequestOrigin.User)
        else terminal.resize(TermSize(c, r), RequestOrigin.User)
    }

    private val pendingInput = StringBuilder()

    // userInput = false: с true JediTerm заводит объект предугадывания на каждый символ, а предугадывание
    // выключено — вставка в мегабайт оборачивалась миллионом лишних объектов
    fun sendText(text: String) {
        if (text.isEmpty()) return
        scrollOrigin = 0
        starter?.sendString(text, false)
    }

    /** Как [sendText], но до запуска оболочки текст копится и уходит сразу после старта. */
    fun sendWhenReady(text: String) {
        if (text.isEmpty()) return
        synchronized(pendingInput) {
            val s = starter
            if (s == null) {
                pendingInput.append(text)
                return
            }
            scrollOrigin = 0
            s.sendString(text, false)
        }
    }

    /** Выполнить команду в папке [dir]: Ctrl+U стирает набранное, затем cd и команда. */
    fun runIn(dir: Path, command: String) {
        val where = dir.toString()
        if (ScriptRunner.hasControlChars(where) || ScriptRunner.hasControlChars(command)) {
            throw java.io.IOException("В имени есть управляющие символы — такую команду безопаснее набрать вручную")
        }
        if (windowsShell) {
            // PowerShell 5.1 не знает «&&»: команда — только если переход удался
            val line = if (isCmd) "${windowsCd(where)} && $command" else "${windowsCd(where)}; if (\$?) { $command }"
            // До запуска оболочки стирать нечего
            if (starter == null) sendWhenReady("$line\r") else sendWindowsLine("$line\r")
            return
        }
        sendWhenReady("\u0015cd -- ${ScriptRunner.word(where)} && $command\r")
    }

    /**
     * Windows: набранное стирает Esc, но winpty и ConPTY читают «Esc + символ» одной порцией как Alt+символ —
     * команда приклеивалась бы к набранному. Esc уходит отдельно, команда — следом через 60 мс, тем же
     * потоком записи (порядок сохраняется).
     */
    private fun sendWindowsLine(line: String) {
        sendText("\u001b")
        runCatching {
            executors.singleThreadScheduledExecutor.schedule({ sendText(line) }, 60, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.onFailure { sendText(line) }
    }

    /** Своя оболочка Windows (PowerShell или cmd), а не ssh/adb: у неё свой синтаксис. */
    private val windowsShell: Boolean get() = File.separatorChar == '\\' && command == null && remote == null
    private val isCmd: Boolean get() = ShellLocator.displayName(shell).equals("cmd.exe", ignoreCase = true)

    /**
     * Переход в папку для оболочки Windows. В PowerShell внутри '…' особые только кавычки
     * (и типографские ‘’‚‛ — их PowerShell тоже считает кавычками): удваиваем. В cmd кавычки
     * в имени файла невозможны. Набранное стирает Esc — Ctrl+U там не работает.
     */
    private fun windowsCd(where: String): String =
        if (isCmd) "cd /d \"$where\""
        else "Set-Location -LiteralPath '" + where.replace(Regex("['\u2018\u2019\u201A\u201B]")) { it.value + it.value } + "'"

    fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // Ctrl+C прерывает и недописанную вставку: иначе он ждал бы в очереди, пока оболочка переварит весь текст
        if (bytes.size == 1 && bytes[0] == ETX) cancelPaste()
        scrollOrigin = 0
        starter?.sendBytes(bytes, false)
    }

    /** Код клавиши (VK_*) с модификаторами (маски InputEvent) — последовательность для приложения. */
    fun sendKey(keyCode: Int, modifiers: Int): Boolean {
        // Копия: jediterm правит байты прямо в своей общей таблице, и после одного Shift+F1
        // обычный F1 начинал слать код от Shift+F1
        val code = terminal.getCodeForKey(keyCode, modifiers)?.copyOf() ?: return false
        sendBytes(code)
        return true
    }

    /** Растёт при Ctrl+C и закрытии: вставки, начатые раньше, дальше не пишутся. */
    private val pasteTicket = AtomicInteger()

    private fun cancelPaste() {
        pasteTicket.incrementAndGet()
    }

    /**
     * Вставка с заменой переводов строк на CR и режимом bracketed paste. Текст уходит в оболочку
     * кусками: раньше он писался одним вызовом, и поток записи висел, пока оболочка не прочтёт
     * всё (fish разбирает 250 КБ секунд пять) — Ctrl+C и закрытие вкладки ждали за ним в очереди.
     * Теперь между кусками проверяется отмена, и после неё дописывается только конец рамки.
     */
    fun paste(text: String) {
        val c = connector ?: return
        if (starter == null) return
        val body = preparePaste(text, bracketed = false).toByteArray(Charsets.UTF_8)
        if (body.isEmpty()) return
        val bracketed = bracketedPaste
        val ticket = pasteTicket.get()
        scrollOrigin = 0
        // Тот же поток, что пишет нажатия и ответы эмулятора: набранное после вставки придёт после неё
        runCatching {
            executors.singleThreadScheduledExecutor.execute { writePaste(c, body, bracketed, ticket) }
        }
    }

    private fun writePaste(c: PtyConnector, body: ByteArray, bracketed: Boolean, ticket: Int) {
        runCatching {
            if (bracketed) c.write(PASTE_START)
            var from = 0
            while (from < body.size && pasteTicket.get() == ticket && !closed) {
                val to = pasteChunkEnd(body, from, PASTE_CHUNK)
                c.write(body.copyOfRange(from, to))
                from = to
            }
            // Рамку закрываем и после отмены — иначе оболочка так и ждала бы конца вставки
            if (bracketed && !closed) c.write(PASTE_END)
        }
    }

    /** Текущая папка оболочки (по /proc). У ssh-сеанса она на сервере — не знаем. */
    fun currentDirectory(): Path? {
        if (remote != null) return null
        val p = pid ?: return null
        return runCatching { Files.readSymbolicLink(Paths.get("/proc/$p/cwd")) }.getOrNull()
    }

    /** Занята ли оболочка программой (в фоне оболочки идёт другая группа процессов). */
    fun isBusy(): Boolean {
        // Что идёт на сервере, отсюда не видно — считаем занятым и ничего не набираем за пользователя
        if (remote != null) return true
        val p = pid ?: return false
        // Windows: /proc нет — занята, если у оболочки есть живой дочерний процесс
        if (File.separatorChar == '\\') return runCatching {
            ProcessHandle.of(p).map { h -> h.children().anyMatch { it.isAlive } }.orElse(false)
        }.getOrDefault(true)
        return runCatching {
            val stat = File("/proc/$p/stat").readText()
            // После «(имя)»: состояние, ppid, pgrp, session, tty_nr, tpgid
            val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
            val pgrp = fields[2].toLong()
            val tpgid = fields[5].toLong()
            tpgid > 0 && tpgid != pgrp
        }.getOrDefault(false)
    }

    /** Перейти в папку (если оболочка свободна): Ctrl+U очищает набранное, затем cd. */
    fun changeDirectory(dir: Path): Boolean {
        if (!isAlive || isBusy()) return false
        val where = dir.toString()
        // Экранирование '…'\''…' в fish обходилось именем с «\»; управляющие символы сработали бы как клавиши
        if (ScriptRunner.hasControlChars(where)) return false
        if (windowsShell) sendWindowsLine("${windowsCd(where)}\r")
        else sendText("\u0015cd -- ${ScriptRunner.word(where)}\r")
        return true
    }

    /**
     * Сброс консоли. Программа может выйти, не вернув терминал в обычный режим: тогда перевод
     * строки перестаёт возвращать каретку, и набранное встаёт «лесенкой». `stty sane` чинит режим
     * tty, а RIS (ESC c) — состояние самого эмулятора (перенос строк, области прокрутки, цвета).
     */
    fun resetTerminal() {
        // Ctrl+C — бросить недонабранное, иначе команда приклеится к нему
        sendBytes(byteArrayOf(3))
        // stty и printf в PowerShell и cmd нет; cls есть в обоих
        if (windowsShell) sendText("cls\r") else sendText("stty sane; printf '\u001bc'\r")
    }

    fun clearScreenAndHistory() {
        buffer.lock()
        try {
            buffer.clearHistory()
        } finally {
            buffer.unlock()
        }
        // Ctrl+L — оболочка перерисует приглашение
        sendBytes(byteArrayOf(12))
        scrollOrigin = 0
        version++
    }

    /** Весь текст экрана и истории. */
    fun allText(): String {
        buffer.lock()
        try {
            val sb = StringBuilder()
            val history = buffer.historyLinesStorage
            for (i in 0 until history.size) sb.append(history.get(i).text.trimEnd()).append('\n')
            sb.append(buffer.getScreenLines().trimEnd())
            return sb.toString()
        } finally {
            buffer.unlock()
        }
    }

    /** Найденное место в выводе: строка (отрицательная — история) и колонки. */
    class Found(val line: Int, val from: Int, val to: Int)

    /**
     * Поиск по видимому экрану и всей истории. Возвращает места сверху вниз; строки истории
     * нумеруются отрицательно — так же, как их адресует прокрутка ([scrollOrigin]).
     */
    fun find(query: String, limit: Int = 5000): List<Found> {
        if (query.isBlank()) return emptyList()
        val needle = query.lowercase()
        val out = ArrayList<Found>()
        buffer.lock()
        try {
            val history = buffer.historyLinesStorage
            val historySize = history.size
            for (i in 0 until historySize) {
                if (out.size >= limit) return out
                // Хранилище истории иногда отдаёт пустое место — молча пропускаем
                val text = runCatching { history.get(i)?.text }.getOrNull() ?: continue
                collectMatches(text, needle, i - historySize, out, limit)
            }
            // Экран берём одной строкой: обращение к строке по номеру за пределами заполненного
            // буфера падало с NullPointerException
            val screen = runCatching { buffer.getScreenLines() }.getOrDefault("").split('\n')
            for ((row, text) in screen.withIndex()) {
                if (out.size >= limit) return out
                if (row >= buffer.height) break
                collectMatches(text, needle, row, out, limit)
            }
        } finally {
            buffer.unlock()
        }
        return out
    }

    private fun collectMatches(text: String, needle: String, line: Int, out: MutableList<Found>, limit: Int) {
        val lower = text.lowercase()
        var at = lower.indexOf(needle)
        while (at >= 0 && out.size < limit) {
            out.add(Found(line, at, at + needle.length))
            at = lower.indexOf(needle, at + needle.length)
        }
    }

    /** Показать найденное и выделить его — как будто пользователь выделил мышью. */
    fun reveal(found: Found) {
        val hist = buffer.let {
            it.lock()
            try {
                it.historyLinesCount
            } finally {
                it.unlock()
            }
        }
        // Строка истории -123 видна, когда прокрутка стоит не ниже неё
        val target = when {
            found.line >= 0 -> 0
            else -> (found.line - buffer.height / 3).coerceIn(-hist, 0)
        }
        scrollOrigin = target
        selection = com.jediterm.terminal.model.TerminalSelection(
            com.jediterm.core.compatibility.Point(found.from, found.line),
            com.jediterm.core.compatibility.Point(found.to, found.line)
        )
        version++
    }

    companion object {
        private const val ESC = '\u001B'
        private const val ETX: Byte = 3

        private val PASTE_START = "$ESC[200~".toByteArray()
        private val PASTE_END = "$ESC[201~".toByteArray()

        /** Кусок вставки: столько же вмещает очередь ввода PTY — отмена срабатывает почти сразу. */
        private const val PASTE_CHUNK = 4096

        /** Конец куска вставки [from, результат): не больше [max] байт и не посреди символа UTF-8. */
        internal fun pasteChunkEnd(bytes: ByteArray, from: Int, max: Int): Int {
            var to = min(bytes.size, from + max)
            // Байты продолжения символа — 10xxxxxx: отступаем к его началу
            while (to < bytes.size && to > from + 1 && (bytes[to].toInt() and 0xC0) == 0x80) to--
            return to
        }

        /**
         * Перерисовка — на своём потоке, общем для всех вкладок: исполнитель JediTerm пишет в PTY
         * и ждёт, пока программа прочтёт ввод (вставка большого текста во время make) — экран бы замер.
         */
        private val redrawTimer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "wex-terminal-redraw").apply { isDaemon = true }
        }

        /**
         * Текст из буфера — не команды терминалу. Управляющие символы убираются: «ESC[201~» внутри
         * вставки закончил бы режим bracketed paste, и остаток выполнился бы без Enter (paste-jacking);
         * ^C, ^U и прочие тоже не должны срабатывать из буфера. Переводы строк — CR, как от клавиатуры.
         */
        internal fun preparePaste(text: String, bracketed: Boolean): String {
            val clean = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> { clean.append('\r'); i++ }
                    c == '\n' || c == '\r' -> clean.append('\r')
                    c == '\t' -> clean.append(c)
                    c < ' ' || c == '\u007F' || c in '\u0080'..'\u009F' -> Unit
                    else -> clean.append(c)
                }
                i++
            }
            return if (bracketed) "$ESC[200~$clean$ESC[201~" else clean.toString()
        }
    }

    @Volatile
    private var closed = false

    /** Закрыть сессию; остановка оболочки (с ожиданием) — не в потоке окна. */
    fun close() {
        if (closed) return
        closed = true
        Thread({ shutdown() }, "wex-term-$id-close").apply { isDaemon = true }.start()
    }

    /** То же с ожиданием — при выходе из программы. */
    fun closeNow() {
        if (closed) return
        closed = true
        shutdown()
    }

    private fun shutdown() {
        cancelPaste()
        runCatching { starter?.requestEmulatorStop() }
        runCatching { starter?.close() }
        runCatching {
            val p = connector?.process
            if (p != null && p.isAlive) {
                p.destroy()
                if (!p.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
            }
        }
        runCatching { connector?.close() }
        executors.shutdownNow()
    }
}

/** Строк истории у каждой вкладки консоли. */
internal const val HISTORY_LINES = 10_000

/** Не чаще одной перерисовки за кадр. */
private const val FRAME_NANOS = 16_000_000L
