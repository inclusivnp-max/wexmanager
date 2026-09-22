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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
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
import kotlinx.coroutines.flow.collectLatest
import org.wexclient.app.editor.lang.SyntaxColors
import org.wexclient.app.editor.lang.Tok
import org.wexclient.app.util.Keymap
import org.wexclient.app.theme.AppFonts
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Поверхность редактора: свой буфер строк, свой рендер и свой ввод — без BasicTextField.
 *
 * Поле ввода Compose держит весь текст одной раскладкой Skia, и она пересчитывается целиком на
 * каждое нажатие: на полутора мегабайтах это пятая часть секунды на букву и семь секунд на
 * открытие. Здесь рисуются только видимые строки, а положение строки и знака — арифметика по
 * ширине моноширинного знака и постоянной высоте строки, поэтому размер файла почти не влияет.
 *
 * Всё управление снаружи: клавиши сначала уходят в [onKey] (там все сочетания редактора), правки —
 * в [onChange] (там автоскобки, отступы, история и подсказки).
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
    contextItems: () -> List<EditorMenuItem>,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = EditorPreferences
    val colors = rememberEditorColors()
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val measurer = rememberTextMeasurer(cacheSize = 0)

    val textStyle = remember(fontSize, lineHeight) {
        TextStyle(fontFamily = AppFonts.JetBrainsMono, fontSize = fontSize, lineHeight = lineHeight)
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
        if (prefs.showMinimap && ctrl.snapshot.lineCount > 40) {
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
    var columns = 0

    /** Раскладка на момент последней отрисовки. */
    var layout: FastLayout? = null

    /** Самая длинная показанная строка в колонках — для горизонтальной прокрутки. */
    var longestColumns = 40

    /** Каретка видна сейчас (мигание); при наборе не мигает. */
    var caretVisible by mutableStateOf(true)

    /** Колонка, к которой стремится каретка при движении вверх-вниз (-1 — не задана). */
    var desiredColumn = -1

    fun maxScrollY(): Float {
        val rows = layout?.totalRows ?: ctrl.snapshot.lineCount
        return max(0f, rows * metrics.lineHeight - viewport.height + metrics.lineHeight)
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
    onZoom: (Float) -> Unit,
    onContextMenu: (Offset) -> Unit,
    clipboard: androidx.compose.ui.platform.ClipboardManager
) {
    val prefs = EditorPreferences
    // Половинка знака вне основного набора, пришедшая первым событием
    var pendingHighSurrogate: Char? = null

    fun snap() = ctrl.snapshot
    fun text() = ctrl.value.text

    fun wrapColumns(): Int {
        if (!prefs.softWrap) return 0
        val usable = state.viewport.width - state.textLeft - 14f
        return max(8, (usable / metrics.charWidth).toInt())
    }

    fun layout(): FastLayout = holder.get(
        snap(),
        prefs.tabSize,
        prefs.showWhitespace,
        wrapColumns(),
        ctrl.effectiveCollapsed()
    ).also { state.layout = it }

    fun offsetAtPoint(pos: Offset): Int {
        val lay = layout()
        val row = ((pos.y + state.scrollY) / metrics.lineHeight).toInt()
        val column = ((pos.x + state.scrollX - state.textLeft) / metrics.charWidth).roundToInt()
        return lay.offsetAt(row.coerceIn(0, max(0, lay.totalRows - 1)), max(0, column))
    }

    fun setSelection(start: Int, end: Int) {
        onChange(ctrl.value.copy(selection = TextRange(start, end)))
    }

    fun moveCaret(to: Int, extend: Boolean, keepColumn: Boolean = false) {
        // Движение вверх-вниз помнит колонку: пройдя короткую строку, каретка возвращается
        // на прежнее место, как в любом редакторе кода
        if (!keepColumn) state.desiredColumn = -1
        val anchor = if (extend) ctrl.value.selection.start else to
        setSelection(anchor, to)
        state.reveal(to)
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

    val vertical = rememberScrollableState { delta ->
        val before = state.scrollY
        state.scrollY = (state.scrollY - delta).coerceIn(0f, state.maxScrollY())
        before - state.scrollY
    }
    val horizontal = rememberScrollableState { delta ->
        if (prefs.softWrap) return@rememberScrollableState 0f
        val maxX = max(0f, state.longestColumns * metrics.charWidth + state.textLeft + 40f - state.viewport.width)
        val before = state.scrollX
        state.scrollX = (state.scrollX - delta).coerceIn(0f, maxX)
        before - state.scrollX
    }

    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .clipToBounds()
            .onSizeChanged { state.viewport = it }
            // Правая кнопка: ловим до жестов выделения, иначе событие до них не доходит
            .pointerInput(ctrl) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        if (!event.buttons.isSecondaryPressed) continue
                        val position = event.changes.firstOrNull()?.position ?: continue
                        runCatching { focusRequester.requestFocus() }
                        // Курсор двигаем только если щёлкнули вне выделения
                        val at = offsetAtPoint(position)
                        val sel = ctrl.value.selection
                        if (at < min(sel.start, sel.end) || at > max(sel.start, sel.end)) setSelection(at, at)
                        onContextMenu(position)
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
                            onZoom(if (dy > 0) -1f else 1f)
                            event.changes.forEach { it.consume() }
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
                    // Правая кнопка: курсор ставим только если щёлкнули вне выделения
                    // Полоса прокрутки справа: тащим вид, а не выделяем текст
                    if (state.viewport.width > 0 && down.position.x >= state.viewport.width - SCROLLBAR_GRAB) {
                        val lay = layout()
                        val total = lay.totalRows * metrics.lineHeight
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
                    val startOffset = offsetAtPoint(down.position)
                    // Shift+щелчок — продлить выделение от текущего края
                    if (currentEvent.keyboardModifiers.isShiftPressed) {
                        setSelection(ctrl.value.selection.start, startOffset)
                        down.consume()
                        return@awaitEachGesture
                    }
                    val now = System.currentTimeMillis()
                    clickCount = if (now - lastClickMs < 400 && abs(startOffset - lastClickOffset) <= 2) clickCount + 1 else 1
                    lastClickMs = now
                    lastClickOffset = startOffset

                    // Полоса номеров: щелчок по стрелке сворачивает участок
                    if (down.position.x < state.gutterWidth) {
                        val lay = layout()
                        val row = ((down.position.y + state.scrollY) / metrics.lineHeight).toInt()
                        val line = lay.lineOfRow(row.coerceIn(0, max(0, lay.totalRows - 1)))
                        val region = ctrl.foldsNow().firstOrNull { it.startLine == line }
                        if (region != null) {
                            ctrl.toggleFold(region)
                            down.consume()
                            return@awaitEachGesture
                        }
                    }

                    val t = text()
                    when {
                        clickCount >= 3 -> {
                            val snap = snap()
                            val line = snap.lineOf(startOffset.coerceIn(0, t.length))
                            val from = snap.lineStart(line)
                            val to = if (line + 1 < snap.lineCount) snap.lineStart(line + 1) else t.length
                            setSelection(from, to)
                            return@awaitEachGesture
                        }
                        clickCount == 2 -> {
                            var a = startOffset.coerceIn(0, t.length)
                            var b = a
                            while (a > 0 && ctrl.language.isWordChar(t[a - 1])) a--
                            while (b < t.length && ctrl.language.isWordChar(t[b])) b++
                            setSelection(a, b)
                            return@awaitEachGesture
                        }
                    }
                    setSelection(startOffset, startOffset)
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        if (!dragging &&
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        ) {
                            dragging = true
                        }
                        if (dragging) {
                            // Потянули за край окна — подкручиваем
                            val h = state.viewport.height
                            if (change.position.y < 0) {
                                state.scrollY = max(0f, state.scrollY + change.position.y / 2f)
                            } else if (h > 0 && change.position.y > h) {
                                state.scrollY = min(state.maxScrollY(), state.scrollY + (change.position.y - h) / 2f)
                            }
                            setSelection(startOffset, offsetAtPoint(change.position))
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
                        pendingHighSurrogate = ch
                        return@onKeyEvent true
                    }
                    val pending = pendingHighSurrogate
                    pendingHighSurrogate = null
                    val text = if (pending != null && Character.isLowSurrogate(ch)) {
                        String(charArrayOf(pending, ch))
                    } else {
                        String(Character.toChars(typed))
                    }
                    replaceSelection(text)
                    return@onKeyEvent true
                }
                // Сочетания редактора (поиск, свёртки, форматирование, подсказки) — снаружи
                if (onKey(e)) return@onKeyEvent true
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false

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
                    if (state.desiredColumn < 0) state.desiredColumn = caretColumn
                    return lay.offsetAt(
                        (caretRow + delta).coerceIn(0, max(0, lay.totalRows - 1)),
                        state.desiredColumn
                    )
                }
                val selection = v.selection
                val selFrom = min(selection.start, selection.end)
                val selTo = max(selection.start, selection.end)
                fun copySelection(): Boolean {
                    if (selection.collapsed) return false
                    clipboard.setText(AnnotatedString(t.substring(selFrom, selTo)))
                    return true
                }
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
                                replaceSelection(pasted.replace("\r\n", "\n").replace('\r', '\n'))
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
                    e.isCtrlPressed && e.key == Key.Home -> { moveCaret(0, e.isShiftPressed); true }
                    e.isCtrlPressed && e.key == Key.MoveEnd -> { moveCaret(t.length, e.isShiftPressed); true }
                    e.isCtrlPressed && e.key == Key.DirectionLeft -> {
                        moveCaret(wordStart(t, caret, ctrl.language::isWordChar), e.isShiftPressed); true
                    }
                    e.isCtrlPressed && e.key == Key.DirectionRight -> {
                        moveCaret(wordEnd(t, caret, ctrl.language::isWordChar), e.isShiftPressed); true
                    }
                    e.key == Key.DirectionDown -> { moveCaret(rowMove(1), e.isShiftPressed, keepColumn = true); true }
                    e.key == Key.DirectionUp -> { moveCaret(rowMove(-1), e.isShiftPressed, keepColumn = true); true }
                    e.key == Key.DirectionLeft -> { moveCaret(stepLeft(t, caret), e.isShiftPressed); true }
                    e.key == Key.DirectionRight -> { moveCaret(stepRight(t, caret), e.isShiftPressed); true }
                    e.key == Key.PageDown -> { moveCaret(rowMove(page), e.isShiftPressed, keepColumn = true); true }
                    e.key == Key.PageUp -> { moveCaret(rowMove(-page), e.isShiftPressed, keepColumn = true); true }
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
        if (snap.lineCount == 0) return@Canvas
        val h = size.height
        val selection = ctrl.value.selection

        val digits = max(2, snap.lineCount.toString().length)
        state.gutterWidth = digits * metrics.charWidth + 14f
        state.textLeft = state.gutterWidth + 8f
        state.columns = lay.columns
        val textLeft = state.textLeft

        val visibleColumns = ceil(size.width / metrics.charWidth).toInt() + 2
        val firstColumn = if (lay.wrap) 0 else (state.scrollX / metrics.charWidth).toInt().coerceAtLeast(0)
        cache.viewportColumns(firstColumn)
        val firstRow = (state.scrollY / metrics.lineHeight).toInt().coerceIn(0, max(0, lay.totalRows - 1))
        val lastRow = min(lay.totalRows - 1, firstRow + ceil(h / metrics.lineHeight).toInt() + 1)
        if (lastRow < firstRow) return@Canvas

        val selFrom = min(selection.start, selection.end).coerceIn(0, snap.text.length)
        val selTo = max(selection.start, selection.end).coerceIn(0, snap.text.length)
        val caret = selection.end.coerceIn(0, snap.text.length)
        val (caretRow, caretColumn) = lay.rowAndColumn(caret)
        val (selFromRow, selFromColumn) = lay.rowAndColumn(selFrom)
        val (selToRow, selToColumn) = lay.rowAndColumn(selTo)

        fun columnX(column: Int): Float = textLeft + column * metrics.charWidth - state.scrollX

        drawRect(colors.gutter, size = Size(state.gutterWidth, h))

        // Совпадения поиска
        if (search.matches.count > 0) {
            val from = lay.offsetAt(firstRow, 0)
            val to = lay.offsetAt(lastRow, VISIBLE_END_COLUMN)
            var i = search.matches.lowerBound(from)
            while (i < search.matches.count && search.matches.start(i) <= to) {
                val (row, column) = lay.rowAndColumn(search.matches.start(i))
                val width = max(1, search.matches.end(i) - search.matches.start(i))
                drawRect(
                    if (i == search.current) colors.searchCurrent else colors.searchMatch,
                    topLeft = Offset(columnX(column), row * metrics.lineHeight - state.scrollY),
                    size = Size(width * metrics.charWidth, metrics.lineHeight)
                )
                i++
            }
        }

        // Вхождения слова под курсором — только в видимой части: поиск по хвосту файла
        // выполнялся на каждом кадре, в том числе при мигании каретки
        if (prefs.highlightOccurrences && selFrom == selTo) {
            val word = TextOps.wordRangeAt(snap.text, caret, ctrl.language::isWordChar)
            val needle = word?.let { snap.text.substring(it.first, it.last + 1) }
                ?.takeIf { it.length >= 2 && !it.all(Char::isDigit) }
            if (needle != null) {
                val from = lay.offsetAt(firstRow, 0).coerceIn(0, snap.text.length)
                val to = (lay.offsetAt(lastRow, VISIBLE_END_COLUMN) + needle.length)
                    .coerceIn(from, snap.text.length)
                val region = snap.text.substring(from, to)
                var i = region.indexOf(needle)
                while (i >= 0) {
                    val at = from + i
                    val before = at == 0 || !ctrl.language.isWordChar(snap.text[at - 1])
                    val end = at + needle.length
                    val after = end >= snap.text.length || !ctrl.language.isWordChar(snap.text[end])
                    if (before && after) {
                        val (row, column) = lay.rowAndColumn(at)
                        drawRect(
                            colors.occurrence,
                            topLeft = Offset(columnX(column), row * metrics.lineHeight - state.scrollY),
                            size = Size(needle.length * metrics.charWidth, metrics.lineHeight)
                        )
                    }
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
                            topLeft = Offset(columnX(column), row * metrics.lineHeight - state.scrollY),
                            size = Size(metrics.charWidth, metrics.lineHeight),
                            style = Stroke(width = 1f)
                        )
                    }
                }
            }
        }

        val indentWidth = if (ctrl.indentUnit == "\t") prefs.tabSize else ctrl.indentUnit.length
        val marks = foldMarks.get(ctrl.folds, lay)
        val foldByLine = marks.first
        val collapsedStarts = marks.second
        // Берём только те проверки, что попадают в видимые строки: полный список бывает большим,
        // а раньше он обходился заново для каждой строки кадра
        val visibleFrom = lay.offsetAt(firstRow, 0)
        val visibleTo = lay.offsetAt(lastRow, VISIBLE_END_COLUMN)
        val visibleDiagnostics = ctrl.diagnostics.filter { it.end > visibleFrom && it.start <= visibleTo }
        var longest = 0

        for (row in firstRow..lastRow) {
            val line = lay.lineOfRow(row)
            val y = row * metrics.lineHeight - state.scrollY
            val shown = lay.shownLine(line)
            if (shown.width > longest) longest = shown.width
            val slice = if (lay.wrap) lay.sliceOf(line, row) else firstColumn..(firstColumn + visibleColumns)
            val lineStart = snap.lineStart(line)
            val lineLength = max(0, snap.lineEnd(line) - lineStart)

            if (row == caretRow && selFrom == selTo && prefs.highlightCurrentLine) {
                drawRect(
                    colors.currentLine,
                    topLeft = Offset(state.gutterWidth, y),
                    size = Size(size.width - state.gutterWidth, metrics.lineHeight)
                )
            }
            if (selTo > selFrom && row in selFromRow..selToRow) {
                val rowWidth = if (lay.wrap) lay.columns else shown.width + 1
                val a = if (row == selFromRow) selFromColumn else 0
                val b = if (row == selToRow) selToColumn else rowWidth
                val x1 = columnX(a)
                val x2 = columnX(max(a, b))
                drawRect(
                    colors.selection,
                    topLeft = Offset(x1, y),
                    size = Size(max(metrics.charWidth * 0.4f, x2 - x1), metrics.lineHeight)
                )
            }

            if (prefs.showIndentGuides && indentWidth > 0 && (!lay.wrap || row == lay.firstRowOf(line))) {
                var lead = 0
                while (lead < shown.text.length && (shown.text[lead] == ' ' || shown.text[lead] == ShownLine.SPACE_MARK)) lead++
                var guide = indentWidth
                while (guide < lead) {
                    val x = columnX(guide)
                    if (x > textLeft - 2f) {
                        drawRect(colors.indentGuide, topLeft = Offset(x, y), size = Size(1f, metrics.lineHeight))
                    }
                    guide += indentWidth
                }
            }

            if (row == lay.firstRowOf(line)) {
                val active = row == caretRow
                val number = cache.number(line + 1, if (active) colors.gutterActive else colors.gutterText)
                drawText(number, topLeft = Offset(state.gutterWidth - number.size.width - 7f, y))
                val fold = foldByLine[line]
                if (fold != null) {
                    val open = !collapsedStarts.contains(line)
                    val mark = cache.mark(if (open) "▾" else "▸", colors.gutterText)
                    drawText(mark, topLeft = Offset(3f, y + (metrics.lineHeight - mark.size.height) / 2f))
                }
            }

            val layout = cache.row(lay, line, row, slice, styles, colors.text)
            drawText(layout, topLeft = Offset(columnX(slice.first), y))

            if (collapsedStarts.contains(line) && row == lay.firstRowOf(line)) {
                val mark = cache.mark(" ⋯ ", colors.dim)
                val markX = columnX(shown.width + 1)
                drawRect(colors.foldBox, topLeft = Offset(markX, y + 1f), size = Size(mark.size.width.toFloat(), metrics.lineHeight - 2f))
                drawText(mark, topLeft = Offset(markX, y))
            }

            if (visibleDiagnostics.isNotEmpty()) {
                for (d in visibleDiagnostics) {
                    if (d.end <= lineStart || d.start > lineStart + lineLength) continue
                    val a = shown.columnOf((d.start - lineStart).coerceIn(0, lineLength))
                    val b = shown.columnOf((d.end - lineStart).coerceIn(0, lineLength))
                    if (b <= a) continue
                    val color = if (d.severity == Severity.ERROR) colors.error else colors.warning
                    drawSurfaceSquiggle(
                        columnX(a).coerceIn(0f, size.width),
                        columnX(b).coerceIn(0f, size.width),
                        y + metrics.lineHeight - 1.5f,
                        color
                    )
                }
            }
        }
        // Считаем по видимым строкам, но не копим: иначе полоса прокрутки навсегда оставалась
        // шириной самой длинной строки, которую когда-либо показывали
        state.longestColumns = max(longest, (state.scrollX / metrics.charWidth).toInt() + visibleColumns)

        // Липкий заголовок: строка объявления, внутри которого мы находимся, закреплена сверху.
        // Видно, в какой функции идёт код, когда её начало уже уехало вверх.
        if (prefs.stickyHeaders && !lay.wrap) {
            val topLine = lay.lineOfRow(firstRow)
            val header = foldMarks.header(ctrl.outline, topLine)
            if (header != null && header.line in 0 until snap.lineCount) {
                val shown = lay.shownLine(header.line)
                val slice = firstColumn..(firstColumn + visibleColumns)
                drawRect(colors.gutter, topLeft = Offset(0f, 0f), size = Size(size.width, metrics.lineHeight))
                drawRect(
                    colors.popupBorder.copy(alpha = 0.5f),
                    topLeft = Offset(0f, metrics.lineHeight - 1f),
                    size = Size(size.width, 1f)
                )
                val number = cache.number(header.line + 1, colors.gutterText)
                drawText(number, topLeft = Offset(state.gutterWidth - number.size.width - 7f, 0f))
                val layout = cache.row(lay, header.line, lay.firstRowOf(header.line), slice, styles, colors.text)
                drawText(layout, topLeft = Offset(columnX(slice.first), 0f))
            }
        }

        if (caretRow in firstRow..lastRow && ctrl.hasFocus && state.caretVisible) {
            drawRect(
                colors.text,
                topLeft = Offset(columnX(caretColumn), caretRow * metrics.lineHeight - state.scrollY),
                size = Size(1.5f, metrics.lineHeight)
            )
        }

        // Полоса прокрутки
        val total = lay.totalRows * metrics.lineHeight
        if (total > h) {
            val barH = max(24f, h * h / total)
            val barY = (state.scrollY / max(1f, total - h)) * (h - barH)
            drawRect(colors.dim.copy(alpha = 0.25f), topLeft = Offset(size.width - 8f, barY), size = Size(6f, barH))
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

/** Волнистое подчёркивание ошибки. */
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSurfaceSquiggle(
    x1: Float,
    x2: Float,
    y: Float,
    color: Color
) {
    if (x2 <= x1) return
    val path = androidx.compose.ui.graphics.Path()
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

/** Начало слова слева от [from] (Ctrl+стрелка и Ctrl+Backspace). */
internal fun wordStart(text: String, from: Int, isWordChar: (Char) -> Boolean): Int {
    var i = from.coerceIn(0, text.length)
    while (i > 0 && text[i - 1] != '\n' && !isWordChar(text[i - 1])) i--
    while (i > 0 && isWordChar(text[i - 1])) i--
    return i
}

/** Конец слова справа от [from]. */
internal fun wordEnd(text: String, from: Int, isWordChar: (Char) -> Boolean): Int {
    var i = from.coerceIn(0, text.length)
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
