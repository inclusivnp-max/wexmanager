package org.wexclient.app.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.file.Path
import java.util.Properties

/** Настройки консоли (~/.config/wexdesktop/terminal.properties). */
object TerminalPreferences {
    private val file = org.wexclient.app.util.AppDirs.config.resolve("terminal.properties").toFile()

    var fontSize by mutableFloatStateOf(13f)
        private set
    var shell by mutableStateOf(ShellLocator.loginShell())
        private set
    /** Консоль переходит в папку панели при навигации. */
    var followPane by mutableStateOf(true)
        private set

    /** Курсор над консолью сразу даёт ей фокус — без щелчка. */
    var focusOnHover by mutableStateOf(true)
        private set
    /** Доля высоты панели под консоль. */
    var heightFraction by mutableFloatStateOf(0.42f)
        private set

    init {
        runCatching {
            if (file.isFile) {
                val p = Properties()
                file.inputStream().use { p.load(it) }
                p.getProperty("fontSize")?.toFloatOrNull()?.let { fontSize = it.coerceIn(8f, 32f) }
                p.getProperty("shell")?.takeIf { File(it).canExecute() }?.let { shell = it }
                p.getProperty("followPane")?.toBooleanStrictOrNull()?.let { followPane = it }
                p.getProperty("focusOnHover")?.toBooleanStrictOrNull()?.let { focusOnHover = it }
                p.getProperty("heightFraction")?.toFloatOrNull()?.let { heightFraction = it.coerceIn(0.15f, 0.85f) }
            }
        }
    }

    private fun save() {
        runCatching {
            file.parentFile.mkdirs()
            val p = Properties()
            p.setProperty("fontSize", fontSize.toString())
            p.setProperty("shell", shell)
            p.setProperty("followPane", followPane.toString())
            p.setProperty("focusOnHover", focusOnHover.toString())
            p.setProperty("heightFraction", heightFraction.toString())
            // Атомарно: сбой посреди записи не оставит пустых настроек
            org.wexclient.app.util.AtomicFiles.writeProperties(file, p, "WEX Desktop Terminal")
        }
    }

    /** Отложенная запись: за одну прокрутку колеса размер меняется десяток раз, файл пишем один. */
    private val saveLater by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "wex-terminal-prefs").apply { isDaemon = true }
        }
    }
    private var pendingSave: java.util.concurrent.ScheduledFuture<*>? = null

    fun zoom(delta: Float) {
        val next = if (delta == 0f) 13f else (fontSize + delta).coerceIn(8f, 32f)
        if (next == fontSize) return
        fontSize = next
        pendingSave?.cancel(false)
        pendingSave = runCatching {
            saveLater.schedule({ save() }, 400, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
        if (pendingSave == null) save()
    }

    /** Записать отложенный размер шрифта перед закрытием программы. */
    fun flush() {
        val waiting = pendingSave
        if (waiting != null && !waiting.isDone) {
            waiting.cancel(false)
            save()
        }
    }

    fun updateShell(s: String) { shell = s; save() }
    fun updateFollowPane(v: Boolean) { followPane = v; save() }
    fun updateFocusOnHover(v: Boolean) { focusOnHover = v; save() }
    fun updateHeightFraction(v: Float) { heightFraction = v.coerceIn(0.15f, 0.85f) }
    fun commitHeight() = save()
}

/** Фокус в какой-либо консоли — глобальные сочетания файлового менеджера не перехватывают ввод. */
object TerminalFocus {
    var active by mutableStateOf(false)
        internal set

    /** Фокус в поле поиска по выводу: корневые сочетания панелей не должны красть буквы. */
    var inField by mutableStateOf(false)
        internal set

    /** Клавиши сейчас принадлежат консоли (сам вывод или её поле поиска). */
    val owned: Boolean get() = active || inField
}

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
        // /system/bin/sh — оболочка Android (там нет ни /etc/passwd, ни /bin)
        return listOfNotNull(fromPasswd, System.getenv("SHELL"), "/bin/bash", "/bin/sh", "/system/bin/sh")
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
 * Консоль одной панели: вкладки с оболочками. Десктоп — PTY (pty4j) и эмулятор xterm (JediTerm),
 * Android — своя реализация.
 */
expect class TerminalPanelState(paneName: String) {
    /** В консоли прямо сейчас работает программа — закрытие вкладки её остановит. */
    val busy: Boolean

    /** Консоль открыта под списком файлов. */
    val visible: Boolean

    /** Консоль на всю высоту панели. */
    var maximized: Boolean

    /** Отдать клавиатуру файловому менеджеру (Esc в консоли). */
    var onRequestOuterFocus: () -> Unit

    /** Есть открытые вкладки. */
    val hasSessions: Boolean

    fun show(dir: Path)
    fun hide()
    fun toggle(dir: Path)

    /** Новая вкладка с оболочкой по умолчанию в папке [dir]. */
    fun newSession(dir: Path)

    /** Выполнить [command] в папке [dir]: в свободной вкладке или в новой. */
    fun runCommand(dir: Path, command: String)

    /** Закрыть все вкладки консоли и их программы. */
    fun dispose()
}

/** Консоль панели: вкладки оболочек под списком файлов. */
@androidx.compose.runtime.Composable
expect fun TerminalPanel(state: TerminalPanelState, currentDir: Path, onOpenPath: (Path) -> Unit, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier)

/** Перетаскиваемая граница между списком файлов и консолью. */
@androidx.compose.runtime.Composable
expect fun TerminalSplitter(totalHeightPx: () -> Float, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier)
