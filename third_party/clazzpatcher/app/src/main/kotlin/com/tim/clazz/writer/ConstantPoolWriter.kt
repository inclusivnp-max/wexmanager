package com.tim.clazz.writer

import com.tim.clazz.model.*
import com.tim.clazz.util.ClassFormatException
import com.tim.clazz.util.ClassOutput

/**
 * Запись пула констант (JVMS §4.4) с проверкой раскладки слотов.
 */
internal object ConstantPoolWriter {

    fun write(out: ClassOutput, pool: List<ConstantPoolEntry?>) {
        val count = pool.size
        if (count == 0 || count > 0xFFFF) {
            throw ClassFormatException("размер пула констант $count вне диапазона 1..65535 (с учётом нулевого слота)")
        }
        if (pool[0] != null) throw ClassFormatException("слот #0 пула констант должен быть null")
        out.u2(count)
        var i = 1
        while (i < count) {
            val entry = pool[i] ?: throw ClassFormatException(
                "пустой слот #$i пула констант (null допустим только после Long/Double)"
            )
            try {
                writeEntry(out, entry)
            } catch (e: ClassFormatException) {
                throw ClassFormatException("пул констант #$i: ${e.message}", e)
            }
            if (entry is ConstantLongInfo || entry is ConstantDoubleInfo) {
                if (i + 1 >= count) {
                    throw ClassFormatException("пул констант #$i: у Long/Double нет второго слота (размер пула $count)")
                }
                if (pool[i + 1] != null) {
                    throw ClassFormatException("пул констант #${i + 1}: второй слот Long/Double #$i должен быть null")
                }
                i += 2
            } else {
                i++
            }
        }
    }

    /** Тег пишется константой в каждой ветке: без мегаморфного вызова entry.tag. */
    private fun writeEntry(out: ClassOutput, entry: ConstantPoolEntry) {
        when (entry) {
            is ConstantUtf8Info -> {
                out.u1(ConstantPoolTags.CONSTANT_Utf8)
                out.u2(entry.bytes.size)
                out.bytes(entry.bytes)
            }
            is ConstantMethodrefInfo -> writeRef(out, ConstantPoolTags.CONSTANT_Methodref, entry)
            is ConstantClassInfo -> writeU2(out, ConstantPoolTags.CONSTANT_Class, entry.nameIndex)
            is ConstantNameAndTypeInfo -> {
                out.u1(ConstantPoolTags.CONSTANT_NameAndType)
                out.u2(entry.nameIndex)
                out.u2(entry.descriptorIndex)
            }
            is ConstantStringInfo -> writeU2(out, ConstantPoolTags.CONSTANT_String, entry.stringIndex)
            is ConstantFieldrefInfo -> writeRef(out, ConstantPoolTags.CONSTANT_Fieldref, entry)
            is ConstantInterfaceMethodrefInfo -> writeRef(out, ConstantPoolTags.CONSTANT_InterfaceMethodref, entry)
            is ConstantIntegerInfo -> {
                out.u1(ConstantPoolTags.CONSTANT_Integer)
                out.s4(entry.value)
            }
            is ConstantFloatInfo -> {
                out.u1(ConstantPoolTags.CONSTANT_Float)
                out.s4(entry.bits)
            }
            is ConstantLongInfo -> {
                out.u1(ConstantPoolTags.CONSTANT_Long)
                out.s8(entry.value)
            }
            is ConstantDoubleInfo -> {
                out.u1(ConstantPoolTags.CONSTANT_Double)
                out.s8(entry.bits)
            }
            is ConstantMethodHandleInfo -> {
                out.u1(ConstantPoolTags.CONSTANT_MethodHandle)
                out.u1(entry.referenceKind)
                out.u2(entry.referenceIndex)
            }
            is ConstantMethodTypeInfo -> writeU2(out, ConstantPoolTags.CONSTANT_MethodType, entry.descriptorIndex)
            is ConstantDynamicInfo -> {
                out.u1(ConstantPoolTags.CONSTANT_Dynamic)
                out.u2(entry.bootstrapMethodAttrIndex)
                out.u2(entry.nameAndTypeIndex)
            }
            is ConstantInvokeDynamicInfo -> {
                out.u1(ConstantPoolTags.CONSTANT_InvokeDynamic)
                out.u2(entry.bootstrapMethodAttrIndex)
                out.u2(entry.nameAndTypeIndex)
            }
            is ConstantModuleInfo -> writeU2(out, ConstantPoolTags.CONSTANT_Module, entry.nameIndex)
            is ConstantPackageInfo -> writeU2(out, ConstantPoolTags.CONSTANT_Package, entry.nameIndex)
        }
    }

    private fun writeU2(out: ClassOutput, tag: Int, value: Int) {
        out.u1(tag)
        out.u2(value)
    }

    private fun writeRef(out: ClassOutput, tag: Int, entry: ConstantRefInfo) {
        out.u1(tag)
        out.u2(entry.classIndex)
        out.u2(entry.nameAndTypeIndex)
    }
}
