package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dex.model.EncodedValue

class EncodedArrayPool(private val state: DexAsmState) {

    fun add(values: List<EncodedValue>): Int {
        val idx = state.encodedArrayItems.size
        state.encodedArrayItems.add(values)
        return idx
    }

    fun get(idx: Int): List<EncodedValue>? = state.encodedArrayItems.getOrNull(idx)
}