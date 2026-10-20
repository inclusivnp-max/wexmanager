package com.tim.csmali.asm

import com.tim.casm.pool.ConstantPoolBuilder
import com.tim.clazz.model.annotations.*

/** Текст значения элемента аннотации → element_value (JVMS §4.7.16.1). */
class SmaliValueBuilder(private val pool: ConstantPoolBuilder) {

    fun build(text: String): ElementValue {
        val s = SmaliScanner(text)
        val v = value(s)
        s.expectEnd()
        return v
    }

    fun value(s: SmaliScanner): ElementValue {
        when (val c = s.peek()) {
            '"' -> return Utf8ValueIndexElement('s', pool.utf8(s.stringLiteral()))
            '\'' -> return ConstValueIndexElement('C', pool.integer(SmaliLiterals.parseChar(s.charLiteralRaw()).code))
            '{' -> return arrayValue(s)
            '.' -> {
                if (s.tryConsume(".subannotation")) return AnnotationValueElement('@', subannotation(s))
                if (s.tryConsume(".enum")) {
                    val type = s.type()
                    if (!s.tryConsume("->")) s.error(".enum: ожидалось '->'")
                    val name = s.memberName()
                    if (s.tryConsume(':') && s.type() != type) s.error(".enum: тип константы не совпадает с типом перечисления")
                    return EnumConstValueElement('e', pool.utf8(type), pool.utf8(name))
                }
                s.error("Неизвестная директива в значении")
            }
            'L', '[' -> {
                val type = s.type()
                return ClassInfoIndexElement('c', pool.utf8(type))
            }
            else -> {
                if (c == 't' && s.text.startsWith("true", s.pos)) {
                    s.pos += 4
                    return ConstValueIndexElement('Z', pool.integer(1))
                }
                if (c == 'f' && s.text.startsWith("false", s.pos)) {
                    s.pos += 5
                    return ConstValueIndexElement('Z', pool.integer(0))
                }
                val w = s.word()
                // Класс примитива (int.class → I, void.class → V): одиночная буква-дескриптор.
                if (w.length == 1 && w[0] in "VZBSCIJFD") return ClassInfoIndexElement('c', pool.utf8(w))
                return literal(w)
            }
        }
    }

    private fun arrayValue(s: SmaliScanner): ElementValue {
        s.expect('{')
        val values = ArrayList<ElementValue>()
        if (s.tryConsume('}')) return ArrayValueElement('[', values)
        while (true) {
            values.add(value(s))
            if (s.tryConsume('}')) return ArrayValueElement('[', values)
            s.expect(',')
        }
    }

    private fun subannotation(s: SmaliScanner): com.tim.clazz.model.annotations.Annotation {
        val type = s.type()
        val pairs = ArrayList<ElementValuePair>()
        while (!s.tryConsume(".end subannotation")) {
            if (s.atEnd()) s.error("Нет .end subannotation")
            val name = s.memberName()
            s.expect('=')
            pairs.add(ElementValuePair(pool.utf8(name), value(s)))
        }
        return com.tim.clazz.model.annotations.Annotation(pool.utf8(type), pairs)
    }

    private fun literal(w: String): ElementValue {
        val lit = SmaliLiterals.parse(w)
        return when (lit.kind) {
            SmaliLiterals.Kind.INT -> ConstValueIndexElement('I', pool.integer(lit.intValue))
            SmaliLiterals.Kind.LONG -> ConstValueIndexElement('J', pool.long(lit.bits))
            SmaliLiterals.Kind.SHORT -> ConstValueIndexElement('S', pool.integer(lit.bits.toInt()))
            SmaliLiterals.Kind.BYTE -> ConstValueIndexElement('B', pool.integer(lit.bits.toInt()))
            SmaliLiterals.Kind.CHAR -> ConstValueIndexElement('C', pool.integer(lit.bits.toInt()))
            SmaliLiterals.Kind.BOOLEAN -> ConstValueIndexElement('Z', pool.integer(if (lit.bits != 0L) 1 else 0))
            SmaliLiterals.Kind.FLOAT -> ConstValueIndexElement('F', pool.float(lit.bits.toInt()))
            SmaliLiterals.Kind.DOUBLE -> ConstValueIndexElement('D', pool.double(lit.bits))
        }
    }
}