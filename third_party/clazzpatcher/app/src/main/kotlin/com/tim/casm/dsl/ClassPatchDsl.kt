package com.tim.casm.dsl

import com.tim.casm.Casm

class ClassPatchDsl(private val className: String, private val casm: Casm) {
    private val methodPatches = ArrayList<MethodPatchDsl>()

    fun method(name: String, block: MethodPatchDsl.() -> Unit) {
        val dsl = MethodPatchDsl(name, casm); dsl.block(); methodPatches.add(dsl)
    }

    fun method(name: String, descriptor: String, block: MethodPatchDsl.() -> Unit) {
        val dsl = MethodPatchDsl(name, casm, descriptor); dsl.block(); methodPatches.add(dsl)
    }

    internal fun apply() {
        val cf = casm.state.source
        val thisName = cf.className(cf.thisClass)
        require(thisName == className) { "Патч для $className, а в файле $thisName" }
        var current = cf
        for (patch in methodPatches) current = patch.apply(current)
        casm.state.replaceSource(current)
    }
}
