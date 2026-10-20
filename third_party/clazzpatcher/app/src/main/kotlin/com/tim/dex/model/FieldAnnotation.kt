package com.tim.dex.model

data class FieldAnnotation(
    val fieldIdx: Int,
    val annotationsSetIdx: Int              // -1 = нет
)