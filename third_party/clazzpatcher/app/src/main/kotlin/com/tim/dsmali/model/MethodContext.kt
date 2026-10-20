package com.tim.dsmali.model

data class MethodContext(
    val registersSize: Int,
    val insSize: Int,
    val isStatic: Boolean,
    val paramTypes: List<String>
)
