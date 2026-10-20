package com.tim.dasm.model

data class TryBlock(
    val startLabel: String,
    val endLabel: String,
    val handlerLabel: String,
    val exceptionType: String?
)
