package com.tim.clazz.parser

import com.tim.clazz.model.annotations.Annotation
import com.tim.clazz.model.annotations.AnnotationValueElement
import com.tim.clazz.model.annotations.ArrayValueElement
import com.tim.clazz.model.annotations.ClassInfoIndexElement
import com.tim.clazz.model.annotations.ConstValueIndexElement
import com.tim.clazz.model.annotations.ElementValue
import com.tim.clazz.model.annotations.ElementValuePair
import com.tim.clazz.model.annotations.EnumConstValueElement
import com.tim.clazz.model.annotations.Utf8ValueIndexElement
import com.tim.clazz.util.ClassFormatException
import com.tim.clazz.util.ClassInput

/**
 * Чтение annotation / element_value (JVMS §4.7.16).
 */
internal class AnnotationReader(private val input: ClassInput) {

    /** Глубина вложенности аннотаций и массивов в значениях (разбор рекурсивный — ограничена). */
    private var depth = 0

    private inline fun <T> nested(block: () -> T): T {
        if (++depth > MAX_DEPTH) throw ClassFormatException("вложенность element_value больше $MAX_DEPTH")
        try {
            return block()
        } finally {
            depth--
        }
    }

    fun readAnnotation(): Annotation = nested {
        val typeIndex = input.u2()
        Annotation(typeIndex, readElementValuePairs())
    }

    fun readElementValuePairs(): List<ElementValuePair> =
        readList(input.u2()) {
            val nameIndex = input.u2()
            ElementValuePair(nameIndex, readElementValue())
        }

    fun readElementValue(): ElementValue {
        val tagByte = input.u1()
        return when (val tag = tagByte.toChar()) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> ConstValueIndexElement(tag, input.u2())
            's' -> Utf8ValueIndexElement(tag, input.u2())
            'e' -> EnumConstValueElement(tag, input.u2(), input.u2())
            'c' -> ClassInfoIndexElement(tag, input.u2())
            '@' -> AnnotationValueElement(tag, readAnnotation())
            '[' -> nested { ArrayValueElement(tag, readList(input.u2()) { readElementValue() }) }
            else -> throw ClassFormatException("неизвестный тег element_value: 0x%02X".format(tagByte))
        }
    }

    private companion object {
        /** Как у DEX (com.tim.dex.parser.MAX_VALUE_DEPTH): компиляторы не порождают и десятка уровней. */
        const val MAX_DEPTH = 256
    }
}
