package org.wexclient.app.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Оболочка Android (/system/bin/sh) без PTY: команды, их вывод и `cd` между командами работают,
 * полноэкранные программы (vim, top) — нет, им нужен терминал (это отдельная часть, через NDK).
 */
class ShellSession(startDir: Path) {
    val id = ids.incrementAndGet()
    var title by mutableStateOf("sh")
        private set
    var output by mutableStateOf("")
        private set
    var exitCode by mutableStateOf<Int?>(null)
        private set

    private val buffer = StringBuilder()
    private val process: Process? = runCatching {
        ProcessBuilder(ShellLocator.loginShell())
            .directory(startDir.takeIf { it.fileSystem === FileSystems.getDefault() && Files.isDirectory(it) }?.toFile() ?: File("/"))
            .redirectErrorStream(true)
            .start()
    }.onFailure { append("Оболочка не запустилась: ${it.message}\n") }.getOrNull()

    init {
        val p = process
        if (p != null) {
            Thread({
                val bytes = ByteArray(8192)
                val input = p.inputStream
                while (true) {
                    val n = runCatching { input.read(bytes) }.getOrDefault(-1)
                    if (n < 0) break
                    append(String(bytes, 0, n, Charsets.UTF_8))
                }
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                append("\n[процесс завершён: $code]\n")
                exitCode = code
            }, "wex-sh-${id}").apply { isDaemon = true }.start()
        }
    }

    @Synchronized
    private fun append(text: String) {
        buffer.append(stripAnsi(text))
        // Вывод бесконечной команды не должен съесть память: держим хвост
        if (buffer.length > MAX_CHARS) buffer.delete(0, buffer.length - MAX_CHARS)
        output = buffer.toString()
    }

    fun send(line: String) {
        val p = process ?: return
        append("$ $line\n")
        runCatching {
            p.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
        }.onFailure { append("Не отправлено: ${it.message}\n") }
    }

    val running: Boolean get() = process?.isAlive == true

    fun close() {
        runCatching { process?.destroy() }
    }

    private companion object {
        val ids = AtomicInteger()
        const val MAX_CHARS = 200_000
        val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[()][0-9A-B]|\r")
        fun stripAnsi(s: String) = ansi.replace(s, "")
    }
}

actual class TerminalPanelState actual constructor(val paneName: String) {
    val sessions = mutableStateListOf<ShellSession>()
    var activeIndex by mutableIntStateOf(0)

    private var visibleState by mutableStateOf(false)
    actual val visible: Boolean get() = visibleState
    actual var maximized by mutableStateOf(false)
    actual var onRequestOuterFocus: () -> Unit = {}
    actual val hasSessions: Boolean get() = sessions.isNotEmpty()

    // Без PTY не узнать, ждёт ли оболочка ввода или выполняет команду — закрытие вкладки не останавливаем
    actual val busy: Boolean get() = false

    val active: ShellSession? get() = sessions.getOrNull(activeIndex.coerceIn(0, (sessions.size - 1).coerceAtLeast(0)))

    actual fun show(dir: Path) {
        visibleState = true
        if (sessions.isEmpty()) newSession(dir)
    }

    actual fun hide() {
        visibleState = false
        onRequestOuterFocus()
    }

    actual fun toggle(dir: Path) = if (visible) hide() else show(dir)

    actual fun newSession(dir: Path) {
        sessions += ShellSession(dir)
        activeIndex = sessions.lastIndex
        visibleState = true
    }

    actual fun runCommand(dir: Path, command: String) {
        show(dir)
        val session = active?.takeIf { it.running } ?: ShellSession(dir).also {
            sessions += it
            activeIndex = sessions.lastIndex
        }
        session.send("cd ${ScriptRunner.quote(dir.toString())} && $command")
    }

    fun close(session: ShellSession) {
        session.close()
        sessions.remove(session)
        activeIndex = activeIndex.coerceIn(0, (sessions.size - 1).coerceAtLeast(0))
        if (sessions.isEmpty()) hide()
    }

    actual fun dispose() {
        sessions.forEach { it.close() }
        sessions.clear()
    }
}

@Composable
actual fun TerminalPanel(state: TerminalPanelState, currentDir: Path, onOpenPath: (Path) -> Unit, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                state.sessions.forEachIndexed { i, s ->
                    val current = i == state.activeIndex
                    Row(
                        Modifier
                            .background(
                                if (current) NoctaliaPrimary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { state.activeIndex = i }
                            .padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${s.title} ${i + 1}", fontSize = 12.sp, color = if (current) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface)
                        IconButton(onClick = { state.close(s) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть вкладку", modifier = Modifier.size(14.dp))
                        }
                    }
                }
                IconButton(onClick = { state.newSession(currentDir) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Новая вкладка", modifier = Modifier.size(18.dp))
                }
            }
            val session = state.active
            val scroll = rememberScrollState()
            LaunchedEffect(session?.output?.length) { scroll.scrollTo(scroll.maxValue) }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                SelectionContainer {
                    Text(
                        session?.output.orEmpty(),
                        fontFamily = AppFonts.JetBrainsMono,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll)
                    )
                }
            }
            var line by remember { mutableStateOf("") }
            val send = {
                if (line.isNotBlank()) session?.send(line)
                line = ""
            }
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = line,
                    onValueChange = { line = it },
                    singleLine = true,
                    placeholder = { Text("команда", fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = AppFonts.JetBrainsMono),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = send) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Выполнить", tint = NoctaliaPrimary)
                }
            }
        }
    }
}

@Composable
actual fun TerminalSplitter(totalHeightPx: () -> Float, modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { TerminalPreferences.commitHeight() }) { change, drag ->
                    change.consume()
                    val total = totalHeightPx().coerceAtLeast(1f)
                    TerminalPreferences.updateHeightFraction(TerminalPreferences.heightFraction - drag.y / total)
                }
            }
    ) {
        Box(
            Modifier
                .width(48.dp)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
        )
    }
}
