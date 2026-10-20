package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dex.model.TypeListItem

class TypeListPool(private val state: DexAsmState) {

    /** Найти type_list_idx по списку type_idx. */
    fun find(typeIdxs: List<Int>): Int? = state.typeListIndex[typeIdxs]

    /** Добавить type_list. Если такой уже есть — вернуть его индекс. */
    fun add(typeIdxs: List<Int>): Int {
        state.typeListIndex[typeIdxs]?.let { return it }
        val copy = ArrayList(typeIdxs)
        val idx = state.typeLists.size
        state.typeLists.add(TypeListItem(copy.size, copy))
        state.typeListIndex[copy] = idx
        return idx
    }

    fun get(typeListIdx: Int): TypeListItem? = state.typeLists.getOrNull(typeListIdx)
}
