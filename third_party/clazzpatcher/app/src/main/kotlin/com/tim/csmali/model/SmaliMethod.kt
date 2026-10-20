package com.tim.csmali.model

data class SmaliMethod(
    var accessFlags: MutableList<String> = mutableListOf(),
    var name: String = "",
    var returnType: String = "V",
    val paramTypes: MutableList<String> = mutableListOf(),
    val annotations: MutableList<SmaliAnnotation> = mutableListOf(),
    val parameterAnnotations: MutableMap<Int, MutableList<SmaliAnnotation>> = mutableMapOf(),
    val typeAnnotations: MutableList<SmaliTypeAnnotation> = mutableListOf(),
    var signature: String? = null,
    val exceptions: MutableList<String> = mutableListOf(),
    var annotationDefault: String? = null,
    /** Записи атрибута MethodParameters по индексу параметра; пустой список — атрибута нет. */
    val methodParameters: MutableList<SmaliMethodParameter?> = mutableListOf(),
    var maxStack: Int = -1,
    var maxLocals: Int = -1,
    val body: MutableList<SmaliBodyElement> = mutableListOf(),
    val catches: MutableList<SmaliCatchDirective> = mutableListOf(),
    /** Нестандартные атрибуты, переносимые байт-в-байт. */
    val rawAttributes: MutableList<SmaliRawAttribute> = mutableListOf(),
    /** Нестандартные атрибуты внутри Code (`.codeattribute`): байт-в-байт, pc в них не пересчитываются. */
    val rawCodeAttributes: MutableList<SmaliRawAttribute> = mutableListOf()
)

/** Запись атрибута MethodParameters: имя (`null` — без имени) и флаги (final/synthetic/mandated). */
data class SmaliMethodParameter(val name: String?, val flags: Int = 0)
