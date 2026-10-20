package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dex.model.DebugInfoItem

class DebugInfoPool(private val state: DexAsmState) {

    fun add(item: DebugInfoItem): Int {
        val idx = state.debugInfoItems.size
        state.debugInfoItems.add(item)
        return idx
    }

    fun get(idx: Int): DebugInfoItem? = state.debugInfoItems.getOrNull(idx)
}