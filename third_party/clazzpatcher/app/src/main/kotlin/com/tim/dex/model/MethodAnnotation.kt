package com.tim.dex.model

data class MethodAnnotation(
    val methodIdx: Int,
    val annotationsSetIdx: Int              // -1 = нет
)