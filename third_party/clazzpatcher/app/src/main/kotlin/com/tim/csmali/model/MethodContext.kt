package com.tim.csmali.model

data class MethodContext(
    val maxLocals: Int,
    val insSize: Int,
    val isStatic: Boolean,
    val paramTypes: List<String>
)