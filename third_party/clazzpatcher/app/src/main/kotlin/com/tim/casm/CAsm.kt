package com.tim.casm

import com.tim.casm.builder.ClassFileBuilder
import com.tim.casm.dsl.CasmDslContext
import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.clazz.model.ClassFile
import com.tim.clazz.parser.ClassReader
import com.tim.clazz.writer.ClassWriter
import java.io.File

/**
 * Создание и патчинг JVM-классов: `Casm.create(...).useDsl { createClass(...) { ... } }`
 * или `Casm.load(file).useDsl { patchClass(...) { ... } }`.
 */
class Casm internal constructor(val state: CasmState) {

    companion object {
        fun load(file: File, hierarchy: ClassHierarchy = ClassHierarchy.EMPTY): Casm =
            load(ClassReader().read(file), hierarchy)

        fun load(classFile: ClassFile, hierarchy: ClassHierarchy = ClassHierarchy.EMPTY): Casm =
            Casm(CasmState(classFile).also { it.hierarchy = hierarchy })

        /** Пустое состояние для `createClass`; [majorVersion] — версия формата класса (52 — Java 8). */
        fun create(majorVersion: Int = 52, hierarchy: ClassHierarchy = ClassHierarchy.EMPTY): Casm {
            require(majorVersion in 45..70) { "Неподдерживаемая версия класса $majorVersion" }
            val empty = ClassFile(
                magic = 0xCAFEBABEL,
                minorVersion = 0,
                majorVersion = majorVersion,
                constantPool = listOf(null),
                accessFlags = 0,
                thisClass = 0,
                superClass = 0,
                interfaces = emptyList(),
                fields = emptyList(),
                methods = emptyList(),
                attributes = emptyList()
            )
            return Casm(CasmState(empty).also { it.hierarchy = hierarchy })
        }

        @Deprecated("Имя и суперкласс задаются в createClass", ReplaceWith("create(majorVersion)"))
        fun create(internalName: String, superClass: String = "java/lang/Object", accessFlags: Int = 0, majorVersion: Int = 52): Casm =
            create(majorVersion)
    }

    fun build(): ClassFile = ClassFileBuilder(state).build()

    fun toByteArray(): ByteArray = ClassWriter(build()).toByteArray()

    fun save(file: File) = ClassWriter(build()).write(file)

    fun useDsl(block: CasmDslContext.() -> Unit) {
        val ctx = CasmDslContext(this)
        ctx.block()
        ctx.apply()
    }
}
