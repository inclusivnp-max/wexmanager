package com.tim.dex.model

data class AnnotationSetRefList(
    val annotationSetIdxs: List<Int>        // элементы могут быть -1 (нет)
)