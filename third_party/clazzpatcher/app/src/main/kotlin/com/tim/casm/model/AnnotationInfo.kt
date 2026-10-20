package com.tim.casm.model

data class AnnotationInfo(
    val type: String,
    val visible: Boolean = true,
    val elements: List<AnnotationElementInfo> = emptyList()
)

data class AnnotationElementInfo(
    val name: String,
    val value: AnnotationValue
)

sealed class AnnotationValue {
    data class Const(val value: Any?) : AnnotationValue()
    data class Enum(val typeDesc: String, val constName: String) : AnnotationValue()
    data class ClassRef(val descriptor: String) : AnnotationValue()
    data class Nested(val annotation: AnnotationInfo) : AnnotationValue()
    data class Array(val values: List<AnnotationValue>) : AnnotationValue()
}

data class TypeAnnotationInfo(
    val targetType: Int,
    val targetInfo: Any?,
    val typePath: List<Pair<Int, Int>>,
    val annotation: AnnotationInfo
)
