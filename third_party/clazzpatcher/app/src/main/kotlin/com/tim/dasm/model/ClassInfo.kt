package com.tim.dasm.model

import com.tim.dex.model.AnnotationItem

data class ClassInfo(
    val name: String,
    val superClass: String?,
    val accessFlags: Int,
    val sourceFile: String?,
    val interfaces: List<String>,
    val annotations: List<AnnotationItem>,
    val methods: List<MethodInfo>,
    val fields: List<FieldInfo>
)