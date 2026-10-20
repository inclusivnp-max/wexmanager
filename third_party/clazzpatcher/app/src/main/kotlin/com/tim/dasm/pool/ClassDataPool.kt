package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dex.model.*

class ClassDataPool(private val state: DexAsmState) {

    /**
     * Изменяемые копии class_data, которые достраиваются через [addField]/[addMethod].
     * Элемент в [DexAsmState.classDataItems] после каждого добавления ссылается на эти
     * же списки, поэтому добавление члена — O(1), без копирования списков.
     */
    private val builders = HashMap<Int, Builder>()

    private class Builder(item: ClassDataItem) {
        val staticFields = ArrayList(item.staticFields)
        val instanceFields = ArrayList(item.instanceFields)
        val directMethods = ArrayList(item.directMethods)
        val virtualMethods = ArrayList(item.virtualMethods)
        val fieldIdxs = HashSet<Int>().apply {
            item.staticFields.forEach { add(it.fieldIdx) }
            item.instanceFields.forEach { add(it.fieldIdx) }
        }
        val methodIdxs = HashSet<Int>().apply {
            item.directMethods.forEach { add(it.methodIdx) }
            item.virtualMethods.forEach { add(it.methodIdx) }
        }

        fun snapshot() = ClassDataItem(
            staticFieldsSize = staticFields.size,
            instanceFieldsSize = instanceFields.size,
            directMethodsSize = directMethods.size,
            virtualMethodsSize = virtualMethods.size,
            staticFields = staticFields,
            instanceFields = instanceFields,
            directMethods = directMethods,
            virtualMethods = virtualMethods
        )
    }

    /** Добавить готовый ClassDataItem целиком. */
    fun add(item: ClassDataItem): Int {
        val idx = state.classDataItems.size
        state.classDataItems.add(item)
        return idx
    }

    /** Создать пустой ClassDataItem. */
    fun empty(): Int = add(
        ClassDataItem(
            staticFieldsSize = 0,
            instanceFieldsSize = 0,
            directMethodsSize = 0,
            virtualMethodsSize = 0,
            staticFields = emptyList(),
            instanceFields = emptyList(),
            directMethods = emptyList(),
            virtualMethods = emptyList()
        )
    )

    fun get(idx: Int): ClassDataItem? = state.classDataItems.getOrNull(idx)

    private fun builder(classDataIdx: Int): Builder =
        builders.getOrPut(classDataIdx) {
            Builder(
                state.classDataItems.getOrNull(classDataIdx)
                    ?: throw IllegalArgumentException("Нет class_data с индексом $classDataIdx")
            )
        }

    /** Добавить статическое/instance поле. Повторное добавление того же поля — ошибка. */
    fun addField(classDataIdx: Int, fieldIdx: Int, accessFlags: Int, isStatic: Boolean) {
        val b = builder(classDataIdx)
        require(b.fieldIdxs.add(fieldIdx)) { "Поле #$fieldIdx уже есть в class_data #$classDataIdx" }
        (if (isStatic) b.staticFields else b.instanceFields).add(EncodedField(fieldIdx, accessFlags))
        state.classDataItems[classDataIdx] = b.snapshot()
    }

    /** Добавить direct/virtual метод. Повторное добавление того же метода — ошибка. */
    fun addMethod(
        classDataIdx: Int,
        methodIdx: Int,
        accessFlags: Int,
        codeIdx: Int,
        isDirect: Boolean
    ) {
        val b = builder(classDataIdx)
        require(b.methodIdxs.add(methodIdx)) { "Метод #$methodIdx уже есть в class_data #$classDataIdx" }
        (if (isDirect) b.directMethods else b.virtualMethods).add(EncodedMethod(methodIdx, accessFlags, codeIdx))
        state.classDataItems[classDataIdx] = b.snapshot()
    }

    fun findEncodedMethod(classDataIdx: Int, methodIdx: Int): EncodedMethod? {
        val data = get(classDataIdx) ?: return null
        return data.directMethods.find { it.methodIdx == methodIdx }
            ?: data.virtualMethods.find { it.methodIdx == methodIdx }
    }

    fun findEncodedField(classDataIdx: Int, fieldIdx: Int): EncodedField? {
        val data = get(classDataIdx) ?: return null
        return data.staticFields.find { it.fieldIdx == fieldIdx }
            ?: data.instanceFields.find { it.fieldIdx == fieldIdx }
    }
}
