package com.tim.casm.model

data class FieldInfo(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
    val annotations: List<AnnotationInfo> = emptyList(),
    val typeAnnotations: List<TypeAnnotationInfo> = emptyList(),
    val constantValue: Any? = null,
    val signature: String? = null
)
