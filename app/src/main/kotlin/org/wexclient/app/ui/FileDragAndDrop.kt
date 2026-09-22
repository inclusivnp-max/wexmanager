package org.wexclient.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import org.wexclient.app.theme.NoctaliaPrimary
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.wexclient.app.vfs.FileClipboard
import java.awt.datatransfer.Transferable
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.nio.file.Path

/** Что сделать с перетащенными файлами. */
enum class DropAction { COPY, MOVE, LINK }

/**
 * Перетаскивание из WEX. Пути внутри архивов в системный формат не передать, поэтому
 * для своих панелей список хранится здесь, а другим программам уходят файлы с диска.
 */
object FileDrag {
    @Volatile
    private var current: Pair<Transferable, List<Path>>? = null

    /** Идёт перетаскивание из самого WEX (панели не подсвечивают чужие форматы). */
    val active: Boolean get() = current != null

    internal fun begin(t: Transferable, paths: List<Path>) {
        current = t to paths
    }

    internal fun end() {
        current = null
    }

    fun pathsOf(t: Transferable): List<Path>? {
        current?.let { (own, paths) -> if (own === t) return paths }
        return FileClipboard.filesFrom(t)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun accepts(event: DragAndDropEvent): Boolean {
        val native = event.nativeEvent
        val flavors = when (native) {
            is DropTargetDragEvent -> native.currentDataFlavorsAsList
            is DropTargetDropEvent -> native.currentDataFlavorsAsList
            else -> return false
        }
        return FileClipboard.mayContainFiles(flavors)
    }

    /** Точка отпускания в координатах корня окна Compose (пиксели). */
    @OptIn(ExperimentalComposeUiApi::class)
    fun dropPoint(event: DragAndDropEvent, density: Float): Offset? {
        val p = (event.nativeEvent as? DropTargetDropEvent)?.location ?: return null
        return Offset(p.x * density, p.y * density)
    }
}

/**
 * Строку можно потянуть: [paths] — что переносится (выделение или сама строка),
 * [label] — подпись у курсора. Обработчики читаются в момент начала перетаскивания.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun rememberFileDragSource(
    paths: () -> List<Path>,
    label: () -> String,
    onFinished: () -> Unit
): Modifier {
    val measurer = rememberTextMeasurer()
    val accent = NoctaliaPrimary
    val currentPaths by rememberUpdatedState(paths)
    val currentLabel by rememberUpdatedState(label)
    val currentFinished by rememberUpdatedState(onFinished)
    return remember(measurer) {
        Modifier.dragAndDropSource(
            drawDragDecoration = { drawDragChip(measurer, currentLabel(), accent) }
        ) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var travelled = Offset.Zero
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // Кнопку отпустили — это был щелчок, а не перенос: строка откроется как обычно
                    if (!change.pressed) break
                    // Правой и средней кнопкой не таскают: это меню и открытие в новой панели
                    if (event.buttons.isSecondaryPressed || event.buttons.isTertiaryPressed) break
                    travelled += change.positionChange()
                    if (change.uptimeMillis - down.uptimeMillis < DRAG_HOLD_MS) continue
                    if (travelled.getDistance() < DRAG_SLOP.toPx()) continue
                    val list = currentPaths()
                    if (list.isEmpty()) break
                    change.consume()
                    val transferable = FileClipboard.transferable(list)
                    FileDrag.begin(transferable, list)
                    startTransfer(
                        DragAndDropTransferData(
                            transferable = DragAndDropTransferable(transferable),
                            supportedActions = listOf(
                                DragAndDropTransferAction.Copy,
                                DragAndDropTransferAction.Move,
                                DragAndDropTransferAction.Link
                            ),
                            dragDecorationOffset = down.position,
                            onTransferCompleted = {
                                FileDrag.end()
                                currentFinished()
                            }
                        )
                    )
                    break
                }
            }
        }
    }
}

/**
 * Перенос начинается, только когда его явно хотят: кнопку держат дольше обычного щелчка
 * и уводят курсор заметно в сторону. Прежний порог (одно дрожание руки) превращал щелчок
 * по папке в перетаскивание — папка не открывалась, а у курсора появлялся знак «нельзя».
 */
private const val DRAG_HOLD_MS = 180L
private val DRAG_SLOP = 12.dp

private fun DrawScope.drawDragChip(measurer: TextMeasurer, text: String, accent: Color) {
    drawRoundRect(color = Color(0xE6201A1A), cornerRadius = CornerRadius(8f, 8f))
    drawRoundRect(color = accent.copy(alpha = 0.8f), cornerRadius = CornerRadius(8f, 8f), style = Stroke(2f))
    val layout = measurer.measure(
        text,
        style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = (size.width - 24f).toInt().coerceAtLeast(1))
    )
    drawText(layout, topLeft = Offset(12f, (size.height - layout.size.height) / 2f))
}

/**
 * Сюда можно бросить файлы: [onHover] — подсветка, [onDrop] — пути и точка в корне окна.
 * Приёмник один на всё время жизни: новый объект при перерисовке оборвал бы перетаскивание.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun rememberFileDropTarget(
    onHover: (Boolean) -> Unit,
    onDrop: (paths: List<Path>, rootPoint: Offset?) -> Boolean
): Modifier {
    val density = LocalDensity.current.density
    val currentHover by rememberUpdatedState(onHover)
    val currentDrop by rememberUpdatedState(onDrop)
    val currentDensity by rememberUpdatedState(density)
    return remember {
        val target = object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = currentHover(true)
            override fun onExited(event: DragAndDropEvent) = currentHover(false)
            override fun onEnded(event: DragAndDropEvent) = currentHover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentHover(false)
                val paths = FileDrag.pathsOf(event.awtTransferable)?.takeIf { it.isNotEmpty() } ?: return false
                return currentDrop(paths, FileDrag.dropPoint(event, currentDensity))
            }
        }
        Modifier.dragAndDropTarget(shouldStartDragAndDrop = { FileDrag.accepts(it) }, target = target)
    }
}
