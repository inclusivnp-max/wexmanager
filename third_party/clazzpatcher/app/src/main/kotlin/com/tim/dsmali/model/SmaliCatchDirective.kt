package com.tim.dsmali.model

data class SmaliCatchDirective(
    val exceptionType: String?,
    val tryStart: String,
    val tryEnd: String,
    val handler: String
)
