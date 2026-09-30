package org.wexclient.app.editor

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Expand
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.editor.lang.Language
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.ui.ToastHost
import org.wexclient.app.ui.rememberToastState
import org.wexclient.app.util.Keymap
import org.wexclient.app.util.isCtrlShortcut
import org.wexclient.app.util.matchesKey
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.util.Base64

private val TRIGGERS_SMALI = ".-/>:["
private val TRIGGERS_MARKUP = "<:@/.?-"
private val TRIGGERS_CSS = "-@"
private val TRIGGERS_SHELL = "$-/{"
private val TRIGGERS_CODE = "./@$"

private const val BIG_LAYOUT_CHARS = 300_000

@Composable
fun CodeEditorView(
    initialText: String,
    language: Language,
    readOnly: Boolean = false,
    fileName: String = "",
    onSave: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    controller: CodeEditorController? = null,
    savedMessage: String = "Сохранено",
    saveButtonText: String = "Сохранить (Ctrl+S)",
    errorPrefix: String = "Не сохранено",
    /** Писать в фоне (файлы на диске и в архивах); без него [onSave] вызывается в потоке окна. */
    backgroundSave: org.wexclient.app.ui.BackgroundSave? = null
) {
    val ctrl = controller ?: rememberCodeEditorController(initialText)
    ctrl.saver = onSave
    val effectiveLanguage = ctrl.languageOverride ?: language
    remember(ctrl, effectiveLanguage) {
        ctrl.applyLanguage(effectiveLanguage)
        effectiveLanguage
    }
    ctrl.syncModel()

    val fontResolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    remember(fontResolver) {
        org.wexclient.app.util.NativeMemory.disableParagraphCache(fontResolver)
        true
    }
    // Большой текст закрыли — его раскладку (память Skia) отпускает только сборка мусора
    androidx.compose.runtime.DisposableEffect(ctrl) {
        onDispose {
            if (ctrl.value.text.length > BIG_LAYOUT_CHARS) org.wexclient.app.util.NativeMemory.releaseSoon()
        }
    }

    val toast = rememberToastState()
    var editing by remember { mutableStateOf(!readOnly) }
    val effectiveReadOnly = readOnly || !editing
    val find = remember { FindState() }
    var matches by remember { mutableStateOf(SearchMatches.EMPTY) }
    var replacing by remember { mutableStateOf(false) }
    var currentMatch by remember { mutableIntStateOf(-1) }
    var lastFindOptions by remember { mutableStateOf<SearchOptions?>(null) }
    var findAnchor by remember { mutableIntStateOf(0) }
    var showGoto by remember { mutableStateOf(false) }
    var showStructure by remember { mutableStateOf(false) }
    var pendingReload by remember { mutableStateOf<Charset?>(null) }
    val focus = remember { FocusRequester() }
    val findFocus = remember { FocusRequester() }
    // Своя поверхность рисует только видимые строки: прокрутка и геометрия живут в ней
    val surface = rememberFastSurfaceState(ctrl)
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val prefs = EditorPreferences
    val fontSizeSp = prefs.fontSizeSp
    val tabSize = prefs.tabSize
    val cfg = ctrl.config(tabSize, prefs.autoClose)
    val canSave = onSave != null && !readOnly
    val baseDir = ctrl.filePath?.takeIf { it.fileSystem == FileSystems.getDefault() }?.parent
    val formatLabel = remember(effectiveLanguage) { CodeFormatter.describe(effectiveLanguage) }

    fun focusEditor() {
        scope.launch {
            // Ждём кадр, а не время: фокус запрашивается в потоке композиции
            requestFocusSafely(focus)
        }
    }

    fun trySave() {
        if (!canSave) return
        val report = { err: String? ->
            if (err == null) toast.success(if (fileName.isNotEmpty()) "$savedMessage: $fileName" else savedMessage)
            else toast.error("$errorPrefix: ${err.lineSequence().first()}")
        }
        if (backgroundSave == null) report(ctrl.save())
        else if (!ctrl.saveInBackground(backgroundSave, report)) toast.info("Сохранение уже идёт")
    }

    fun edit(v: TextFieldValue?, kind: EditKind = EditKind.OTHER) {
        if (v == null || effectiveReadOnly) return
        ctrl.applyEdit(v, kind)
    }

    fun doFormat() {
        if (effectiveReadOnly) return
        val snap = ctrl.snapshot
        val text = ctrl.value.text
        val caret = ctrl.value.selection.end
        val line = snap.lineOf(caret)
        val col = caret - snap.lineStart(line)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                CodeFormatter.format(text, snap, ctrl.indentUnit, tabSize, ctrl.filePath)
            }
            if (ctrl.value.text != text) return@launch
            val out = r.text
            if (out == null) {
                toast.error(r.error ?: "Не удалось отформатировать")
                return@launch
            }
            val normalized = TextCodec.normalize(out).let { if (!text.endsWith("\n") && it.endsWith("\n")) it.dropLast(1) else it }
            if (normalized == text) {
                toast.info("Уже отформатировано (${r.tool})")
                return@launch
            }
            // Курсор — на ту же строку и колонку
            var ls = 0
            var l = 0
            while (l < line) {
                val nl = normalized.indexOf('\n', ls)
                if (nl < 0) break
                ls = nl + 1
                l++
            }
            val le = normalized.indexOf('\n', ls).let { if (it < 0) normalized.length else it }
            ctrl.applyEdit(TextFieldValue(normalized, TextRange((ls + col).coerceIn(ls, le))))
            toast.success("Отформатировано: ${r.tool}")
        }
    }

    fun navigateTo(offset: Int, end: Int = offset, center: Boolean = true) {
        ctrl.select(offset, end, center)
        focusEditor()
    }

    fun gotoProblem(backward: Boolean) {
        val diags = ctrl.diagnostics.sortedBy { it.start }
        if (diags.isEmpty()) {
            toast.info("Проблем не найдено")
            return
        }
        val caret = ctrl.value.selection.start
        val target = if (backward) diags.lastOrNull { it.start < caret } ?: diags.last()
        else diags.firstOrNull { it.start > caret } ?: diags.first()
        ctrl.select(target.start, target.start, center = true)
        toast.info(target.message)
    }

    /** Где искать: область «в выделении», если она включена. */
    fun searchWithin(): IntRange? = if (find.inSelection) ctrl.searchScope?.let { it.min until it.max } else null

    fun selectMatch(i: Int) {
        val m = matches
        if (i !in 0 until m.count) return
        currentMatch = i
        ctrl.select(m.start(i), m.end(i), center = true)
    }

    fun findNext(backward: Boolean) {
        val m = matches
        if (m.count == 0) return
        val sel = ctrl.value.selection
        val cur = m.indexOfRange(sel.min, sel.max)
        val i = when {
            cur >= 0 -> if (backward) (cur - 1 + m.count) % m.count else (cur + 1) % m.count
            backward -> m.indexBefore(sel.min)
            else -> m.indexAtOrAfter(sel.max)
        }
        selectMatch(i)
    }

    fun replaceCurrent() {
        if (effectiveReadOnly) return
        val m = matches
        val sel = ctrl.value.selection
        val cur = m.indexOfRange(sel.min, sel.max)
        if (cur < 0) {
            findNext(false)
            return
        }
        val text = ctrl.value.text
        val repl = SearchEngine.replacementFor(text, sel.min, sel.max, find.options, find.replacement)
        if (repl == null) {
            toast.error("Не удалось подставить замену")
            return
        }
        val newText = text.substring(0, sel.min) + repl + text.substring(sel.max)
        val after = sel.min + repl.length
        ctrl.applyEdit(TextFieldValue(newText, TextRange(after)))
        // Следующее совпадение ищется после пересчёта
        scope.launch {
            val within = searchWithin()
            val next = withContext(Dispatchers.Default) { SearchEngine.findAll(newText, find.options, within = within) }
            matches = next
            if (next.count > 0) {
                val i = next.indexAtOrAfter(after)
                currentMatch = i
                ctrl.select(next.start(i), next.end(i), center = true)
            } else currentMatch = -1
        }
    }

    fun replaceAll() {
        if (effectiveReadOnly || replacing) return
        val text = ctrl.value.text
        val options = find.options
        val replacement = find.replacement
        val within = searchWithin()
        replacing = true
        // В фоне и со сроком: сложное выражение не вешает окно, ошибка переполнения стека не роняет его
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { SearchEngine.replaceAll(text, options, replacement, within = within) }
            }
            replacing = false
            if (ctrl.value.text != text) {
                toast.error("Текст изменился во время замены — повторите")
                return@launch
            }
            result.onSuccess { (out, n) ->
                if (n == 0) {
                    toast.info("Совпадений нет")
                } else {
                    ctrl.applyEdit(TextFieldValue(out, TextRange(ctrl.value.selection.start.coerceAtMost(out.length))), reveal = false)
                    toast.success("Заменено: $n")
                }
            }.onFailure { e ->
                toast.error(if (e is StackOverflowError) "Слишком сложное регулярное выражение" else e.message ?: "Ошибка замены")
            }
        }
    }

    fun openFind(replace: Boolean) {
        val sel = ctrl.value.selection
        if (!sel.collapsed) {
            val s = ctrl.value.text.substring(sel.min, sel.max)
            if (!s.contains('\n') && s.length <= 500) find.query = s
            // Выделено несколько строк — ищем в них: искать такой кусок целиком незачем
            else if (s.contains('\n')) {
                ctrl.searchScope = TextRange(sel.min, sel.max)
                find.inSelection = true
            }
        }
        ctrl.clearExtraCarets()
        findAnchor = sel.min
        find.visible = true
        if (replace && !effectiveReadOnly) find.replaceVisible = true
        scope.launch {
            requestFocusSafely(findFocus)
        }
    }

    /** «В выделении»: область — текущее выделение; без него — прежняя область, если была. */
    fun toggleFindInSelection() {
        if (find.inSelection) {
            find.inSelection = false
            return
        }
        val sel = ctrl.value.selection
        if (!sel.collapsed) ctrl.searchScope = TextRange(sel.min, sel.max)
        if (ctrl.searchScope == null) {
            toast.info("Сначала выделите, где искать")
            return
        }
        find.inSelection = true
    }

    /** Каретки на все найденные совпадения (Alt+Enter в строке поиска). */
    fun selectAllMatches() {
        val m = matches
        if (m.count == 0) return
        val ranges = (0 until minOf(m.count, MAX_CARETS)).map { TextRange(m.start(it), m.end(it)) }
        ctrl.setCarets(ranges, ranges.getOrNull(currentMatch) ?: ranges.first())
        if (m.count > 1) toast.info("Кареток: ${ranges.size} — Esc оставит одну")
        focusEditor()
    }

    fun transform(fn: (String) -> String) {
        if (effectiveReadOnly) return
        try {
            if (ctrl.hasMultipleCarets) {
                // В каждой каретке — её выделение или слово под ней
                ctrl.editCarets(EditKind.OTHER) { text, sel ->
                    val range = if (sel.collapsed) TextOps.wordRangeAt(text, sel.start, ctrl.language::isWordChar)
                        ?.let { TextRange(it.first, it.last + 1) } ?: return@editCarets null
                    else sel
                    val out = fn(text.substring(range.min, range.max))
                    CaretEdit(range.min, range.max, out, 0, out.length)
                }
            } else {
                edit(TextOps.transformSelection(ctrl.value, ctrl.language::isWordChar, fn))
            }
        } catch (e: Exception) {
            toast.error("Не удалось преобразовать: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Клавиши каретки: добавить выше или ниже — по раскладке поверхности (перенос, табы). */
    fun addCaretVertically(down: Boolean) {
        val lay = surface.layout ?: return
        ctrl.addCaretVertically(down, lay::rowAndColumn, lay::offsetAt, lay.totalRows)
    }

    fun perform(action: EditorAction) {
        val ro = effectiveReadOnly
        if (ctrl.hasMultipleCarets) {
            when (action) {
                EditorAction.Undo, EditorAction.Redo -> Unit
                EditorAction.Duplicate -> {
                    if (!ro) ctrl.editCarets(EditKind.OTHER) { text, sel -> CaretOps.duplicate(text, sel) }
                    return
                }
                EditorAction.DeleteLine -> {
                    if (!ro) ctrl.deleteLinesAtCarets()
                    return
                }
                EditorAction.LineComment -> {
                    if (!ro && ctrl.toggleCommentAtCarets()) return
                    ctrl.clearExtraCarets()
                }
                EditorAction.ToggleCase -> {
                    transform { if (it.any(Char::isLowerCase)) it.uppercase() else it.lowercase() }
                    return
                }
                EditorAction.Camel, EditorAction.Pascal, EditorAction.Snake, EditorAction.Screaming, EditorAction.Kebab,
                EditorAction.Base64Encode, EditorAction.Base64Decode, EditorAction.UrlEncode, EditorAction.UrlDecode,
                EditorAction.HexEncode, EditorAction.HexDecode, EditorAction.Escape, EditorAction.Unescape,
                EditorAction.UnicodeUnescape -> Unit
                // Остальное — команды одной каретки: работают от основной
                else -> ctrl.clearExtraCarets()
            }
        }
        val v = ctrl.value
        when (action) {
            EditorAction.Undo -> if (!ro) ctrl.undo()
            EditorAction.Redo -> if (!ro) ctrl.redo()
            EditorAction.Duplicate -> edit(TextOps.duplicate(v))
            EditorAction.DeleteLine -> edit(TextOps.deleteLines(v))
            EditorAction.MoveUp -> edit(TextOps.moveLines(v, up = true))
            EditorAction.MoveDown -> edit(TextOps.moveLines(v, up = false))
            EditorAction.JoinLines -> edit(TextOps.joinLines(v, cfg))
            EditorAction.ExpandSelection -> ctrl.expandSelection(cfg)
            EditorAction.ShrinkSelection -> ctrl.shrinkSelection()
            EditorAction.Complete -> {
                focusEditor()
                ctrl.requestCompletion(explicit = true)
            }
            EditorAction.Format -> doFormat()
            EditorAction.LineComment -> edit(TextOps.toggleLineComment(v, cfg))
            EditorAction.BlockComment -> edit(TextOps.toggleBlockComment(v, cfg, ctrl.snapshot))
            EditorAction.Rename -> if (!ro) {
                val n = ctrl.startRename()
                if (n > 0) toast.info("Переименование: $n вхожд. — печатайте, Enter — готово") else toast.info("Под курсором нет имени")
                focusEditor()
            }
            EditorAction.MatchingBracket -> {
                val pair = TextOps.bracketPairNear(v.text, v.selection.start, ctrl.snapshot)
                if (pair != null) {
                    val target = if (v.selection.start - 1 == pair.first || v.selection.start == pair.first) pair.second else pair.first
                    ctrl.select(target, target)
                }
            }
            EditorAction.NextError -> gotoProblem(false)
            EditorAction.PrevError -> gotoProblem(true)
            EditorAction.GotoLine -> showGoto = true
            EditorAction.StructurePopup -> showStructure = true
            EditorAction.Fold -> ctrl.foldAtCaret(true)
            EditorAction.Unfold -> ctrl.foldAtCaret(false)
            EditorAction.FoldAll -> ctrl.foldAll(true)
            EditorAction.UnfoldAll -> ctrl.foldAll(false)
            EditorAction.SortAsc -> edit(TextOps.transformLines(v) { it.sortedWith(String.CASE_INSENSITIVE_ORDER) })
            EditorAction.SortDesc -> edit(TextOps.transformLines(v) { it.sortedWith(String.CASE_INSENSITIVE_ORDER).reversed() })
            EditorAction.Unique -> edit(TextOps.transformLines(v) { it.distinct() })
            EditorAction.Reverse -> edit(TextOps.transformLines(v) { it.reversed() })
            EditorAction.RemoveBlank -> edit(TextOps.transformLines(v) { lines -> lines.filter { it.isNotBlank() } })
            EditorAction.TrimTrailing -> edit(TextOps.transformLines(v) { lines -> lines.map { it.trimEnd(' ', '\t') } })
            EditorAction.ToggleCase -> edit(TextOps.toggleCase(v, ctrl.language::isWordChar))
            EditorAction.Camel -> transform(TextOps::toCamelCase)
            EditorAction.Pascal -> transform(TextOps::toPascalCase)
            EditorAction.Snake -> transform(TextOps::toSnakeCase)
            EditorAction.Screaming -> transform(TextOps::toScreamingSnake)
            EditorAction.Kebab -> transform(TextOps::toKebabCase)
            EditorAction.Base64Encode -> transform { Base64.getEncoder().encodeToString(it.toByteArray()) }
            EditorAction.Base64Decode -> transform { String(Base64.getMimeDecoder().decode(it.trim()), Charsets.UTF_8) }
            EditorAction.UrlEncode -> transform { URLEncoder.encode(it, Charsets.UTF_8) }
            EditorAction.UrlDecode -> transform { URLDecoder.decode(it, Charsets.UTF_8) }
            EditorAction.HexEncode -> transform { s -> s.toByteArray().joinToString(" ") { "%02x".format(it) } }
            EditorAction.HexDecode -> transform { s ->
                val clean = s.replace(Regex("0x|\\\\x|[\\s,:]"), "")
                require(clean.length % 2 == 0 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "это не HEX" }
                String(ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, Charsets.UTF_8)
            }
            EditorAction.Escape -> transform(TextOps::escapeString)
            EditorAction.Unescape -> transform(TextOps::unescapeString)
            EditorAction.UnicodeUnescape -> transform { s ->
                Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }
            }
            EditorAction.NextOccurrence -> if (!ctrl.selectNextOccurrence()) toast.info("Других вхождений нет")
            EditorAction.AllOccurrences -> ctrl.selectAllOccurrences().let { n ->
                if (n == 0) toast.info("Под кареткой нет слова") else if (n > 1) toast.info("Кареток: $n — Esc оставит одну")
            }
            EditorAction.CaretAbove -> addCaretVertically(false)
            EditorAction.CaretBelow -> addCaretVertically(true)
            EditorAction.CaretsLineEnds -> if (!ctrl.caretsAtLineEnds()) toast.info("Выделите несколько строк")
        }
    }

    /** Колонки кареток для ↑/↓ и каретки, для которых они посчитаны. */
    val caretGoals = remember(ctrl) { arrayOfNulls<Pair<List<TextRange>, IntArray>>(1) }

    /**
     * Клавиши при нескольких каретках: правка и движение в каждой. false — сочетание не для них
     * (сохранение, поиск, отмена…): его обработают как обычно.
     */
    fun handleCaretsKey(e: KeyEvent): Boolean {
        val key = e.key
        val ctrlKey = e.isCtrlPressed || e.isMetaPressed
        val shift = e.isShiftPressed
        val alt = e.isAltPressed
        val ro = effectiveReadOnly
        val text = ctrl.value.text
        val isWord = ctrl.language::isWordChar
        fun move(to: (TextRange) -> Int) = ctrl.moveCarets { sel -> to(sel).let { if (shift) TextRange(sel.start, it) else TextRange(it) } }
        fun edit(kind: EditKind, op: (String, TextRange) -> CaretEdit?) {
            if (!ro) ctrl.editCarets(kind, op)
        }
        when (Keymap.action(e, Keymap.GROUP_EDITOR)) {
            "editor.selectAll" -> {
                ctrl.clearExtraCarets()
                return false
            }
            "editor.duplicate", "editor.deleteLine", "editor.comment", "editor.save", "editor.find", "editor.replace",
            "editor.undo", "editor.redo", "editor.wrap", "editor.nextOccurrence", "editor.prevOccurrence",
            "editor.allOccurrences", "editor.caretAbove", "editor.caretBelow", "editor.caretsLineEnds",
            "editor.findInSelection", "editor.goto", "editor.structure" -> return false
            null -> Unit
            // Команды одной каретки (форматирование, перенос строк…) — от основной
            else -> {
                ctrl.clearExtraCarets()
                return false
            }
        }
        when {
            key == Key.Escape -> ctrl.clearExtraCarets()
            // ---- буфер обмена ----
            (ctrlKey && !alt && e.matchesKey(Key.C)) || (ctrlKey && key == Key.Insert) ->
                clipboard.setText(AnnotatedString(ctrl.caretClipboardText()))
            (ctrlKey && !alt && e.matchesKey(Key.X)) || (shift && key == Key.Delete) -> if (!ro) {
                clipboard.setText(AnnotatedString(ctrl.caretClipboardText()))
                ctrl.cutAtCarets()
            }
            (ctrlKey && !alt && e.matchesKey(Key.V)) || (shift && key == Key.Insert) -> if (!ro) {
                clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let { ctrl.pasteAtCarets(it) }
            }
            // ---- правка ----
            key == Key.Backspace && !alt -> edit(EditKind.DELETE) { t, sel -> CaretOps.backspace(t, sel, cfg, word = ctrlKey) }
            key == Key.Delete && !alt -> edit(EditKind.DELETE) { t, sel -> CaretOps.delete(t, sel, cfg, word = ctrlKey) }
            (key == Key.Enter || key == Key.NumPadEnter) && !ctrlKey && !alt ->
                edit(EditKind.OTHER) { t, sel -> CaretOps.newline(t, sel, cfg) }
            key == Key.Tab && !ctrlKey && !alt -> if (!ro) {
                when {
                    shift -> ctrl.shiftLinesAtCarets(indent = false)
                    ctrl.carets().all { it.collapsed } -> ctrl.editCarets(EditKind.OTHER) { t, sel -> CaretOps.tab(t, sel, cfg) }
                    else -> ctrl.shiftLinesAtCarets(indent = true)
                }
            }
            // ---- движение: каждая каретка сама по себе ----
            key == Key.DirectionLeft && !alt -> move { sel ->
                when {
                    ctrlKey -> wordStart(text, sel.end, isWord)
                    !sel.collapsed && !shift -> sel.min
                    else -> stepLeft(text, sel.end)
                }
            }
            key == Key.DirectionRight && !alt -> move { sel ->
                when {
                    ctrlKey -> wordEnd(text, sel.end, isWord)
                    !sel.collapsed && !shift -> sel.max
                    else -> stepRight(text, sel.end)
                }
            }
            (key == Key.DirectionUp || key == Key.DirectionDown) && !ctrlKey && !alt -> {
                val lay = surface.layout ?: return true
                val delta = if (key == Key.DirectionDown) 1 else -1
                val all = ctrl.carets()
                // Каждая каретка помнит свою колонку: пройдя короткую строку, возвращается на неё
                val known = caretGoals[0]
                val goals = if (known != null && known.first == all) known.second
                else IntArray(all.size) { lay.rowAndColumn(all[it].end).second }
                val moved = all.mapIndexed { i, sel ->
                    val target = lay.rowAndColumn(sel.end).first + delta
                    val to = if (target !in 0 until lay.totalRows) sel.end else lay.offsetAt(target, goals[i])
                    if (shift) TextRange(sel.start, to) else TextRange(to)
                }
                ctrl.setCarets(moved, moved[all.indexOf(ctrl.value.selection).coerceAtLeast(0)])
                val now = ctrl.carets()
                caretGoals[0] = if (now.size == all.size) now to goals else null
            }
            key == Key.MoveHome && !ctrlKey && !alt -> move { sel ->
                val ls = TextOps.lineStartOf(text, sel.end)
                var first = ls
                while (first < text.length && (text[first] == ' ' || text[first] == '\t')) first++
                if (sel.end == first) ls else first
            }
            key == Key.MoveEnd && !ctrlKey && !alt -> move { sel -> TextOps.lineEndOf(text, sel.end) }
            // Прочее (Ctrl+Home, PgUp, сочетания поверхности) — одной кареткой
            else -> {
                if (key == Key.MoveHome || key == Key.MoveEnd || key == Key.PageUp || key == Key.PageDown) ctrl.clearExtraCarets()
                return false
            }
        }
        return true
    }

    fun handleKey(e: KeyEvent): Boolean {
        if (e.type == KeyEventType.KeyDown) ctrl.followCaret = true
        val down = e.type == KeyEventType.KeyDown
        val ctrlKey = e.isCtrlPressed || e.isMetaPressed
        val shift = e.isShiftPressed
        val alt = e.isAltPressed
        val key = e.key
        val completion = ctrl.completion
        if (completion != null && !ctrlKey && !alt) {
            when (key) {
                Key.DirectionDown -> { if (down) ctrl.moveCompletion(1); return true }
                Key.DirectionUp -> { if (down) ctrl.moveCompletion(-1); return true }
                Key.PageDown -> { if (down) ctrl.moveCompletion(8); return true }
                Key.PageUp -> { if (down) ctrl.moveCompletion(-8); return true }
                Key.Enter, Key.NumPadEnter -> if (!shift) {
                    if (down) completion.result.items.getOrNull(completion.selected)?.let { ctrl.acceptCompletion(it, false) }
                    return true
                }
                Key.Tab -> if (!shift) {
                    if (down) completion.result.items.getOrNull(completion.selected)?.let { ctrl.acceptCompletion(it, true) }
                    return true
                }
                Key.Escape -> { if (down) ctrl.closeCompletion(); return true }
                Key.DirectionLeft, Key.DirectionRight, Key.MoveHome, Key.MoveEnd -> if (down) ctrl.closeCompletion()
            }
        }
        if (!down) return false
        val ro = effectiveReadOnly
        if (ctrl.hasMultipleCarets && handleCaretsKey(e)) return true
        ctrl.linked?.let { s ->
            if (key == Key.Escape) {
                ctrl.endLinked(); return true
            }
            if (!s.isRename && key == Key.Tab && !ctrlKey && !alt) {
                ctrl.snippetNext(shift); return true
            }
            if (s.isRename && (key == Key.Enter || key == Key.NumPadEnter)) {
                ctrl.endLinked(); return true
            }
        }
        val v = ctrl.value
        // Сочетания редактора — из общего списка биндов (настройки → «Бинды»)
        when (Keymap.action(e, Keymap.GROUP_EDITOR)) {
            "editor.save" -> if (canSave) { trySave(); return true }
            "editor.find" -> { openFind(false); return true }
            "editor.replace" -> { openFind(true); return true }
            "editor.goto" -> { showGoto = true; return true }
            "editor.undo" -> { if (!ro) ctrl.undo(); return true }
            "editor.redo" -> { if (!ro) ctrl.redo(); return true }
            "editor.duplicate" -> { perform(EditorAction.Duplicate); return true }
            "editor.deleteLine" -> { perform(EditorAction.DeleteLine); return true }
            "editor.comment" -> { perform(EditorAction.LineComment); return true }
            "editor.moveUp" -> { perform(EditorAction.MoveUp); return true }
            "editor.moveDown" -> { perform(EditorAction.MoveDown); return true }
            "editor.complete" -> { ctrl.requestCompletion(explicit = true); return true }
            "editor.wrap" -> { prefs.updateSoftWrap(!prefs.softWrap); return true }
            "editor.format" -> { doFormat(); return true }
            "editor.structure" -> { showStructure = true; return true }
            "editor.findInSelection" -> {
                if (!find.visible) openFind(false)
                val sel = ctrl.value.selection
                if (!sel.collapsed) {
                    ctrl.searchScope = TextRange(sel.min, sel.max)
                    find.inSelection = true
                } else toggleFindInSelection()
                return true
            }
            "editor.nextOccurrence" -> { perform(EditorAction.NextOccurrence); return true }
            "editor.prevOccurrence" -> { ctrl.unselectLastOccurrence(); return true }
            "editor.allOccurrences" -> { perform(EditorAction.AllOccurrences); return true }
            "editor.caretAbove" -> { addCaretVertically(false); return true }
            "editor.caretBelow" -> { addCaretVertically(true); return true }
            "editor.caretsLineEnds" -> { perform(EditorAction.CaretsLineEnds); return true }
        }
        when {
            ctrlKey && alt && !shift && (key == Key.Enter || key == Key.NumPadEnter) -> {
                if (!ro) edit(TextOps.newline(v, cfg, ctrl.snapshot, TextOps.NewlineMode.ABOVE))
                return true
            }
            e.isCtrlShortcut(Key.W) -> { ctrl.expandSelection(cfg); return true }
            e.isCtrlShortcut(Key.W, shift = true) -> { ctrl.shrinkSelection(); return true }
            e.isCtrlShortcut(Key.U, shift = true) -> { perform(EditorAction.ToggleCase); return true }
            e.isCtrlShortcut(Key.J, shift = true) -> { perform(EditorAction.JoinLines); return true }
            e.isCtrlShortcut(Key.M, shift = true) -> { perform(EditorAction.MatchingBracket); return true }
            ctrlKey && !alt && shift && (key == Key.Slash || key == Key.Period) -> {
                perform(EditorAction.BlockComment); return true
            }
            ctrlKey && !alt && shift && key == Key.Comma -> { perform(EditorAction.BlockComment); return true }
            ctrlKey && shift && !alt && key == Key.DirectionUp -> { perform(EditorAction.MoveUp); return true }
            ctrlKey && shift && !alt && key == Key.DirectionDown -> { perform(EditorAction.MoveDown); return true }
            ctrlKey && shift && key == Key.LeftBracket -> { ctrl.foldAtCaret(true); return true }
            ctrlKey && shift && key == Key.RightBracket -> { ctrl.foldAtCaret(false); return true }
            ctrlKey && !shift && key == Key.NumPadSubtract -> { ctrl.foldAtCaret(true); return true }
            ctrlKey && !shift && key == Key.NumPadAdd -> { ctrl.foldAtCaret(false); return true }
            ctrlKey && shift && (key == Key.Minus || key == Key.NumPadSubtract) -> { ctrl.foldAll(true); return true }
            ctrlKey && shift && (key == Key.Equals || key == Key.Plus || key == Key.NumPadAdd) -> { ctrl.foldAll(false); return true }
            ctrlKey && !shift && !alt && (key == Key.Equals || key == Key.Plus) -> { prefs.updateFontSize(fontSizeSp + 1f); return true }
            ctrlKey && !shift && !alt && key == Key.Minus -> { prefs.updateFontSize(fontSizeSp - 1f); return true }
            ctrlKey && !shift && !alt && (key == Key.Zero || key == Key.NumPad0) -> { prefs.resetFontSize(); return true }
            alt && !ctrlKey && key == Key.Seven -> { prefs.updateShowOutline(!prefs.showOutline); return true }
            key == Key.F2 && !ctrlKey && !alt -> { gotoProblem(shift); return true }
            key == Key.F3 && !ctrlKey && !alt -> {
                if (!find.visible || matches.count == 0) openFind(false) else findNext(shift)
                return true
            }
            key == Key.F6 && shift && !ctrlKey -> { perform(EditorAction.Rename); return true }
            key == Key.Escape -> {
                if (ctrl.clearExtraCarets()) return true
                if (find.visible) {
                    find.visible = false
                    find.inSelection = false
                    return true
                }
                return false
            }
            // Копирование/вырезание строки без выделения, как в IntelliJ
            ctrlKey && !shift && !alt && e.matchesKey(Key.C) && v.selection.collapsed -> {
                val ls = TextOps.lineStartOf(v.text, v.selection.start)
                val le = TextOps.lineEndOf(v.text, v.selection.start)
                val line = v.text.substring(ls, le) + "\n"
                clipboard.setText(AnnotatedString(line))
                ctrl.copiedLine = line
                return true
            }
            ctrlKey && !shift && !alt && e.matchesKey(Key.X) && v.selection.collapsed -> {
                if (ro) return true
                val ls = TextOps.lineStartOf(v.text, v.selection.start)
                val le = TextOps.lineEndOf(v.text, v.selection.start)
                val line = v.text.substring(ls, le) + "\n"
                clipboard.setText(AnnotatedString(line))
                ctrl.copiedLine = line
                edit(TextOps.deleteLines(v))
                return true
            }
            ctrlKey && !shift && !alt && e.matchesKey(Key.V) && v.selection.collapsed && !ro -> {
                val clip = clipboard.getText()?.text
                if (clip != null && clip == ctrl.copiedLine) {
                    // Строка целиком — вставляем над текущей
                    val ls = TextOps.lineStartOf(v.text, v.selection.start)
                    val t = v.text.substring(0, ls) + clip + v.text.substring(ls)
                    edit(TextFieldValue(t, TextRange(v.selection.start + clip.length)))
                    return true
                }
                return false
            }
        }
        // Home, Ctrl+Home и Ctrl+End делает поверхность: там же прокрутка к каретке и память колонки
        // для стрелок вверх-вниз. Отсюда Home не прокручивал длинную строку к её началу
        if (ctrlKey || ro) return false
        when (key) {
            Key.Enter, Key.NumPadEnter -> {
                if (alt) return false
                val mode = if (shift) TextOps.NewlineMode.BELOW else TextOps.NewlineMode.SPLIT
                edit(TextOps.newline(v, cfg, ctrl.snapshot, mode))
                return true
            }
            Key.Tab -> {
                if (alt) return false
                if (shift) {
                    edit(TextOps.unindent(v, cfg))
                    return true
                }
                // Живой шаблон: «sout» + Tab
                if (v.selection.collapsed) {
                    val caret = v.selection.start
                    val start = TextOps.wordRangeAt(v.text, caret, ctrl.language::isWordChar)?.first
                    if (start != null && start < caret) {
                        val word = v.text.substring(start, caret)
                        val snippet = ctrl.language.vocabulary.snippets.firstOrNull { it.trigger == word }
                        if (snippet != null) {
                            ctrl.insertSnippet(start, caret, snippet.body)
                            return true
                        }
                    }
                }
                edit(TextOps.tab(v, cfg, ctrl.snapshot))
                return true
            }
        }
        return false
    }

    /**
     * Вставка из буфера обмена — одной правкой, без автоскобок и подсказок: раньше она шла как ввод,
     * и вставленная «(» или «"» получала лишнюю парную.
     */
    fun paste(raw: String) {
        if (effectiveReadOnly || raw.isEmpty()) return
        val v = ctrl.value
        val inserted = TextCodec.normalize(raw)
        val from = v.selection.min
        val to = v.selection.max
        ctrl.applyEdit(TextFieldValue(v.text.substring(0, from) + inserted + v.text.substring(to), TextRange(from + inserted.length)))
        ctrl.closeCompletion()
    }

    fun onChange(new: TextFieldValue) {
        val hadPopup = ctrl.completion
        val oldSel = ctrl.value.selection
        val oldVersion = ctrl.version
        val kind = ctrl.onFieldChange(new, cfg, effectiveReadOnly)
        // Стрелки, Home/End, Ctrl+End, ввод у края: поле само вид не прокручивает — курсор уходил за край окна
        if (ctrl.followCaret && (ctrl.value.selection != oldSel || ctrl.version != oldVersion)) {
            ctrl.requestReveal(ctrl.value.selection.end)
        }
        val lang = ctrl.language
        when (kind) {
            null -> if (hadPopup != null && ctrl.value.selection != oldSel) ctrl.closeCompletion()
            EditKind.TYPING -> {
                val caret = ctrl.value.selection.end
                val ch = ctrl.value.text.getOrNull(caret - 1)
                val triggers = when {
                    lang.id == "smali" -> TRIGGERS_SMALI
                    lang.markup -> TRIGGERS_MARKUP
                    lang.id == "css" || lang.id == "scss" -> TRIGGERS_CSS
                    lang.id == "shell" || lang.id == "fish" -> TRIGGERS_SHELL
                    else -> TRIGGERS_CODE
                }
                val trigger = ch != null && (lang.isWordChar(ch) || ch in triggers)
                if (trigger && (hadPopup != null || prefs.autoPopup)) ctrl.requestCompletion(explicit = hadPopup?.explicit ?: false)
                else ctrl.closeCompletion()
            }
            EditKind.DELETE -> if (hadPopup != null) ctrl.requestCompletion(explicit = hadPopup.explicit)
            EditKind.OTHER -> ctrl.closeCompletion()
        }
    }

    // ---------- Фоновые пересчёты ----------

    val version = ctrl.version
    LaunchedEffect(ctrl, version, effectiveLanguage) {
        val snap = ctrl.snapshot
        delay(if (snap.text.length > 300_000) 350 else 90)
        val folds = withContext(Dispatchers.Default) { runCatching { FoldingEngine.compute(snap) }.getOrDefault(emptyList()) }
        if (ctrl.snapshot.version != snap.version) return@LaunchedEffect
        ctrl.replaceFolds(folds)
        val outline = withContext(Dispatchers.Default) { runCatching { OutlineEngine.compute(snap, folds) }.getOrDefault(emptyList()) }
        if (ctrl.snapshot.version != snap.version) return@LaunchedEffect
        ctrl.outline = outline
        delay(120)
        val index = withContext(Dispatchers.Default) { runCatching { WordIndexer.build(snap.text, snap.language, snap.version) }.getOrNull() }
        if (index != null) ctrl.wordIndex = index
    }

    LaunchedEffect(ctrl, version, effectiveLanguage, prefs.diagnostics, prefs.externalChecks) {
        if (!prefs.diagnostics) {
            ctrl.diagnostics = emptyList()
            return@LaunchedEffect
        }
        val snap = ctrl.snapshot
        delay(if (snap.text.length > 300_000) 900 else 350)
        val quick = withContext(Dispatchers.Default) { runCatching { DiagnosticsEngine.quick(snap, fileName) }.getOrDefault(emptyList()) }
        if (ctrl.snapshot.version != snap.version) return@LaunchedEffect
        ctrl.diagnostics = quick
        delay(600)
        val slow = withContext(Dispatchers.IO) {
            runCatching { DiagnosticsEngine.slow(snap, fileName, prefs.externalChecks) }.getOrDefault(emptyList())
        }
        if (ctrl.snapshot.version != snap.version) return@LaunchedEffect
        ctrl.diagnostics = quick + slow
    }

    val findOptions = find.options
    val findScope = if (find.inSelection) ctrl.searchScope else null
    LaunchedEffect(ctrl, find.visible, findOptions, version, findScope) {
        if (!find.visible || findOptions.query.isEmpty()) {
            matches = SearchMatches.EMPTY
            currentMatch = -1
            lastFindOptions = findOptions
            return@LaunchedEffect
        }
        val text = ctrl.value.text
        // Условия поменялись — старые совпадения не годятся (и «Заменить все» по ним тоже)
        if (findOptions != lastFindOptions) matches = SearchMatches.EMPTY
        if (text.length > 200_000) delay(150)
        val job = coroutineContext[kotlinx.coroutines.Job]
        val within = findScope?.let { it.min until it.max }
        val m = withContext(Dispatchers.Default) {
            SearchEngine.findAll(text, findOptions, cancelled = { job?.isActive == false }, within = within)
        }
        if (ctrl.value.text != text) return@LaunchedEffect
        matches = m
        val sel = ctrl.value.selection
        currentMatch = m.indexOfRange(sel.min, sel.max)
        if (findOptions != lastFindOptions) {
            lastFindOptions = findOptions
            // Поиск по мере ввода — к ближайшему совпадению от места открытия
            if (m.count > 0) {
                val i = m.indexAtOrAfter(findAnchor)
                currentMatch = i
                ctrl.select(m.start(i), m.end(i), center = true)
            }
        }
    }

    val completionRequest = ctrl.completionRequest
    LaunchedEffect(ctrl, completionRequest) {
        if (completionRequest == 0) return@LaunchedEffect
        val explicit = ctrl.completionExplicit
        val v = ctrl.value
        val req = CompletionRequest(v.text, v.selection.end, ctrl.language, ctrl.snapshot, ctrl.wordIndex, explicit, baseDir)
        val result = withContext(Dispatchers.Default) { runCatching { CompletionEngine.complete(req) }.getOrNull() }
        if (ctrl.value.text != v.text || ctrl.value.selection != v.selection) return@LaunchedEffect
        if (result == null || result.items.isEmpty()) {
            ctrl.showCompletion(null)
            if (explicit) toast.info("Нет подсказок")
            return@LaunchedEffect
        }
        // Выбор сохраняется, если тот же элемент остался в списке
        val prevLabel = ctrl.completion?.let { it.result.items.getOrNull(it.selected)?.label }
        val keep = prevLabel?.let { l -> result.items.indexOfFirst { it.label == l } }?.takeIf { it in 0..3 } ?: 0
        ctrl.showCompletion(CompletionUi(result, keep, explicit))
    }

    val reveal = ctrl.revealRequest
    LaunchedEffect(reveal, surface.viewport) {
        val off = reveal ?: return@LaunchedEffect
        if (surface.viewport.height <= 0) return@LaunchedEffect
        surface.reveal(off, ctrl.revealCenter)
        ctrl.consumeReveal()
    }

    LaunchedEffect(ctrl) {
        requestFocusSafely(focus)
    }

    // Меню правой кнопки: со значками и сочетаниями, как остальные меню WEX.
    // Пункты буфера обмена раньше добавляло само поле ввода — теперь они наши.
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val contextItems: () -> List<EditorMenuItem> = {
        val v = ctrl.value
        val hasSelection = !v.selection.collapsed
        // Несколько кареток: буфер обмена работает со всеми сразу
        val multi = ctrl.hasMultipleCarets
        fun selectedText(): String {
            val from = minOf(v.selection.start, v.selection.end)
            val to = maxOf(v.selection.start, v.selection.end)
            return v.text.substring(from, to)
        }
        buildList {
            if (!effectiveReadOnly) {
                add(
                    EditorMenuItem("Отменить", Keymap.label("editor.undo"), Icons.AutoMirrored.Filled.Undo, ctrl.history.canUndo) {
                        perform(EditorAction.Undo)
                    }
                )
                add(
                    EditorMenuItem("Повторить", Keymap.label("editor.redo"), Icons.AutoMirrored.Filled.Redo, ctrl.history.canRedo) {
                        perform(EditorAction.Redo)
                    }
                )
                add(EditorMenuItem.separator())
                add(
                    EditorMenuItem("Вырезать", "Ctrl+X", Icons.Default.ContentCut, hasSelection || multi) {
                        if (multi) {
                            clip.setText(AnnotatedString(ctrl.caretClipboardText()))
                            ctrl.cutAtCarets()
                            return@EditorMenuItem
                        }
                        val from = minOf(v.selection.start, v.selection.end)
                        val to = maxOf(v.selection.start, v.selection.end)
                        clip.setText(AnnotatedString(selectedText()))
                        onChange(TextFieldValue(v.text.substring(0, from) + v.text.substring(to), TextRange(from)))
                    }
                )
            }
            add(
                EditorMenuItem("Копировать", "Ctrl+C", Icons.Default.ContentCopy, hasSelection || multi) {
                    clip.setText(AnnotatedString(if (multi) ctrl.caretClipboardText() else selectedText()))
                }
            )
            if (!effectiveReadOnly) {
                add(
                    EditorMenuItem("Вставить", "Ctrl+V", Icons.Default.ContentPaste) {
                        val pasted = clip.getText()?.text.orEmpty()
                        if (multi) {
                            if (pasted.isNotEmpty()) ctrl.pasteAtCarets(pasted)
                        } else paste(pasted)
                    }
                )
            }
            add(
                EditorMenuItem("Выделить всё", Keymap.label("editor.selectAll"), Icons.Default.SelectAll) {
                    onChange(v.copy(selection = androidx.compose.ui.text.TextRange(0, v.text.length)))
                }
            )
            add(EditorMenuItem.separator())
            if (!effectiveReadOnly) {
                if (formatLabel != null) {
                    add(EditorMenuItem("Форматировать", Keymap.label("editor.format"), Icons.Default.AutoFixHigh) { doFormat() })
                }
                add(EditorMenuItem("Закомментировать", Keymap.label("editor.comment"), Icons.AutoMirrored.Filled.Comment) { perform(EditorAction.LineComment) })
                add(EditorMenuItem("Переименовать вхождения", "Shift+F6", Icons.Default.Edit) { perform(EditorAction.Rename) })
                add(EditorMenuItem("Каретки на все вхождения", Keymap.label("editor.allOccurrences"), Icons.Default.SelectAll) { perform(EditorAction.AllOccurrences) })
                add(EditorMenuItem("Дублировать", Keymap.label("editor.duplicate"), Icons.Default.LibraryAdd) { perform(EditorAction.Duplicate) })
                add(EditorMenuItem.separator())
            }
            add(EditorMenuItem("Расширить выделение", "Ctrl+W", Icons.Default.Expand) { ctrl.expandSelection(cfg) })
            add(EditorMenuItem("Найти", Keymap.label("editor.find"), Icons.Default.Search) { openFind(false) })
            add(EditorMenuItem("Перейти к строке", Keymap.label("editor.goto"), Icons.Default.MyLocation) { showGoto = true })
            add(EditorMenuItem("Структура файла", Keymap.label("editor.structure"), Icons.Default.AccountTree) { showStructure = true })
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            EditorToolbar(
                ctrl = ctrl,
                fileName = fileName,
                readOnly = effectiveReadOnly,
                canEditToggle = !readOnly,
                editing = editing,
                onToggleEditing = { editing = !editing },
                canSave = canSave,
                saveButtonText = if (backgroundSave?.busy == true) "Сохранение…" else saveButtonText,
                errorPrefix = errorPrefix,
                onSave = { trySave() },
                onFind = {
                    if (find.visible) {
                        find.visible = false
                        find.inSelection = false
                    } else openFind(false)
                },
                onFormat = { doFormat() },
                formatLabel = formatLabel,
                onCopyAll = {
                    clipboard.setText(AnnotatedString(TextCodec.denormalize(ctrl.value.text, ctrl.lineSeparator)))
                    toast.info("Текст скопирован")
                },
                act = { perform(it) }
            )
            // Строка поиска раскрывается, а не возникает рывком
            androidx.compose.animation.AnimatedVisibility(
                visible = find.visible,
                enter = androidx.compose.animation.expandVertically(tween(org.wexclient.app.ui.Motion.ENTER)) +
                    androidx.compose.animation.fadeIn(tween(org.wexclient.app.ui.Motion.ENTER)),
                exit = androidx.compose.animation.shrinkVertically(tween(org.wexclient.app.ui.Motion.EXIT)) +
                    androidx.compose.animation.fadeOut(tween(org.wexclient.app.ui.Motion.EXIT))
            ) {
                FindBar(
                    state = find,
                    matches = matches,
                    current = currentMatch,
                    readOnly = effectiveReadOnly,
                    focusRequester = findFocus,
                    onNext = { findNext(false) },
                    onPrev = { findNext(true) },
                    onReplace = { replaceCurrent() },
                    onReplaceAll = { replaceAll() },
                    onClose = {
                        find.visible = false
                        find.inSelection = false
                        focusEditor()
                    },
                    onToggleInSelection = { toggleFindInSelection() },
                    onSelectAll = { selectAllMatches() },
                    // Сохранить и перейти к строке можно и не уходя из строки поиска
                    onEditorKey = { e ->
                        when (Keymap.action(e, Keymap.GROUP_EDITOR)) {
                            "editor.save" -> canSave.also { if (it) trySave() }
                            "editor.goto" -> true.also { showGoto = true }
                            else -> false
                        }
                    }
                )
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (prefs.showOutline) {
                    StructurePanel(
                        ctrl = ctrl,
                        onNavigate = { navigateTo(it.offset) },
                        onClose = { prefs.updateShowOutline(false) },
                        modifier = Modifier.width(270.dp).fillMaxHeight()
                    )
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                }
                // Пределы теперь у быстрой поверхности: она рисует только видимые строки.
                // Ключ — версия, а не текст: сравнение текста было полным проходом на каждую букву.
                // Шрифт больше не уменьшается на длинных файлах: общей раскладки высотой в весь текст
                // (и её предела в 16 777 215 px) у быстрой поверхности нет
                val tooBig = remember(ctrl.version) { EditorLimits.fastProblem(ctrl.value.text) }
                if (tooBig != null) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(
                            "Текст не показать в редакторе: $tooBig.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                } else FastEditorSurface(
                    ctrl = ctrl,
                    state = surface,
                    readOnly = effectiveReadOnly,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.54f).sp,
                    search = SearchHighlight(matches, currentMatch, findScope.takeIf { find.visible }),
                    focusRequester = focus,
                    onKey = ::handleKey,
                    onChange = ::onChange,
                    onPaste = ::paste,
                    onTypeCarets = { typed ->
                        if (!effectiveReadOnly) ctrl.editCarets(EditKind.TYPING) { text, sel -> CaretOps.type(text, sel, typed, cfg) }
                    },
                    contextItems = contextItems,
                    // Размер читаем в момент прокрутки колесом: захваченный при первом показе давал только один шаг
                    onZoom = { prefs.updateFontSize(prefs.fontSizeSp + it) },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
            EditorStatusBar(
                ctrl = ctrl,
                readOnly = effectiveReadOnly,
                languageName = ctrl.language.name,
                onGoto = { showGoto = true },
                onNextError = { gotoProblem(false) },
                onFormatInfo = formatLabel,
                toastInfo = { toast.info(it) },
                onConfirmReload = { cs ->
                    if (ctrl.isDirty) pendingReload = cs
                    else if (ctrl.reloadWithCharset(cs)) toast.info("Открыто в ${TextFileFormat.charsetLabel(cs)}")
                }
            )
        }
        if (showGoto) GotoLinePopup(ctrl) {
            showGoto = false
            focusEditor()
        }
        if (showStructure) StructurePopup(ctrl, onNavigate = { navigateTo(it.offset) }, onDismiss = {
            showStructure = false
            focusEditor()
        })
        pendingReload?.let { cs ->
            AlertDialog(
                onDismissRequest = { pendingReload = null },
                title = { Text("Открыть заново в ${TextFileFormat.charsetLabel(cs)}?") },
                text = { Text("Несохранённые правки будут потеряны. Файл будет перечитан с диска в выбранной кодировке.") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingReload = null
                        if (ctrl.reloadWithCharset(cs)) toast.info("Открыто в ${TextFileFormat.charsetLabel(cs)}")
                    }) { Text("Перечитать", color = NoctaliaPrimary) }
                },
                dismissButton = { TextButton(onClick = { pendingReload = null }) { Text("Отмена") } }
            )
        }
        ToastHost(toast)
    }
}
