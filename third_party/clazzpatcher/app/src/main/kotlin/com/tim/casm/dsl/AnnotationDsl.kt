package com.tim.casm.dsl

import com.tim.casm.model.AnnotationElementInfo
import com.tim.casm.model.AnnotationInfo
import com.tim.casm.model.AnnotationValue

class AnnotationDsl {
    private var type: String = ""
    private var visible: Boolean = true
    private val elements = ArrayList<AnnotationElementInfo>()

    fun type(descriptor: String) { type = descriptor }
    fun runtimeVisible() { visible = true }
    fun runtimeInvisible() { visible = false }

    fun element(name: String, value: Any?) {
        elements.add(AnnotationElementInfo(name, encode(value)))
    }

    fun enumElement(name: String, typeDesc: String, constName: String) {
        elements.add(AnnotationElementInfo(name, AnnotationValue.Enum(typeDesc, constName)))
    }

    fun classElement(name: String, descriptor: String) {
        elements.add(AnnotationElementInfo(name, AnnotationValue.ClassRef(descriptor)))
    }

    fun annotationElement(name: String, block: AnnotationDsl.() -> Unit) {
        val dsl = AnnotationDsl(); dsl.block()
        elements.add(AnnotationElementInfo(name, AnnotationValue.Nested(dsl.buildInfo())))
    }

    fun arrayElement(name: String, values: List<Any?>) {
        elements.add(AnnotationElementInfo(name, AnnotationValue.Array(values.map { encode(it) })))
    }

    private fun encode(value: Any?): AnnotationValue = when (value) {
        null -> AnnotationValue.Const(null)
        is Boolean, is Byte, is Char, is Short, is Int, is Long, is Float, is Double, is String ->
        AnnotationValue.Const(value)
        else -> throw IllegalArgumentException("Неподдерживаемое значение аннотации: $value")
    }

    internal fun buildInfo(): AnnotationInfo {
        require(type.isNotEmpty()) { "Не задан тип аннотации" }
        return AnnotationInfo(type, visible, elements.toList())
    }
}
