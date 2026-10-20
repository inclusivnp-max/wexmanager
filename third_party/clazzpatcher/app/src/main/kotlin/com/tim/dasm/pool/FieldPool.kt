package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dex.model.FieldIdItem

class FieldPool(
    private val state: DexAsmState,
    private val strings: StringPool,
    private val types: TypePool
) {

    fun find(className: String, fieldName: String, fieldType: String): Int? {
        val classIdx = types.find(className) ?: return null
        val typeIdx = types.find(fieldType) ?: return null
        val nameIdx = strings.find(fieldName) ?: return null
        return state.fieldIndex[FieldIdItem(classIdx, typeIdx, nameIdx)]
    }

    fun add(className: String, fieldName: String, fieldType: String): Int {
        val item = FieldIdItem(types.add(className), types.add(fieldType), strings.add(fieldName))
        state.fieldIndex[item]?.let { return it }
        val idx = state.fieldIds.size
        state.fieldIds.add(item)
        state.fieldIndex[item] = idx
        return idx
    }

    fun get(idx: Int): FieldIdItem? = state.fieldIds.getOrNull(idx)
}
