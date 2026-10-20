package com.tim.csmali.model

data class SmaliAnnotation(
    var visibility: String = "runtime",
    var type: String = "",
    val elements: MutableList<SmaliAnnotationElement> = mutableListOf(),
    var isSub: Boolean = false
)

data class SmaliAnnotationElement(
    var name: String = "",
    var value: String = ""
)

sealed class SmaliBodyElement

data class SmaliInsn(val text: String) : SmaliBodyElement()
data class SmaliLabel(val name: String) : SmaliBodyElement()

data class SmaliDebug(
    val kind: com.tim.csmali.data.SmaliDebugKind,
    val raw: String
) : SmaliBodyElement()

data class SmaliCatchDirective(
    val exceptionType: String?,
    val tryStart: String,
    val tryEnd: String,
    val handler: String
)