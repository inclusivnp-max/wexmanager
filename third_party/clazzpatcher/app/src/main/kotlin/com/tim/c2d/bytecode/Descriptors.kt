package com.tim.c2d.bytecode

/** Разбор дескрипторов JVM (JVMS §4.3). */
object Descriptors {

    /** Параметры и тип результата метода. */
    class MethodType(val params: List<String>, val returnType: String) {
        /** Слотов, занимаемых параметрами (long/double — по два). */
        val paramWords: Int = params.sumOf { width(it) }
    }

    fun method(descriptor: String): MethodType {
        require(descriptor.startsWith("(")) { "Дескриптор метода должен начинаться с '(': $descriptor" }
        val params = ArrayList<String>(4)
        var i = 1
        while (i < descriptor.length && descriptor[i] != ')') {
            val end = typeEnd(descriptor, i)
            params.add(descriptor.substring(i, end))
            i = end
        }
        require(i < descriptor.length) { "Дескриптор метода без ')': $descriptor" }
        val ret = descriptor.substring(i + 1)
        require(ret.isNotEmpty() && typeEnd(ret, 0) == ret.length) { "Некорректный тип результата в дескрипторе $descriptor" }
        return MethodType(params, ret)
    }

    /** Слотов в локалах/на стеке: 2 для `J`/`D`, 0 для `V`, иначе 1. */
    fun width(type: String): Int = when (type[0]) {
        'J', 'D' -> 2
        'V' -> 0
        else -> 1
    }

    fun isReference(type: String): Boolean = type[0] == 'L' || type[0] == '['

    /** Дескриптор типа по внутреннему имени из CONSTANT_Class (`java/lang/String` → `Ljava/lang/String;`, массивы как есть). */
    fun ofClass(internalName: String): String =
        if (internalName.startsWith("[")) internalName else "L$internalName;"

    private fun typeEnd(s: String, start: Int): Int {
        require(start < s.length) { "Неожиданный конец дескриптора: $s" }
        return when (s[start]) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'V' -> start + 1
            'L' -> {
                val semi = s.indexOf(';', start)
                require(semi > start + 1) { "Дескриптор класса без ';': $s" }
                semi + 1
            }
            '[' -> {
                var i = start
                while (i < s.length && s[i] == '[') i++
                typeEnd(s, i)
            }
            else -> throw IllegalArgumentException("Некорректный дескриптор типа: $s (позиция $start)")
        }
    }
}
