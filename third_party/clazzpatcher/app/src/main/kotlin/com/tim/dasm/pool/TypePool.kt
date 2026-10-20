package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dex.model.TypeIdItem

class TypePool(
    private val state: DexAsmState,
    private val strings: StringPool
) {

    /** Найти type_idx по дескриптору. */
    fun find(typeDesc: String): Int? = state.typeIndex[typeDesc]

    /** Добавить тип. Если есть — вернуть его индекс. */
    fun add(typeDesc: String): Int {
        state.typeIndex[typeDesc]?.let { return it }
        require(typeDesc.isNotEmpty()) { "Пустой дескриптор типа" }
        val descIdx = strings.add(typeDesc)
        val idx = state.typeIds.size
        state.typeIds.add(TypeIdItem(descIdx))
        state.typeIndex[typeDesc] = idx
        return idx
    }

    fun addNullable(typeDesc: String?): Int = if (typeDesc == null) -1 else add(typeDesc)

    /** Дескриптор типа по type_idx. */
    fun getDescriptor(typeIdx: Int): String? {
        val item = state.typeIds.getOrNull(typeIdx) ?: return null
        return strings.get(item.descriptorIdx)
    }
}
