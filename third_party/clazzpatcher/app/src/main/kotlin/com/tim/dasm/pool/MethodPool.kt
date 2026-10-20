package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dex.model.MethodIdItem

class MethodPool(
    private val state: DexAsmState,
    private val strings: StringPool,
    private val types: TypePool,
    private val protos: ProtoPool
) {

    fun find(
        className: String,
        methodName: String,
        paramTypes: List<String>,
        returnType: String
    ): Int? {
        val classIdx = types.find(className) ?: return null
        val nameIdx = strings.find(methodName) ?: return null
        val protoIdx = protos.find(returnType, paramTypes) ?: return null
        return state.methodIndex[MethodIdItem(classIdx, protoIdx, nameIdx)]
    }

    fun add(
        className: String,
        methodName: String,
        paramTypes: List<String>,
        returnType: String
    ): Int {
        val item = MethodIdItem(types.add(className), protos.add(returnType, paramTypes), strings.add(methodName))
        state.methodIndex[item]?.let { return it }
        val idx = state.methodIds.size
        state.methodIds.add(item)
        state.methodIndex[item] = idx
        return idx
    }

    fun get(idx: Int): MethodIdItem? = state.methodIds.getOrNull(idx)
}
