package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dex.model.ClassDefItem

class ClassPool(
    private val state: DexAsmState,
    private val types: TypePool
) {

    /** Найти class_def_idx по имени класса. */
    fun find(className: String): Int? {
        val classIdx = types.find(className) ?: return null
        return state.classDefIndex[classIdx]
    }

    /** Добавить class_def_item. Повторное определение класса — ошибка. */
    fun add(
        className: String,
        superClass: String?,
        accessFlags: Int,
        interfacesTypeListIdx: Int,
        sourceFileIdx: Int,
        classDataIdx: Int
    ): Int {
        val classIdx = types.add(className)
        require(classIdx !in state.classDefIndex) { "Класс $className уже определён" }
        val superIdx = superClass?.let { types.add(it) } ?: -1
        val idx = state.classDefs.size
        state.classDefs.add(
            ClassDefItem(
                classIdx = classIdx,
                accessFlags = accessFlags,
                superclassIdx = superIdx,
                interfacesTypeListIdx = interfacesTypeListIdx,
                sourceFileIdx = sourceFileIdx,
                annotationsDirectoryIdx = -1,
                classDataIdx = classDataIdx,
                staticValuesIdx = -1
            )
        )
        state.classDefIndex[classIdx] = idx
        return idx
    }

    /** Заменить class_def_item. Единственный «не-append» мутатор. */
    fun replace(classDefIdx: Int, transform: (ClassDefItem) -> ClassDefItem) {
        val old = state.classDefs[classDefIdx]
        val new = transform(old)
        if (new.classIdx != old.classIdx) {
            require(new.classIdx !in state.classDefIndex) {
                "Класс с type_idx ${new.classIdx} уже определён"
            }
            if (state.classDefIndex[old.classIdx] == classDefIdx) state.classDefIndex.remove(old.classIdx)
            state.classDefIndex[new.classIdx] = classDefIdx
        }
        state.classDefs[classDefIdx] = new
    }

    fun get(classDefIdx: Int): ClassDefItem? = state.classDefs.getOrNull(classDefIdx)
}
