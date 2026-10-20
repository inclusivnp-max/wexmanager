package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dasm.model.MethodHandleKind
import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.CallSite
import com.tim.dex.model.CallSiteIdItem
import com.tim.dex.model.EncodedValue

/**
 * Пул call site (DEX 038+): `call_site_item` (encoded array) + `call_site_id_item`.
 *
 * Содержимое call site: bootstrap method handle, имя метода, method type и
 * дополнительные аргументы bootstrap-метода.
 */
class CallSitePool(
    private val state: DexAsmState,
    private val strings: StringPool,
    private val protos: ProtoPool
) {
    private data class Key(val name: String?, val callSite: CallSite)

    private val index = HashMap<Key, Int>()

    init {
        for (i in state.callSiteIds.indices) {
            val cs = state.callSiteItems.getOrNull(state.callSiteIds[i].callSiteIdx) ?: continue
            index.putIfAbsent(Key(null, cs), i)
        }
    }

    /**
     * Добавить call site и вернуть индекс его `call_site_id`.
     *
     * @param bootstrapHandleIdx индекс method handle bootstrap-метода (должен быть invoke-static).
     * @param extraArgs          дополнительные аргументы (закодированные значения).
     * @param name               необязательное имя call site (например, `call_site_3` из smali):
     *                           одинаковые call site с разными именами не объединяются.
     */
    fun add(
        bootstrapHandleIdx: Int,
        methodName: String,
        returnType: String,
        paramTypes: List<String>,
        extraArgs: List<EncodedValue> = emptyList(),
        name: String? = null
    ): Int {
        val handle = state.methodHandles.getOrNull(bootstrapHandleIdx)
            ?: throw IllegalArgumentException("call site: нет method handle #$bootstrapHandleIdx")
        require(handle.methodHandleType == MethodHandleKind.INVOKE_STATIC) {
            "call site: bootstrap-метод должен быть invoke-static, а не ${MethodHandleKind.name(handle.methodHandleType)}"
        }
        val args = ArrayList<EncodedValue>(extraArgs.size + 2)
        args.add(EncodedValue(EncodedValueType.VALUE_STRING, strings.add(methodName)))
        args.add(EncodedValue(EncodedValueType.VALUE_METHOD_TYPE, protos.add(returnType, paramTypes)))
        args.addAll(extraArgs)
        val callSite = CallSite(EncodedValue(EncodedValueType.VALUE_METHOD_HANDLE, bootstrapHandleIdx), args)

        val key = Key(name, callSite)
        index[key]?.let { return it }
        state.requireDexVersion(38)
        val itemIdx = state.callSiteItems.size
        state.callSiteItems.add(callSite)
        val idIdx = state.callSiteIds.size
        state.callSiteIds.add(CallSiteIdItem(itemIdx))
        index[key] = idIdx
        return idIdx
    }

    /**
     * Call site, уже бывшие в загруженном DEX, находятся и по имени `call_site_N` (N — их номер), как их
     * печатает дизассемблер: пересобранный из такого текста класс ссылается на прежние call site, а не
     * добавляет копии.
     */
    fun nameLoadedAsNumbered() {
        for (i in state.callSiteIds.indices) {
            val cs = state.callSiteItems.getOrNull(state.callSiteIds[i].callSiteIdx) ?: continue
            index.putIfAbsent(Key("call_site_$i", cs), i)
        }
    }

    fun get(callSiteIdIdx: Int): CallSite? =
        state.callSiteIds.getOrNull(callSiteIdIdx)?.let { state.callSiteItems.getOrNull(it.callSiteIdx) }
}
