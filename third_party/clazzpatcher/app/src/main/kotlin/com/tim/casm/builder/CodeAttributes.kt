package com.tim.casm.builder

import com.tim.casm.assembler.CodeAssembler
import com.tim.casm.model.ExceptionTableEntry
import com.tim.casm.model.LineNumberEntry
import com.tim.casm.model.LocalVariableEntry
import com.tim.casm.model.RawStackMapFrame
import com.tim.casm.pool.ConstantPoolBuilder
import com.tim.clazz.model.attributes.AttributeInfo
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.attributes.LineNumberTable
import com.tim.clazz.model.attributes.LocalVariableTable
import com.tim.clazz.model.attributes.LocalVariableTypeTable

/** Атрибут Code с таблицами строк, локалов (и их сигнатур) и StackMapTable. */
object CodeAttributes {

    /** [extra] — дополнительные атрибуты Code (type-аннотации кода, нестандартные атрибуты). */
    fun build(pool: ConstantPoolBuilder, a: CodeAssembler.Assembled, extra: List<AttributeInfo> = emptyList()): Code =
        build(pool, a.code, a.maxStack, a.maxLocals, a.exceptionTable, a.lineNumbers, a.localVariables, a.stackMapFrames, extra)

    fun build(
        pool: ConstantPoolBuilder,
        code: ByteArray,
        maxStack: Int,
        maxLocals: Int,
        exceptionTable: List<ExceptionTableEntry>,
        lineNumbers: List<LineNumberEntry>,
        localVariables: List<LocalVariableEntry>,
        frames: List<RawStackMapFrame>,
        extra: List<AttributeInfo> = emptyList()
    ): Code {
        val attrs = ArrayList<AttributeInfo>(4)
        if (lineNumbers.isNotEmpty()) {
            attrs.add(LineNumberTable(pool.utf8("LineNumberTable"), 0,
                lineNumbers.map { LineNumberTable.LineNumberTableEntry(it.startPc, it.lineNumber) }))
        }
        if (localVariables.isNotEmpty()) {
            attrs.add(LocalVariableTable(pool.utf8("LocalVariableTable"), 0, localVariables.map {
                LocalVariableTable.LocalVariableTableEntry(it.startPc, it.length, pool.utf8(it.name), pool.utf8(it.descriptor), it.index)
            }))
            val typed = localVariables.filter { it.signature != null }
            if (typed.isNotEmpty()) {
                attrs.add(LocalVariableTypeTable(pool.utf8("LocalVariableTypeTable"), 0, typed.map {
                    LocalVariableTypeTable.LocalVariableTypeTableEntry(it.startPc, it.length, pool.utf8(it.name), pool.utf8(it.signature!!), it.index)
                }))
            }
        }
        if (frames.isNotEmpty()) attrs.add(StackMaps.toAttribute(pool, frames))
        attrs.addAll(extra)
        val table = exceptionTable.map {
            Code.ExceptionTableEntry(it.startPc, it.endPc, it.handlerPc, it.catchType?.let { t -> pool.classInfo(t) } ?: 0)
        }
        return Code(pool.utf8("Code"), 0, maxStack, maxLocals, code, table, attrs)
    }
}
