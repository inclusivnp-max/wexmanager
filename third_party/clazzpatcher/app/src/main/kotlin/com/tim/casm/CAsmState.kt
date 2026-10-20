package com.tim.casm

import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.pool.BootstrapMethodPool
import com.tim.casm.pool.ConstantPoolBuilder
import com.tim.clazz.model.ClassFile

/** Изменяемое состояние сборки одного класса: исходный класс, пул констант, bootstrap-методы. */
class CasmState(source: ClassFile) {
    var source: ClassFile = source
        private set

    var pool = ConstantPoolBuilder(source.constantPool)
        private set

    var bootstrapPool = BootstrapMethodPool(pool).also { it.loadExisting(source) }
        private set

    /** Иерархия классов для слияния типов во фреймах StackMapTable. */
    var hierarchy: ClassHierarchy = ClassHierarchy.EMPTY

    var requiredMajorVersion: Int = 45
        private set

    fun requireVersion(major: Int) {
        if (major > requiredMajorVersion) requiredMajorVersion = major
    }

    val majorVersion: Int get() = maxOf(source.majorVersion, requiredMajorVersion)

    /** Режим StackMapTable по версии класса. */
    val frameMode: com.tim.casm.frames.FrameMode get() = com.tim.casm.frames.FrameMode.forVersion(majorVersion)

    fun replaceSource(newSource: ClassFile) {
        source = newSource
        pool = ConstantPoolBuilder(newSource.constantPool)
        bootstrapPool = BootstrapMethodPool(pool).also { it.loadExisting(newSource) }
    }
}
