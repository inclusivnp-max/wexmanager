package com.tim.d2c.model

/**
 * Синтаксис дескрипторов типов (dex-format «TypeDescriptor», JVMS §4.3.2): их проверяет загрузчик ART,
 * а переводчик опирается на то, что тип `L…;`, массив или примитив.
 */
internal object Descriptors {

    /** Дескриптор типа поля/параметра: примитив (кроме V), `L<имя>;` или массив (до 255 измерений). */
    fun isFieldType(d: String): Boolean = fieldTypeEnd(d, 0) == d.length

    /** Дескриптор из type_ids: тип поля или `V`. */
    fun isType(d: String): Boolean = d == "V" || isFieldType(d)

    fun isClass(d: String): Boolean = d.length > 2 && d[0] == 'L' && isFieldType(d)

    fun isArray(d: String): Boolean = d.length > 1 && d[0] == '[' && isFieldType(d)

    fun isReference(d: String): Boolean = isClass(d) || isArray(d)

    /** Конец дескриптора типа поля, начинающегося в [from]; -1 — некорректен. */
    fun fieldTypeEnd(d: String, from: Int): Int {
        var i = from
        while (i < d.length && d[i] == '[') i++
        if (i - from > 255 || i >= d.length) return -1
        return when (d[i]) {
            'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> i + 1
            'L' -> {
                val end = d.indexOf(';', i)
                if (end <= i + 1) return -1
                var segmentStart = i + 1
                for (k in i + 1 until end) {
                    val c = d[k]
                    if (c == '/') {
                        if (k == segmentStart) return -1
                        segmentStart = k + 1
                    } else if (c == '.' || c == '[' || c == '<' || c == '>') return -1
                }
                if (segmentStart == end) -1 else end + 1
            }
            else -> -1
        }
    }
}
