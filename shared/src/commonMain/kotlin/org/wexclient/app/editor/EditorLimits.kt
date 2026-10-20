package org.wexclient.app.editor

import org.wexclient.app.util.Plural
import java.util.Locale

/**
 * Что выдерживает редактор: весь текст — одна раскладка BasicTextField, а у Compose её высота и ширина
 * не больше 16 777 215 px (дальше окно падает: 900 000 строк — 18 000 016 px), и Skia держит около
 * 2 КБ памяти на строку. Пределы — с запасом на крупный шрифт (36 sp) и экран с масштабом 2.
 */
object EditorLimits {
    const val MAX_LINES = 160_000
    const val MAX_LINE_CHARS = 100_000

    /**
     * Пределы быстрого редактора: он рисует только видимые строки и не строит одну общую
     * раскладку, поэтому и строк, и длины строки выдерживает на порядки больше. Осталась только
     * защита от заведомо невозможного — на такой файл не хватит памяти под сам текст.
     */
    const val FAST_MAX_LINES = 20_000_000
    const val FAST_MAX_LINE_CHARS = 20_000_000

    /** Почему текст не показать даже быстрым редактором, или null. */
    fun fastProblem(text: CharSequence): String? = problem(text, FAST_MAX_LINES, FAST_MAX_LINE_CHARS)

    /** Не открыть обычным редактором (одна раскладка на весь текст), но быстрый справится. */
    fun needsFastEditor(text: CharSequence): Boolean = problem(text) != null && fastProblem(text) == null

    /** Почему текст не открыть в редакторе, или null. */
    fun problem(text: CharSequence): String? = problem(text, MAX_LINES, MAX_LINE_CHARS)

    private fun problem(text: CharSequence, maxLines: Int, maxLineChars: Int): String? {
        // Строк не больше, чем знаков плюс одна: короткий текст можно не просматривать. Редактор
        // спрашивает это на каждую правку, и полный проход по мегабайтам шёл на каждую букву
        if (text.length < maxLines && text.length <= maxLineChars) return null
        var lines = 1
        var longest = 0
        var start = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                lines++
                longest = maxOf(longest, i - start)
                start = i + 1
            }
        }
        longest = maxOf(longest, text.length - start)
        return when {
            lines > maxLines ->
                "в нём ${number(lines)} ${Plural.ru(lines.toLong(), "строка", "строки", "строк")}, " +
                    "а редактор показывает до ${number(maxLines)}"
            longest > maxLineChars ->
                "в нём строка длиной ${number(longest)} ${Plural.ru(longest.toLong(), "символ", "символа", "символов")}, " +
                    "а редактор показывает строки до ${number(maxLineChars)}"
            else -> null
        }
    }

    private fun number(n: Int): String = String.format(Locale.forLanguageTag("ru"), "%,d", n)
}
