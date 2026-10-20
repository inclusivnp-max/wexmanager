package com.tim.c2d.translate

import com.tim.c2d.C2dOptions
import com.tim.c2d.bytecode.ConstantPoolView
import com.tim.c2d.bytecode.Descriptors
import com.tim.c2d.bytecode.InvokeDynamicRef
import com.tim.c2d.bytecode.MemberRef
import com.tim.c2d.bytecode.PoolConstant
import com.tim.c2d.meta.NestAccess
import com.tim.dasm.DexAsm
import com.tim.dasm.model.MethodHandleKind
import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.EncodedValue

/**
 * Общее для всех методов класса: пулы DEX, пул констант класса и сведения о самом классе.
 * Переводит ссылки JVM (члены, method handle, call site) в индексы DEX.
 */
class ClassContext(
    val asm: DexAsm,
    val pool: ConstantPoolView,
    val options: C2dOptions,
    /** `имя + дескриптор` приватных методов класса: вызов такого метода — invoke-direct. */
    private val privateMethods: Set<String>,
    /** Мосты к private-методам соседей по гнезду. */
    val nest: NestAccess = NestAccess.NONE,
    /**
     * `имя + дескриптор` → имя private-копии: invokespecial собственного не-private метода. В JVM это
     * вызов без виртуального выбора, а invoke-direct к виртуальному методу ART отвергает (ICCE).
     */
    val specialCopies: Map<String, String> = emptyMap(),
    /** Классы лямбд из рассахаривания: вызов их фабрики встраивается ([MethodTranslator]). */
    val lambdas: Map<String, com.tim.c2d.desugar.LambdaFactory> = emptyMap()
) {
    val thisClass: String = pool.thisClass
    val thisDescriptor: String = Descriptors.ofClass(thisClass)

    fun isOwnPrivateMethod(ref: MemberRef): Boolean =
        ref.owner == thisClass && (ref.name + ref.descriptor) in privateMethods

    private val fieldIndexByPool = IntArray(pool.cf.constantPool.size) { -1 }
    private val methodIndexByPool = IntArray(pool.cf.constantPool.size) { -1 }

    fun fieldIndex(ref: MemberRef): Int = asm.fields.add(Descriptors.ofClass(ref.owner), ref.name, ref.descriptor)

    /** field_idx по индексу Fieldref в пуле класса (запоминается). */
    fun fieldIndex(poolIndex: Int): Int {
        val cached = fieldIndexByPool[poolIndex]
        if (cached >= 0) return cached
        return fieldIndex(pool.member(poolIndex)).also { fieldIndexByPool[poolIndex] = it }
    }

    fun methodIndex(owner: String, name: String, descriptor: String): Int {
        val type = Descriptors.method(descriptor)
        return asm.methods.add(Descriptors.ofClass(owner), name, type.params, type.returnType)
    }

    /** method_idx по индексу Methodref/InterfaceMethodref в пуле класса (запоминается). */
    fun methodIndex(poolIndex: Int): Int {
        val cached = methodIndexByPool[poolIndex]
        if (cached >= 0) return cached
        val ref = pool.member(poolIndex)
        val type = ref.methodType ?: throw IllegalArgumentException("#$poolIndex — не ссылка на метод")
        return asm.methods.add(Descriptors.ofClass(ref.owner), ref.name, type.params, type.returnType)
            .also { methodIndexByPool[poolIndex] = it }
    }

    fun protoIndex(descriptor: String): Int {
        val type = Descriptors.method(descriptor)
        return asm.protos.add(type.returnType, type.params)
    }

    /** method_handle_item по константе CONSTANT_MethodHandle (JVMS §4.4.8 → DEX method_handle_type). */
    fun methodHandleIndex(handle: PoolConstant.MethodHandleValue): Int {
        val ref = handle.member
        return when (handle.referenceKind) {
            REF_GET_FIELD -> asm.methodHandles.add(MethodHandleKind.INSTANCE_GET, fieldIndex(ref))
            REF_GET_STATIC -> asm.methodHandles.add(MethodHandleKind.STATIC_GET, fieldIndex(ref))
            REF_PUT_FIELD -> asm.methodHandles.add(MethodHandleKind.INSTANCE_PUT, fieldIndex(ref))
            REF_PUT_STATIC -> asm.methodHandles.add(MethodHandleKind.STATIC_PUT, fieldIndex(ref))
            REF_INVOKE_VIRTUAL -> methodHandle(MethodHandleKind.INVOKE_INSTANCE, ref)
            REF_INVOKE_STATIC -> methodHandle(MethodHandleKind.INVOKE_STATIC, ref)
            REF_INVOKE_SPECIAL -> methodHandle(MethodHandleKind.INVOKE_DIRECT, ref)
            REF_NEW_INVOKE_SPECIAL -> methodHandle(MethodHandleKind.INVOKE_CONSTRUCTOR, ref)
            REF_INVOKE_INTERFACE -> methodHandle(MethodHandleKind.INVOKE_INTERFACE, ref)
            else -> throw IllegalArgumentException("неизвестный reference_kind ${handle.referenceKind} у method handle")
        }
    }

    private fun methodHandle(kind: Int, ref: MemberRef): Int =
        asm.methodHandles.add(kind, methodIndex(ref.owner, ref.name, ref.descriptor))

    /** call_site_id для invokedynamic. */
    fun callSiteIndex(indy: InvokeDynamicRef): Int {
        val bsm = pool.constant(indy.bootstrap.bootstrapMethodRef) as? PoolConstant.MethodHandleValue
            ?: throw IllegalArgumentException("bootstrap-метод invokedynamic ${indy.name} — не CONSTANT_MethodHandle")
        if (bsm.referenceKind != REF_INVOKE_STATIC) {
            throw IllegalArgumentException(
                "bootstrap-метод invokedynamic ${indy.name} должен быть статическим (reference_kind ${bsm.referenceKind})"
            )
        }
        val type = Descriptors.method(indy.descriptor)
        val args = indy.bootstrap.bootstrapArguments.map { encode(pool.constant(it)) }
        return asm.callSites.add(methodHandleIndex(bsm), indy.name, type.returnType, type.params, args)
    }

    /** Статический аргумент bootstrap-метода → encoded_value. */
    private fun encode(c: PoolConstant): EncodedValue = when (c) {
        is PoolConstant.IntValue -> EncodedValue(EncodedValueType.VALUE_INT, c.value)
        is PoolConstant.FloatValue -> EncodedValue(EncodedValueType.VALUE_FLOAT, java.lang.Float.intBitsToFloat(c.bits))
        is PoolConstant.LongValue -> EncodedValue(EncodedValueType.VALUE_LONG, c.value)
        is PoolConstant.DoubleValue -> EncodedValue(EncodedValueType.VALUE_DOUBLE, java.lang.Double.longBitsToDouble(c.bits))
        is PoolConstant.StringValue -> EncodedValue(EncodedValueType.VALUE_STRING, asm.strings.add(c.value))
        is PoolConstant.ClassValue -> EncodedValue(EncodedValueType.VALUE_TYPE, asm.types.add(c.descriptor))
        is PoolConstant.MethodTypeValue -> EncodedValue(EncodedValueType.VALUE_METHOD_TYPE, protoIndex(c.descriptor))
        is PoolConstant.MethodHandleValue -> EncodedValue(EncodedValueType.VALUE_METHOD_HANDLE, methodHandleIndex(c))
    }

    companion object {
        const val REF_GET_FIELD = 1
        const val REF_GET_STATIC = 2
        const val REF_PUT_FIELD = 3
        const val REF_PUT_STATIC = 4
        const val REF_INVOKE_VIRTUAL = 5
        const val REF_INVOKE_STATIC = 6
        const val REF_INVOKE_SPECIAL = 7
        const val REF_NEW_INVOKE_SPECIAL = 8
        const val REF_INVOKE_INTERFACE = 9
    }
}
