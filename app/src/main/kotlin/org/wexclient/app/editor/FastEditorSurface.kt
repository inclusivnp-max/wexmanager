package org.wexclient.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import org.wexclient.app.editor.lang.SyntaxColors
import org.wexclient.app.editor.lang.Tok
import org.wexclient.app.util.Keymap
import org.wexclient.app.theme.AppFonts
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Поверхность редактора: свой буфер строк, свой рендер и свой ввод — без BasicTextField.
 *
 * Поле ввода Compose держит весь текст одной раскладкой Skia, и она пересчитывается целиком на
 * каждое нажатие: на полутора мегабайтах это пятая часть секунды на букву и семь секунд на
 * открытие. Здесь рисуются только видимые строки, а положение строки и знака — арифметика по
 * ширине моноширинного знака и постоянной высоте строки, поэтому размер файла почти не влияет.
 *
 * Всё управление снаружи: клавиши сначала уходят в [onKey] (там все сочетания редактора), правки —
 * в [onChange] (там автоскобки, отступы, история и подсказки), вставка из буфера — в [onPaste]:
 * вставленная скобка — это не набранная, и парную к ней дописывать не нужно.
 */
@Composable
internal fun FastEditorSurface(
    ctrl: CodeEditorController,
    state: FastSurfaceState,
    readOnly: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    search: SearchHighlight,
    focusRequester: FocusRequester,
    onKey: (KeyEvent) -> Boolean,
    onChange: (TextFieldValue) -> Unit,
    onPaste: (String) -> Unit,
    /** Набранный знак при нескольких каретках — во все сразу. */
    onTypeCarets: (String) -> Unit,
    contextItems: () -> List<EditorMenuItem>,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = EditorPreferences
    val colors = rememberEditorColors()
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val measurer = rememberTextMeasurer(cacheSize = 0)

    val ligatures = prefs.ligatures
    val textStyle = remember(fontSize, lineHeight, ligatures) {
        TextStyle(
            fontFamily = AppFonts.JetBrainsMono,
            fontSize = fontSize,
            lineHeight = lineHeight,
            // Лигатуры JetBrains Mono (-> != ===) живут в «calt». Ширина знаков у них та же,
            // так что сетка колонок от переключателя не меняется
            fontFeatureSettings = if (ligatures) null else "calt off, liga off, dlig off"
        )
    }
    val metrics = remember(textStyle, density) {
        val probe = measurer.measure(AnnotatedString("M".repeat(100)), textStyle, softWrap = false)
        Metrics(probe.size.width / 100f, probe.size.height.toFloat())
    }
    state.metrics = metrics

    val styles = remember(colors) { Array(Tok.values().size) { SyntaxColors.style(Tok.values()[it]) } }
    val cache = remember(textStyle) { RowLayoutCache(measurer, textStyle) }
    val foldMarks = remember(ctrl) { FoldMarks() }
    val holder = remember(ctrl) { LayoutHolder() }

    // Каретка мигает, но замирает, пока печатают: следим за правками и курсором без рекомпозиции
    androidx.compose.runtime.LaunchedEffect(ctrl) {
        androidx.compose.runtime.snapshotFlow { ctrl.value.selection.end to ctrl.version }
            .collectLatest {
                state.caretVisible = true
                while (true) {
                    kotlinx.coroutines.delay(CARET_BLINK_MS)
                    state.caretVisible = !state.caretVisible
                }
            }
    }

    // Меню правой кнопки — своё, в общем виде WEX: компактные строки со значками и сочетаниями
    var menuAt by remember { mutableStateOf<Offset?>(null) }

    Row(modifier) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.fillMaxSize()) {
                FastSurfaceCanvas(
                    ctrl = ctrl,
                    state = state,
                    holder = holder,
                    cache = cache,
                    foldMarks = foldMarks,
                    styles = styles,
                    metrics = metrics,
                    colors = colors,
                    search = search,
                    readOnly = readOnly,
                    focusRequester = focusRequester,
                    onKey = onKey,
                    onChange = onChange,
                    onPaste = onPaste,
                    onTypeCarets = onTypeCarets,
                    onZoom = onZoom,
                    onContextMenu = { menuAt = it },
                    clipboard = clipboard
                )
                FastSurfaceCompletion(
                    ctrl = ctrl,
                    state = state,
                    holder = holder,
                    metrics = metrics,
                    colors = colors,
                    fontSize = fontSize,
                    focusRequester = focusRequester
                )
                FastSurfaceHoverTip(ctrl, state, colors)
                // Якорь нулевого размера в точке щелчка: меню открывается ровно под курсором
                menuAt?.let { where ->
                    Box(
                        Modifier.offset {
                            androidx.compose.ui.unit.IntOffset(where.x.roundToInt(), where.y.roundToInt())
                        }
                    ) {
                        org.wexclient.app.ui.WexDropdownMenu(
                            expanded = true,
                            onDismissRequest = { menuAt = null }
                        ) {
                            for (item in contextItems()) {
                                EditorMenuRow(item) {
                                    menuAt = null
                                    runCatching { focusRequester.requestFocus() }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showsMinimap(ctrl)) {
            Minimap(
                ctrl = ctrl,
                scrollY = { state.scrollY },
                contentH = { state.contentHeight() },
                search = search,
                colors = colors,
                viewportH = state.viewport.height,
                onJump = { y -> state.scrollY = y.toFloat().coerceIn(0f, state.maxScrollY()) },
                modifier = Modifier.width(74.dp).fillMaxHeight()
            )
        }
    }
}

/** Мини-карта справа: включена и файл не умещается на экран с запасом. */
internal fun showsMinimap(ctrl: CodeEditorController): Boolean =
    EditorPreferences.showMinimap && ctrl.snapshot.lineCount > MINIMAP_MIN_LINES

/** Ошибки под мышью: где их показать ([anchor] — экранная строка у указателя) и какие. */
internal class HoverTip(val anchor: Rect, val items: List<Diagnostic>, val version: Long)

/**
 * Прокрутка и геометрия поверхности. Держится снаружи: редактор просит показать позицию
 * (переход к строке, к найденному, к ошибке), а поверхность знает, где что нарисовано.
 */
internal class FastSurfaceState(private val ctrl: CodeEditorController) {
    var scrollY by mutableStateOf(0f)
    var scrollX by mutableStateOf(0f)
    var viewport by mutableStateOf(IntSize.Zero)
    var metrics: Metrics = Metrics(7f, 14f)

    /** Геометрия последней отрисовки — нужна обработчикам мыши. */
    var textLeft = 60f
    var gutterWidth = 52f
    /** Где в полосе номеров начинаются стрелки свёртки. */
    var foldLeft = 38f
    var columns = 0

    /** Раскладка на момент последней отрисовки. */
    var layout: FastLayout? = null

    /** Самая длинная показанная строка в колонках — для горизонтальной прокрутки. */
    var longestColumns = 40

    /** Строка закреплённого заголовка на последнем кадре; -1 — заголовка нет. */
    var stickyLine = -1

    /** Каретка видна сейчас (мигание); при наборе не мигает. */
    var caretVisible by mutableStateOf(true)

    /** Колонка, к которой стремится каретка при движении вверх-вниз (-1 — не задана). */
    var desiredColumn = -1

    /**
     * Где каретка осталась после последнего шага вверх-вниз и какой был текст. Сдвинулась иначе
     * (ввод, щелчок, стрелки вбок) — колонка забывается: раньше после набора на короткой строке
     * стрелка вниз уводила каретку на колонку, запомненную до набора.
     */
    var desiredCaret = -1
    var desiredVersion = -1L

    /** Ошибки под мышью; подсказка с ними появляется, если мышь задержится. */
    var hoverTarget by mutableStateOf<HoverTip?>(null)

    fun maxScrollY(): Float {
        val rows = layout?.totalRows ?: ctrl.snapshot.lineCount
        return max(0f, rows * metrics.lineHeight - viewport.height + metrics.lineHeight)
    }

    /**
     * Правее самой длинной видимой строки не едем, но и назад не отбрасываем: после прокрутки к
     * короткому месту вид остаётся там, где был. Раньше предел считался от текущего сдвига,
     * и тачпадом можно было уехать вправо в пустоту без конца.
     */
    fun maxScrollX(): Float {
        val content = longestColumns * metrics.charWidth + textLeft + 40f - viewport.width
        return max(0f, max(content, scrollX))
    }

    fun contentHeight(): Float {
        val rows = layout?.totalRows ?: ctrl.snapshot.lineCount
        return max(1f, rows * metrics.lineHeight)
    }

    /** Показать позицию: [center] — по центру окна, иначе минимальным движением. */
    fun reveal(offset: Int, center: Boolean = false) {
        val lay = layout ?: return
        if (viewport.height <= 0) return
        val (row, column) = lay.rowAndColumn(offset)
        val y = row * metrics.lineHeight
        if (center) {
            scrollY = (y - viewport.height / 2f).coerceIn(0f, maxScrollY())
        } else {
            if (y < scrollY) scrollY = y
            val bottom = y + metrics.lineHeight
            if (bottom > scrollY + viewport.height) scrollY = bottom - viewport.height
            scrollY = scrollY.coerceIn(0f, max(0f, maxScrollY()))
        }
        if (lay.wrap) {
            scrollX = 0f
            return
        }
        val x = column * metrics.charWidth
        if (x < scrollX) scrollX = max(0f, x - metrics.charWidth * 4)
        val visibleWidth = viewport.width - textLeft
        if (visibleWidth > 0 && x + metrics.charWidth > scrollX + visibleWidth) {
            scrollX = x + metrics.charWidth - visibleWidth
        }
        if (scrollX < 0f) scrollX = 0f
    }
}

@Composable
internal fun rememberFastSurfaceState(ctrl: CodeEditorController): FastSurfaceState =
    remember(ctrl) { FastSurfaceState(ctrl) }

/** Чем растёт выделение при протяжке: знаками, словами (двойной щелчок) или строками. */
private enum class SelectBy { CHAR, WORD, LINE }

@Composable
private fun FastSurfaceCanvas(
    ctrl: CodeEditorController,
    state: FastSurfaceState,
    holder: LayoutHolder,
    cache: RowLayoutCache,
    foldMarks: FoldMarks,
    styles: Array<SpanStyle?>,
    metrics: Metrics,
    colors: EditorColors,
    search: SearchHighlight,
    readOnly: Boolean,
    focusRequester: FocusRequester,
    onKey: (KeyEvent) -> Boolean,
    onChange: (TextFieldValue) -> Unit,
    onPaste: (String) -> Unit,
    onTypeCarets: (String) -> Unit,
    onZoom: (Float) -> Unit,
    onContextMenu: (Offset) -> Unit,
    clipboard: androidx.compose.ui.platform.ClipboardManager
) {
    val prefs = EditorPreferences
    // Обработчики мыши запускаются один раз на контроллер и помнят только первую композицию.
    // Всё, что меняется, они берут через актуальные ссылки, а размеры знака — из [state]: раньше
    // после смены шрифта щелчок попадал по старой сетке, а Ctrl+колесо не шло дальше одного шага
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnContextMenu by rememberUpdatedState(onContextMenu)
    // Половинка знака вне основного набора, пришедшая первым событием (переживает рекомпозицию)
    val pendingHighSurrogate = remember { arrayOfNulls<Char>(1) }
    // Один Path на все подчёркивания: новый на каждую ошибку в каждом кадре копил нативную память
    val squigglePath = remember { androidx.compose.ui.graphics.Path() }
    // Над текстом — текстовый курсор, над номерами строк и полосой прокрутки — обычная стрелка
    var textCursor by remember { mutableStateOf(true) }

    fun m() = state.metrics
    fun snap() = ctrl.snapshot
    fun text() = ctrl.value.text

    fun wrapColumns(): Int {
        if (!prefs.softWrap) return 0
        val usable = state.viewport.width - state.textLeft - 14f
        return max(8, (usable / m().charWidth).toInt())
    }

    fun layout(): FastLayout = holder.get(
        snap(),
        prefs.tabSize,
        prefs.showWhitespace,
        wrapColumns(),
        ctrl.effectiveCollapsed()
    ).also { state.layout = it }

    /** Экранная строка под точкой [y] окна. */
    fun rowAt(y: Float, lay: FastLayout): Int =
        ((y + state.scrollY) / m().lineHeight).toInt().coerceIn(0, max(0, lay.totalRows - 1))

    /** Позиция в тексте под точкой: [nearest] — ближайшая граница знаков (для каретки), иначе сам знак. */
    fun offsetAtPoint(pos: Offset, nearest: Boolean = true): Int {
        val lay = layout()
        val x = (pos.x + state.scrollX - state.textLeft) / m().charWidth
        val column = if (nearest) x.roundToInt() else floor(x).toInt()
        return lay.offsetAt(rowAt(pos.y, lay), max(0, column))
    }

    fun setSelection(start: Int, end: Int) {
        currentOnChange(ctrl.value.copy(selection = TextRange(start, end)))
    }

    fun moveCaret(to: Int, extend: Boolean, keepColumn: Boolean = false) {
        // Движение вверх-вниз помнит колонку: пройдя короткую строку, каретка возвращается
        // на прежнее место, как в любом редакторе кода
        if (!keepColumn) state.desiredColumn = -1
        val anchor = if (extend) ctrl.value.selection.start else to
        setSelection(anchor, to)
        if (keepColumn) {
            state.desiredCaret = ctrl.value.selection.end
            state.desiredVersion = ctrl.version
        }
        state.reveal(ctrl.value.selection.end)
    }

    fun replaceSelection(inserted: String) {
        if (readOnly) return
        val v = ctrl.value
        val from = min(v.selection.start, v.selection.end)
        val to = max(v.selection.start, v.selection.end)
        onChange(TextFieldValue(v.text.substring(0, from) + inserted + v.text.substring(to), TextRange(from + inserted.length)))
        state.reveal(ctrl.value.selection.end)
    }

    fun deleteRange(from: Int, to: Int) {
        if (readOnly || from >= to) return
        val v = ctrl.value
        onChange(TextFieldValue(v.text.substring(0, from) + v.text.substring(to), TextRange(from)))
        state.reveal(ctrl.value.selection.end)
    }

    /** Слово под позицией; на пробелах — весь их пробег, на прочем — сам знак. */
    fun wordAround(t: String, at: Int): Pair<Int, Int> {
        val p = at.coerceIn(0, t.length)
        var a = p
        var b = p
        val isWord = ctrl.language::isWordChar
        while (a > 0 && isWord(t[a - 1])) a--
        while (b < t.length && isWord(t[b])) b++
        if (a < b) return a to b
        if (p < t.length && (t[p] == ' ' || t[p] == '\t')) {
            while (a > 0 && (t[a - 1] == ' ' || t[a - 1] == '\t')) a--
            while (b < t.length && (t[b] == ' ' || t[b] == '\t')) b++
            return a to b
        }
        return if (p < t.length && t[p] != '\n') p to p + 1 else p to p
    }

    /** Строка под позицией вместе с её переводом строки. */
    fun lineAround(at: Int): Pair<Int, Int> {
        val snap = snap()
        val line = snap.lineOf(at.coerceIn(0, snap.text.length))
        val end = if (line + 1 < snap.lineCount) snap.lineStart(line + 1) else snap.text.length
        return snap.lineStart(line) to end
    }

    /**
     * Прямоугольное выделение от точки [from] до [to]: по каретке на каждую экранную строку между
     * ними, выделение — между теми же колонками (короткая строка — каретка в её конце).
     */
    fun boxSelect(from: Offset, to: Offset) {
        val lay = layout()
        val mt = m()
        fun column(p: Offset) = max(0, ((p.x + state.scrollX - state.textLeft) / mt.charWidth).roundToInt())
        val r1 = rowAt(from.y, lay)
        val r2 = rowAt(to.y, lay)
        val c1 = column(from)
        val c2 = column(to)
        val rows = if (r1 <= r2) r1..r2 else r2..r1
        val ranges = rows.map { r -> TextRange(lay.offsetAt(r, c1), lay.offsetAt(r, c2)) }
        ctrl.setCarets(ranges, if (r2 >= r1) ranges.last() else ranges.first(), reveal = false)
    }

    /** Мышь за краем поля при протяжке: подкручиваем вид в её сторону. true — было куда. */
    fun edgeScroll(p: Offset): Boolean {
        val mt = m()
        val h = state.viewport.height.toFloat()
        val w = state.viewport.width.toFloat()
        var moved = false
        val dy = when {
            p.y < 0f -> p.y
            h > 0f && p.y > h -> p.y - h
            else -> 0f
        }
        if (dy != 0f) {
            val before = state.scrollY
            state.scrollY = (state.scrollY + sign(dy) * max(mt.lineHeight / 2f, abs(dy) / 2f)).coerceIn(0f, state.maxScrollY())
            moved = state.scrollY != before
        }
        if (!prefs.softWrap && w > 0f) {
            val dx = when {
                p.x < state.textLeft && state.scrollX > 0f -> p.x - state.textLeft
                p.x > w -> p.x - w
                else -> 0f
            }
            if (dx != 0f) {
                val before = state.scrollX
                state.scrollX = (state.scrollX + sign(dx) * max(mt.charWidth, abs(dx) / 2f)).coerceIn(0f, state.maxScrollX())
                moved = moved || state.scrollX != before
            }
        }
        return moved
    }

    /** Ошибки под указателем: знак, над которым мышь, попадает в подчёркнутый кусок. */
    fun hoverAt(p: Offset): HoverTip? {
        val diagnostics = ctrl.diagnostics
        if (diagnostics.isEmpty() || p.y < 0f) return null
        val lay = layout()
        val mt = m()
        val row = ((p.y + state.scrollY) / mt.lineHeight).toInt()
        if (row >= lay.totalRows) return null
        val column = floor((p.x + state.scrollX - state.textLeft) / mt.charWidth).toInt()
        if (column < 0) return null
        val at = lay.offsetAt(row, column)
        val hits = diagnostics.filter { at >= it.start && at < max(it.end, it.start + 1) }
        if (hits.isEmpty()) return null
        val current = state.hoverTarget
        if (current != null && current.items == hits) return current
        val y = row * mt.lineHeight - state.scrollY
        return HoverTip(Rect(p.x, y, p.x + 1f, y + mt.lineHeight), hits, ctrl.version)
    }

    val vertical = rememberScrollableState { delta ->
        val before = state.scrollY
        state.scrollY = (state.scrollY - delta).coerceIn(0f, state.maxScrollY())
        before - state.scrollY
    }
    val horizontal = rememberScrollableState { delta ->
        if (prefs.softWrap) return@rememberScrollableState 0f
        val before = state.scrollX
        state.scrollX = (state.scrollX - delta).coerceIn(0f, state.maxScrollX())
        before - state.scrollX
    }

    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .clipToBounds()
            .onSizeChanged { state.viewport = it }
            .pointerHoverIcon(if (textCursor) PointerIcon.Text else PointerIcon.Default)
            // Правая кнопка: ловим до жестов выделения, иначе событие до них не доходит
            .pointerInput(ctrl) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        if (event.buttons.isTertiaryPressed) {
                            // Средняя кнопка с протяжкой — прямоугольное выделение
                            val start = event.changes.firstOrNull()?.position ?: continue
                            runCatching { focusRequester.requestFocus() }
                            state.hoverTarget = null
                            event.changes.forEach { it.consume() }
                            var last = start
                            var moved = false
                            while (true) {
                                val next = withTimeoutOrNull(AUTOSCROLL_TICK_MS) { awaitPointerEvent(PointerEventPass.Initial) }
                                if (next == null) {
                                    if (moved && edgeScroll(last)) boxSelect(start, last)
                                    continue
                                }
                                val change = next.changes.firstOrNull() ?: break
                                if (!next.buttons.isTertiaryPressed) break
                                last = change.position
                                if (!moved && (last - start).getDistance() > viewConfiguration.touchSlop) moved = true
                                if (moved) {
                                    edgeScroll(last)
                                    boxSelect(start, last)
                                }
                                next.changes.forEach { it.consume() }
                            }
                            continue
                        }
                        if (!event.buttons.isSecondaryPressed) continue
                        val position = event.changes.firstOrNull()?.position ?: continue
                        runCatching { focusRequester.requestFocus() }
                        state.hoverTarget = null
                        // Курсор двигаем только если щёлкнули вне выделения; несколько кареток не трогаем —
                        // меню (копировать, вырезать, вставить) работает со всеми
                        val at = offsetAtPoint(position)
                        val sel = ctrl.value.selection
                        if (!ctrl.hasMultipleCarets && (at < min(sel.start, sel.end) || at > max(sel.start, sel.end))) setSelection(at, at)
                        currentOnContextMenu(position)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            // Ctrl+колесо — размер шрифта; смотрим событие раньше прокрутки
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Scroll) continue
                        if (!event.keyboardModifiers.isCtrlPressed) continue
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy != 0f) {
                            currentOnZoom(if (dy > 0) -1f else 1f)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            // Мышь без нажатия: вид курсора и подсказка к подчёркнутой ошибке. События не забираем
            .pointerInput(ctrl) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter -> {
                                val p = event.changes.firstOrNull()?.position ?: continue
                                val overText = p.x >= state.gutterWidth &&
                                    p.x < state.viewport.width - SCROLLBAR_GRAB &&
                                    !(state.stickyLine >= 0 && p.y < m().lineHeight)
                                if (overText != textCursor) textCursor = overText
                                if (!event.buttons.areAnyPressed) {
                                    state.hoverTarget = if (overText) hoverAt(p) else null
                                }
                            }
                            PointerEventType.Exit, PointerEventType.Press, PointerEventType.Scroll -> state.hoverTarget = null
                        }
                    }
                }
            }
            .scrollable(vertical, Orientation.Vertical)
            .scrollable(horizontal, Orientation.Horizontal)
            .focusRequester(focusRequester)
            .onFocusChanged { ctrl.hasFocus = it.isFocused }
            .focusable()
            // Мышь: нажатие, протяжка, двойной и тройной щелчок одним обработчиком — иначе щелчок
            // после протяжки приходит следом и сбрасывает только что сделанное выделение
            .pointerInput(ctrl) {
                var lastClickMs = 0L
                var lastClickOffset = -1
                var clickCount = 0
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    runCatching { focusRequester.requestFocus() }
                    ctrl.followCaret = false
                    state.desiredColumn = -1
                    // Полоса прокрутки справа: тащим вид, а не выделяем текст
                    if (state.viewport.width > 0 && down.position.x >= state.viewport.width - SCROLLBAR_GRAB) {
                        val lay = layout()
                        val total = lay.totalRows * m().lineHeight
                        val visible = state.viewport.height.toFloat()
                        if (total > visible) {
                            fun scrollTo(y: Float) {
                                val barH = max(24f, visible * visible / total)
                                val frac = ((y - barH / 2f) / max(1f, visible - barH)).coerceIn(0f, 1f)
                                state.scrollY = frac * state.maxScrollY()
                            }
                            scrollTo(down.position.y)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                scrollTo(change.position.y)
                                change.consume()
                            }
                        }
                        return@awaitEachGesture
                    }
                    // Закреплённый заголовок: щелчок ведёт к самому объявлению
                    val sticky = state.stickyLine
                    if (sticky >= 0 && down.position.y < m().lineHeight) {
                        val snap = snap()
                        val t = text()
                        if (sticky < snap.lineCount) {
                            var at = snap.lineStart(sticky)
                            val end = snap.lineEnd(sticky)
                            while (at < end && (t[at] == ' ' || t[at] == '\t')) at++
                            setSelection(at, at)
                            state.scrollY = (layout().firstRowOf(sticky) * m().lineHeight).coerceIn(0f, state.maxScrollY())
                        }
                        down.consume()
                        return@awaitEachGesture
                    }
                    val startOffset = offsetAtPoint(down.position)
                    val shift = currentEvent.keyboardModifiers.isShiftPressed
                    if (currentEvent.keyboardModifiers.isAltPressed && down.position.x >= state.gutterWidth) {
                        if (shift) {
                            // Alt+Shift+протяжка — прямоугольное выделение
                            boxSelect(down.position, down.position)
                            var last = down.position
                            while (true) {
                                val event = withTimeoutOrNull(AUTOSCROLL_TICK_MS) { awaitPointerEvent() }
                                if (event == null) {
                                    if (edgeScroll(last)) boxSelect(down.position, last)
                                    continue
                                }
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                last = change.position
                                edgeScroll(last)
                                boxSelect(down.position, last)
                                change.consume()
                            }
                        } else {
                            // Alt+щелчок — ещё одна каретка (или убрать ту, что уже здесь)
                            ctrl.toggleCaretAt(startOffset)
                        }
                        down.consume()
                        return@awaitEachGesture
                    }
                    val now = System.currentTimeMillis()
                    clickCount = if (now - lastClickMs < 400 && abs(startOffset - lastClickOffset) <= 2) clickCount + 1 else 1
                    lastClickMs = now
                    lastClickOffset = startOffset

                    // Полоса номеров: стрелка сворачивает участок, номер выделяет строку
                    val inGutter = down.position.x < state.gutterWidth
                    if (inGutter && !shift && down.position.x >= state.foldLeft) {
                        val lay = layout()
                        val line = lay.lineOfRow(rowAt(down.position.y, lay))
                        val region = ctrl.foldsNow().firstOrNull { it.startLine == line }
                        if (region != null) {
                            ctrl.toggleFold(region)
                            down.consume()
                            return@awaitEachGesture
                        }
                    }

                    val t = text()
                    val mode = when {
                        shift -> SelectBy.CHAR
                        inGutter || clickCount >= 3 -> SelectBy.LINE
                        clickCount == 2 -> SelectBy.WORD
                        else -> SelectBy.CHAR
                    }
                    fun spanAt(at: Int): Pair<Int, Int> =
                        if (mode == SelectBy.LINE) lineAround(at) else wordAround(text(), at)
                    // Shift+щелчок продлевает выделение от прежнего начала; протяжка — тоже
                    val (anchorStart, anchorEnd) = when {
                        shift -> ctrl.value.selection.start.let { it to it }
                        mode == SelectBy.CHAR -> startOffset to startOffset
                        mode == SelectBy.WORD -> wordAround(t, startOffset)
                        else -> lineAround(startOffset)
                    }
                    fun selectTo(pos: Offset) {
                        val at = offsetAtPoint(pos)
                        when {
                            mode == SelectBy.CHAR -> setSelection(anchorStart, at)
                            at < anchorStart -> setSelection(anchorEnd, spanAt(at).first)
                            else -> setSelection(anchorStart, max(anchorEnd, spanAt(at).second))
                        }
                    }
                    if (shift) setSelection(anchorStart, startOffset) else setSelection(anchorStart, anchorEnd)

                    var dragging = false
                    var last = down.position
                    while (true) {
                        // Мышь замерла за краем окна — событий нет, а вид должен ехать дальше
                        val event = withTimeoutOrNull(AUTOSCROLL_TICK_MS) { awaitPointerEvent() }
                        if (event == null) {
                            if (dragging && edgeScroll(last)) selectTo(last)
                            continue
                        }
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        last = change.position
                        if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                        }
                        if (dragging) {
                            edgeScroll(last)
                            selectTo(last)
                            change.consume()
                        }
                    }
                }
            }
            .onKeyEvent { e ->
                // Печатаемый знак берём из события «символ напечатан» (KEY_TYPED): при нажатии
                // клавиши раскладка ещё не применена, и кириллица приходит как «знак не определён»
                if (e.type == KeyEventType.Unknown) {
                    val typed = e.utf16CodePoint
                    // Ctrl+Alt вместе — это AltGr (на европейских раскладках им набирают @ # { [ €),
                    // а вот Ctrl или Alt по отдельности — сочетание, а не ввод
                    val altGr = e.isCtrlPressed && e.isAltPressed
                    val modifier = (e.isCtrlPressed || e.isAltPressed) && !altGr
                    if (readOnly || modifier || typed < 32 || typed == 127 || typed == 0xFFFF) {
                        return@onKeyEvent false
                    }
                    val ch = typed.toChar()
                    // Знаки вне основного набора (эмодзи) приходят двумя половинками — склеиваем
                    if (Character.isHighSurrogate(ch)) {
                        pendingHighSurrogate[0] = ch
                        return@onKeyEvent true
                    }
                    val pending = pendingHighSurrogate[0]
                    pendingHighSurrogate[0] = null
                    val text = if (pending != null && Character.isLowSurrogate(ch)) {
                        String(charArrayOf(pending, ch))
                    } else {
                        String(Character.toChars(typed))
                    }
                    if (ctrl.hasMultipleCarets) onTypeCarets(text) else replaceSelection(text)
                    return@onKeyEvent true
                }
                // Сочетания редактора (поиск, свёртки, форматирование, подсказки) — снаружи
                if (onKey(e)) return@onKeyEvent true
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                state.hoverTarget = null

                val lay = layout()
                val snap = lay.snapshot
                val t = text()
                val v = ctrl.value
                val caret = v.selection.end.coerceIn(0, t.length)
                val line = if (snap.lineCount > 0) snap.lineOf(caret) else 0
                val lineStart = snap.lineStart(line)
                val lineEnd = snap.lineEnd(line)
                val page = max(1, (state.viewport.height / metrics.lineHeight).toInt() - 2)
                val (caretRow, caretColumn) = lay.rowAndColumn(caret)
                fun rowMove(delta: Int): Int {
                    if (state.desiredColumn < 0 || state.desiredCaret != caret || state.desiredVersion != ctrl.version) {
                        state.desiredColumn = caretColumn
                    }
                    return lay.offsetAt(
                        (caretRow + delta).coerceIn(0, max(0, lay.totalRows - 1)),
                        state.desiredColumn
                    )
                }
                fun pageMove(delta: Int, extend: Boolean) {
                    val target = rowMove(delta)
                    // Вид листается на ту же страницу: каретка остаётся на прежнем месте экрана
                    state.scrollY = (state.scrollY + delta * metrics.lineHeight).coerceIn(0f, state.maxScrollY())
                    moveCaret(target, extend, keepColumn = true)
                }
                fun scrollRows(delta: Int) {
                    state.scrollY = (state.scrollY + delta * metrics.lineHeight).coerceIn(0f, state.maxScrollY())
                }
                val selection = v.selection
                val selFrom = min(selection.start, selection.end)
                val selTo = max(selection.start, selection.end)
                fun copySelection(): Boolean {
                    if (selection.collapsed) return false
                    clipboard.setText(AnnotatedString(t.substring(selFrom, selTo)))
                    return true
                }
                val plainCtrl = e.isCtrlPressed && !e.isShiftPressed && !e.isAltPressed
                when {
                    // ---- буфер обмена и выделение: раньше это делало само поле ввода ----
                    Keymap.matches("editor.selectAll", e) -> {
                        onChange(v.copy(selection = TextRange(0, t.length)))
                        true
                    }
                    (e.isCtrlPressed && e.key == Key.C) || (e.isCtrlPressed && e.key == Key.Insert) -> copySelection()
                    (e.isCtrlPressed && e.key == Key.X) || (e.isShiftPressed && e.key == Key.Delete) -> {
                        if (readOnly || selection.collapsed) false
                        else {
                            copySelection()
                            deleteRange(selFrom, selTo)
                            true
                        }
                    }
                    (e.isCtrlPressed && e.key == Key.V) || (e.isShiftPressed && e.key == Key.Insert) -> {
                        if (readOnly) false
                        else {
                            val pasted = clipboard.getText()?.text
                            if (pasted.isNullOrEmpty()) false
                            else {
                                onPaste(pasted)
                                true
                            }
                        }
                    }
                    e.isCtrlPressed && e.key == Key.Backspace -> {
                        if (readOnly) false
                        else {
                            if (!selection.collapsed) deleteRange(selFrom, selTo)
                            else deleteRange(wordStart(t, caret, ctrl.language::isWordChar), caret)
                            true
                        }
                    }
                    e.isCtrlPressed && e.key == Key.Delete -> {
                        if (readOnly) false
                        else {
                            if (!selection.collapsed) deleteRange(selFrom, selTo)
                            else deleteRange(caret, wordEnd(t, caret, ctrl.language::isWordChar))
                            true
                        }
                    }
                    // ---- перемещение ----
                    e.isCtrlPressed && e.key == Key.MoveHome -> { moveCaret(0, e.isShiftPressed); true }
                    e.isCtrlPressed && e.key == Key.MoveEnd -> { moveCaret(t.length, e.isShiftPressed); true }
                    e.isCtrlPressed && e.key == Key.DirectionLeft -> {
                        moveCaret(wordStart(t, caret, ctrl.language::isWordChar), e.isShiftPressed); true
                    }
                    e.isCtrlPressed && e.key == Key.DirectionRight -> {
                        moveCaret(wordEnd(t, caret, ctrl.language::isWordChar), e.isShiftPressed); true
                    }
                    // Ctrl+↑/↓ — прокрутка на строку без движения каретки
                    plainCtrl && e.key == Key.DirectionDown -> { scrollRows(1); true }
                    plainCtrl && e.key == Key.DirectionUp -> { scrollRows(-1); true }
                    e.key == Key.DirectionDown -> { moveCaret(rowMove(1), e.isShiftPressed, keepColumn = true); true }
                    e.key == Key.DirectionUp -> { moveCaret(rowMove(-1), e.isShiftPressed, keepColumn = true); true }
                    e.key == Key.DirectionLeft -> {
                        // Стрелка вбок при выделении — к его краю, как в любом поле ввода
                        val to = if (!selection.collapsed && !e.isShiftPressed) selFrom else stepLeft(t, caret)
                        moveCaret(to, e.isShiftPressed); true
                    }
                    e.key == Key.DirectionRight -> {
                        val to = if (!selection.collapsed && !e.isShiftPressed) selTo else stepRight(t, caret)
                        moveCaret(to, e.isShiftPressed); true
                    }
                    e.key == Key.PageDown -> { pageMove(page, e.isShiftPressed); true }
                    e.key == Key.PageUp -> { pageMove(-page, e.isShiftPressed); true }
                    e.key == Key.MoveHome -> {
                        // Сначала к первому непробельному знаку строки, повторно — к самому началу
                        var i = lineStart
                        while (i < lineEnd && (t[i] == ' ' || t[i] == '\t')) i++
                        moveCaret(if (caret == i) lineStart else i, e.isShiftPressed)
                        true
                    }
                    e.key == Key.MoveEnd -> { moveCaret(lineEnd, e.isShiftPressed); true }
                    // ---- правка ----
                    readOnly -> false
                    e.key == Key.Backspace -> {
                        val sel = v.selection
                        if (!sel.collapsed) deleteRange(min(sel.start, sel.end), max(sel.start, sel.end))
                        else if (caret > 0) deleteRange(stepLeft(t, caret), caret)
                        true
                    }
                    e.key == Key.Delete -> {
                        val sel = v.selection
                        if (!sel.collapsed) deleteRange(min(sel.start, sel.end), max(sel.start, sel.end))
                        else if (caret < t.length) deleteRange(caret, stepRight(t, caret))
                        true
                    }
                    e.key == Key.Enter || e.key == Key.NumPadEnter -> { replaceSelection("\n"); true }
                    e.key == Key.Tab -> { replaceSelection(ctrl.indentUnit); true }
                    // Сам знак вставляется по событию «символ напечатан» выше
                    else -> false
                }
            }
    ) {
        val lay = layout()
        val snap = lay.snapshot
        state.stickyLine = -1
        if (snap.lineCount == 0) return@Canvas
        val h = size.height
        val lh = metrics.lineHeight
        val cw = metrics.charWidth
        val selection = ctrl.value.selection

        // Полоса слева: номера строк (если включены), за ними стрелки свёртки, затем текст
        val digits = max(2, snap.lineCount.toString().length)
        val numbersWidth = if (prefs.showLineNumbers) digits * cw + GUTTER_PAD * 2 else GUTTER_PAD
        val foldWidth = cw + GUTTER_PAD
        state.foldLeft = numbersWidth
        state.gutterWidth = numbersWidth + foldWidth
        state.textLeft = state.gutterWidth + 6f
        state.columns = lay.columns
        val textLeft = state.textLeft

        val visibleColumns = ceil(size.width / cw).toInt() + 2
        val firstColumn = if (lay.wrap) 0 else (state.scrollX / cw).toInt().coerceAtLeast(0)
        cache.viewportColumns(firstColumn)
        val firstRow = (state.scrollY / lh).toInt().coerceIn(0, max(0, lay.totalRows - 1))
        val lastRow = min(lay.totalRows - 1, firstRow + ceil(h / lh).toInt() + 1)
        if (lastRow < firstRow) return@Canvas

        val selFrom = min(selection.start, selection.end).coerceIn(0, snap.text.length)
        val selTo = max(selection.start, selection.end).coerceIn(0, snap.text.length)
        val caret = selection.end.coerceIn(0, snap.text.length)
        val (caretRow, caretColumn) = lay.rowAndColumn(caret)
        val (selFromRow, selFromColumn) = lay.rowAndColumn(selFrom)
        val (selToRow, selToColumn) = lay.rowAndColumn(selTo)

        fun columnX(column: Int): Float = textLeft + column * cw - state.scrollX
        fun rowY(row: Int): Float = row * lh - state.scrollY
        /** Сколько колонок текста в экранной строке (при переносе — её кусок строки). */
        fun rowTextWidth(row: Int): Int {
            val line = lay.lineOfRow(row)
            val width = lay.shownLine(line).width
            return if (lay.wrap) (width - (row - lay.firstRowOf(line)) * lay.columns).coerceIn(0, lay.columns) else width
        }
        /** Докуда тянуть выделение, переходящее на следующую экранную строку. */
        fun rowWidth(row: Int): Int = if (lay.wrap) lay.columns else rowTextWidth(row) + 1

        /**
         * Прямоугольники под кусок текста [from, to): по одному на экранную строку, с учётом табов
         * и переноса. Раньше ширина бралась числом знаков — совпадение с табом или перенесённое
         * на следующую строку рисовалось мимо текста.
         */
        fun spanRects(from: Int, to: Int, color: Color) {
            val (r1, c1) = lay.rowAndColumn(from)
            val (r2, c2) = lay.rowAndColumn(to)
            for (r in max(r1, firstRow)..min(r2, lastRow)) {
                val a = if (r == r1) c1 else 0
                val b = if (r == r2) c2 else rowWidth(r)
                if (b <= a && r != r1) continue
                drawRect(color, topLeft = Offset(columnX(a), rowY(r)), size = Size(max(1, b - a) * cw, lh))
            }
        }

        val visibleFrom = lay.offsetAt(firstRow, 0)
        val visibleTo = lay.offsetAt(lastRow, VISIBLE_END_COLUMN)

        // Где ищем при «в выделении» — чуть тронутый фон
        search.scope?.let { scope ->
            if (scope.max > visibleFrom && scope.min <= visibleTo) {
                spanRects(max(scope.min, visibleFrom), min(scope.max, visibleTo), colors.searchScope)
            }
        }

        // Совпадения поиска
        if (search.matches.count > 0) {
            var i = search.matches.lowerBound(visibleFrom)
            // Совпадение, начавшееся выше экрана и уходящее в него (многострочное выражение)
            if (i > 0 && search.matches.end(i - 1) > visibleFrom) i--
            while (i < search.matches.count && search.matches.start(i) <= visibleTo) {
                spanRects(
                    search.matches.start(i),
                    search.matches.end(i),
                    if (i == search.current) colors.searchCurrent else colors.searchMatch
                )
                i++
            }
        }

        // Вхождения слова под курсором (или выделенного куска строки) — только в видимой части:
        // поиск по хвосту файла выполнялся на каждом кадре, в том числе при мигании каретки
        if (prefs.highlightOccurrences) {
            val wholeWord = selFrom == selTo
            val needle = if (wholeWord) {
                TextOps.wordRangeAt(snap.text, caret, ctrl.language::isWordChar)
                    ?.let { snap.text.substring(it.first, it.last + 1) }
                    ?.takeIf { it.length >= 2 && !it.all(Char::isDigit) }
            } else {
                // Выделили кусок в пределах строки — видно, где ещё он встречается
                (selTo - selFrom).takeIf { it in 2..MAX_OCCURRENCE_SELECTION }
                    ?.let { snap.text.substring(selFrom, selTo) }
                    ?.takeIf { it.indexOf('\n') < 0 && it.isNotBlank() }
            }
            if (needle != null) {
                val from = visibleFrom.coerceIn(0, snap.text.length)
                val to = (visibleTo + needle.length).coerceIn(from, snap.text.length)
                val region = snap.text.substring(from, to)
                var i = region.indexOf(needle)
                while (i >= 0) {
                    val at = from + i
                    val end = at + needle.length
                    val before = !wholeWord || at == 0 || !ctrl.language.isWordChar(snap.text[at - 1])
                    val after = !wholeWord || end >= snap.text.length || !ctrl.language.isWordChar(snap.text[end])
                    if (before && after) spanRects(at, end, colors.occurrence)
                    i = region.indexOf(needle, i + needle.length)
                }
            }
        }

        // Парные скобки
        if (selFrom == selTo) {
            val pair = runCatching { ctrl.bracketPair(snap.text, caret, snap) }.getOrNull()
            if (pair != null) {
                for (offset in listOf(pair.first, pair.second)) {
                    val (row, column) = lay.rowAndColumn(offset)
                    if (row in firstRow..lastRow) {
                        drawRect(
                            colors.bracket,
                            topLeft = Offset(columnX(column), rowY(row)),
                            size = Size(cw, lh),
                            style = Stroke(width = 1f)
                        )
                    }
                }
            }
        }

        // Выделения остальных кареток (основная рисуется ниже, построчно)
        val extras = ctrl.extraCarets
        for (c in extras) {
            if (!c.collapsed && c.max >= visibleFrom && c.min <= visibleTo) spanRects(c.min, c.max, colors.selection)
        }

        val indentWidth = if (ctrl.indentUnit == "\t") prefs.tabSize else ctrl.indentUnit.length
        val marks = foldMarks.get(ctrl.folds, lay)
        val foldByLine = marks.first
        val collapsedStarts = marks.second
        var longest = 0

        for (row in firstRow..lastRow) {
            val line = lay.lineOfRow(row)
            val y = rowY(row)
            val shown = lay.shownLine(line)
            if (shown.width > longest) longest = shown.width
            val slice = if (lay.wrap) lay.sliceOf(line, row) else firstColumn..(firstColumn + visibleColumns)
            val firstOfLine = row == lay.firstRowOf(line)

            if (row == caretRow && selFrom == selTo && prefs.highlightCurrentLine) {
                drawRect(
                    colors.currentLine,
                    topLeft = Offset(state.gutterWidth, y),
                    size = Size(size.width - state.gutterWidth, lh)
                )
            }
            if (selTo > selFrom && row in selFromRow..selToRow) {
                val a = if (row == selFromRow) selFromColumn else 0
                val b = if (row == selToRow) selToColumn else rowWidth(row)
                val x1 = columnX(a)
                val x2 = columnX(max(a, b))
                drawRect(
                    colors.selection,
                    topLeft = Offset(x1, y),
                    size = Size(max(cw * 0.4f, x2 - x1), lh)
                )
            }

            if (prefs.showIndentGuides && indentWidth > 0 && (!lay.wrap || firstOfLine)) {
                var lead = 0
                while (lead < shown.text.length && (shown.text[lead] == ' ' || shown.text[lead] == ShownLine.SPACE_MARK)) lead++
                var guide = indentWidth
                while (guide < lead) {
                    val x = columnX(guide)
                    if (x > textLeft - 2f) {
                        drawRect(colors.indentGuide, topLeft = Offset(x, y), size = Size(1f, lh))
                    }
                    guide += indentWidth
                }
            }

            val layout = cache.row(lay, line, row, slice, styles, colors.text)
            drawText(layout, topLeft = Offset(columnX(slice.first), y))

            if (collapsedStarts.contains(line) && firstOfLine) {
                val mark = cache.mark(" ⋯ ", colors.dim)
                val markX = columnX(shown.width + 1)
                drawRect(colors.foldBox, topLeft = Offset(markX, y + 1f), size = Size(mark.size.width.toFloat(), lh - 2f))
                drawText(mark, topLeft = Offset(markX, y))
            }
        }

        // Ошибки: волнистая черта под каждым куском, по экранным строкам (и при переносе тоже)
        for (d in ctrl.diagnostics) {
            if (d.end < visibleFrom || d.start > visibleTo) continue
            val color = if (d.severity == Severity.ERROR) colors.error else colors.warning
            val (r1, c1) = lay.rowAndColumn(d.start)
            val (r2, c2) = lay.rowAndColumn(d.end)
            for (r in max(r1, firstRow)..min(r2, lastRow)) {
                val a = if (r == r1) c1 else 0
                // Пустой кусок (ошибка «в конце файла») — хотя бы под одним знаком
                val b = if (r == r2) max(c2, if (r == r1) a + 1 else c2) else rowTextWidth(r)
                if (b <= a) continue
                drawSurfaceSquiggle(
                    squigglePath,
                    columnX(a).coerceIn(0f, size.width),
                    columnX(b).coerceIn(0f, size.width),
                    rowY(r) + lh - 1.5f,
                    color
                )
            }
        }
        // Считаем по видимым строкам, но не копим: иначе полоса прокрутки навсегда оставалась
        // шириной самой длинной строки, которую когда-либо показывали
        state.longestColumns = longest

        // Полоса номеров — поверх текста: сдвинутые вбок выделение и начало строки под неё
        // уходят, а не рисуются поверх номеров
        drawRect(colors.gutter, size = Size(state.gutterWidth, h))
        fun gutterMarks(line: Int, y: Float, active: Boolean) {
            if (prefs.showLineNumbers) {
                val number = cache.number(line + 1, if (active) colors.gutterActive else colors.gutterText)
                drawText(number, topLeft = Offset(numbersWidth - GUTTER_PAD - number.size.width, y))
            }
            if (foldByLine[line] != null) {
                val open = !collapsedStarts.contains(line)
                val mark = cache.mark(if (open) "▾" else "▸", colors.gutterText)
                drawText(
                    mark,
                    topLeft = Offset(state.foldLeft + (foldWidth - mark.size.width) / 2f, y + (lh - mark.size.height) / 2f)
                )
            }
        }
        for (row in firstRow..lastRow) {
            val line = lay.lineOfRow(row)
            if (row == lay.firstRowOf(line)) gutterMarks(line, rowY(row), active = row == caretRow)
        }

        // Липкий заголовок: строка объявления, внутри которого мы находимся, закреплена сверху.
        // Видно, в какой функции идёт код, когда её начало уже уехало вверх.
        if (prefs.stickyHeaders && !lay.wrap) {
            val topLine = lay.lineOfRow(firstRow)
            val header = foldMarks.header(ctrl.outline, topLine)
            if (header != null && header.line in 0 until snap.lineCount) {
                val slice = firstColumn..(firstColumn + visibleColumns)
                drawRect(colors.gutter, topLeft = Offset(0f, 0f), size = Size(size.width, lh))
                val layout = cache.row(lay, header.line, lay.firstRowOf(header.line), slice, styles, colors.text)
                drawText(layout, topLeft = Offset(columnX(slice.first), 0f))
                drawRect(colors.gutter, topLeft = Offset(0f, 0f), size = Size(state.gutterWidth, lh))
                gutterMarks(header.line, 0f, active = false)
                drawRect(
                    colors.popupBorder.copy(alpha = 0.5f),
                    topLeft = Offset(0f, lh - 1f),
                    size = Size(size.width, 1f)
                )
                state.stickyLine = header.line
            }
        }

        // Каретка под закреплённым заголовком или под полосой номеров не видна — как и её строка
        val caretHidden = (state.stickyLine >= 0 && rowY(caretRow) < lh) || columnX(caretColumn) < state.gutterWidth
        if (caretRow in firstRow..lastRow && ctrl.hasFocus && state.caretVisible && !caretHidden) {
            drawRect(
                colors.text,
                topLeft = Offset(columnX(caretColumn), rowY(caretRow)),
                size = Size(1.5f, lh)
            )
        }
        // Остальные каретки — мигают вместе с основной
        if (extras.isNotEmpty() && ctrl.hasFocus && state.caretVisible) {
            for (c in extras) {
                if (c.end < visibleFrom || c.end > visibleTo) continue
                val (row, column) = lay.rowAndColumn(c.end)
                val x = columnX(column)
                if (row !in firstRow..lastRow || x < state.gutterWidth) continue
                if (state.stickyLine >= 0 && rowY(row) < lh) continue
                drawRect(colors.text, topLeft = Offset(x, rowY(row)), size = Size(1.5f, lh))
            }
        }

        // Полоса прокрутки
        val total = lay.totalRows * lh
        if (total > h) {
            val barH = max(24f, h * h / total)
            val barY = (state.scrollY / max(1f, total - h)) * (h - barH)
            drawRect(colors.dim.copy(alpha = 0.25f), topLeft = Offset(size.width - 8f, barY), size = Size(6f, barH))
            // Без мини-карты отметки ошибок и найденного — прямо на полосе прокрутки
            if (!showsMinimap(ctrl)) {
                val rows = max(1, lay.totalRows).toFloat()
                val length = snap.text.length
                fun markY(offset: Int): Float =
                    lay.firstRowOf(snap.lineOf(offset.coerceIn(0, length))) / rows * (h - 2f)
                val m = search.matches
                val step = max(1, m.count / 800)
                var i = 0
                while (i < m.count) {
                    drawRect(colors.searchCurrent, topLeft = Offset(size.width - 6f, markY(m.start(i))), size = Size(4f, 2f))
                    i += step
                }
                for (d in ctrl.diagnostics.take(500)) {
                    drawRect(
                        if (d.severity == Severity.ERROR) colors.error else colors.warning,
                        topLeft = Offset(size.width - 7f, markY(d.start)),
                        size = Size(6f, 3f)
                    )
                }
            }
        }
    }
}

/** Подсказка к ошибке под мышью: появляется, если мышь задержалась над подчёркнутым местом. */
@Composable
private fun FastSurfaceHoverTip(ctrl: CodeEditorController, state: FastSurfaceState, colors: EditorColors) {
    val target = state.hoverTarget
    var shown by remember { mutableStateOf<HoverTip?>(null) }
    androidx.compose.runtime.LaunchedEffect(target) {
        shown = null
        if (target != null) {
            kotlinx.coroutines.delay(HOVER_TIP_DELAY_MS)
            shown = target
        }
    }
    val tip = shown ?: return
    // Текст поменялся — под мышью уже не то место
    if (tip.version != ctrl.version || ctrl.completion != null) return
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = remember(tip) { CaretPopupPosition(tip.anchor, gap) },
        onDismissRequest = { state.hoverTarget = null },
        properties = androidx.compose.ui.window.PopupProperties(focusable = false)
    ) {
        Surface(
            color = colors.popup,
            shape = RoundedCornerShape(6.dp),
            shadowElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.popupBorder),
            modifier = Modifier.widthIn(max = 560.dp)
        ) {
            androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                for (d in tip.items.take(4)) {
                    Row(verticalAlignment = Alignment.Top) {
                        val (icon, tint) = when (d.severity) {
                            Severity.ERROR -> Icons.Default.Error to colors.error
                            Severity.WARNING -> Icons.Default.Warning to colors.warning
                            Severity.INFO -> Icons.Default.Info to colors.dim
                        }
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(d.message, fontSize = 12.sp, color = colors.text)
                        if (d.source.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(d.source, fontSize = 11.sp, color = colors.dim, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Список подсказок у курсора. */
@Composable
private fun FastSurfaceCompletion(
    ctrl: CodeEditorController,
    state: FastSurfaceState,
    holder: LayoutHolder,
    metrics: Metrics,
    colors: EditorColors,
    fontSize: TextUnit,
    focusRequester: FocusRequester
) {
    val completion = ctrl.completion ?: return
    val lay = state.layout ?: return
    val density = LocalDensity.current
    val (row, column) = lay.rowAndColumn(ctrl.value.selection.end)
    val x = state.textLeft + column * metrics.charWidth - state.scrollX
    val y = row * metrics.lineHeight - state.scrollY
    val caretRect = androidx.compose.ui.geometry.Rect(x, y, x + 2f, y + metrics.lineHeight)
    val gap = with(density) { 2.dp.roundToPx() }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = remember(caretRect) { CaretPopupPosition(caretRect, gap) },
        onDismissRequest = { ctrl.closeCompletion() },
        properties = androidx.compose.ui.window.PopupProperties(focusable = false)
    ) {
        CompletionPopup(completion, colors, fontSize) { item, word ->
            ctrl.acceptCompletion(item, word)
            runCatching { focusRequester.requestFocus() }
        }
    }
}

/**
 * Метки свёрток по строкам и закреплённый заголовок. Раньше и то и другое собиралось заново на
 * каждом кадре: в большом файле это десятки тысяч записей и полный обход структуры файла.
 */
internal class FoldMarks {
    private var foldsKey: Any? = null
    private var value: Pair<Map<Int, FoldRegion>, Set<Int>> = emptyMap<Int, FoldRegion>() to emptySet()

    fun get(folds: List<FoldRegion>, layout: FastLayout): Pair<Map<Int, FoldRegion>, Set<Int>> {
        val key = Triple(folds, layout.collapsed, layout.snapshot)
        if (key != foldsKey) {
            foldsKey = key
            val byLine = HashMap<Int, FoldRegion>(folds.size * 2)
            for (f in folds) byLine.putIfAbsent(f.startLine, f)
            val starts = HashSet<Int>()
            val length = layout.snapshot.text.length
            for (r in layout.collapsed) starts.add(layout.snapshot.lineOf(r.first.coerceIn(0, length)))
            value = byLine to starts
        }
        return value
    }

    private var headerLine = -1
    private var headerOutline: Any? = null
    private var header: OutlineItem? = null

    /** Объявление, внутри которого идёт строка [line] (закреплённый заголовок). */
    fun header(outline: List<OutlineItem>, line: Int): OutlineItem? {
        if (line != headerLine || outline !== headerOutline) {
            headerLine = line
            headerOutline = outline
            header = outline.lastOrNull { it.line < line && it.endLine >= line }
        }
        return header
    }
}

/** Заведомо больше любой видимой колонки: «до конца экранной строки». */
private const val VISIBLE_END_COLUMN = 1 shl 20

/** Каретка мигает с этим периодом — как в прежнем редакторе. */
private const val CARET_BLINK_MS = 530L

/** Полоса прокрутки: сколько пикселей справа считается её областью. */
private const val SCROLLBAR_GRAB = 12f

/** Отступы в полосе номеров: вокруг номеров и у стрелок свёртки. */
private const val GUTTER_PAD = 5f

/** Как часто подкручивать вид, пока мышь с зажатой кнопкой стоит за краем поля. */
private const val AUTOSCROLL_TICK_MS = 30L

/** Задержка перед подсказкой к ошибке под мышью. */
private const val HOVER_TIP_DELAY_MS = 450L

/** Выделение длиннее — уже не «слово», и его вхождения не подсвечиваем. */
private const val MAX_OCCURRENCE_SELECTION = 200

/** Мини-карта нужна файлам, которые заметно длиннее экрана. */
private const val MINIMAP_MIN_LINES = 40

/** Волнистое подчёркивание ошибки. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSurfaceSquiggle(
    path: androidx.compose.ui.graphics.Path,
    x1: Float,
    x2: Float,
    y: Float,
    color: Color
) {
    if (x2 <= x1) return
    // Нарисованное Skia уже скопировала себе, так что общий Path можно переписывать
    path.rewind()
    val step = 2.5f
    var x = x1
    var up = true
    path.moveTo(x, y)
    while (x < x2) {
        x += step
        path.lineTo(min(x, x2), if (up) y - 2f else y)
        up = !up
    }
    drawPath(path, color, style = Stroke(width = 1.1f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
}

/** Ширина знака и высота строки — по ним считается всё положение. */
internal class Metrics(val charWidth: Float, val lineHeight: Float)

/** Раскладка документа, пересобираемая только при изменении текста или ширины. */
internal class LayoutHolder {
    private var value: FastLayout? = null

    fun get(
        snap: HighlightSnapshot,
        tabSize: Int,
        whitespace: Boolean,
        columns: Int,
        collapsed: List<IntRange> = emptyList()
    ): FastLayout {
        val cur = value
        if (cur != null && cur.snapshot === snap && cur.columns == columns && cur.tabSize == tabSize &&
            cur.whitespace == whitespace && cur.collapsed == collapsed
        ) {
            return cur
        }
        return FastLayout(snap, tabSize, whitespace, columns, collapsed).also { value = it }
    }
}

/** Шаг каретки влево: через знак целиком, а не по половинке пары. */
internal fun stepLeft(text: String, from: Int): Int {
    if (from <= 0) return 0
    val before = from - 1
    return if (before > 0 && Character.isLowSurrogate(text[before]) && Character.isHighSurrogate(text[before - 1])) {
        before - 1
    } else {
        before
    }
}

/** Шаг каретки вправо. */
internal fun stepRight(text: String, from: Int): Int {
    if (from >= text.length) return text.length
    val next = from + 1
    return if (next < text.length && Character.isHighSurrogate(text[from]) && Character.isLowSurrogate(text[next])) {
        next + 1
    } else {
        next
    }
}

/**
 * Начало слова слева от [from] (Ctrl+стрелка и Ctrl+Backspace). В начале строки — шаг через
 * перевод строки: раньше Ctrl+← там стояла на месте, а Ctrl+Backspace ничего не удалял.
 */
internal fun wordStart(text: String, from: Int, isWordChar: (Char) -> Boolean): Int {
    var i = from.coerceIn(0, text.length)
    if (i > 0 && text[i - 1] == '\n') return i - 1
    while (i > 0 && text[i - 1] != '\n' && !isWordChar(text[i - 1])) i--
    while (i > 0 && isWordChar(text[i - 1])) i--
    return i
}

/** Конец слова справа от [from]; в конце строки — шаг на следующую. */
internal fun wordEnd(text: String, from: Int, isWordChar: (Char) -> Boolean): Int {
    var i = from.coerceIn(0, text.length)
    if (i < text.length && text[i] == '\n') return i + 1
    while (i < text.length && isWordChar(text[i])) i++
    while (i < text.length && text[i] != '\n' && !isWordChar(text[i])) i++
    return i
}

/**
 * Раскладки показанных экранных строк: одна строка — один маленький абзац Skia. Пока строка на
 * экране, её раскладка живёт здесь, поэтому прокрутка не считает то, что уже посчитано.
 */
internal class RowLayoutCache(private val measurer: TextMeasurer, private val style: TextStyle) {
    private var key: Any? = null
    private var firstColumn = -1
    private val rows = object : LinkedHashMap<Long, TextLayoutResult>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, TextLayoutResult>) = size > 400
    }
    private val numbers = object : LinkedHashMap<Long, TextLayoutResult>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, TextLayoutResult>) = size > 300
    }
    private val marks = HashMap<String, TextLayoutResult>()

    /**
     * Раскладки годятся, пока не сменились текст, стили и сама раскладка документа (перенос,
     * табы, показ пробелов, свёртки). Раньше сброс был только по снимку текста — и после Alt+Z
     * или смены темы на экране оставались строки, посчитанные по-старому.
     */
    private fun ensureFresh(layout: FastLayout, styles: Array<SpanStyle?>) {
        val current = Triple(layout.snapshot, layout, styles)
        if (current != key) {
            key = current
            rows.clear()
            numbers.clear()
            marks.clear()
        }
    }

    fun row(
        layout: FastLayout,
        line: Int,
        row: Int,
        slice: IntRange,
        styles: Array<SpanStyle?>,
        color: Color
    ): TextLayoutResult {
        ensureFresh(layout, styles)
        val rowKey = line.toLong() shl 20 or (row - layout.firstRowOf(line)).toLong()
        rows[rowKey]?.let { return it }
        val annotated = buildShownLine(layout.shownLine(line), layout.snapshot.lineTokens(line), styles, slice)
        val result = measurer.measure(annotated, style.copy(color = color), softWrap = false, maxLines = 1)
        rows[rowKey] = result
        return result
    }

    /** Сдвинулись по горизонтали — видимые куски строк другие. */
    fun viewportColumns(first: Int) {
        if (first != firstColumn) {
            firstColumn = first
            rows.clear()
        }
    }

    /** Цвет входит в ключ: активная строка рисуется другим цветом, а номер тот же. */
    fun number(n: Int, color: Color): TextLayoutResult {
        val key = (n.toLong() shl 32) or (color.value shr 32).toLong()
        numbers[key]?.let { return it }
        val result = measurer.measure(AnnotatedString(n.toString()), style.copy(color = color), softWrap = false, maxLines = 1)
        numbers[key] = result
        return result
    }

    fun mark(text: String, color: Color): TextLayoutResult = marks.getOrPut(text) {
        measurer.measure(AnnotatedString(text), style.copy(color = color), softWrap = false, maxLines = 1)
    }
}
