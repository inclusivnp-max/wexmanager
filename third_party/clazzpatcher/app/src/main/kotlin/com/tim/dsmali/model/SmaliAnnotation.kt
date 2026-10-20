package com.tim.dsmali.model

data class SmaliAnnotation(
    var visibility: String = "runtime",
    var type: String = "",
    val elements: MutableList<SmaliAnnotationElement> = mutableListOf(),
    var isSub: Boolean = false
)
