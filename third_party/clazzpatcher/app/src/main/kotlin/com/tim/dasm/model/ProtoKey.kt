package com.tim.dasm.model

/**
 * Ключ proto: (returnTypeIdx, paramTypeIdxs).
 * Используется вызывающей стороной для дедупликации на уровне модели.
 */
data class ProtoKey(
    val returnTypeIdx: Int,
    val paramTypeIdxs: List<Int>
)