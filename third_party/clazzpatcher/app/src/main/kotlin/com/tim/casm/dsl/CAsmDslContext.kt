package com.tim.casm.dsl

import com.tim.casm.Casm
import com.tim.casm.builder.ClassFileBuilder
import com.tim.casm.model.ClassInfo

class CasmDslContext internal constructor(private val casm: Casm) {
    private val classes = ArrayList<ClassInfo>()
    private val patches = ArrayList<() -> Unit>()

    fun createClass(name: String, block: ClassDsl.() -> Unit) {
        val dsl = ClassDsl(name, casm)
        dsl.block()
        classes.add(dsl.build())
    }

    fun patchClass(name: String, block: ClassPatchDsl.() -> Unit) {
        val dsl = ClassPatchDsl(name, casm); dsl.block(); patches.add { dsl.apply() }
    }

    internal fun apply() {
        val builder = ClassFileBuilder(casm.state)
        if (classes.isNotEmpty()) {
            require(classes.size == 1) { "Casm пока поддерживает один класс на файл (получено ${classes.size})" }
            require(casm.state.source.thisClass == 0) {
                "createClass нельзя использовать на загруженном классе; используйте patchClass"
            }
            val built = builder.materializeClass(classes[0])
            casm.state.replaceSource(built)
        }
        for (p in patches) p()
    }
}
