package com.tim.csmali.model

data class SmaliField(
    var accessFlags: MutableList<String> = mutableListOf(),
    var name: String = "",
    var type: String = "",
    val annotations: MutableList<SmaliAnnotation> = mutableListOf(),
    val typeAnnotations: MutableList<SmaliTypeAnnotation> = mutableListOf(),
    var signature: String? = null,
    var initialValue: String? = null,
    /** Нестандартные атрибуты, переносимые байт-в-байт. */
    val rawAttributes: MutableList<SmaliRawAttribute> = mutableListOf()
)