package com.tim.casm.model

object JvmType {
    fun slotSize(descriptor: String): Int = when (descriptor[0]) {
        'J', 'D' -> 2
        else -> 1
    }

    fun paramsSlotSize(paramTypes: List<String>): Int {
        var s = 0
        for (t in paramTypes) s += slotSize(t)
        return s
    }

    fun parseMethodDescriptor(descriptor: String): Pair<List<String>, String> {
        require(descriptor.startsWith("(")) { "Дескриптор метода должен начинаться с '(': $descriptor" }
        val params = ArrayList<String>()
        var i = 1
        while (i < descriptor.length) {
            val c = descriptor[i]
            if (c == ')') {
                val ret = descriptor.substring(i + 1)
                return params to ret
            }
            val end = typeEnd(descriptor, i)
            params.add(descriptor.substring(i, end))
            i = end
        }
        throw IllegalArgumentException("Дескриптор метода без ')': $descriptor")
    }

    private fun typeEnd(s: String, start: Int): Int {
        when (s[start]) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'V' -> return start + 1
            'L' -> {
                val semi = s.indexOf(';', start)
                require(semi >= 0) { "Дескриптор класса без ';': $s" }
                return semi + 1
            }
            '[' -> {
                var i = start
                while (i < s.length && s[i] == '[') i++
                return typeEnd(s, i)
            }
            else -> throw IllegalArgumentException("Некорректный дескриптор типа: $s (позиция $start)")
        }
    }

    fun descriptorOf(canonical: String): String {
        if (canonical.endsWith("[]")) return "[" + descriptorOf(canonical.dropLast(2))
        return when (canonical) {
            "void" -> "V"
            "boolean" -> "Z"
            "byte" -> "B"
            "char" -> "C"
            "short" -> "S"
            "int" -> "I"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            else -> "L" + canonical.replace('.', '/') + ";"
        }
    }

    fun internalName(binaryName: String): String = binaryName.replace('.', '/')
    fun binaryName(internalName: String): String = internalName.replace('/', '.')
}
