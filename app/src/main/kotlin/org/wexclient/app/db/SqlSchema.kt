package org.wexclient.app.db

/** Что за объект лежит в базе. */
enum class SqlObjectKind(val title: String) {
    TABLE("Таблица"),
    VIEW("Представление"),
    INDEX("Индекс"),
    TRIGGER("Триггер")
}

/** Столбец таблицы: [rowidAlias] — тот самый INTEGER PRIMARY KEY, который на деле хранится как rowid. */
class SqlColumn(
    val name: String,
    val type: String = "",
    val primaryKey: Boolean = false,
    val rowidAlias: Boolean = false,
    val notNull: Boolean = false
)

/** Объект из sqlite_master. */
class SqlObject(
    val kind: SqlObjectKind,
    val name: String,
    val tableName: String,
    val rootPage: Int,
    val sql: String?
) {
    /** У таких таблиц строки лежат в индексном дереве, а rowid нет вовсе. */
    val withoutRowid: Boolean = kind == SqlObjectKind.TABLE && SqlSchema.isWithoutRowid(sql)

    val columns: List<SqlColumn> = when (kind) {
        SqlObjectKind.TABLE -> SqlSchema.tableColumns(sql, withoutRowid)
        SqlObjectKind.INDEX -> SqlSchema.indexColumns(sql)
        else -> emptyList()
    }

    /** Служебные таблицы самого SQLite. */
    val internal: Boolean = name.startsWith("sqlite_", ignoreCase = true)

    /** Имена столбцов для строки из [count] значений: недостающие — по номеру. */
    fun columnNames(count: Int): List<String> = List(count) { i -> columns.getOrNull(i)?.name ?: "столбец ${i + 1}" }
}

/**
 * Разбор текста CREATE TABLE / CREATE INDEX: имена и типы столбцов, ключи, WITHOUT ROWID.
 * Полноценный разборщик SQL здесь не нужен — важны только заголовки таблицы.
 */
object SqlSchema {

    /** Текст между внешними скобками определения и хвост после них. */
    internal fun body(sql: String?): Pair<String, String>? {
        if (sql.isNullOrBlank()) return null
        var depth = 0
        var start = -1
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    i = skipQuoted(sql, i, c, if (c == '\'') '\'' else c)
                    continue
                }
                c == '[' -> {
                    i = skipQuoted(sql, i, '[', ']')
                    continue
                }
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                    continue
                }
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    val end = sql.indexOf("*/", i + 2)
                    i = if (end < 0) sql.length else end + 2
                    continue
                }
                c == '(' -> {
                    if (depth == 0) start = i + 1
                    depth++
                }
                c == ')' -> {
                    depth--
                    if (depth == 0 && start >= 0) return sql.substring(start, i) to sql.substring(i + 1)
                }
            }
            i++
        }
        return null
    }

    /** Возвращает позицию сразу за закрывающей кавычкой (учитывает удвоенные кавычки). */
    private fun skipQuoted(s: String, from: Int, open: Char, close: Char): Int {
        var i = from + 1
        while (i < s.length) {
            if (s[i] == close) {
                if (open == close && i + 1 < s.length && s[i + 1] == close) {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return s.length
    }

    /** Убирает комментарии вне строк и имён в кавычках. */
    internal fun stripComments(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    val end = skipQuoted(s, i, c, c)
                    out.append(s, i, end)
                    i = end
                }
                c == '[' -> {
                    val end = skipQuoted(s, i, '[', ']')
                    out.append(s, i, end)
                    i = end
                }
                c == '-' && i + 1 < s.length && s[i + 1] == '-' -> {
                    while (i < s.length && s[i] != '\n') i++
                }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    val end = s.indexOf("*/", i + 2)
                    i = if (end < 0) s.length else end + 2
                    out.append(' ')
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Разбивает список определений по запятым верхнего уровня. */
    internal fun split(body: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    val end = skipQuoted(body, i, c, c)
                    current.append(body, i, end)
                    i = end
                    continue
                }
                c == '[' -> {
                    val end = skipQuoted(body, i, '[', ']')
                    current.append(body, i, end)
                    i = end
                    continue
                }
                c == '(' -> {
                    depth++
                    current.append(c)
                }
                c == ')' -> {
                    depth--
                    current.append(c)
                }
                c == ',' && depth == 0 -> {
                    out.add(current.toString().trim())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) out.add(current.toString().trim())
        return out.filter { it.isNotEmpty() }
    }

    /** Имя столбца из начала определения плюс остаток строки. */
    internal fun name(definition: String): Pair<String, String>? {
        val s = definition.trimStart()
        if (s.isEmpty()) return null
        val quote = when (s[0]) {
            '"', '`', '\'' -> s[0]
            '[' -> ']'
            else -> null
        }
        if (quote != null) {
            val end = skipQuoted(s, 0, s[0], quote)
            val raw = s.substring(1, (end - 1).coerceAtLeast(1))
            val unescaped = if (s[0] == '[') raw else raw.replace("$quote$quote", quote.toString())
            return unescaped to s.substring(end).trim()
        }
        val end = s.indexOfFirst { it.isWhitespace() || it == '(' }
        return if (end < 0) s to "" else s.substring(0, end) to s.substring(end).trim()
    }

    private val CONSTRAINT_STARTS = setOf("CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN")

    private val TYPE_ENDS = setOf(
        "NOT", "NULL", "PRIMARY", "UNIQUE", "CHECK", "DEFAULT", "COLLATE",
        "REFERENCES", "GENERATED", "AS", "AUTOINCREMENT"
    )

    /** Тип столбца: всё до первого ключевого слова ограничений (скобки типа сохраняются). */
    internal fun type(rest: String): String {
        val out = StringBuilder()
        var depth = 0
        for (token in tokens(rest)) {
            if (depth == 0 && token.substringBefore('(').uppercase() in TYPE_ENDS) break
            depth += token.count { it == '(' } - token.count { it == ')' }
            if (out.isNotEmpty() && !token.startsWith("(")) out.append(' ')
            out.append(token)
        }
        return out.toString().trim()
    }

    /** Слова определения: скобочная группа идёт одним куском. */
    private fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in s) {
            when {
                c == '(' -> {
                    depth++
                    current.append(c)
                }
                c == ')' -> {
                    depth--
                    current.append(c)
                }
                c.isWhitespace() && depth == 0 -> {
                    if (current.isNotEmpty()) {
                        out.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun words(s: String): List<String> = tokens(s).map { it.trimEnd(',').uppercase() }

    fun isWithoutRowid(sql: String?): Boolean {
        val tail = body(sql)?.second ?: return false
        val upper = tail.uppercase()
        return Regex("\\bWITHOUT\\s+ROWID\\b").containsMatchIn(upper)
    }

    /** Столбцы таблицы по её CREATE TABLE. */
    fun tableColumns(sql: String?, withoutRowid: Boolean = isWithoutRowid(sql)): List<SqlColumn> {
        val body = body(sql)?.first?.let(::stripComments) ?: return emptyList()
        val columns = ArrayList<SqlColumn>()
        val keyColumns = ArrayList<String>()
        var keyIsDescending = false
        for (part in split(body)) {
            val head = part.trimStart().takeWhile { it.isLetter() }.uppercase()
            if (head in CONSTRAINT_STARTS) {
                // Ключ, объявленный отдельной строкой: PRIMARY KEY (a, b)
                val upper = part.uppercase()
                val at = upper.indexOf("PRIMARY")
                if (at >= 0 && head != "FOREIGN") {
                    body(part.substring(at))?.first?.let { inner ->
                        keyIsDescending = inner.uppercase().contains(Regex("\\bDESC\\b"))
                        split(inner).forEach { item -> name(item)?.let { keyColumns.add(it.first) } }
                    }
                }
                continue
            }
            val (columnName, rest) = name(part) ?: continue
            val restWords = words(rest)
            val inlineKey = restWords.zipWithNext().any { (a, b) -> a == "PRIMARY" && b == "KEY" }
            val declaredType = type(rest)
            val notNull = restWords.zipWithNext().any { (a, b) -> a == "NOT" && b == "NULL" }
            val descending = inlineKey && restWords.contains("DESC")
            columns.add(
                SqlColumn(
                    name = columnName,
                    type = declaredType,
                    primaryKey = inlineKey,
                    // Только INTEGER PRIMARY KEY (и не по убыванию) на самом деле хранится как rowid
                    rowidAlias = inlineKey && !withoutRowid && !descending &&
                        declaredType.equals("INTEGER", ignoreCase = true),
                    notNull = notNull
                )
            )
        }
        if (keyColumns.isEmpty()) return columns
        val single = keyColumns.size == 1
        return columns.map { column ->
            if (keyColumns.none { it.equals(column.name, ignoreCase = true) }) return@map column
            SqlColumn(
                name = column.name,
                type = column.type,
                primaryKey = true,
                rowidAlias = single && !withoutRowid && !keyIsDescending &&
                    column.type.equals("INTEGER", ignoreCase = true),
                notNull = column.notNull
            )
        }
    }

    /** Столбцы индекса: перечисленные в CREATE INDEX плюс ссылка на строку таблицы. */
    fun indexColumns(sql: String?): List<SqlColumn> {
        val body = body(sql)?.first?.let(::stripComments) ?: return emptyList()
        val columns = split(body).mapNotNull { part ->
            name(part)?.let { (n, _) -> SqlColumn(name = n) }
        }
        return if (columns.isEmpty()) emptyList() else columns + SqlColumn(name = "rowid", type = "INTEGER")
    }
}
