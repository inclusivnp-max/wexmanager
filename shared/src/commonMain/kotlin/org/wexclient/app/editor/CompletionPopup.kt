package org.wexclient.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.wexclient.app.editor.lang.ItemKind
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.ui.ResizeDiagonalPointerIcon
import org.wexclient.app.ui.glass
import kotlin.math.max
import kotlin.math.min

internal object CompletionPopupSizing {
    const val DEFAULT_WIDTH = 720f
    const val DEFAULT_HEIGHT = 420f
    const val MIN_WIDTH = 280f
    const val MIN_HEIGHT = 180f
    const val MAX_WIDTH = 2400f
    const val MAX_HEIGHT = 1600f
    private const val WINDOW_MARGIN = 8f

    fun preferred(width: Float, height: Float) = DpSize(
        (if (width.isFinite()) width else DEFAULT_WIDTH).coerceIn(MIN_WIDTH, MAX_WIDTH).dp,
        (if (height.isFinite()) height else DEFAULT_HEIGHT).coerceIn(MIN_HEIGHT, MAX_HEIGHT).dp
    )

    fun fit(requested: DpSize, available: DpSize): DpSize {
        val preferred = preferred(requested.width.value, requested.height.value)
        val width = max(1f, available.width.value - WINDOW_MARGIN)
        val height = max(1f, available.height.value - WINDOW_MARGIN)
        return DpSize(
            preferred.width.value.coerceIn(min(MIN_WIDTH, width), width).dp,
            preferred.height.value.coerceIn(min(MIN_HEIGHT, height), height).dp
        )
    }
}

/** Компактный список и полное описание выбранного элемента; размер меняется без смены фокуса. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CompletionPopup(
    ui: CompletionUi,
    colors: EditorColors,
    fontSize: TextUnit,
    availableSize: DpSize,
    onSelect: (Int) -> Unit,
    onAccept: (CompletionItem, Boolean) -> Unit
) {
    val items = ui.result.items
    val selected = items.getOrNull(ui.selected)
    val select by rememberUpdatedState(onSelect)
    val listState = rememberLazyListState()
    val detailScroll = rememberScrollState()
    var requested by remember {
        mutableStateOf(DpSize(EditorPreferences.completionWidthDp.dp, EditorPreferences.completionHeightDp.dp))
    }
    val limits by rememberUpdatedState(availableSize)
    val visibleSize = CompletionPopupSizing.fit(requested, availableSize)
    val density = LocalDensity.current.density
    val shape = RoundedCornerShape(8.dp)
    LaunchedEffect(ui.selected, items, visibleSize.height, fontSize) {
        withFrameNanos { }
        val layout = listState.layoutInfo
        val row = layout.visibleItemsInfo.firstOrNull { it.index == ui.selected }
        if (row == null) listState.scrollToItem(max(0, ui.selected - 4))
        else {
            val overflow = when {
                row.offset < layout.viewportStartOffset -> row.offset - layout.viewportStartOffset
                row.offset + row.size > layout.viewportEndOffset -> row.offset + row.size - layout.viewportEndOffset
                else -> 0
            }
            if (overflow != 0) listState.scrollBy(overflow.toFloat())
        }
    }
    LaunchedEffect(selected?.label, selected?.detail) { detailScroll.scrollTo(0) }

    Surface(
        color = Color.Transparent, shape = shape, shadowElevation = 8.dp,
        modifier = Modifier.width(visibleSize.width).height(visibleSize.height)
            .testTag("completion-popup").glass(shape, colors.popup)
            .border(1.dp, colors.popupBorder, shape)
    ) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.weight(0.65f).fillMaxWidth().testTag("completion-list")) {
                itemsIndexed(items, key = { index, item -> "$index:${item.label}:${item.kind}" }) { index, item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp)
                            .testTag("completion-item-$index")
                            .background(if (index == ui.selected) colors.popupSelected else Color.Transparent)
                            .pointerInput(index) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        // Прокрутка под неподвижной мышью не должна перебивать выбор стрелками.
                                        if (event.type == PointerEventType.Move) select(index)
                                    }
                                }
                            }
                            .clickable { onAccept(item, false) }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        KindBadge(item.kind)
                        Spacer(Modifier.width(8.dp))
                        Text(highlightCompletionMatches(item), fontFamily = AppFonts.JetBrainsMono,
                            fontSize = fontSize * 0.92f, color = colors.text, maxLines = 1,
                            softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.4f))
                        if (item.detail.isNotEmpty()) {
                            Spacer(Modifier.width(12.dp))
                            Text(item.detail, fontSize = fontSize * 0.78f, color = colors.dim,
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(0.6f))
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.popupBorder)
            Column(
                Modifier.weight(0.35f).fillMaxWidth().testTag("completion-details")
                    .verticalScroll(detailScroll).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                selected?.let { item ->
                    Text(item.label, fontFamily = AppFonts.JetBrainsMono, fontSize = fontSize * 0.92f,
                        fontWeight = FontWeight.SemiBold, color = colors.text,
                        modifier = Modifier.testTag("completion-detail-name"))
                    if (item.detail.isNotEmpty()) Text(item.detail, fontSize = fontSize * 0.88f,
                        color = colors.text, softWrap = true, modifier = Modifier.testTag("completion-detail-text"))
                }
            }
            Row(
                Modifier.fillMaxWidth().height(28.dp).background(colors.background.copy(alpha = 0.35f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("↑↓ — выбор · Tab — вставить · угол — размер", fontSize = 10.sp,
                    color = colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 10.dp))
                Canvas(
                    Modifier.size(28.dp).testTag("completion-resize-handle")
                        .semantics { contentDescription = "Изменить размер подсказок" }
                        .pointerHoverIcon(ResizeDiagonalPointerIcon)
                        .pointerInput(density) {
                            detectDragGestures(
                                onDragStart = { requested = CompletionPopupSizing.fit(requested, limits) },
                                onDragEnd = {
                                    EditorPreferences.updateCompletionSize(requested.width.value, requested.height.value)
                                }
                            ) { change, delta ->
                                change.consume()
                                requested = CompletionPopupSizing.fit(DpSize(
                                    (requested.width.value + delta.x / density).dp,
                                    (requested.height.value + delta.y / density).dp
                                ), limits)
                            }
                        }
                ) {
                    val edge = size.width - 5.dp.toPx()
                    for (step in 1..3) {
                        val inset = step * 5.dp.toPx()
                        drawLine(colors.dim, Offset(edge - inset, edge), Offset(edge, edge - inset), 1.4.dp.toPx())
                    }
                }
            }
        }
    }
}

private fun highlightCompletionMatches(item: CompletionItem): AnnotatedString {
    val positions = item.matched ?: return AnnotatedString(item.label)
    val selected = positions.toSet()
    return buildAnnotatedString {
        item.label.forEachIndexed { index, char ->
            if (index in selected) withStyle(SpanStyle(color = NoctaliaPrimary, fontWeight = FontWeight.Bold)) { append(char) }
            else append(char)
        }
    }
}
