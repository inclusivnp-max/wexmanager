package com.tim.casm.frames

import com.tim.clazz.model.*

/** Чтение пула констант для анализа кода: ссылки на члены, типы ldc, имена классов. */
internal class PoolAccess(private val entry: (Int) -> ConstantPoolEntry?) {

    class MemberRef(val owner: String, val name: String, val descriptor: String)

    private fun fail(idx: Int, expected: String): Nothing {
        val e = entry(idx)
        throw IllegalArgumentException("#$idx в пуле констант: ожидался $expected, найдено ${e?.tagName ?: "пусто"}")
    }

    fun utf8(idx: Int): String = (entry(idx) as? ConstantUtf8Info ?: fail(idx, "CONSTANT_Utf8")).getString()

    fun className(idx: Int): String = utf8((entry(idx) as? ConstantClassInfo ?: fail(idx, "CONSTANT_Class")).nameIndex)

    private fun nameAndType(idx: Int): ConstantNameAndTypeInfo =
        entry(idx) as? ConstantNameAndTypeInfo ?: fail(idx, "CONSTANT_NameAndType")

    fun member(idx: Int): MemberRef {
        val ref = entry(idx) as? ConstantRefInfo ?: fail(idx, "ссылка на поле/метод")
        val nat = nameAndType(ref.nameAndTypeIndex)
        return MemberRef(className(ref.classIndex), utf8(nat.nameIndex), utf8(nat.descriptorIndex))
    }

    fun invokeDynamicDescriptor(idx: Int): String {
        val dyn = entry(idx) as? ConstantInvokeDynamicInfo ?: fail(idx, "CONSTANT_InvokeDynamic")
        return utf8(nameAndType(dyn.nameAndTypeIndex).descriptorIndex)
    }

    /** Тип значения, которое кладёт ldc/ldc_w/ldc2_w. */
    fun ldcType(idx: Int): VerType = when (val e = entry(idx)) {
        is ConstantIntegerInfo -> VerType.IntT
        is ConstantFloatInfo -> VerType.FloatT
        is ConstantLongInfo -> VerType.LongT
        is ConstantDoubleInfo -> VerType.DoubleT
        is ConstantStringInfo -> VerType.STRING
        is ConstantClassInfo -> VerType.CLASS
        is ConstantMethodTypeInfo -> VerType.METHOD_TYPE
        is ConstantMethodHandleInfo -> VerType.METHOD_HANDLE
        is ConstantDynamicInfo -> VerType.of(utf8(nameAndType(e.nameAndTypeIndex).descriptorIndex))
        else -> throw IllegalArgumentException("ldc #$idx: недопустимая запись пула ${e?.tagName ?: "пусто"}")
    }
}
