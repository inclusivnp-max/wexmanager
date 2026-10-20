package org.wexclient.app.vfs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.wexclient.app.util.TextClipboard
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Android: файлы держит сам WEX (пути внутри архивов системе не передать), а в системный буфер
 * кладём их пути текстом — их можно вставить в другую программу или обратно в WEX.
 */
actual object FileClipboard {
    private class Owned(val paths: List<Path>, val cut: Boolean, val text: String)

    @Volatile
    private var owned: Owned? = null

    private var cutState by mutableStateOf<Set<Path>>(emptySet())

    actual val cutPaths: Set<Path> get() = cutState

    actual fun copy(paths: List<Path>) = put(paths, cut = false)

    actual fun cut(paths: List<Path>) = put(paths, cut = true)

    private fun put(paths: List<Path>, cut: Boolean) {
        if (paths.isEmpty()) return
        val text = paths.joinToString("\n") { it.toString() }
        owned = Owned(paths, cut, text)
        cutState = if (cut) paths.toSet() else emptySet()
        TextClipboard.copy(text)
    }

    actual fun clearCut(moved: Collection<Path>) {
        val o = owned ?: return
        if (!o.cut) return
        owned = null
        cutState = emptySet()
    }

    actual fun read(): ClipContent? {
        val text = TextClipboard.text()
        val o = owned
        // Свои пути — пока в системном буфере тот же текст (другая программа его не заменила)
        if (o != null && (text == null || text == o.text)) return ClipContent.FileList(o.paths, o.cut)
        if (text.isNullOrEmpty()) return null
        // Пути, скопированные текстом (из другой программы или консоли), — тоже файлы
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val paths = lines.mapNotNull { line ->
            val p = line.removePrefix("file://")
            if (p.startsWith("/")) runCatching { Paths.get(p) }.getOrNull() else null
        }
        if (paths.isNotEmpty() && paths.size == lines.size && paths.all { Files.exists(it) }) {
            return ClipContent.FileList(paths, cut = false)
        }
        return ClipContent.PlainText(text)
    }
}
