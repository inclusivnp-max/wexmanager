package org.wexclient.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import org.wexclient.app.editor.DecodedText
import org.wexclient.app.editor.TextCodec
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsManager

/**
 * Отдельное окно редактора для текстового файла: открывается прямо на файле, без панелей за ним.
 * Кнопка «Расположение» в шапке показывает файл в окне менеджера.
 */
@Composable
fun EditorWindowUi(
    window: WexWindows.EditorWindow,
    vfsManager: VfsManager,
    onReveal: (Path) -> Unit,
    /** Открыть не удалось (двоичный, слишком большой): пусть решает менеджер. */
    onFallback: (Path) -> Unit,
    onClose: () -> Unit
) {
    class Loaded(val entry: VfsEntry, val decoded: DecodedText, val bytes: ByteArray)

    var loaded by remember(window.file) { mutableStateOf<Loaded?>(null) }

    LaunchedEffect(window.file) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = vfsManager.readBytesLimited(window.file, MAX_BYTES)
                val decoded = TextCodec.decode(bytes)
                // Отказываем только если не справится и быстрый редактор
                if (org.wexclient.app.editor.EditorLimits.fastProblem(decoded.text) != null) {
                    error("слишком большой текст")
                }
                Loaded(VfsEntry.of(window.file), decoded, bytes)
            }
        }
        result.onSuccess { loaded = it }.onFailure {
            onFallback(window.file)
            onClose()
        }
    }

    val data = loaded ?: return
    FileViewerDialog(
        entry = data.entry,
        content = data.decoded.text,
        decoded = data.decoded,
        originalBytes = data.bytes,
        // Вызывается в фоне: ошибку редактор покажет сам и не снимет пометку «изменено»
        onSave = { bytes -> vfsManager.writeBytes(window.file, bytes) },
        onOpenFile = { target -> WexWindows.open(target) },
        onReveal = { onReveal(window.file) },
        onDismiss = onClose
    )
}

/** Столько же, сколько открывает панель: дальше это уже не текст для правки. */
private const val MAX_BYTES = 2 * 1024 * 1024
