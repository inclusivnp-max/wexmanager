package com.tim.dsmali.model

data class MethodRef(
    val cls: String,
    val name: String,
    val ret: String,
    val params: List<String>
)
