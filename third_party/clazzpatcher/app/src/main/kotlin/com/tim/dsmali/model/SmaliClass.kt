package com.tim.dsmali.model

data class SmaliClass(
    val descriptor: String,
    var accessFlags: MutableList<String> = mutableListOf(),
    var superClass: String? = "Ljava/lang/Object;",
    var sourceFile: String? = null,
    val interfaces: MutableList<String> = mutableListOf(),
    val annotations: MutableList<SmaliAnnotation> = mutableListOf(),
    val fields: MutableList<SmaliField> = mutableListOf(),
    val methods: MutableList<SmaliMethod> = mutableListOf()
)
