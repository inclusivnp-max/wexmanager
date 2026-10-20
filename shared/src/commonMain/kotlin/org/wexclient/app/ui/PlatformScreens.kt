package org.wexclient.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerIcon
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsManager
import java.nio.file.Path

/** Что сделать с перетащенными файлами. */
enum class DropAction { COPY, MOVE, LINK }

/**
 * Мышью строку переносят, только подержав кнопку хотя бы столько: щелчок с дрожанием руки — не перенос.
 * Сдвиг вбок раньше этого — выделение строки (оттягивание, как свайп в MT Manager). Окно с запасом:
 * «зажал и потянул» укладывается в него, даже если тянуть не спеша.
 */
const val FILE_DRAG_HOLD_MS = 350L

/**
 * Строку можно потянуть: [paths] — что переносится (выделение или сама строка), [label] — подпись у курсора.
 * Десктоп — перенос в другие программы и обратно (AWT); на Android пока нет.
 */
@Composable
expect fun rememberFileDragSource(paths: () -> List<Path>, label: () -> String, onFinished: () -> Unit): Modifier

/** Сюда можно бросить файлы: [onHover] — подсветка, [onDrop] — пути и точка в корне окна. */
@Composable
expect fun rememberFileDropTarget(onHover: (Boolean) -> Unit, onDrop: (paths: List<Path>, rootPoint: Offset?) -> Boolean): Modifier

/** Сколько раз подряд щёлкнули (двойной щелчок мышью); где щелчков-серий нет (касания) — 1. */
expect fun PointerEvent.clickCount(): Int

/** Курсор «тянуть по горизонтали» — у разделителя панелей. */
expect val ResizeHorizontalPointerIcon: PointerIcon

// Просмотрщики, у которых декодирование своё на каждой платформе (Skia/ffmpeg/poppler — android.graphics/MediaPlayer/PdfRenderer)

@Composable
expect fun ImageViewerDialog(entry: VfsEntry, siblingImages: List<VfsEntry> = emptyList(), vfsManager: VfsManager, onDismiss: () -> Unit)

@Composable
expect fun VideoPlayerDialog(entry: VfsEntry, siblingVideos: List<VfsEntry> = emptyList(), onDismiss: () -> Unit)

@Composable
expect fun PdfViewerDialog(entry: VfsEntry, onDismiss: () -> Unit)
