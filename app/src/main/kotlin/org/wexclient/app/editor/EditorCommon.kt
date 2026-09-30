package org.wexclient.app.editor

import androidx.compose.material.icons.filled.DataObject
import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import org.wexclient.app.ui.glass
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.wexclient.app.editor.lang.ItemKind
import org.wexclient.app.editor.lang.SyntaxColors
import org.wexclient.app.editor.lang.Tok
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Цвета редактора, выведенные из темы. */
internal class EditorColors(
    val background: Color,
    val gutter: Color,
    val gutterText: Color,
    val gutterActive: Color,
    val currentLine: Color,
    val selection: Color,
    val occurrence: Color,
    val searchMatch: Color,
    val searchCurrent: Color,
    /** Область «в выделении» при поиске. */
    val searchScope: Color,
    val bracket: Color,
    val indentGuide: Color,
    val indentGuideActive: Color,
    val error: Color,
    val warning: Color,
    val foldBox: Color,
    val linked: Color,
    val linkedActive: Color,
    val text: Color,
    val dim: Color,
    val popup: Color,
    val popupBorder: Color,
    val popupSelected: Color
)

@Composable
internal fun rememberEditorColors(): EditorColors {
    val cs = MaterialTheme.colorScheme
    val primary = NoctaliaPrimary
    val tertiary = NoctaliaTertiary
    return remember(cs, primary, tertiary) {
        EditorColors(
            background = cs.background,
            gutter = cs.surface,
            gutterText = cs.onSurfaceVariant.copy(alpha = 0.45f),
            gutterActive = primary,
            currentLine = cs.onSurface.copy(alpha = 0.045f),
            selection = primary.copy(alpha = 0.32f),
            occurrence = cs.onSurface.copy(alpha = 0.12f),
            searchMatch = tertiary.copy(alpha = 0.30f),
            searchCurrent = tertiary.copy(alpha = 0.65f),
            searchScope = tertiary.copy(alpha = 0.08f),
            bracket = primary.copy(alpha = 0.55f),
            indentGuide = cs.onSurface.copy(alpha = 0.07f),
            indentGuideActive = cs.onSurface.copy(alpha = 0.22f),
            error = Color(0xFFFF6B6B),
            warning = Color(0xFFE5C07B),
            foldBox = primary.copy(alpha = 0.16f),
            linked = primary.copy(alpha = 0.45f),
            linkedActive = primary,
            text = cs.onBackground,
            dim = cs.onSurfaceVariant.copy(alpha = 0.6f),
            popup = cs.surfaceContainerHigh,
            popupBorder = cs.outlineVariant,
            popupSelected = primary.copy(alpha = 0.22f)
        )
    }
}

/** Стили токенов по Tok.ordinal (пересчёт при смене темы). */
@Composable
internal fun rememberTokenStyles(): Array<SpanStyle?> {
    val p = NoctaliaPrimary
    val t = NoctaliaTertiary
    val s = NoctaliaSecondary
    return remember(p, t, s) { Array(Tok.values().size) { SyntaxColors.style(Tok.values()[it]) } }
}

/** Найденное для отрисовки: совпадения, текущее и область «в выделении» (если поиск в ней). */
internal class SearchHighlight(val matches: SearchMatches, val current: Int, val scope: TextRange? = null)

/** Позиция всплывающего окна у курсора: снизу, а если не влезает — сверху. */
internal class CaretPopupPosition(private val caret: Rect, private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        var x = anchorBounds.left + caret.left.roundToInt()
        var y = anchorBounds.top + caret.bottom.roundToInt() + gapPx
        if (y + popupContentSize.height > windowSize.height) {
            y = anchorBounds.top + caret.top.roundToInt() - popupContentSize.height - gapPx
        }
        if (x + popupContentSize.width > windowSize.width) x = windowSize.width - popupContentSize.width
        return IntOffset(max(0, x), max(0, y))
    }
}

@OptIn(ExperimentalComposeUiApi::class)

@Composable
internal fun CompletionPopup(
    ui: CompletionUi,
    colors: EditorColors,
    fontSize: TextUnit,
    onAccept: (CompletionItem, Boolean) -> Unit
) {
    val items = ui.result.items
    val listState = rememberLazyListState()
    LaunchedEffect(ui.selected, items) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (ui.selected < first || ui.selected > last - 1) listState.scrollToItem(max(0, ui.selected - 4))
    }
    val rowHeight = (fontSize.value * 1.9f).dp
    val mono = AppFonts.JetBrainsMono
    val popupShape = RoundedCornerShape(8.dp)
    Surface(
        color = Color.Transparent,
        shape = popupShape,
        shadowElevation = 8.dp,
        modifier = Modifier
            .widthIn(min = 320.dp, max = 620.dp)
            .glass(popupShape, colors.popup)
            .border(1.dp, colors.popupBorder, popupShape)
    ) {
        Column {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = rowHeight * 11)) {
                itemsIndexed(items, key = { i, it -> "$i:${it.label}:${it.kind}" }) { index, item ->
                    val selected = index == ui.selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (selected) colors.popupSelected else Color.Transparent)
                            .clickable { onAccept(item, false) }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        KindBadge(item.kind)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            highlightMatches(item, colors),
                            fontFamily = mono,
                            fontSize = fontSize * 0.92f,
                            color = colors.text,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (item.detail.isNotEmpty()) {
                            Spacer(Modifier.width(16.dp))
                            Text(
                                item.detail,
                                fontSize = fontSize * 0.78f,
                                color = colors.dim,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 280.dp)
                            )
                        }
                    }
                }
            }
            val sel = items.getOrNull(ui.selected)
            Row(
                Modifier.fillMaxWidth().background(colors.background.copy(alpha = 0.35f)).padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (sel?.isSnippet == true) "Шаблон: ${sel.insertText.lineSequence().first().take(60)}"
                    else "Enter — вставить · Tab — заменить слово · Esc — закрыть",
                    fontSize = 10.sp,
                    color = colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun highlightMatches(item: CompletionItem, colors: EditorColors): AnnotatedString {
    val pos = item.matched ?: return AnnotatedString(item.label)
    val set = pos.toSet()
    return buildAnnotatedString {
        item.label.forEachIndexed { i, ch ->
            if (i in set) withStyle(SpanStyle(color = NoctaliaPrimary, fontWeight = FontWeight.Bold)) { append(ch) }
            else append(ch)
        }
    }
}

@Composable
internal fun KindBadge(kind: ItemKind) {
    val color = when (kind) {
        ItemKind.KEYWORD -> NoctaliaPrimary
        ItemKind.TYPE -> Color(0xFFC4A7FF)
        ItemKind.FUNCTION, ItemKind.COMMAND -> Color(0xFF93C5FD)
        ItemKind.PROPERTY, ItemKind.ATTRIBUTE -> Color(0xFF7DD3FC)
        ItemKind.CONSTANT, ItemKind.VALUE -> Color(0xFFFCA5A5)
        ItemKind.SNIPPET -> NoctaliaTertiary
        ItemKind.TAG, ItemKind.DIRECTIVE -> NoctaliaPrimary
        ItemKind.OPCODE -> NoctaliaTertiary
        ItemKind.VARIABLE -> Color(0xFFF9A8D4)
        ItemKind.LABEL -> Color(0xFF86EFAC)
        ItemKind.FILE, ItemKind.MODULE -> NoctaliaSecondary
        ItemKind.WORD -> MaterialTheme.colorScheme.outline
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 22.dp, height = 16.dp)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
    ) {
        if (kind == ItemKind.SNIPPET) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Default.DataObject,
                contentDescription = kind.title,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
        } else {
            Text(kind.badge, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1, softWrap = false)
        }
    }
}

private class MinimapImage(val bitmap: ImageBitmap, val lines: Int, val rowPx: Float, val version: Long) {
    /** Пиксели лежат в нативной памяти Skia: сборщик мусора её не видит и освобождает поздно. */
    fun close() = bitmap.asSkiaBitmap().close()
}

@Composable
internal fun Minimap(
    ctrl: CodeEditorController,
    /** Текущая прокрутка в пикселях и вся высота содержимого — мини-карта их только читает. */
    scrollY: () -> Float,
    contentH: () -> Float,
    search: SearchHighlight,
    colors: EditorColors,
    viewportH: Int,
    onJump: (Int) -> Unit,
    modifier: Modifier
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var image by remember { mutableStateOf<MinimapImage?>(null) }
    // Снятая с показа картинка. Закрываем её только при следующей замене: к тому времени кадры
    // рисуют уже новую, а раньше каждая правка оставляла по растру в нативной памяти
    val retired = remember { arrayOfNulls<MinimapImage>(1) }
    DisposableEffect(Unit) {
        onDispose {
            retired[0]?.close()
            retired[0] = null
            image?.close()
        }
    }
    // Обработчики мыши ниже запускаются один раз: высоту окна берём свежую, а не с первого показа
    val currentViewportH by androidx.compose.runtime.rememberUpdatedState(viewportH)
    val version = ctrl.version
    val tokenColors = remember(colors, NoctaliaPrimary, NoctaliaTertiary) {
        IntArray(Tok.values().size) { SyntaxColors.color(Tok.values()[it], colors.text).copy(alpha = 0.85f).toArgbInt() }
    }
    val plain = remember(colors) { colors.text.copy(alpha = 0.45f).toArgbInt() }
    LaunchedEffect(version, boxSize, tokenColors) {
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        delay(if (image == null) 0 else 250)
        val snap = ctrl.snapshot
        val img = withContext(Dispatchers.Default) { renderMinimap(snap, boxSize.width - 12, boxSize.height, tokenColors, plain) }
        retired[0]?.close()
        retired[0] = image
        image = img
    }
    Box(
        modifier
            .background(colors.gutter)
            .onSizeChanged { boxSize = it }
            .pointerInput(ctrl) {
                fun jump(y: Float) {
                    val img = image ?: return
                    val contentH = contentH()
                    val mapH = min(boxSize.height.toFloat(), img.lines * img.rowPx).coerceAtLeast(1f)
                    val frac = (y / mapH).coerceIn(0f, 1f)
                    onJump((frac * contentH - currentViewportH / 2f).roundToInt())
                }
                detectTapGestures { jump(it.y) }
            }
            .pointerInput(ctrl, "drag") {
                detectDragGestures { change, _ ->
                    val img = image ?: return@detectDragGestures
                    val contentH = contentH()
                    val mapH = min(boxSize.height.toFloat(), img.lines * img.rowPx).coerceAtLeast(1f)
                    val frac = (change.position.y / mapH).coerceIn(0f, 1f)
                    onJump((frac * contentH - currentViewportH / 2f).roundToInt())
                }
            }
            .drawWithContent {
                val img = image ?: return@drawWithContent
                val mapH = min(size.height.toFloat(), img.lines * img.rowPx)
                drawImage(img.bitmap, dstOffset = IntOffset(4, 0), dstSize = IntSize(img.bitmap.width, img.bitmap.height))
                // Окно видимой области
                val contentH = contentH().coerceAtLeast(1f)
                val top = scrollY() / contentH * mapH
                val h = max(12f, viewportH / contentH * mapH)
                drawRect(colors.dim.copy(alpha = 0.12f), Offset(0f, top), Size(size.width.toFloat() - 8f, h))
                drawRect(colors.dim.copy(alpha = 0.25f), Offset(0f, top), Size(size.width.toFloat() - 8f, h), style = Stroke(1f))
                // Полоса маркеров справа: ошибки, совпадения, курсор
                val snap = ctrl.snapshot
                val lines = max(1, snap.lineCount)
                val stripeX = size.width - 6f
                val textLen = ctrl.value.text.length
                fun yOf(offset: Int) = snap.lineOf(offset.coerceIn(0, textLen)).toFloat() / lines * mapH
                val m = search.matches
                val step = max(1, m.count / 800)
                var i = 0
                while (i < m.count) {
                    val y = yOf(m.start(i))
                    drawRect(if (i == search.current) NoctaliaTertiary else NoctaliaTertiary.copy(alpha = 0.6f), Offset(stripeX, y), Size(5f, 2f))
                    i += step
                }
                for (d in ctrl.diagnostics.take(500)) {
                    val y = yOf(d.start)
                    drawRect(if (d.severity == Severity.ERROR) colors.error else colors.warning, Offset(stripeX - 1f, y), Size(6f, 3f))
                }
                val cy = yOf(ctrl.value.selection.end)
                drawRect(NoctaliaPrimary, Offset(0f, cy), Size(size.width.toFloat(), 1.5f))
                drawLine(colors.popupBorder, Offset(0.5f, 0f), Offset(0.5f, size.height.toFloat()), 1f)
            }
    )
}

private fun Color.toArgbInt(): Int {
    val a = (alpha * 255).roundToInt().coerceIn(0, 255)
    val r = (red * 255).roundToInt().coerceIn(0, 255)
    val g = (green * 255).roundToInt().coerceIn(0, 255)
    val b = (blue * 255).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun renderMinimap(snap: HighlightSnapshot, width: Int, height: Int, tokenColors: IntArray, plain: Int): MinimapImage? {
    if (width <= 4 || height <= 4) return null
    val lines = snap.lineCount
    val rowPx = if (lines * 2 <= height) 2f else max(height.toFloat() / lines, 0.0001f)
    val rows = min(height, (lines * rowPx).toInt().coerceAtLeast(1))
    val pixels = IntArray(width * rows)
    val text = snap.text
    val linesPerRow = if (rowPx >= 1f) 1f / rowPx else lines.toFloat() / rows
    for (row in 0 until rows) {
        val line = if (rowPx >= 2f) row / 2 else (row * linesPerRow).toInt()
        if (line >= lines) break
        if (rowPx >= 2f && row % 2 == 1) continue
        val ls = snap.lineStart(line)
        val le = min(snap.lineEnd(line), ls + width * 2)
        val toks = snap.lineTokens(line)
        var col = 0
        var k = 0
        var i = ls
        while (i < le && col < width) {
            val ch = text[i]
            if (ch == '\t') {
                col += 4 - col % 4
                i++
                continue
            }
            if (!ch.isWhitespace()) {
                val rel = i - ls
                while (k < toks.size && toks[k + 1] <= rel) k += 3
                val color = if (k < toks.size && toks[k] <= rel) tokenColors[toks[k + 2]] else plain
                pixels[row * width + col] = color
            }
            col++
            i++
        }
    }
    // ARGB -> BGRA premul (N32 на little-endian)
    val bytes = ByteArray(width * rows * 4)
    for (p in pixels.indices) {
        val c = pixels[p]
        if (c == 0) continue
        val a = (c ushr 24) and 0xFF
        val r = ((c shr 16) and 0xFF) * a / 255
        val g = ((c shr 8) and 0xFF) * a / 255
        val b = (c and 0xFF) * a / 255
        val o = p * 4
        bytes[o] = b.toByte()
        bytes[o + 1] = g.toByte()
        bytes[o + 2] = r.toByte()
        bytes[o + 3] = a.toByte()
    }
    val bmp = Bitmap()
    val info = ImageInfo.makeN32(width, rows, ColorAlphaType.PREMUL)
    bmp.allocPixels(info)
    bmp.installPixels(info, bytes, width * 4)
    // Растягиваем строки в 2 пикселя, если их мало
    bmp.setImmutable() // см. util/ImageScale.kt: frozen
    return MinimapImage(bmp.asComposeImageBitmap(), lines, if (rowPx >= 2f) 1f else rowPx, snap.version).let {
        MinimapImage(it.bitmap, lines, rows.toFloat() / lines, snap.version)
    }
}
