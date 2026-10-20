package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dex.model.*

class MiscPool(private val state: DexAsmState) {

    fun addHiddenApi(item: HiddenApiClassData): Int {
        val idx = state.hiddenApiClassDataItems.size
        state.hiddenApiClassDataItems.add(item)
        return idx
    }
}
