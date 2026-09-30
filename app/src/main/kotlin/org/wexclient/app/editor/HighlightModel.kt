package org.wexclient.app.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import org.wexclient.app.editor.lang.Language
import org.wexclient.app.editor.lang.Tok
import org.wexclient.app.editor.lang.Token

/** Что лежит в позиции текста — для автоскобок, комментариев и поиска пар. */
interface SyntaxInfo {
    /** Тип токена, покрывающего символ [offset] (null — обычный код/пробел). */
    fun tokenAt(offset: Int): Tok?

    fun isCodeAt(offset: Int): Boolean = when (tokenAt(offset)) {
        Tok.STRING, Tok.ESCAPE, Tok.COMMENT, Tok.DOC_COMMENT, Tok.DOC_TAG, Tok.REGEX, Tok.CODE -> false
        else -> true
    }

    object None : SyntaxInfo {
        override fun tokenAt(offset: Int): Tok? = null
    }
}

/**
 * Неизменяемый снимок разметки — можно читать из фоновых задач
 * (массивы модели после создания не меняются, при правке создаются новые).
 */
class HighlightSnapshot(
    val text: String,
    val language: Language,
    private val starts: IntArray,
    val lineCount: Int,
    private val tokens: Array<IntArray?>,
    val version: Long,
    /** Состояние лексера в конце каждой строки: не 0 — строка ушла дальше незакрытой (строка, комментарий…). */
    private val endStates: IntArray? = null,
    /** Текст разобран лексером (слишком большой только разбит на строки — токенов нет). */
    val lexed: Boolean = true
) : SyntaxInfo {
    fun lineStart(line: Int): Int = starts[line]

    /**
     * Состояние лексера в конце строки [line]. Сам по себе номер значит своё у каждого лексера, но
     * 0 у всех — «ничего не тянется на следующую строку»: однострочный комментарий кончился,
     * строковый литерал закрыт.
     */
    fun endState(line: Int): Int = endStates?.getOrNull(line) ?: 0
    fun lineEnd(line: Int): Int = if (line + 1 < lineCount) starts[line + 1] - 1 else text.length

    fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = lineCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Упакованные токены строки: тройки (начало, конец, Tok.ordinal) относительно начала строки. */
    fun lineTokens(line: Int): IntArray = tokens[line] ?: EMPTY

    // Максимум концов токенов строки до i включительно: дальше назад искать покрытие бессмысленно.
    // Снимок неизменяем, одновременная запись одного и того же значения безвредна.
    private val maxEnds = arrayOfNulls<IntArray>(lineCount.coerceAtLeast(1))

    private fun maxEndsOf(line: Int, t: IntArray): IntArray {
        maxEnds[line]?.let { return it }
        val n = t.size / 3
        val out = IntArray(n)
        var m = 0
        for (i in 0 until n) {
            m = maxOf(m, t[i * 3 + 1])
            out[i] = m
        }
        maxEnds[line] = out
        return out
    }

    override fun tokenAt(offset: Int): Tok? {
        if (offset < 0 || offset >= text.length || lineCount == 0) return null
        val line = lineOf(offset)
        val rel = offset - starts[line]
        val t = tokens[line] ?: return null
        // Последний токен, начавшийся не позже rel и покрывающий его (токены могут перекрываться).
        // Начала отсортированы: двоичный поиск вместо прохода по всей строке — на минифицированном
        // файле в одну строку подсветка скобок иначе замирала.
        var lo = 0
        var hi = t.size / 3 - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid * 3] <= rel) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (idx < 0) return null
        val ends = maxEndsOf(line, t)
        var k = idx
        while (k >= 0 && ends[k] > rel) {
            if (rel < t[k * 3 + 1]) return TOKS[t[k * 3 + 2]]
            k--
        }
        return null
    }

    /** Абсолютный диапазон токена, покрывающего [offset], со склейкой соседних строковых кусков. */
    fun tokenRangeAt(offset: Int): Pair<IntRange, Tok>? {
        if (offset < 0 || offset > text.length || lineCount == 0) return null
        val line = lineOf(offset)
        val base = starts[line]
        val rel = offset - base
        val t = tokens[line] ?: return null
        var k = 0
        while (k < t.size) {
            if (t[k] <= rel && rel < t[k + 1]) {
                val type = TOKS[t[k + 2]]
                var s = t[k]
                var e = t[k + 1]
                if (type == Tok.STRING || type == Tok.ESCAPE) {
                    // Строка = цепочка STRING/ESCAPE/VARIABLE без разрывов
                    var j = k - 3
                    while (j >= 0 && t[j + 1] == s && TOKS[t[j + 2]].isStringPart()) {
                        s = t[j]; j -= 3
                    }
                    j = k + 3
                    while (j < t.size && t[j] == e && TOKS[t[j + 2]].isStringPart()) {
                        e = t[j + 1]; j += 3
                    }
                    return (base + s until base + e) to Tok.STRING
                }
                return (base + s until base + e) to type
            }
            k += 3
        }
        return null
    }

    private fun Tok.isStringPart() = this == Tok.STRING || this == Tok.ESCAPE || this == Tok.VARIABLE

    companion object {
        val EMPTY = IntArray(0)
        val TOKS: Array<Tok> = Tok.values()
    }
}

/** Подсветка с построчным кэшем: при правке перелексируются только затронутые строки. */
class HighlightModel(language: Language) {
    var language: Language = language
        private set
    var text: String = ""
        private set
    var version = 0L
        private set

    private var starts = IntArray(1)
    private var count = 1
    private var inStates = IntArray(1)
    private var outStates = IntArray(1)
    private var tokens: Array<IntArray?> = arrayOfNulls(1)
    private val scratch = ArrayList<Token>()

    /** Слишком большие файлы не раскрашиваем — только разбиваем на строки. */
    var maxLexChars = 4_000_000

    val lineCount: Int get() = count

    var snapshot: HighlightSnapshot = HighlightSnapshot("", language, starts, 1, tokens, 0)
        private set

    /** Текст разбит на строки, но ещё не разобран лексером (открытие не ждёт разбора). */
    private var lexed = false

    fun setLanguage(lang: Language) {
        if (lang === language) {
            if (!lexed) lexAll()
            return
        }
        language = lang
        reset(text)
    }

    /**
     * [lex] = false — только разбить на строки. Так открытие большого файла не платит дважды:
     * редактор всё равно сразу задаёт язык файла, и разбор делается один раз, уже нужным лексером.
     */
    fun reset(newText: String, lex: Boolean = true) {
        text = newText
        var n = 1
        var i = newText.indexOf('\n')
        while (i >= 0) {
            n++
            i = newText.indexOf('\n', i + 1)
        }
        val s = IntArray(n)
        var k = 1
        i = newText.indexOf('\n')
        while (i >= 0) {
            s[k++] = i + 1
            i = newText.indexOf('\n', i + 1)
        }
        starts = s
        count = n
        inStates = IntArray(n)
        outStates = IntArray(n)
        tokens = arrayOfNulls(n)
        lexed = false
        if (lex) lexAll() else publish()
    }

    /** Полный разбор текста лексером языка. */
    private fun lexAll() {
        lexed = true
        // Свежие массивы: снимок «только строки» уже отдан наружу и должен остаться неизменным
        inStates = IntArray(count)
        outStates = IntArray(count)
        tokens = arrayOfNulls(count)
        if (text.length <= maxLexChars) {
            var state = 0
            for (line in 0 until count) {
                inStates[line] = state
                state = lex(line, state)
                outStates[line] = state
            }
        }
        publish()
    }

    /** Инкрементальное обновление после правки [e] (text -> newText). */
    fun update(newText: String, e: TextEdit) {
        val old = text
        // Правка до того, как задали язык (разбора ещё не было) — считаем с нуля
        if (!lexed) {
            reset(newText)
            return
        }
        if (e.removedEnd > old.length || newText.length != old.length + e.delta || newText.length > maxLexChars || old.length > maxLexChars) {
            reset(newText)
            return
        }
        val firstLine = lineOfIn(starts, count, e.start)
        val oldLastLine = lineOfIn(starts, count, e.removedEnd)
        val removedNl = oldLastLine - firstLine
        var insertedNl = 0
        var idx = e.inserted.indexOf('\n')
        while (idx >= 0) {
            insertedNl++
            idx = e.inserted.indexOf('\n', idx + 1)
        }
        val oldCount = count
        val newCount = oldCount - removedNl + insertedNl
        val delta = e.delta
        val ns = IntArray(newCount)
        System.arraycopy(starts, 0, ns, 0, firstLine + 1)
        var k = firstLine + 1
        idx = e.inserted.indexOf('\n')
        while (idx >= 0) {
            ns[k++] = e.start + idx + 1
            idx = e.inserted.indexOf('\n', idx + 1)
        }
        for (j in oldLastLine + 1 until oldCount) ns[k++] = starts[j] + delta

        val oldIn = inStates
        val oldOut = outStates
        val oldTok = tokens
        val nIn = IntArray(newCount)
        val nOut = IntArray(newCount)
        val nTok = arrayOfNulls<IntArray>(newCount)
        System.arraycopy(oldIn, 0, nIn, 0, firstLine + 1)
        System.arraycopy(oldOut, 0, nOut, 0, firstLine)
        System.arraycopy(oldTok, 0, nTok, 0, firstLine)

        text = newText
        starts = ns
        count = newCount
        inStates = nIn
        outStates = nOut
        tokens = nTok

        val lastChangedNew = firstLine + insertedNl
        val shift = insertedNl - removedNl
        var line = firstLine
        var state = nIn[firstLine]
        while (line < newCount) {
            if (line > lastChangedNew) {
                val oldIdx = line - shift
                if (oldIdx in 0 until oldCount && oldIn[oldIdx] == state) {
                    val len = newCount - line
                    System.arraycopy(oldIn, oldIdx, nIn, line, len)
                    System.arraycopy(oldOut, oldIdx, nOut, line, len)
                    System.arraycopy(oldTok, oldIdx, nTok, line, len)
                    break
                }
            }
            nIn[line] = state
            state = lex(line, state)
            nOut[line] = state
            line++
        }
        publish()
    }

    private fun publish() {
        version++
        snapshot = HighlightSnapshot(text, language, starts, count, tokens, version, outStates, lexed && text.length <= maxLexChars)
    }

    private fun lex(line: Int, state: Int): Int {
        val s = starts[line]
        val e = if (line + 1 < count) starts[line + 1] - 1 else text.length
        if (e <= s && state == 0) {
            tokens[line] = null
            return 0
        }
        val str = text.substring(s, e)
        scratch.clear()
        val end = try {
            language.lexer.tokenize(str, state, scratch)
        } catch (_: Exception) {
            scratch.clear()
            0
        } catch (_: StackOverflowError) {
            // Регулярное выражение лексера на очень длинной строке: строка останется без подсветки,
            // а не уронит окно
            scratch.clear()
            0
        }
        tokens[line] = pack(scratch, str.length)
        return end
    }

    private fun pack(list: List<Token>, len: Int): IntArray? {
        if (list.isEmpty()) return null
        val arr = IntArray(list.size * 3)
        var n = 0
        var sorted = true
        var lastStart = -1
        for (t in list) {
            val a = t.start.coerceIn(0, len)
            val b = t.end.coerceIn(a, len)
            if (b <= a || t.type == Tok.TEXT) continue
            if (a < lastStart) sorted = false
            lastStart = a
            arr[n++] = a
            arr[n++] = b
            arr[n++] = t.type.ordinal
        }
        if (n == 0) return null
        val packed = if (n == arr.size) arr else arr.copyOf(n)
        if (!sorted) {
            val order = (0 until n / 3).sortedBy { packed[it * 3] }
            val out = IntArray(n)
            order.forEachIndexed { i, src ->
                out[i * 3] = packed[src * 3]
                out[i * 3 + 1] = packed[src * 3 + 1]
                out[i * 3 + 2] = packed[src * 3 + 2]
            }
            return out
        }
        return packed
    }

    companion object {
        fun lineOfIn(starts: IntArray, count: Int, offset: Int): Int {
            var lo = 0
            var hi = count - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
}
