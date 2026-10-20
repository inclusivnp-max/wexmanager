package com.tim.dsmali.model

data class SmaliMethod(
    var accessFlags: MutableList<String> = mutableListOf(),
    var name: String = "",
    var returnType: String = "V",
    val paramTypes: MutableList<String> = mutableListOf(),
    val annotations: MutableList<SmaliAnnotation> = mutableListOf(),
    val parameterAnnotations: MutableMap<Int, MutableList<SmaliAnnotation>> = mutableMapOf(),
    var registers: Int = -1,
    var locals: Int = -1,
    val paramNames: MutableList<String?> = mutableListOf(),
    val body: MutableList<SmaliBodyElement> = mutableListOf(),
    val catches: MutableList<SmaliCatchDirective> = mutableListOf()
) {
    /** Строка `.method` в тексте (с 1); 0 — не из текста. В equals не участвует. */
    var line: Int = 0
}
