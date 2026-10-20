package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dasm.model.ProtoKey
import com.tim.dex.model.ProtoIdItem

class ProtoPool(
    private val state: DexAsmState,
    private val strings: StringPool,
    private val types: TypePool,
    private val typeLists: TypeListPool
) {

    /**
     * Найти proto_idx. Требует, чтобы `returnType` и `paramTypes` уже были
     * в пуле типов — иначе вернёт null.
     */
    fun find(returnType: String, paramTypes: List<String>): Int? {
        val returnIdx = types.find(returnType) ?: return null
        val paramIdxs = ArrayList<Int>(paramTypes.size)
        for (t in paramTypes) paramIdxs.add(types.find(t) ?: return null)
        return state.protoIndex[ProtoKey(returnIdx, paramIdxs)]
    }

    /** Добавить proto. Создаёт type_list при необходимости. */
    fun add(returnType: String, paramTypes: List<String>): Int {
        val returnIdx = types.add(returnType)
        val paramIdxs = ArrayList<Int>(paramTypes.size)
        for (t in paramTypes) paramIdxs.add(types.add(t))
        val key = ProtoKey(returnIdx, paramIdxs)
        state.protoIndex[key]?.let { return it }

        val typeListIdx = if (paramIdxs.isEmpty()) -1 else typeLists.add(paramIdxs)
        val shortyIdx = strings.add(buildShorty(returnType, paramTypes))
        val idx = state.protoIds.size
        state.protoIds.add(ProtoIdItem(shortyIdx, returnIdx, typeListIdx))
        state.protoIndex[key] = idx
        return idx
    }

    fun getReturnType(protoIdx: Int): String? {
        val p = state.protoIds.getOrNull(protoIdx) ?: return null
        return types.getDescriptor(p.returnTypeIdx)
    }

    fun getParamTypes(protoIdx: Int): List<String> {
        val p = state.protoIds.getOrNull(protoIdx)
            ?: throw IllegalArgumentException("Нет proto с индексом $protoIdx")
        if (p.parametersTypeListIdx < 0) return emptyList()
        val list = state.typeLists.getOrNull(p.parametersTypeListIdx)
            ?: throw IllegalStateException("proto #$protoIdx ссылается на несуществующий type_list")
        return list.typeIdx.map {
            types.getDescriptor(it) ?: throw IllegalStateException("proto #$protoIdx: нет типа #$it")
        }
    }

    private fun buildShorty(returnType: String, paramTypes: List<String>): String {
        val sb = StringBuilder(paramTypes.size + 1)
        sb.append(shortyChar(returnType))
        for (t in paramTypes) sb.append(shortyChar(t))
        return sb.toString()
    }

    private fun shortyChar(type: String): Char = when (type[0]) {
        'V', 'Z', 'B', 'S', 'C', 'I', 'J', 'F', 'D' -> {
            require(type.length == 1) { "Некорректный дескриптор типа: $type" }
            type[0]
        }
        'L', '[' -> 'L'
        else -> throw IllegalArgumentException("Некорректный дескриптор типа: $type")
    }
}
