package org.wexclient.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset

/**
 * Элементы для мыши: подсказки при наведении и полосы прокрутки. На десктопе — как в Compose Desktop;
 * на Android наведения нет, листают пальцем — там подсказки и полосы не рисуются.
 */

/** Подсказка, всплывающая при наведении через [delayMillis]; [cursorOffset] — у курсора с этим сдвигом. */
@Composable
expect fun WexTooltipArea(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    delayMillis: Int = 500,
    cursorOffset: DpOffset? = null,
    content: @Composable () -> Unit
)

/** Вид полосы прокрутки (null — по умолчанию темы). */
class WexScrollbarStyle(
    val thickness: Dp,
    val minimalHeight: Dp? = null,
    val unhoverColor: Color,
    val hoverColor: Color,
    val hoverDurationMillis: Int? = null
)

@Composable
expect fun WexHorizontalScrollbar(state: ScrollState, modifier: Modifier = Modifier, style: WexScrollbarStyle? = null)

@Composable
expect fun WexVerticalScrollbar(state: LazyListState, modifier: Modifier = Modifier, style: WexScrollbarStyle? = null)

/** Колесо мыши над элементом (сдвиг в [PointerInputChange.scrollDelta]); событие можно поглотить. */
fun Modifier.onScrollWheel(handler: (androidx.compose.ui.input.pointer.PointerEvent) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Scroll) handler(event)
            }
        }
    }
