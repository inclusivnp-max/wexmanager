package com.tim.c2d.bytecode

import com.tim.clazz.model.*
import com.tim.clazz.model.attributes.BootstrapMethods

/** Ссылка на поле или метод из пула констант. */
class MemberRef(
    val owner: String,
    val name: String,
    val descriptor: String,
    /** CONSTANT_InterfaceMethodref. */
    val isInterface: Boolean
) {
    /** Разобранный дескриптор метода (`null` у полей) — разбирается один раз на запись пула. */
    val methodType: Descriptors.MethodType? = if (descriptor.startsWith("(")) Descriptors.method(descriptor) else null
}

/** Константа `ldc`/`ldc2_w` или статический аргумент bootstrap-метода. */
sealed class PoolConstant {
    class IntValue(val value: Int) : PoolConstant()
    class FloatValue(val bits: Int) : PoolConstant()
    class LongValue(val value: Long) : PoolConstant()
    class DoubleValue(val bits: Long) : PoolConstant()
    class StringValue(val value: String) : PoolConstant()
    /** Класс: дескриптор типа (`Ljava/lang/String;`, `[I`). */
    class ClassValue(val descriptor: String) : PoolConstant()
    class MethodTypeValue(val descriptor: String) : PoolConstant()
    class MethodHandleValue(val referenceKind: Int, val member: MemberRef) : PoolConstant()
}

/** invokedynamic: bootstrap-метод и вызываемые имя/тип. */
class InvokeDynamicRef(
    val bootstrap: BootstrapMethods.BootstrapMethod,
    val name: String,
    val descriptor: String
)

/**
 * Типизированный доступ к пулу констант класса с кэшированием ссылок на члены
 * (одни и те же индексы встречаются в коде многократно).
 */
class ConstantPoolView(val cf: ClassFile) {
    private val pool = cf.constantPool
    private val members = arrayOfNulls<MemberRef>(pool.size)
    private val bootstrapMethods: List<BootstrapMethods.BootstrapMethod> =
        cf.attributes.filterIsInstance<BootstrapMethods>().firstOrNull()?.bootstrapMethods ?: emptyList()

    val thisClass: String = cf.thisClassName

    fun utf8(index: Int): String = cf.utf8(index)

    /** Внутреннее имя класса из CONSTANT_Class. */
    fun className(index: Int): String = cf.className(index)

    /** Дескриптор типа по CONSTANT_Class. */
    fun classDescriptor(index: Int): String = Descriptors.ofClass(className(index))

    fun member(index: Int): MemberRef {
        members.getOrNull(index)?.let { return it }
        val ref = pool.getOrNull(index) as? ConstantRefInfo
            ?: throw IllegalArgumentException("#$index в пуле констант — не ссылка на поле или метод")
        val nat = cf.entry<ConstantNameAndTypeInfo>(ref.nameAndTypeIndex)
        val m = MemberRef(
            owner = className(ref.classIndex),
            name = utf8(nat.nameIndex),
            descriptor = utf8(nat.descriptorIndex),
            isInterface = ref is ConstantInterfaceMethodrefInfo
        )
        members[index] = m
        return m
    }

    fun constant(index: Int): PoolConstant = when (val e = pool.getOrNull(index)) {
        is ConstantIntegerInfo -> PoolConstant.IntValue(e.value)
        is ConstantFloatInfo -> PoolConstant.FloatValue(e.bits)
        is ConstantLongInfo -> PoolConstant.LongValue(e.value)
        is ConstantDoubleInfo -> PoolConstant.DoubleValue(e.bits)
        is ConstantStringInfo -> PoolConstant.StringValue(utf8(e.stringIndex))
        is ConstantClassInfo -> PoolConstant.ClassValue(Descriptors.ofClass(utf8(e.nameIndex)))
        is ConstantMethodTypeInfo -> PoolConstant.MethodTypeValue(utf8(e.descriptorIndex))
        is ConstantMethodHandleInfo -> PoolConstant.MethodHandleValue(e.referenceKind, member(e.referenceIndex))
        is ConstantDynamicInfo -> throw IllegalArgumentException(
            "#$index: CONSTANT_Dynamic (динамические константы) не поддерживается в DEX"
        )
        null -> throw IllegalArgumentException("#$index вне пула констант")
        else -> throw IllegalArgumentException("#$index: $e не может быть константой ldc")
    }

    fun invokeDynamic(index: Int): InvokeDynamicRef {
        val e = cf.entry<ConstantInvokeDynamicInfo>(index)
        val nat = cf.entry<ConstantNameAndTypeInfo>(e.nameAndTypeIndex)
        val bsm = bootstrapMethods.getOrNull(e.bootstrapMethodAttrIndex)
            ?: throw IllegalArgumentException(
                "invokedynamic #$index: нет bootstrap-метода #${e.bootstrapMethodAttrIndex} (всего ${bootstrapMethods.size})"
            )
        return InvokeDynamicRef(bsm, utf8(nat.nameIndex), utf8(nat.descriptorIndex))
    }
}
