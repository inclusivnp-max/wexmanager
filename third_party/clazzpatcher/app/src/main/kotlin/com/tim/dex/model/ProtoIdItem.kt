package com.tim.dex.model

data class ProtoIdItem(
    val shortyIdx: Int,
    val returnTypeIdx: Int,
    val parametersTypeListIdx: Int          // -1 = нет параметров
)