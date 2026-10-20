package com.tim.dasm.dsl

import com.tim.dasm.DexAsm
import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.Annotation
import com.tim.dex.model.AnnotationElement
import com.tim.dex.model.AnnotationItem
import com.tim.dex.model.EncodedValue

class AnnotationDsl(private val asm: DexAsm) {
    private var type: String = ""
    private val elements = mutableListOf<AnnotationElement>()
    private var visibility: Byte = 1

    fun type(name: String) { this.type = name }
    fun runtimeVisibility() { visibility = 1 }
    fun buildVisibility() { visibility = 0 }
    fun systemVisibility() { visibility = 2 }

    fun element(name: String, value: Any?) {
        val nameIdx = asm.strings.add(name)
        elements.add(AnnotationElement(nameIdx, encode(value)))
    }

    fun element(name: String, type: Int, value: Any?) {
        val nameIdx = asm.strings.add(name)
        elements.add(AnnotationElement(nameIdx, EncodedValue(type, value)))
    }

    fun elementType(name: String, className: String) {
        val nameIdx = asm.strings.add(name)
        val typeIdx = asm.types.add(className)
        elements.add(AnnotationElement(nameIdx, EncodedValue(EncodedValueType.VALUE_TYPE, typeIdx)))
    }

    fun elementEnum(name: String, enumCls: String, enumField: String, enumType: String) {
        val nameIdx = asm.strings.add(name)
        val fieldIdx = asm.fields.add(enumCls, enumField, enumType)
        elements.add(AnnotationElement(nameIdx, EncodedValue(EncodedValueType.VALUE_ENUM, fieldIdx)))
    }

    fun typeArray(name: String, classNames: List<String>) {
        val nameIdx = asm.strings.add(name)
        val encoded = classNames.map {
            EncodedValue(EncodedValueType.VALUE_TYPE, asm.types.add(it))
        }
        elements.add(AnnotationElement(nameIdx, EncodedValue(EncodedValueType.VALUE_ARRAY, encoded)))
    }

    fun stringArray(name: String, values: List<String>) {
        val nameIdx = asm.strings.add(name)
        val encoded = values.map {
            EncodedValue(EncodedValueType.VALUE_STRING, asm.strings.add(it))
        }
        elements.add(AnnotationElement(nameIdx, EncodedValue(EncodedValueType.VALUE_ARRAY, encoded)))
    }

    private fun encode(value: Any?): EncodedValue = when (value) {
        null -> EncodedValue(EncodedValueType.VALUE_NULL, null)
        is String -> EncodedValue(EncodedValueType.VALUE_STRING, asm.strings.add(value))
        is Int -> EncodedValue(EncodedValueType.VALUE_INT, value)
        is Long -> EncodedValue(EncodedValueType.VALUE_LONG, value)
        is Float -> EncodedValue(EncodedValueType.VALUE_FLOAT, value)
        is Double -> EncodedValue(EncodedValueType.VALUE_DOUBLE, value)
        is Boolean -> EncodedValue(EncodedValueType.VALUE_BOOLEAN, value)
        is Byte -> EncodedValue(EncodedValueType.VALUE_BYTE, value)
        is Char -> EncodedValue(EncodedValueType.VALUE_CHAR, value)
        is Short -> EncodedValue(EncodedValueType.VALUE_SHORT, value)
        is List<*> -> EncodedValue(
            EncodedValueType.VALUE_ARRAY,
            value.map { encode(it) }
        )
        else -> throw IllegalArgumentException("Unsupported annotation value: $value")
    }

    fun build(): AnnotationItem {
        require(type.isNotEmpty()) { "Annotation type must be set" }
        val typeIdx = asm.types.add(type)
        return AnnotationItem(
            visibility = visibility,
            annotation = Annotation(typeIdx, elements)
        )
    }
}