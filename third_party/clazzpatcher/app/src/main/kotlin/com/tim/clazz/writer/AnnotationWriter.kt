package com.tim.clazz.writer

import com.tim.clazz.model.annotations.*
import com.tim.clazz.model.annotations.Annotation
import com.tim.clazz.util.ClassFormatException
import com.tim.clazz.util.ClassOutput
import com.tim.clazz.util.fastForEach

/**
 * Запись annotation / element_value (JVMS §4.7.16).
 */
internal class AnnotationWriter(private val out: ClassOutput) {

    fun writeAnnotation(annotation: Annotation) {
        out.u2(annotation.typeIndex)
        writeElementValuePairs(annotation.elementValuePairs)
    }

    fun writeElementValuePairs(pairs: List<ElementValuePair>) {
        out.u2(pairs.size)
        pairs.fastForEach { pair ->
            out.u2(pair.elementNameIndex)
            writeElementValue(pair.value)
        }
    }

    fun writeElementValue(value: ElementValue) {
        val tag = value.tag
        val valid = when (value) {
            is ConstValueIndexElement -> tag in "BCDFIJSZ"
            is Utf8ValueIndexElement -> tag == 's'
            is EnumConstValueElement -> tag == 'e'
            is ClassInfoIndexElement -> tag == 'c'
            is AnnotationValueElement -> tag == '@'
            is ArrayValueElement -> tag == '['
        }
        if (!valid) {
            val kind = when (value) {
                is ConstValueIndexElement -> "const_value_index"
                is Utf8ValueIndexElement -> "const_value_index (строка)"
                is EnumConstValueElement -> "enum_const_value"
                is ClassInfoIndexElement -> "class_info_index"
                is AnnotationValueElement -> "annotation_value"
                is ArrayValueElement -> "array_value"
            }
            throw ClassFormatException("тег '$tag' не соответствует элементу $kind")
        }
        out.u1(tag.code)
        when (value) {
            is ConstValueIndexElement -> out.u2(value.constValueIndex)
            is Utf8ValueIndexElement -> out.u2(value.utf8ValueIndex)
            is EnumConstValueElement -> {
                out.u2(value.typeNameIndex)
                out.u2(value.constNameIndex)
            }
            is ClassInfoIndexElement -> out.u2(value.classInfoIndex)
            is AnnotationValueElement -> writeAnnotation(value.annotation)
            is ArrayValueElement -> {
                out.u2(value.values.size)
                value.values.fastForEach { v -> writeElementValue(v) }
            }
        }
    }
}
