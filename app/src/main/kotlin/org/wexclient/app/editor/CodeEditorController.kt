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
        extraCarets = emptyList()
        searchScope = null
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
        reloadBytes(bytes, cs)
        return true
    }

    /**
     * Файл поменяли снаружи (git, другой редактор): перечитать его новые байты в той же кодировке.
     * Правки и история отмены теряются — зовётся, когда правок нет или их решили не хранить.
     */
    fun reloadFromDisk(bytes: ByteArray) {
        reloadBytes(bytes, charset)
        originalBytes = bytes
    }

    /** Каретка после перечитывания — на той же строке и колонке, насколько новый текст позволяет. */
    private fun reloadBytes(bytes: ByteArray, cs: Charset?) {
        val caret = value.selection.end
        val line = model.snapshot.lineOf(caret)
        val column = caret - model.snapshot.lineStart(line)
        val decoded = TextCodec.decode(bytes, cs)
        reset(decoded.text)
        charset = decoded.format.charset
        hasBom = decoded.format.bom
        lineSeparator = decoded.format.lineSeparator
        val snap = model.snapshot
        val l = line.coerceAtMost(snap.lineCount - 1)
        val at = (snap.lineStart(l) + column).coerceAtMost(snap.lineEnd(l))
        value = value.copy(selection = TextRange(at))
        requestReveal(at, center = true)
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
                // Щелчок без Alt или движение одной кареткой — остальные каретки уходят
                if (extraCarets.isNotEmpty()) extraCarets = emptyList()
                occurrenceWholeWord = false
                occurrenceTrail.clear()
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
        // Правка одной кареткой (команда, замена, подсказка) — остальные каретки ей не нужны
        if (extraCarets.isNotEmpty()) extraCarets = emptyList()
        occurrenceTrail.clear()
        value = result
        selectionStack.clear()
        lastExpanded = null
        recomputeDirty()
        bump()
    }

    private fun shiftCollapsed(e: TextEdit) {
        searchScope?.let { scope ->
            // Вставка в самом начале или конце области попадает в неё: так «заменить все» и набор у края не теряют кусок
            fun mapStart(p: Int) = when {
                p <= e.start -> p
                p >= e.removedEnd -> p + e.delta
                else -> e.start
            }
            fun mapEnd(p: Int) = when {
                p < e.start -> p
                p >= e.removedEnd -> p + e.delta
                else -> e.insertedEnd
            }
            val a = mapStart(scope.min)
            searchScope = TextRange(a, maxOf(a, mapEnd(scope.max)))
        }
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
        extraCarets = emptyList()
        linked = LinkedSession(mutableListOf(ranges), 0, value.selection.start, isRename = true)
        value = value.copy(selection = TextRange(word.first, word.last + 1))
        return ranges.size
    }

    fun endLinked() {
        linked = null
    }

    // ---------- Несколько кареток ----------

    /** Дополнительные каретки (основная — в [value]); по порядку в тексте, не пересекаются. */
    var extraCarets by mutableStateOf<List<TextRange>>(emptyList())
        internal set

    val hasMultipleCarets: Boolean get() = extraCarets.isNotEmpty()

    /** Выделение начато Alt+J со слова под кареткой — дальше ищем это слово только целиком. */
    private var occurrenceWholeWord = false

    /** Прежние основные каретки при Alt+J — Alt+Shift+J возвращается к ним. */
    private val occurrenceTrail = ArrayList<TextRange>()

    /** Все каретки по порядку в тексте, основная среди них. */
    fun carets(): List<TextRange> =
        if (extraCarets.isEmpty()) listOf(value.selection) else normalizeCarets(extraCarets, value.selection, value.text.length).first

    /** Задать каретки: пересекающиеся сливаются, [primary] — основная (к ней едет вид). */
    fun setCarets(all: List<TextRange>, primary: TextRange, reveal: Boolean = true) {
        val (list, index) = normalizeCarets(all.take(MAX_CARETS), primary, value.text.length)
        val main = list[index]
        history.breakGroup()
        if (list.size > 1) {
            linked = null
            completion = null
        }
        value = value.copy(selection = main)
        extraCarets = list.filterIndexed { i, _ -> i != index }
        unfoldAround(main.min, main.max)
        if (reveal) requestReveal(main.end)
    }

    /** Оставить одну основную каретку (Esc). */
    fun clearExtraCarets(): Boolean {
        if (extraCarets.isEmpty()) return false
        extraCarets = emptyList()
        occurrenceTrail.clear()
        return true
    }

    /** Сдвинуть каждую каретку, текст не меняется. */
    fun moveCarets(move: (TextRange) -> TextRange) {
        val all = carets()
        val primary = value.selection
        val moved = all.map(move)
        setCarets(moved, moved[all.indexOf(primary).coerceAtLeast(0)])
    }

    /** Alt+щелчок: каретка в [offset] — добавить, а если она там уже есть — убрать. */
    fun toggleCaretAt(offset: Int) {
        val at = offset.coerceIn(0, value.text.length)
        val all = carets()
        val hit = all.firstOrNull { it.collapsed && it.start == at }
        if (hit != null && all.size > 1) {
            val rest = all - hit
            setCarets(rest, if (hit == value.selection) rest.last() else value.selection, reveal = false)
        } else if (hit == null) {
            setCarets(all + TextRange(at), TextRange(at), reveal = false)
        }
    }

    /**
     * Правка в каждой каретке одной правкой истории: [edit] по тексту и выделению каретки
     * говорит, что заменить. Каретки идут по порядку, куски, заехавшие на соседнюю правку, обрезаются.
     */
    internal fun editCarets(kind: EditKind, edit: (String, TextRange) -> CaretEdit?): Boolean {
        val old = value
        val text = old.text
        val all = carets()
        val primaryIndex = all.indexOf(old.selection).coerceAtLeast(0)
        val sb = StringBuilder(text.length + all.size * 4)
        val layer = ArrayList<TextEdit>()
        val after = ArrayList<TextRange>(all.size)
        var pos = 0
        for (sel in all) {
            val e = edit(text, sel) ?: CaretEdit.keep(sel)
            val from = e.from.coerceIn(pos, text.length)
            val to = e.to.coerceIn(from, text.length)
            sb.append(text, pos, from)
            val at = sb.length
            sb.append(e.text)
            after.add(TextRange(at + e.selStart, at + e.selEnd))
            if (from != to || e.text.isNotEmpty()) layer.add(TextEdit(from, text.substring(from, to), e.text))
            pos = to
        }
        sb.append(text, pos, text.length)
        if (layer.isEmpty()) {
            setCarets(after, after[primaryIndex])
            return false
        }
        val newText = sb.toString()
        val (list, index) = normalizeCarets(after, after[primaryIndex], newText.length)
        val primary = list[index]
        val extras = list.filterIndexed { i, _ -> i != index }
        history.recordCarets(layer, old.selection, primary, extraCarets, extras, kind)
        // Разметке — одной правкой от первой каретки до последней; свёрткам и ошибкам — по каждой
        val first = layer.first()
        val last = layer.last()
        val delta = layer.sumOf { it.delta }
        model.update(newText, TextEdit(first.start, text.substring(first.start, last.removedEnd), newText.substring(first.start, last.removedEnd + delta)))
        for (e in layer.asReversed()) shiftCollapsed(e)
        linked = null
        completion = null
        occurrenceTrail.clear()
        value = TextFieldValue(newText, primary)
        extraCarets = extras
        selectionStack.clear()
        lastExpanded = null
        recomputeDirty()
        bump()
        requestReveal(primary.end)
        return true
    }

    /**
     * Правка по строкам кареток (отступ, комментарий, удаление строк): [layer] — правки по
     * возрастанию, в координатах текущего текста; каретки сдвигаются вместе с текстом.
     */
    internal fun editLines(layer: List<TextEdit>, kind: EditKind = EditKind.OTHER) {
        val edits = layer.filterNot { it.isEmpty }
        if (edits.isEmpty()) return
        val all = carets()
        val primaryIndex = all.indexOf(value.selection).coerceAtLeast(0)
        val mapped = all.map { TextRange(mapThroughLayer(it.start, edits), mapThroughLayer(it.end, edits)) }
        val text = value.text
        // Правки строк не привязаны к кареткам — применяем слой целиком
        val newText = TextEdit.applyLayer(text, edits) ?: return
        val (list, index) = normalizeCarets(mapped, mapped[primaryIndex], newText.length)
        val primary = list[index]
        val extras = list.filterIndexed { i, _ -> i != index }
        history.recordCarets(edits, value.selection, primary, extraCarets, extras, kind)
        history.breakGroup()
        val first = edits.first()
        val last = edits.last()
        val delta = edits.sumOf { it.delta }
        model.update(newText, TextEdit(first.start, text.substring(first.start, last.removedEnd), newText.substring(first.start, last.removedEnd + delta)))
        for (e in edits.asReversed()) shiftCollapsed(e)
        linked = null
        completion = null
        value = TextFieldValue(newText, primary)
        extraCarets = extras
        selectionStack.clear()
        lastExpanded = null
        recomputeDirty()
        bump()
        requestReveal(primary.end)
    }

    /**
     * Alt+J: без выделения — слово под каждой кареткой; с выделением — ещё и следующее такое же
     * место в тексте (по кругу). null — добавлять нечего.
     */
    fun selectNextOccurrence(): Boolean {
        val text = value.text
        val all = carets()
        if (all.all { it.collapsed }) {
            val words = all.mapNotNull { c -> TextOps.wordRangeAt(text, c.start, language::isWordChar)?.let { TextRange(it.first, it.last + 1) } }
            if (words.isEmpty()) return false
            val primaryWord = TextOps.wordRangeAt(text, value.selection.start, language::isWordChar)
                ?.let { TextRange(it.first, it.last + 1) } ?: words.last()
            setCarets(words, primaryWord)
            occurrenceWholeWord = true
            return true
        }
        val primary = value.selection.takeIf { !it.collapsed } ?: all.last { !it.collapsed }
        val needle = text.substring(primary.min, primary.max)
        val whole = occurrenceWholeWord && needle.all(language::isWordChar)
        val at = nextOccurrence(text, needle, primary.max, all, whole, language::isWordChar) ?: return false
        val added = TextRange(at, at + needle.length)
        val trail = ArrayList(occurrenceTrail).apply { add(value.selection) }
        setCarets(all + added, added)
        occurrenceTrail.clear()
        occurrenceTrail.addAll(trail)
        occurrenceWholeWord = whole
        return true
    }

    /** Alt+Shift+J: убрать последнее добавленное вхождение. */
    fun unselectLastOccurrence(): Boolean {
        if (extraCarets.isEmpty()) return false
        val all = carets()
        val current = value.selection
        val rest = all - current
        val back = occurrenceTrail.removeLastOrNull()?.takeIf { it in rest } ?: rest.last()
        val trail = ArrayList(occurrenceTrail)
        val whole = occurrenceWholeWord
        setCarets(rest, back)
        occurrenceTrail.addAll(trail)
        occurrenceWholeWord = whole
        return true
    }

    /** Выделить все вхождения выделенного (или слова под кареткой). Сколько их. */
    fun selectAllOccurrences(): Int {
        val text = value.text
        var sel = value.selection
        var whole = occurrenceWholeWord
        if (sel.collapsed) {
            val w = TextOps.wordRangeAt(text, sel.start, language::isWordChar) ?: return 0
            sel = TextRange(w.first, w.last + 1)
            whole = true
        }
        val needle = text.substring(sel.min, sel.max)
        val found = allOccurrences(text, needle, whole && needle.all(language::isWordChar), language::isWordChar, MAX_CARETS)
        if (found.isEmpty()) return 0
        setCarets(found, found.firstOrNull { it.min == sel.min } ?: found.first())
        occurrenceWholeWord = whole
        return found.size
    }

    /** Каретки в концы строк, которых касаются выделения (Alt+Shift+I). */
    fun caretsAtLineEnds(): Boolean {
        val all = carets()
        if (all.all { it.collapsed }) return false
        val snap = model.snapshot
        val ends = caretLines(snap, all).map { TextRange(snap.lineEnd(it)) }
        if (ends.size < 2) return false
        setCarets(ends, ends.last())
        return true
    }

    /**
     * Ctrl+Alt+↑/↓: каретка строкой выше самой верхней (ниже самой нижней) в той же экранной колонке.
     * [rowAndColumn] и [offsetAt] — раскладка поверхности (перенос, табы, свёртки).
     */
    fun addCaretVertically(down: Boolean, rowAndColumn: (Int) -> Pair<Int, Int>, offsetAt: (Int, Int) -> Int, rows: Int): Boolean {
        val all = carets()
        val edge = if (down) all.maxBy { rowAndColumn(it.end).first } else all.minBy { rowAndColumn(it.end).first }
        val row = rowAndColumn(edge.end).first + if (down) 1 else -1
        if (row !in 0 until rows) return false
        val column = if (value.selection == verticalFrom) verticalColumn else rowAndColumn(value.selection.end).second
        val at = offsetAt(row, column)
        val added = TextRange(at)
        setCarets(all + added, added)
        verticalFrom = value.selection
        verticalColumn = column
        return true
    }

    /** Колонка, в которой Ctrl+Alt+↑/↓ ставят каретки подряд (короткие строки её не сбивают). */
    private var verticalFrom: TextRange? = null
    private var verticalColumn = 0

    /** Текст для буфера обмена: выделения кареток через перевод строки (без выделений — их строки). */
    fun caretClipboardText(): String {
        val text = value.text
        val all = carets()
        return if (all.any { !it.collapsed }) {
            all.filter { !it.collapsed }.joinToString("\n") { text.substring(it.min, it.max) }
        } else {
            all.joinToString("\n") { text.substring(TextOps.lineStartOf(text, it.start), TextOps.lineEndOf(text, it.start)) }
        }
    }

    /** Вставка у каждой каретки; строк в буфере столько же, сколько кареток, — по строке в каждую. */
    fun pasteAtCarets(raw: String) {
        val clip = TextCodec.normalize(raw)
        val all = carets()
        val lines = clip.split('\n').let { if (it.size == all.size + 1 && it.last().isEmpty()) it.dropLast(1) else it }
        val perCaret = lines.size == all.size && all.size > 1
        var i = 0
        editCarets(EditKind.OTHER) { _, sel -> CaretEdit.replace(sel, if (perCaret) lines[i++] else clip) }
    }

    /** Вырезать выделенное у всех кареток (без выделений — их строки целиком). */
    fun cutAtCarets() {
        val text = value.text
        val all = carets()
        if (all.any { !it.collapsed }) {
            editCarets(EditKind.DELETE) { _, sel -> if (sel.collapsed) null else CaretEdit.delete(sel.min, sel.max) }
            return
        }
        val snap = model.snapshot
        val lines = caretLines(snap, all)
        editLines(lines.map { l ->
            val from = snap.lineStart(l)
            val to = if (l + 1 < snap.lineCount) snap.lineStart(l + 1) else text.length
            TextEdit(from, text.substring(from, to), "")
        })
    }

    /** Отступ строк кареток на уровень ([indent]) или снять его. */
    fun shiftLinesAtCarets(indent: Boolean) {
        val text = value.text
        val snap = model.snapshot
        editLines(caretLines(snap, carets()).mapNotNull { l ->
            val ls = snap.lineStart(l)
            val le = snap.lineEnd(l)
            if (indent) {
                if (text.substring(ls, le).isBlank()) null else TextEdit(ls, "", indentUnit)
            } else {
                var n = 0
                if (text.getOrNull(ls) == '\t') n = 1
                else while (n < indentUnitWidth() && ls + n < le && text[ls + n] == ' ') n++
                if (n == 0) null else TextEdit(ls, text.substring(ls, ls + n), "")
            }
        })
    }

    private fun indentUnitWidth(): Int = if (indentUnit == "\t") 4 else indentUnit.length

    /** Ctrl+/ несколькими каретками: все их строки комментируются или раскомментируются вместе. */
    fun toggleCommentAtCarets(): Boolean {
        val token = language.lineComment?.trimEnd() ?: return false
        val text = value.text
        val snap = model.snapshot
        val lines = caretLines(snap, carets()).filter { text.substring(snap.lineStart(it), snap.lineEnd(it)).isNotBlank() }
        if (lines.isEmpty()) return false
        val commented = lines.all { text.substring(snap.lineStart(it), snap.lineEnd(it)).trimStart().startsWith(token) }
        val minIndent = lines.minOf { l ->
            val ls = snap.lineStart(l)
            var i = ls
            while (i < snap.lineEnd(l) && (text[i] == ' ' || text[i] == '\t')) i++
            i - ls
        }
        editLines(lines.map { l ->
            val ls = snap.lineStart(l)
            if (commented) {
                val at = text.indexOf(token, ls)
                val end = at + token.length + if (text.getOrNull(at + token.length) == ' ') 1 else 0
                TextEdit(at, text.substring(at, end), "")
            } else {
                TextEdit(ls + minIndent, "", "$token ")
            }
        })
        return true
    }

    /** Удалить строки кареток целиком. */
    fun deleteLinesAtCarets() {
        val text = value.text
        val snap = model.snapshot
        val lines = caretLines(snap, carets())
        editLines(lines.map { l ->
            val from = snap.lineStart(l)
            val to = if (l + 1 < snap.lineCount) snap.lineStart(l + 1) else text.length
            // Последняя строка файла — вместе с переводом строки перед ней (если та строка остаётся)
            if (to == text.length && from > 0 && (l - 1) !in lines) TextEdit(from - 1, text.substring(from - 1, to), "")
            else TextEdit(from, text.substring(from, to), "")
        })
    }

    // ---------- Поиск в выделении ----------

    /** Где искать при «в выделении»; сдвигается вместе с правками текста. */
    var searchScope by mutableStateOf<TextRange?>(null)

    // ---------- Отмена ----------

    fun undo(): Boolean {
        val v = history.undo(value) ?: return false
        restore(v, history.restoredExtras)
        return true
    }

    fun redo(): Boolean {
        val v = history.redo(value) ?: return false
        restore(v, history.restoredExtras)
        return true
    }

    private fun restore(v: TextFieldValue, extras: List<TextRange>) {
        val old = value
        val edit = TextEdit.diff(old.text, v.text)
        model.update(v.text, edit)
        shiftCollapsed(edit)
        linked = null
        completion = null
        occurrenceTrail.clear()
        extraCarets = if (extras.isEmpty()) emptyList()
        else normalizeCarets(extras, v.selection, v.text.length).let { (list, idx) -> list.filterIndexed { i, _ -> i != idx } }
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
        if (extraCarets.isNotEmpty()) extraCarets = emptyList()
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

    // Внешние свёртки спрашивают на каждом кадре и на каждое движение мыши: после «Свернуть всё»
    // в большом файле это тысячи участков, и сортировать их каждый раз незачем
    private var effectiveOf: List<IntRange>? = null
    private var effectiveLength = -1
    private var effectiveValue: List<IntRange> = emptyList()

    /** Внешние (не вложенные в свёрнутые) участки для отображения. */
    fun effectiveCollapsed(): List<IntRange> {
        val len = value.text.length
        val all = collapsed
        if (all === effectiveOf && len == effectiveLength) return effectiveValue
        val sorted = all.filter { it.first >= 0 && it.last < len && it.last >= it.first }.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var lastEnd = -1
        for (r in sorted) {
            if (r.first <= lastEnd) continue
            out.add(r)
            lastEnd = r.last
        }
        effectiveOf = all
        effectiveLength = len
        effectiveValue = out
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
