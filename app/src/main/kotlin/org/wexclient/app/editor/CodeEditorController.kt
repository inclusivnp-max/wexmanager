package org.wexclient.app.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.wexclient.app.editor.lang.Language
import org.wexclient.app.editor.lang.Languages
import java.nio.charset.Charset
import java.nio.file.Path

/** Связанные диапазоны: поля шаблона (с зеркалами) или вхождения при переименовании. */
class LinkedSession(
    /** Группы диапазонов [start, end]; все диапазоны группы меняются вместе. */
    val groups: MutableList<MutableList<IntArray>>,
    var active: Int,
    var finalOffset: Int,
    val isRename: Boolean
) {
    fun activeRanges(): List<IntArray> = groups.getOrNull(active) ?: emptyList()
}

class CompletionUi(val result: CompletionResult, val selected: Int, val explicit: Boolean)

/**
 * Состояние редактора, которое можно держать снаружи: текст переживает смену вкладок,
 * а диалог закрытия видит «изменено» и может сохранить.
 */
class CodeEditorController(initialText: String) {
    private var savedText: String = TextCodec.normalize(initialText)

    /** Текущий текст и выделение (переводы строк всегда '\n'). */
    var value by mutableStateOf(TextFieldValue(savedText))
        internal set

    var isDirty by mutableStateOf(false)
        internal set
    var saveError by mutableStateOf<String?>(null)
        internal set
    internal var saver: ((String) -> Unit)? = null

    val text: String get() = value.text

    // ---------- Формат файла ----------

    /** Перевод строк при сохранении (по умолчанию — как в исходном тексте). */
    var lineSeparator by mutableStateOf(TextCodec.detectLineSeparator(initialText))
        private set

    /** Кодировка файла; null — редактор открыт не для файла (строка из APK/DEX). */
    var charset by mutableStateOf<Charset?>(null)
        private set
    var hasBom by mutableStateOf(false)
        private set

    /** Байты с диска — для «Открыть заново в кодировке». */
    internal var originalBytes: ByteArray? = null
    private var formatDirty = false
    private var formatVersion = 0

    // ---------- Язык и отступы ----------

    /** Язык, выбранный вручную в строке состояния (перекрывает переданный). */
    var languageOverride by mutableStateOf<Language?>(null)
    var language by mutableStateOf(Languages.PLAIN)
        internal set
    var indentUnit by mutableStateOf("    ")
    internal var indentDetected = false

    // ---------- Модели ----------

    // Только разбивка на строки: язык файла задаётся сразу после создания, и разбор идёт один раз
    internal val model = HighlightModel(Languages.PLAIN).also { it.reset(savedText, lex = false) }
    internal val history = EditHistory()

    /** Растёт при каждом изменении текста/разметки — ключ для фоновых пересчётов. */
    var version by mutableLongStateOf(0L)
        private set

    var folds by mutableStateOf<List<FoldRegion>>(emptyList())
        internal set
    var collapsed by mutableStateOf<List<IntRange>>(emptyList())
        internal set
    var outline by mutableStateOf<List<OutlineItem>>(emptyList())
        internal set
    var diagnostics by mutableStateOf<List<Diagnostic>>(emptyList())
        internal set
    @Volatile
    internal var wordIndex: WordIndex? = null

    var completion by mutableStateOf<CompletionUi?>(null)
        internal set
    /** Запрос подсказок: номер растёт, флаг — явный (Ctrl+Space). */
    var completionRequest by mutableIntStateOf(0)
        private set
    internal var completionExplicit = false

    var linked by mutableStateOf<LinkedSession?>(null)
        internal set



    /** Позиция, которую нужно показать после следующей раскладки текста. */
    var revealRequest by mutableStateOf<Int?>(null)
        internal set
    internal var revealCenter = false

    /**
     * Последнее действие — клавиша (стрелки, Home/End, ввод): курсор после него держим на виду.
     * Щелчок мышью сбрасывает — вид под мышью не дёргается.
     */
    internal var followCaret = false

    /** Стек выделений для Ctrl+Shift+W. */
    private val selectionStack = ArrayList<TextRange>()
    private var lastExpanded: TextRange? = null

    /** Строка, скопированная Ctrl+C без выделения (вставка — над текущей строкой). */
    internal var copiedLine: String? = null

    var filePath: Path? = null
        internal set

    /** Фокус в текстовом поле редактора. */
    var hasFocus by mutableStateOf(false)
        internal set

    val snapshot: HighlightSnapshot get() = model.snapshot

    // ---------- Сохранение ----------

    /** null — сохранено, иначе текст ошибки (правки остаются «изменёнными»). */
    fun save(): String? {
        val s = saver ?: return "Сохранение недоступно"
        return try {
            s(TextCodec.denormalize(value.text, lineSeparator))
            savedText = value.text
            formatDirty = false
            isDirty = false
            saveError = null
            history.breakGroup()
            null
        } catch (e: Exception) {
            (e.message ?: e.javaClass.simpleName).also { saveError = it }
        }
    }

    /**
     * Сохранение в фоне: [saver] вызывается не в потоке окна — запись файла внутри архива
     * переписывает весь архив. Правки, сделанные во время записи, остаются «изменено».
     */
    fun saveInBackground(runner: org.wexclient.app.ui.BackgroundSave, done: (String?) -> Unit = {}): Boolean {
        val s = saver ?: run {
            done("Сохранение недоступно")
            return true
        }
        val text = value.text
        val out = TextCodec.denormalize(text, lineSeparator)
        val formatStamp = formatVersion
        return runner.run({ s(out) }) { err ->
            if (err == null) {
                savedText = text
                if (formatVersion == formatStamp) formatDirty = false
                saveError = null
                history.breakGroup()
                recomputeDirty()
            } else {
                saveError = err
            }
            done(err)
        }
    }

    fun reset(text: String) {
        val normalized = TextCodec.normalize(text)
        savedText = normalized
        lineSeparator = TextCodec.detectLineSeparator(text)
        value = TextFieldValue(normalized)
        history.clear()
        collapsed = emptyList()
        linked = null
        completion = null
        formatDirty = false
        isDirty = false
        saveError = null
        indentDetected = false
        model.reset(normalized)
        bump()
    }

    /** Файл с диска: кодировка, BOM и исходные байты. */
    fun attachFile(decoded: DecodedText, bytes: ByteArray?, path: Path?) {
        charset = decoded.format.charset
        hasBom = decoded.format.bom
        lineSeparator = decoded.format.lineSeparator
        originalBytes = bytes
        filePath = path
    }

    fun changeLineSeparator(sep: LineSeparator) {
        if (sep == lineSeparator) return
        lineSeparator = sep
        formatDirty = true
        formatVersion++
        recomputeDirty()
    }

    /** Сохранять в другой кодировке (текст не меняется). */
    fun convertCharset(cs: Charset, bom: Boolean = hasBom && TextCodec.supportsBom(cs)) {
        if (cs == charset && bom == hasBom) return
        charset = cs
        hasBom = bom
        formatDirty = true
        formatVersion++
        recomputeDirty()
    }

    /** Перечитать исходные байты в другой кодировке (правки теряются). */
    fun reloadWithCharset(cs: Charset): Boolean {
        val bytes = originalBytes ?: return false
        val decoded = TextCodec.decode(bytes, cs)
        reset(decoded.text)
        charset = decoded.format.charset
        hasBom = decoded.format.bom
        lineSeparator = decoded.format.lineSeparator
        return true
    }

    private fun recomputeDirty() {
        val t = value.text
        isDirty = formatDirty || t.length != savedText.length || t != savedText
    }

    private fun bump() {
        version++
    }

    // ---------- Язык ----------

    internal fun applyLanguage(lang: Language) {
        // Зовём всегда: при открытии текст только разбит на строки, разбор делается здесь
        model.setLanguage(lang)
        if (lang !== language || model.language !== lang) {
            language = lang
            folds = emptyList()
            collapsed = emptyList()
            outline = emptyList()
            diagnostics = emptyList()
            wordIndex = null
            bump()
        }
        if (!indentDetected) {
            indentUnit = Languages.detectIndent(value.text, lang)
            indentDetected = true
        }
    }

    fun config(tabSize: Int, autoClose: Boolean) = EditConfig(language, indentUnit, tabSize, autoClose)

    /** Модель разметки соответствует тексту (на случай внешней замены value). */
    internal fun syncModel() {
        val t = value.text
        if (model.text !== t && model.text != t) {
            model.reset(t)
            collapsed = emptyList()
            linked = null
            bump()
        }
    }

    // ---------- Правки ----------

    /** Изменение из текстового поля (ввод, вставка, перемещение курсора). */
    internal fun onFieldChange(new: TextFieldValue, cfg: EditConfig, readOnly: Boolean): EditKind? {
        val old = value
        if (new.text == old.text) {
            var sel = new.selection
            if (sel != old.selection) sel = adjustSelectionForFolds(old.selection, sel)
            if (sel != old.selection) {
                history.breakGroup()
                if (lastExpanded != null && sel != lastExpanded) {
                    selectionStack.clear()
                    lastExpanded = null
                }
                // Курсор ушёл из связанных диапазонов — сеанс окончен
                linked?.let { s ->
                    val caret = sel.start
                    if (s.groups.none { g -> g.any { caret >= it[0] && caret <= it[1] } }) linked = null
                }
            }
            value = new.copy(selection = sel)
            return null
        }
        if (readOnly) return null
        var result = new
        // Переводы строк из буфера обмена: \r\n -> \n
        if (result.text.indexOf('\r') >= 0) {
            val before = result.text.substring(0, result.selection.end.coerceIn(0, result.text.length))
            val fixedBefore = TextCodec.normalize(before)
            val fixed = TextCodec.normalize(result.text)
            val caret = fixedBefore.length
            result = TextFieldValue(fixed, TextRange(caret))
        }
        var kind = EditKind.OTHER
        val composing = new.composition != null
        if (!composing) {
            val typed = typedChar(old, result)
            if (typed != null) {
                kind = EditKind.TYPING
                TextOps.onCharTyped(old, typed, cfg, model.snapshot)?.let { result = it }
            } else if (isBackspace(old, result)) {
                kind = EditKind.DELETE
                TextOps.onBackspace(old, cfg, model.snapshot)?.let { result = it }
            } else if (isForwardDelete(old, result)) {
                kind = EditKind.DELETE
            }
        }
        commit(old, result, kind)
        return kind
    }

    private fun typedChar(old: TextFieldValue, new: TextFieldValue): Char? {
        val a = old.selection.min
        val b = old.selection.max
        val ot = old.text
        val nt = new.text
        if (nt.length != ot.length - (b - a) + 1) return null
        if (!new.selection.collapsed || new.selection.start != a + 1) return null
        if (!nt.regionMatches(0, ot, 0, a)) return null
        if (!nt.regionMatches(a + 1, ot, b, ot.length - b)) return null
        val c = nt[a]
        return if (c == '\n' || c == '\t') null else c
    }

    private fun isBackspace(old: TextFieldValue, new: TextFieldValue): Boolean {
        if (!old.selection.collapsed) return false
        val c = old.selection.start
        if (c == 0 || new.text.length != old.text.length - 1) return false
        if (!new.selection.collapsed || new.selection.start != c - 1) return false
        return new.text.regionMatches(0, old.text, 0, c - 1) && new.text.regionMatches(c - 1, old.text, c, old.text.length - c)
    }

    private fun isForwardDelete(old: TextFieldValue, new: TextFieldValue): Boolean {
        if (!old.selection.collapsed) return false
        return new.text.length < old.text.length && new.selection.collapsed && new.selection.start == old.selection.start
    }

    /** Программная правка (команды, подсказки, форматирование). */
    fun applyEdit(new: TextFieldValue, kind: EditKind = EditKind.OTHER, reveal: Boolean = true) {
        val old = value
        if (new.text == old.text) {
            value = new
            if (reveal) requestReveal(new.selection.end)
            return
        }
        history.breakGroup()
        commit(old, new, kind)
        history.breakGroup()
        if (reveal) requestReveal(value.selection.end)
    }

    /**
     * В тексте редактора переводы строк — только '\n'. Вставка или замена с \r\n иначе давала
     * при сохранении CRLF-файла «\r\r\n».
     */
    private fun normalizeBreaks(v: TextFieldValue): TextFieldValue {
        val t = v.text
        if (t.indexOf('\r') < 0) return v
        val sb = StringBuilder(t.length)
        val s0 = v.selection.start
        val e0 = v.selection.end
        var cutBeforeStart = 0
        var cutBeforeEnd = 0
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\r') {
                if (i + 1 < t.length && t[i + 1] == '\n') {
                    if (i < s0) cutBeforeStart++
                    if (i < e0) cutBeforeEnd++
                } else {
                    sb.append('\n')
                }
            } else {
                sb.append(c)
            }
            i++
        }
        return TextFieldValue(sb.toString(), TextRange(s0 - cutBeforeStart, e0 - cutBeforeEnd))
    }

    private fun commit(old: TextFieldValue, newValue: TextFieldValue, kind: EditKind) {
        var result = normalizeBreaks(newValue)
        var edit = TextEdit.diff(old.text, result.text)
        // Связанные диапазоны: зеркалим правку
        val session = linked
        if (session != null) {
            val mirrored = applyLinked(session, edit, result)
            if (mirrored == null) {
                linked = null
            } else {
                result = mirrored
                edit = TextEdit.diff(old.text, result.text)
            }
        }
        history.record(edit, old.selection, result.selection, kind)
        model.update(result.text, edit)
        shiftCollapsed(edit)
        value = result
        selectionStack.clear()
        lastExpanded = null
        recomputeDirty()
        bump()
    }

    private fun shiftCollapsed(e: TextEdit) {
        if (diagnostics.isNotEmpty()) {
            diagnostics = diagnostics.mapNotNull { d ->
                when {
                    d.end <= e.start -> d
                    d.start >= e.removedEnd -> Diagnostic(d.start + e.delta, d.end + e.delta, d.severity, d.message, d.source)
                    else -> null
                }
            }
        }
        if (collapsed.isNotEmpty()) {
            collapsed = collapsed.mapNotNull { r ->
                val end = r.last + 1
                when {
                    end <= e.start -> r
                    r.first >= e.removedEnd -> (r.first + e.delta) until (end + e.delta)
                    else -> null
                }
            }
        }
        if (folds.isEmpty()) return
        val lineShift = e.inserted.count { it == '\n' } - e.removed.count { it == '\n' }
        // Обычный набор букв не меняет номера строк, а список участков свёртки в большом файле —
        // десятки тысяч записей. Пересобирать его на каждое нажатие незачем: номера строк остаются
        // верными, а смещения сдвинем при первом же обращении к ним (клик по стрелке свёртки).
        if (lineShift == 0 && collapsed.isEmpty() && foldDrift.size < 1000) {
            foldDrift.add(e)
            return
        }
        applyFoldDrift()
        shiftFolds(e, lineShift)
    }

    /** Правки, на которые ещё не сдвинуты смещения свёрток. */
    private val foldDrift = ArrayList<TextEdit>()

    private fun shiftFolds(e: TextEdit, lineShift: Int) {
        folds = folds.mapNotNull { f ->
            when {
                f.end <= e.start -> f
                f.start >= e.removedEnd -> FoldRegion(f.start + e.delta, f.end + e.delta, f.startLine + lineShift, f.endLine + lineShift, f.kind)
                else -> null
            }
        }
    }

    private fun applyFoldDrift() {
        if (foldDrift.isEmpty()) return
        val pending = ArrayList(foldDrift)
        foldDrift.clear()
        for (e in pending) shiftFolds(e, 0)
    }

    /** Участки свёртки с актуальными смещениями (для действий пользователя). */
    internal fun foldsNow(): List<FoldRegion> {
        applyFoldDrift()
        return folds
    }

    /** Свёртки пересчитаны заново — накопленный сдвиг больше не нужен. */
    internal fun replaceFolds(list: List<FoldRegion>) {
        foldDrift.clear()
        folds = list
    }

    // ---------- Связанные диапазоны ----------

    private fun applyLinked(s: LinkedSession, e: TextEdit, result: TextFieldValue): TextFieldValue? {
        val caretBefore = value.selection.start
        val group = s.activeRanges()
        // Диапазон активной группы, в котором идёт правка
        val chosen = group.firstOrNull { it[0] <= e.start && e.removedEnd <= it[1] && caretBefore >= it[0] && caretBefore <= it[1] }
            ?: group.firstOrNull { it[0] <= e.start && e.removedEnd <= it[1] }
            ?: return null
        for (g in s.groups) for (r in g) {
            if (r === chosen) continue
            when {
                r[1] <= e.start -> Unit
                r[0] >= e.removedEnd -> {
                    r[0] += e.delta; r[1] += e.delta
                }
                else -> return null
            }
        }
        chosen[1] += e.delta
        if (e.start <= s.finalOffset && s.finalOffset >= e.removedEnd) s.finalOffset += e.delta
        val text = result.text
        val content = text.substring(chosen[0], chosen[1])
        if (s.isRename && content.isNotEmpty() && !content.all { language.isWordChar(it) }) return null
        val caret = result.selection.end
        val anchor = result.selection.start
        // Все остальные вхождения — за один проход: раньше на каждое копировался весь текст,
        // и переименование с тысячами вхождений подвисало на каждой букве
        val replace = group.filter { r ->
            r !== chosen && !(r[1] - r[0] == content.length && text.regionMatches(r[0], content, 0, content.length))
        }.sortedBy { it[0] }
        if (replace.isEmpty()) return TextFieldValue(text, TextRange(anchor, caret))
        val ends = IntArray(replace.size)
        val shifts = IntArray(replace.size)
        val sb = StringBuilder(text.length + replace.size * content.length)
        var pos = 0
        var acc = 0
        replace.forEachIndexed { i, r ->
            sb.append(text, pos, r[0]).append(content)
            pos = r[1]
            acc += content.length - (r[1] - r[0])
            ends[i] = r[1]
            shifts[i] = acc
        }
        sb.append(text, pos, text.length)
        // Сдвиг позиции: сумма изменений длины у заменённых кусков, закончившихся не позже неё
        fun shift(at: Int): Int {
            var lo = 0
            var hi = ends.size - 1
            var idx = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (ends[mid] <= at) {
                    idx = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return if (idx < 0) 0 else shifts[idx]
        }
        val replaced = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<IntArray, Boolean>()).apply { addAll(replace) }
        for (g in s.groups) for (q in g) {
            if (q in replaced) continue
            val d = shift(q[0])
            q[0] += d
            q[1] += d
        }
        for (r in replace) {
            r[0] += shift(r[0])
            r[1] = r[0] + content.length
        }
        s.finalOffset += shift(s.finalOffset)
        return TextFieldValue(sb.toString(), TextRange(anchor + shift(anchor), caret + shift(caret)))
    }

    /** Вставка шаблона на место [start, end). */
    fun insertSnippet(start: Int, end: Int, body: String) {
        val text = value.text
        val ls = TextOps.lineStartOf(text, start)
        val indent = TextOps.leadingWhitespace(text, ls).let { if (it.length > start - ls) text.substring(ls, start) else it }
        val exp = SnippetExpander.expand(body, indent, indentUnit)
        val newText = text.substring(0, start) + exp.text + text.substring(end)
        linked = null
        val groups = exp.stops.map { g -> g.map { r -> intArrayOf(start + r.first, start + r.last + 1) }.toMutableList() }.toMutableList()
        val finalOffset = start + exp.finalOffset
        val sel = if (groups.isNotEmpty()) {
            val first = groups[0][0]
            TextRange(first[0], first[1])
        } else TextRange(finalOffset)
        applyEdit(TextFieldValue(newText, sel))
        if (groups.isNotEmpty()) linked = LinkedSession(groups, 0, finalOffset, isRename = false)
    }

    /** Tab/Shift+Tab в шаблоне. false — сеанса нет. */
    fun snippetNext(backward: Boolean): Boolean {
        val s = linked ?: return false
        if (s.isRename) return false
        val next = s.active + if (backward) -1 else 1
        if (next < 0) return true
        if (next >= s.groups.size) {
            linked = null
            value = value.copy(selection = TextRange(s.finalOffset.coerceIn(0, value.text.length)))
            return true
        }
        s.active = next
        val r = s.groups[next][0]
        value = value.copy(selection = TextRange(r[0], r[1]))
        linked = s
        requestReveal(r[1])
        return true
    }

    /** Переименование всех вхождений слова под курсором (Shift+F6). */
    fun startRename(): Int {
        val text = value.text
        val word = TextOps.wordRangeAt(text, value.selection.start, language::isWordChar) ?: return 0
        val name = text.substring(word.first, word.last + 1)
        if (name.isEmpty() || name.first().isDigit()) return 0
        val ranges = ArrayList<IntArray>()
        var i = text.indexOf(name)
        while (i >= 0 && ranges.size < 5000) {
            val before = if (i > 0) text[i - 1] else ' '
            val after = if (i + name.length < text.length) text[i + name.length] else ' '
            if (!language.isWordChar(before) && !language.isWordChar(after)) ranges.add(intArrayOf(i, i + name.length))
            i = text.indexOf(name, i + name.length)
        }
        if (ranges.isEmpty()) return 0
        linked = LinkedSession(mutableListOf(ranges), 0, value.selection.start, isRename = true)
        value = value.copy(selection = TextRange(word.first, word.last + 1))
        return ranges.size
    }

    fun endLinked() {
        linked = null
    }

    // ---------- Отмена ----------

    fun undo(): Boolean {
        val v = history.undo(value) ?: return false
        restore(v)
        return true
    }

    fun redo(): Boolean {
        val v = history.redo(value) ?: return false
        restore(v)
        return true
    }

    private fun restore(v: TextFieldValue) {
        val old = value
        val edit = TextEdit.diff(old.text, v.text)
        model.update(v.text, edit)
        shiftCollapsed(edit)
        linked = null
        completion = null
        value = v
        recomputeDirty()
        bump()
        requestReveal(v.selection.end)
    }

    // ---------- Выделение ----------

    fun expandSelection(cfg: EditConfig) {
        val r = TextOps.expandSelection(value, cfg, model.snapshot) ?: return
        selectionStack.add(value.selection)
        value = value.copy(selection = r)
        lastExpanded = r
        unfoldAround(r.min, r.max)
    }

    fun shrinkSelection() {
        val prev = selectionStack.removeLastOrNull() ?: return
        value = value.copy(selection = prev)
        lastExpanded = prev
    }

    fun select(start: Int, end: Int = start, center: Boolean = false) {
        val len = value.text.length
        val s = start.coerceIn(0, len)
        val e = end.coerceIn(0, len)
        unfoldAround(minOf(s, e), maxOf(s, e))
        history.breakGroup()
        value = value.copy(selection = TextRange(s, e))
        requestReveal(e, center)
    }

    /** Курсор на строку [TextTarget.line] (с 1) и выделение найденного — строка по центру. */
    fun goTo(target: TextTarget) {
        val text = value.text
        var start = 0
        var line = 1
        while (line < target.line) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) break
            start = nl + 1
            line++
        }
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val s = (start + target.column - 1).coerceIn(start, end)
        select(s, (s + target.length).coerceIn(s, end), center = true)
    }

    // Пара скобок у курсора — один расчёт на текст и позицию, а не на каждую перерисовку
    private var bracketText: String? = null
    private var bracketCaret = -1
    private var bracketValue: Pair<Int, Int>? = null

    internal fun bracketPair(text: String, caret: Int, syntax: SyntaxInfo): Pair<Int, Int>? {
        if (text === bracketText && caret == bracketCaret) return bracketValue
        val v = TextOps.bracketPairNear(text, caret, syntax)
        bracketText = text
        bracketCaret = caret
        bracketValue = v
        return v
    }

    fun requestReveal(offset: Int, center: Boolean = false) {
        revealCenter = center
        revealRequest = offset
    }

    internal fun consumeReveal() {
        revealRequest = null
    }

    // ---------- Подсказки ----------

    fun requestCompletion(explicit: Boolean) {
        completionExplicit = explicit
        completionRequest++
    }

    internal fun showCompletion(ui: CompletionUi?) {
        completion = ui
    }

    fun moveCompletion(delta: Int) {
        val c = completion ?: return
        val n = c.result.items.size
        if (n == 0) return
        var i = c.selected + delta
        i = if (delta == 1 || delta == -1) ((i % n) + n) % n else i.coerceIn(0, n - 1)
        completion = CompletionUi(c.result, i, c.explicit)
    }

    fun closeCompletion() {
        completion = null
    }

    /** Принять подсказку: Enter заменяет префикс, Tab — слово целиком. */
    fun acceptCompletion(item: CompletionItem, replaceWord: Boolean) {
        val c = completion ?: return
        completion = null
        val text = value.text
        val caret = value.selection.end
        val start = c.result.replaceStart.coerceIn(0, text.length)
        if (caret < start) return
        var end = caret
        if (replaceWord) {
            end = maxOf(caret, c.result.wordEnd + (caret - c.result.caret)).coerceIn(caret, text.length)
        }
        val insert = item.insertText
        val isSnippet = item.isSnippet || insert.contains("\$0") || insert.contains("\${")
        // Закрывающая кавычка/скобка уже стоит после курсора — не дублируем
        var tailSkip = 0
        if (!isSnippet && insert.isNotEmpty()) {
            val last = insert.last()
            if ((last == '>' || last == '"' || last == '}') && text.getOrNull(end) == last) tailSkip = 1
        }
        if (isSnippet) {
            insertSnippet(start, end, insert)
        } else {
            val newText = text.substring(0, start) + insert + text.substring(end + tailSkip)
            applyEdit(TextFieldValue(newText, TextRange(start + insert.length + tailSkip)))
        }
        if (item.retrigger) requestCompletion(explicit = true)
    }

    // ---------- Свёртка ----------

    /** Внешние (не вложенные в свёрнутые) участки для отображения. */
    fun effectiveCollapsed(): List<IntRange> {
        val len = value.text.length
        val sorted = collapsed.filter { it.first >= 0 && it.last < len && it.last >= it.first }.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var lastEnd = -1
        for (r in sorted) {
            if (r.first <= lastEnd) continue
            out.add(r)
            lastEnd = r.last
        }
        return out
    }

    fun toggleFold(region: FoldRegion) {
        // Смещения могли отстать от набранного текста — берём свежие
        val fresh = foldsNow().firstOrNull { it.startLine == region.startLine } ?: region
        val r = fresh.start until fresh.end
        collapsed = if (collapsed.any { it.first == r.first && it.last == r.last }) collapsed.filterNot { it.first == r.first }
        else {
            // Курсор внутри — выносим на заголовок
            val sel = value.selection
            if (sel.end in (fresh.start + 1) until fresh.end || sel.start in (fresh.start + 1) until fresh.end) {
                value = value.copy(selection = TextRange(fresh.start))
            }
            collapsed + listOf(r)
        }
    }

    fun isCollapsed(region: FoldRegion) = collapsed.any { it.first == region.start && it.last == region.end - 1 }

    /** Свернуть/развернуть ближайший блок вокруг курсора. */
    fun foldAtCaret(collapse: Boolean): Boolean {
        val c = value.selection.start
        val line = model.snapshot.lineOf(c)
        val candidates = folds.filter { (it.startLine <= line && it.endLine >= line) }
        if (collapse) {
            val target = candidates.filter { !isCollapsed(it) }.maxByOrNull { it.start } ?: return false
            toggleFold(target)
        } else {
            val target = collapsed.filter { it.first <= c + 1 && it.last + 1 >= c - 1 || model.snapshot.lineOf(it.first) == line }
                .minByOrNull { it.last - it.first } ?: return false
            collapsed = collapsed.filterNot { it == target }
        }
        return true
    }

    fun foldAll(collapse: Boolean) {
        if (!collapse) {
            collapsed = emptyList()
            return
        }
        val caret = value.selection.start
        // Верхний уровень не сворачиваем, если это единственный блок (класс целиком)
        val candidates = folds.filter { !(caret > it.start && caret < it.end) }
        collapsed = candidates.map { it.start until it.end }
    }

    /** Развернуть свёртки, закрывающие диапазон (переход к найденному, к ошибке и т.п.). */
    fun unfoldAround(start: Int, end: Int) {
        if (collapsed.isEmpty()) return
        val keep = collapsed.filter { r -> !((start > r.first && start <= r.last) || (end > r.first && end <= r.last) || (start <= r.first && end > r.last + 1)) }
        if (keep.size != collapsed.size) collapsed = keep
    }

    private fun adjustSelectionForFolds(old: TextRange, new: TextRange): TextRange {
        val folds = effectiveCollapsed()
        if (folds.isEmpty()) return new
        fun fix(pos: Int, oldPos: Int): Int {
            val f = folds.firstOrNull { pos > it.first && pos <= it.last } ?: return pos
            return if (oldPos > f.last) f.first else f.last + 1
        }
        return TextRange(fix(new.start, old.start), fix(new.end, old.end))
    }
}

/** Место в тексте: строка и колонка с 1, длина выделения. */
data class TextTarget(val line: Int, val column: Int = 1, val length: Int = 0)

@Composable
fun rememberCodeEditorController(initialText: String): CodeEditorController =
    remember(initialText) { CodeEditorController(initialText) }
