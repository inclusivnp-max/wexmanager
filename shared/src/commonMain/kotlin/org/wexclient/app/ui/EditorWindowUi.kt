package org.wexclient.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.wexclient.app.editor.CodeEditorController
import org.wexclient.app.editor.EditorWork
import org.wexclient.app.editor.lang.Languages
import kotlinx.coroutines.runInterruptible
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
    class Loaded(val entry: VfsEntry, val decoded: DecodedText, val bytes: ByteArray, val controller: CodeEditorController)

    var loaded by remember(window.file) { mutableStateOf<Loaded?>(null) }

    LaunchedEffect(window.file) {
        val jobContext = coroutineContext
        val result = runInterruptible(EditorWork.loading) {
            runCatching {
                val bytes = vfsManager.readBytesLimited(window.file, MAX_BYTES)
                val decoded = TextCodec.decode(bytes)
                // Отказываем только если не справится и быстрый редактор
                if (org.wexclient.app.editor.EditorLimits.fastProblem(decoded.text) != null) {
                    error("слишком большой текст")
                }
                jobContext.ensureActive()
                val controller = CodeEditorController(decoded.text)
                controller.attachFile(decoded, bytes, window.file)
                controller.applyLanguage(Languages.detect(window.file.fileName.toString(), decoded.text.take(8192)))
                jobContext.ensureActive()
                Loaded(VfsEntry.of(window.file), decoded, bytes, controller)
            }
        }
        coroutineContext.ensureActive()
        result.onSuccess { loaded = it }.onFailure {
            if (it is CancellationException) throw it
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
        preparedController = data.controller,
        // Вызывается в фоне: ошибку редактор покажет сам и не снимет пометку «изменено»
        onSave = { bytes -> vfsManager.writeBytes(window.file, bytes) },
        onOpenFile = { target -> WexWindows.open(target) },
        onReveal = { onReveal(window.file) },
        onDismiss = onClose
    )
}

/** Столько же, сколько открывает панель: дальше это уже не текст для правки. */
private const val MAX_BYTES = org.wexclient.app.editor.EditorLimits.MAX_FILE_BYTES
