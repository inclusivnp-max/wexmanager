package com.tim.dex.model

data class AnnotationsDirectoryItem(
    val classAnnotationsSetIdx: Int,        // -1 = нет
    val fieldAnnotations: List<FieldAnnotation>,
    val methodAnnotations: List<MethodAnnotation>,
    val parameterAnnotations: List<ParameterAnnotation>
)