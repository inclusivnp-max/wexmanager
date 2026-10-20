package com.tim.dex.model

data class EncodedMethod(
    val methodIdx: Int,
    val accessFlags: Int,
    val codeIdx: Int                        // -1 = нет кода (abstract / native)
)