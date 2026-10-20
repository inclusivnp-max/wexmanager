package com.tim.clazz.parser

import com.tim.clazz.model.*
import com.tim.clazz.util.ClassFormatException
import com.tim.clazz.util.ClassInput

/**
 * Чтение пула констант (JVMS §4.4).
 */
internal object ConstantPoolReader {

    /** Список размером constant_pool_count; индекс 0 и второй слот Long/Double — null. */
    fun read(input: ClassInput): List<ConstantPoolEntry?> {
        val count = input.u2()
        if (count == 0) throw ClassFormatException("constant_pool_count = 0 (должен быть не меньше 1)")
        val pool = arrayOfNulls<ConstantPoolEntry>(count)
        var i = 1
        while (i < count) {
            val start = input.pos
            val entry = try {
                readEntry(input)
            } catch (e: ClassFormatException) {
                throw ClassFormatException("пул констант #$i (смещение $start): ${e.message}", e)
            }
            pool[i] = entry
            if (entry is ConstantLongInfo || entry is ConstantDoubleInfo) {
                if (i + 1 >= count) {
                    throw ClassFormatException("пул констант #$i: Long/Double в последнем слоте (constant_pool_count = $count)")
                }
                i += 2
            } else {
                i++
            }
        }
        return pool.asList()
    }

    private fun readEntry(input: ClassInput): ConstantPoolEntry {
        return when (val tag = input.u1()) {
            ConstantPoolTags.CONSTANT_Utf8 -> ConstantUtf8Info(input.bytes(input.u2()))
            ConstantPoolTags.CONSTANT_Integer -> ConstantIntegerInfo(input.s4())
            ConstantPoolTags.CONSTANT_Float -> ConstantFloatInfo(input.s4())
            ConstantPoolTags.CONSTANT_Long -> ConstantLongInfo(input.s8())
            ConstantPoolTags.CONSTANT_Double -> ConstantDoubleInfo(input.s8())
            ConstantPoolTags.CONSTANT_Class -> ConstantClassInfo(input.u2())
            ConstantPoolTags.CONSTANT_String -> ConstantStringInfo(input.u2())
            ConstantPoolTags.CONSTANT_Fieldref -> ConstantFieldrefInfo(input.u2(), input.u2())
            ConstantPoolTags.CONSTANT_Methodref -> ConstantMethodrefInfo(input.u2(), input.u2())
            ConstantPoolTags.CONSTANT_InterfaceMethodref -> ConstantInterfaceMethodrefInfo(input.u2(), input.u2())
            ConstantPoolTags.CONSTANT_NameAndType -> ConstantNameAndTypeInfo(input.u2(), input.u2())
            ConstantPoolTags.CONSTANT_MethodHandle -> ConstantMethodHandleInfo(input.u1(), input.u2())
            ConstantPoolTags.CONSTANT_MethodType -> ConstantMethodTypeInfo(input.u2())
            ConstantPoolTags.CONSTANT_Dynamic -> ConstantDynamicInfo(input.u2(), input.u2())
            ConstantPoolTags.CONSTANT_InvokeDynamic -> ConstantInvokeDynamicInfo(input.u2(), input.u2())
            ConstantPoolTags.CONSTANT_Module -> ConstantModuleInfo(input.u2())
            ConstantPoolTags.CONSTANT_Package -> ConstantPackageInfo(input.u2())
            else -> throw ClassFormatException("неизвестный тег пула констант: $tag")
        }
    }
}
