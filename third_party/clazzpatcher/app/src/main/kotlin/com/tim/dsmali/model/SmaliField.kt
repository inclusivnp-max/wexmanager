package com.tim.dsmali.model

data class SmaliField(
    var accessFlags: MutableList<String> = mutableListOf(),
    var name: String = "",
    var type: String = "",
    val annotations: MutableList<SmaliAnnotation> = mutableListOf(),
    var initialValue: String? = null
) {
    /** Строка `.field` в тексте (с 1); 0 — не из текста. В equals не участвует. */
    var line: Int = 0
}
