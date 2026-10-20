package com.tim.dex.model

data class ParameterAnnotation(
    val methodIdx: Int,
    val annotationsSetRefListIdx: Int       // -1 = нет
)