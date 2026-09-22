package org.wexclient.app.appimage

import java.util.Locale

/**
 * Файл .desktop (freedesktop Desktop Entry): строки, комментарии и порядок сохраняются,
 * меняются только нужные ключи. Значения хранятся в экранированном виде ([raw]).
 */
class DesktopEntry private constructor(private val lines: MutableList<String>) {

    val groups: List<String> get() = lines.mapNotNull(::groupName)

    fun raw(key: String, group: String = MAIN): String? {
        val r = range(group) ?: return null
        for (i in r) keyOf(lines[i])?.let { if (it == key) return lines[i].substringAfter('=').trim() }
        return null
    }

    fun get(key: String, group: String = MAIN): String? = raw(key, group)?.let(::unescape)

    /** Name[ru_RU] → Name[ru] → Name. */
    fun localized(key: String, locale: Locale = Locale.getDefault()): String? {
        val keys = buildList {
            if (locale.country.isNotEmpty()) add("$key[${locale.language}_${locale.country}]")
            add("$key[${locale.language}]")
            add(key)
        }
        return keys.firstNotNullOfOrNull { k -> get(k)?.takeIf { it.isNotBlank() } }
    }

    fun setRaw(key: String, value: String, group: String = MAIN) {
        val r = range(group) ?: run {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("[$group]")
            range(group)!!
        }
        for (i in r) {
            if (keyOf(lines[i]) == key) {
                lines[i] = "$key=$value"
                return
            }
        }
        // Новый ключ — после последней непустой строки группы
        var at = r.first
        for (i in r) if (lines[i].isNotBlank()) at = i + 1
        lines.add(at, "$key=$value")
    }

    fun set(key: String, value: String, group: String = MAIN) = setRaw(key, escape(value), group)

    fun remove(key: String, group: String = MAIN) {
        val r = range(group) ?: return
        for (i in r.reversed()) if (keyOf(lines[i]) == key) lines.removeAt(i)
    }

    fun text(): String = lines.joinToString("\n") + "\n"

    private fun range(group: String): IntRange? {
        val start = lines.indexOfFirst { groupName(it) == group }
        if (start < 0) return null
        var end = start + 1
        while (end < lines.size && groupName(lines[end]) == null) end++
        return (start + 1) until end
    }

    companion object {
        const val MAIN = "Desktop Entry"

        fun parse(text: String): DesktopEntry {
            val lines = text.replace("\r\n", "\n").split('\n').toMutableList()
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            return DesktopEntry(lines)
        }

        fun create(name: String): DesktopEntry =
            parse("[$MAIN]\nType=Application\nName=${escape(name)}\nTerminal=false\n")

        private fun groupName(line: String): String? {
            val t = line.trim()
            return if (t.length > 2 && t.startsWith("[") && t.endsWith("]")) t.substring(1, t.length - 1) else null
        }

        private fun keyOf(line: String): String? {
            if (line.startsWith("#")) return null
            val eq = line.indexOf('=')
            return if (eq > 0) line.substring(0, eq).trim() else null
        }

        /** \s \n \t \r \\ — экранирование строковых значений. */
        fun unescape(value: String): String {
            if ('\\' !in value) return value
            val sb = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        's' -> sb.append(' ')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '\\' -> sb.append('\\')
                        else -> sb.append(c).append(value[i + 1])
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        fun escape(value: String): String =
            value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")

        private const val RESERVED = " \t\n\"'\\><~|&;$*?#()`"

        /** Аргумент Exec: с пробелами и спецсимволами — в кавычках (правила спецификации). */
        fun quoteExecArg(arg: String): String {
            // Буквальный % пишется как %% — иначе «100%f» стал бы кодом поля
            val literal = arg.replace("%", "%%")
            if (literal.isNotEmpty() && literal.none { it in RESERVED }) return literal
            val inner = literal.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")
            return "\"$inner\""
        }

        /** Слова Exec в сыром значении: пробелы разделяют, "…" — одно слово. */
        private fun execTokens(s: String): List<IntRange> {
            val out = ArrayList<IntRange>()
            var i = 0
            while (i < s.length) {
                while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
                if (i >= s.length) break
                val start = i
                while (i < s.length && s[i] != ' ' && s[i] != '\t') {
                    if (s[i] == '"') {
                        i++
                        while (i < s.length && s[i] != '"') {
                            if (s[i] == '\\' && i + 1 < s.length) i++
                            i++
                        }
                        if (i < s.length) i++
                    } else {
                        i++
                    }
                }
                out.add(start until i)
            }
            return out
        }

        /** Аргументы Exec как записаны (с %U), без «env VAR=…» и самого исполняемого файла. */
        fun execArguments(execRaw: String): String {
            val tokens = execTokens(execRaw)
            var i = 0
            if (tokens.isNotEmpty() && execRaw.substring(tokens[0]) == "env") {
                i++
                while (i < tokens.size) {
                    val t = execRaw.substring(tokens[i])
                    if ('=' in t && !t.startsWith("-") && !t.startsWith("/")) i++ else break
                }
            }
            i++
            return if (i < tokens.size) execRaw.substring(tokens[i].first).trim() else ""
        }
    }
}
