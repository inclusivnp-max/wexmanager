package com.tim.casm.model

data class RawStackMapFrame(
    val frameType: Int,
    val offsetDelta: Int,
    val locals: List<RawVerificationType>,
    val stack: List<RawVerificationType>
)

data class RawVerificationType(
    val tag: Int,
    val descriptor: String? = null,
    val value: Int = 0
)
