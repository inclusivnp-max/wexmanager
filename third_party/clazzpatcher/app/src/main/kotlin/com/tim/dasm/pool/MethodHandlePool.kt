package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dasm.model.MethodHandleKind
import com.tim.dex.model.MethodHandleItem

/** Пул `method_handle_item` (DEX 038+). */
class MethodHandlePool(
    private val state: DexAsmState,
    private val fields: FieldPool,
    private val methods: MethodPool
) {

    /**
     * Добавить method handle.
     *
     * @param kind      тип из [MethodHandleKind].
     * @param memberIdx field_idx для STATIC_PUT..INSTANCE_GET, иначе method_idx.
     */
    fun add(kind: Int, memberIdx: Int): Int {
        MethodHandleKind.name(kind)
        if (MethodHandleKind.isField(kind)) {
            require(memberIdx in state.fieldIds.indices) { "method handle: нет поля #$memberIdx" }
        } else {
            require(memberIdx in state.methodIds.indices) { "method handle: нет метода #$memberIdx" }
        }
        val item = MethodHandleItem(kind, 0, memberIdx, 0)
        state.methodHandleIndex[item]?.let { return it }
        state.requireDexVersion(38)
        val idx = state.methodHandles.size
        state.methodHandles.add(item)
        state.methodHandleIndex[item] = idx
        return idx
    }

    /** Method handle на метод (`invoke-static@Lcls;->m()V` и т.п.). */
    fun addMethod(kind: Int, className: String, name: String, paramTypes: List<String>, returnType: String): Int {
        require(!MethodHandleKind.isField(kind)) { "${MethodHandleKind.name(kind)} ссылается на поле, а не на метод" }
        return add(kind, methods.add(className, name, paramTypes, returnType))
    }

    /** Method handle на поле (`static-get@Lcls;->f:I` и т.п.). */
    fun addField(kind: Int, className: String, name: String, fieldType: String): Int {
        require(MethodHandleKind.isField(kind)) { "${MethodHandleKind.name(kind)} ссылается на метод, а не на поле" }
        return add(kind, fields.add(className, name, fieldType))
    }

    fun get(idx: Int): MethodHandleItem? = state.methodHandles.getOrNull(idx)
}
