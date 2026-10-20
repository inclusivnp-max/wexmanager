package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dex.model.CodeItem

class CodePool(private val state: DexAsmState) {

    fun add(item: CodeItem): Int {
        val idx = state.codeItems.size
        state.codeItems.add(item)
        return idx
    }

    fun replace(idx: Int, item: CodeItem) {
        state.codeItems[idx] = item
    }

    fun get(idx: Int): CodeItem? = state.codeItems.getOrNull(idx)
}