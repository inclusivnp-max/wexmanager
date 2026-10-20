package com.tim.dsmali.asm

/**
 * Посимвольный разбор операндов smali (инструкции, значения аннотаций, директивы).
 * Пробелы, табуляции и переводы строк между токенами незначимы; `#` вне строк —
 * начало комментария до конца строки.
 */
class SmaliScanner(val text: String, var pos: Int = 0) {

    fun error(msg: String): Nothing =
        throw IllegalArgumentException("$msg (позиция $pos в \"$text\")")

    /** Пропустить пробелы, переводы строк и комментарии. */
    fun skipWs(): SmaliScanner {
        while (pos < text.length) {
            val c = text[pos]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++
            else if (c == '#') {
                while (pos < text.length && text[pos] != '\n') pos++
            } else break
        }
        return this
    }

    fun atEnd(): Boolean { skipWs(); return pos >= text.length }

    fun peek(): Char { skipWs(); return if (pos < text.length) text[pos] else '\u0000' }

    fun tryConsume(c: Char): Boolean {
        skipWs()
        if (pos < text.length && text[pos] == c) { pos++; return true }
        return false
    }

    fun tryConsume(s: String): Boolean {
        skipWs()
        if (text.startsWith(s, pos)) { pos += s.length; return true }
        return false
    }

    fun expect(c: Char) {
        if (!tryConsume(c)) error("Ожидался '$c'")
    }

    fun expectEnd() {
        if (!atEnd()) error("Лишний текст \"${text.substring(pos)}\"")
    }

    // ---- Регистры ----

    /**
     * Регистр `vN`/`pN` → номер v-регистра; p-регистры пересчитываются через [paramBase]
     * (= registers - ins_size; `-1` — p-регистры недопустимы).
     */
    fun register(paramBase: Int, paramCount: Int): Int {
        skipWs()
        if (pos >= text.length) error("Ожидался регистр")
        val kind = text[pos]
        if (kind != 'v' && kind != 'p' && kind != 'V' && kind != 'P') error("Ожидался регистр")
        pos++
        val start = pos
        var n = 0
        while (pos < text.length && text[pos] in '0'..'9') {
            n = n * 10 + (text[pos] - '0')
            if (n > 0xFFFF) error("Слишком большой номер регистра")
            pos++
        }
        if (pos == start) error("Ожидался номер регистра")
        if (kind == 'v' || kind == 'V') return n
        if (paramBase < 0) error("p-регистр без .registers/.locals")
        if (n >= paramCount) error("Регистр p$n за пределами параметров (их регистров $paramCount)")
        return paramBase + n
    }

    // ---- Метки ----

    /** Метка `:имя`; как у эталонного smali, между `:` и именем допускаются пробелы (`: имя`). */
    fun label(): String {
        skipWs()
        if (pos >= text.length || text[pos] != ':') error("Ожидалась метка")
        pos++
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t')) pos++
        val start = pos
        while (pos < text.length && isNameChar(text[pos])) pos++
        if (pos == start) error("Пустое имя метки")
        return ":" + text.substring(start, pos)
    }

    // ---- Строки и символы ----

    /** Строковый литерал → (исходный текст с кавычками, значение). */
    fun stringLiteralRaw(): String {
        skipWs()
        if (pos >= text.length || text[pos] != '"') error("Ожидался строковый литерал")
        val start = pos
        pos++
        while (pos < text.length) {
            val c = text[pos]
            if (c == '\\') { pos += 2; continue }
            if (c == '"') { pos++; return text.substring(start, pos) }
            if (c == '\n') break
            pos++
        }
        error("Незавершённый строковый литерал")
    }

    fun stringLiteral(): String {
        val raw = stringLiteralRaw()
        return SmaliLiterals.unescape(raw, 1, raw.length - 1)
    }

    fun charLiteralRaw(): String {
        skipWs()
        if (pos >= text.length || text[pos] != '\'') error("Ожидался символьный литерал")
        val start = pos
        pos++
        while (pos < text.length) {
            val c = text[pos]
            if (c == '\\') { pos += 2; continue }
            if (c == '\'') { pos++; return text.substring(start, pos) }
            if (c == '\n') break
            pos++
        }
        error("Незавершённый символьный литерал")
    }

    // ---- Типы и ссылки ----

    /**
     * Дескриптор типа: `[`* и примитив или `L...;`. Сегменты имени класса с пробелами (DEX 040+)
     * пишутся в обратных кавычках (`` Lcom/`my pkg`/A; ``) — кавычки снимаются (в SimpleName их нет).
     */
    fun type(): String {
        skipWs()
        val start = pos
        while (pos < text.length && text[pos] == '[') pos++
        if (pos >= text.length) error("Ожидался тип")
        when (text[pos]) {
            'V', 'Z', 'B', 'S', 'C', 'I', 'J', 'F', 'D' -> pos++
            'L' -> {
                var quoted = false
                var hasQuotes = false
                while (true) {
                    pos++
                    if (pos >= text.length) error("Незавершённый дескриптор класса")
                    val c = text[pos]
                    if (c == '`') { quoted = !quoted; hasQuotes = true }
                    else if (c == ';' && !quoted) break
                    else if (c == '\n' && !quoted) error("Незавершённый дескриптор класса")
                }
                pos++
                if (hasQuotes) return text.substring(start, pos).replace("`", "")
            }
            else -> error("Некорректный дескриптор типа")
        }
        return text.substring(start, pos)
    }

    /** `(типы)тип` → (параметры, результат). */
    fun proto(): Pair<List<String>, String> {
        expect('(')
        val params = ArrayList<String>(4)
        while (true) {
            if (pos < text.length && text[pos] == ')') { pos++; break }
            if (pos >= text.length) error("Незавершённый прототип")
            params.add(type())
        }
        return params to type()
    }

    /** Имя члена (до `:` или `(`); имя с пробелами — в обратных кавычках. */
    fun memberName(): String {
        if (pos < text.length && text[pos] == '`') {
            val end = text.indexOf('`', pos + 1)
            if (end < 0) error("Незакрытая обратная кавычка в имени")
            val name = text.substring(pos + 1, end)
            pos = end + 1
            if (name.isEmpty()) error("Пустое имя члена класса")
            return name
        }
        val start = pos
        while (pos < text.length) {
            val c = text[pos]
            if (c == ':' || c == '(' || c == ' ' || c == ',' || c == '\n' || c == '\t') break
            pos++
        }
        if (pos == start) error("Ожидалось имя члена класса")
        return text.substring(start, pos)
    }

    /** Имя для simple_name (метки, имена call site, элементы аннотаций). */
    fun simpleName(): String {
        skipWs()
        val start = pos
        while (pos < text.length && isNameChar(text[pos])) pos++
        if (pos == start) error("Ожидалось имя")
        return text.substring(start, pos)
    }

    /** Слово до разделителя (пробел, `,`, `}`, `)`, `#`, перевод строки). */
    fun word(): String {
        skipWs()
        val start = pos
        while (pos < text.length) {
            val c = text[pos]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == '}' || c == ')' || c == '#') break
            pos++
        }
        if (pos == start) error("Ожидался литерал")
        return text.substring(start, pos)
    }

    fun startsWithAt(s: String): Boolean { skipWs(); return text.startsWith(s, pos) }

    companion object {
        fun isNameChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '_' || c == '$' || c == '-' || c == '<' || c == '>' ||
                (c.code >= 0x80 && !c.isWhitespace())
    }
}
