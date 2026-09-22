package org.wexclient.app.editor

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

data class SearchOptions(
    val query: String = "",
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false
)

/** Найденные совпадения: пары (начало, конец) подряд в [ranges]. */
class SearchMatches(val ranges: IntArray, val count: Int, val error: String?, val truncated: Boolean) {
    fun start(i: Int) = ranges[i * 2]
    fun end(i: Int) = ranges[i * 2 + 1]

    /** Индекс первого совпадения, начинающегося не раньше [offset] (по кругу). */
    fun indexAtOrAfter(offset: Int): Int {
        if (count == 0) return -1
        var lo = 0
        var hi = count - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (start(mid) >= offset) {
                ans = mid; hi = mid - 1
            } else lo = mid + 1
        }
        return if (ans < 0) 0 else ans
    }

    /** Первое совпадение, начинающееся не раньше [offset], или [count]. */
    fun lowerBound(offset: Int): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (start(mid) < offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun indexBefore(offset: Int): Int {
        if (count == 0) return -1
        var lo = 0
        var hi = count - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (start(mid) < offset) {
                ans = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return if (ans < 0) count - 1 else ans
    }

    fun indexOfRange(s: Int, e: Int): Int {
        val i = indexAtOrAfter(s)
        return if (i >= 0 && start(i) == s && end(i) == e) i else -1
    }

    companion object {
        val EMPTY = SearchMatches(IntArray(0), 0, null, false)
    }
}

/** Регулярное выражение считалось дольше срока (катастрофический перебор) или поиск отменён. */
class RegexTimeout : RuntimeException("Регулярное выражение считается слишком долго — упростите его", null, false, false)

/**
 * Текст, который прерывает поиск по сроку: java.util.regex постоянно читает символы,
 * так что выражение вида (a+)+b на длинной строке больше не вешает окно.
 */
class TimedText(
    private val text: CharSequence,
    private val deadlineNanos: Long,
    private val cancelled: () -> Boolean = { false }
) : CharSequence {
    private var ticks = 0

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (++ticks and 0x3FFF == 0 && (System.nanoTime() > deadlineNanos || cancelled())) throw RegexTimeout()
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

    override fun toString(): String = text.toString()

    companion object {
        fun of(text: CharSequence, timeoutMs: Long, cancelled: () -> Boolean = { false }) =
            TimedText(text, System.nanoTime() + timeoutMs * 1_000_000, cancelled)
    }
}

object SearchEngine {
    const val LIMIT = 50_000

    fun compile(o: SearchOptions): Pattern {
        var flags = Pattern.MULTILINE
        if (!o.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        var body = if (o.regex) o.query else Pattern.quote(o.query)
        if (o.wholeWord) body = "(?<![\\p{L}\\p{N}_])(?:$body)(?![\\p{L}\\p{N}_])"
        return Pattern.compile(body, flags)
    }

    fun findAll(text: String, o: SearchOptions, timeoutMs: Long = 3000, cancelled: () -> Boolean = { false }): SearchMatches {
        if (o.query.isEmpty()) return SearchMatches.EMPTY
        val pattern = try {
            compile(o)
        } catch (e: PatternSyntaxException) {
            return SearchMatches(IntArray(0), 0, e.description ?: "Ошибка в регулярном выражении", false)
        }
        var arr = IntArray(64)
        var n = 0
        val m = pattern.matcher(TimedText.of(text, timeoutMs, cancelled))
        var from = 0
        var truncated = false
        try {
            while (from <= text.length && m.find(from)) {
                val s = m.start()
                val e = m.end()
                if (e == s) {
                    // Пустые совпадения (^, \b) не показываем, но и не зацикливаемся
                    from = s + 1
                    continue
                }
                if (n / 2 >= LIMIT) {
                    truncated = true
                    break
                }
                if (n + 2 > arr.size) arr = arr.copyOf(arr.size * 2)
                arr[n++] = s
                arr[n++] = e
                from = e
            }
        } catch (e: StackOverflowError) {
            return SearchMatches(IntArray(0), 0, "Слишком сложное регулярное выражение", false)
        } catch (e: RegexTimeout) {
            return SearchMatches(IntArray(0), 0, e.message, false)
        }
        return SearchMatches(arr, n / 2, null, truncated)
    }

    /** Текст замены для совпадения [start, end) с учётом групп $1 и \n в режиме регулярок. */
    fun replacementFor(text: String, start: Int, end: Int, o: SearchOptions, replacement: String): String? {
        if (!o.regex) return replacement
        val m: Matcher = compile(o).matcher(TimedText.of(text, 3000))
        val found = try {
            m.find(start)
        } catch (_: RegexTimeout) {
            return null
        } catch (_: StackOverflowError) {
            return null
        }
        if (!found || m.start() != start || m.end() != end) return null
        return try {
            val sb = StringBuilder()
            m.appendReplacement(sb, expandEscapes(replacement))
            // appendReplacement добавляет и текст до совпадения — отрезаем его
            sb.substring(start)
        } catch (_: Exception) {
            null
        }
    }

    /** \n, \t в строке замены (как в IntelliJ). */
    private fun expandEscapes(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2; continue }
                    't' -> { sb.append('\t'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                }
                // \$ и \\ оставляем для Matcher
                sb.append(c).append(s[i + 1])
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun replaceAll(
        text: String,
        o: SearchOptions,
        replacement: String,
        within: IntRange? = null,
        timeoutMs: Long = 10_000,
        cancelled: () -> Boolean = { false }
    ): Pair<String, Int> {
        if (o.query.isEmpty()) return text to 0
        val pattern = compile(o)
        val m = pattern.matcher(TimedText.of(text, timeoutMs, cancelled))
        if (within != null) m.region(within.first, within.last + 1)
        val sb = StringBuilder(text.length)
        var count = 0
        val repl = if (o.regex) expandEscapes(replacement) else Matcher.quoteReplacement(replacement)
        while (m.find()) {
            if (m.end() == m.start()) continue
            m.appendReplacement(sb, repl)
            count++
        }
        m.appendTail(sb)
        return sb.toString() to count
    }
}
