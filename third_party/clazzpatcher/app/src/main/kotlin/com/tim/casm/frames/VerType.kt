package com.tim.casm.frames

import com.tim.clazz.model.attributes.VerificationTypeInfo

/**
 * Тип верификатора (JVMS §4.10.1.2). Ссылки хранят дескриптор поля: `Ljava/lang/String;` или `[I`.
 * Long/Double занимают в локалах два слота: второй — [Top].
 */
internal sealed class VerType(val tag: Int) {
    object Top : VerType(VerificationTypeInfo.ITEM_Top)
    object IntT : VerType(VerificationTypeInfo.ITEM_Integer)
    object FloatT : VerType(VerificationTypeInfo.ITEM_Float)
    object LongT : VerType(VerificationTypeInfo.ITEM_Long)
    object DoubleT : VerType(VerificationTypeInfo.ITEM_Double)
    object Null : VerType(VerificationTypeInfo.ITEM_Null)
    object UninitThis : VerType(VerificationTypeInfo.ITEM_UninitializedThis)

    /** Адрес возврата jsr (только в анализе без фреймов: классы до версии 51). */
    object ReturnAddress : VerType(VerificationTypeInfo.ITEM_Top)

    data class Ref(val descriptor: String) : VerType(VerificationTypeInfo.ITEM_Object)
    data class Uninit(val newPc: Int) : VerType(VerificationTypeInfo.ITEM_Uninitialized)

    /** Размер в словах стека / слотах локалов. */
    val size: Int get() = if (this === LongT || this === DoubleT) 2 else 1

    val isReference: Boolean get() = this is Ref || this === Null || this is Uninit || this === UninitThis

    override fun toString(): String = when (this) {
        Top -> "top"
        IntT -> "int"
        FloatT -> "float"
        LongT -> "long"
        DoubleT -> "double"
        Null -> "null"
        UninitThis -> "uninitializedThis"
        ReturnAddress -> "returnAddress"
        is Ref -> descriptor
        is Uninit -> "uninitialized($newPc)"
    }

    companion object {
        // Экземпляры подклассов в инициализаторе VerType создавать нельзя: при расчёте фреймов
        // из нескольких потоков инициализация VerType и Ref взаимно блокируется.
        val OBJECT: Ref get() = KnownVerTypes.OBJECT
        val STRING: Ref get() = KnownVerTypes.STRING
        val CLASS: Ref get() = KnownVerTypes.CLASS
        val THROWABLE: Ref get() = KnownVerTypes.THROWABLE
        val METHOD_TYPE: Ref get() = KnownVerTypes.METHOD_TYPE
        val METHOD_HANDLE: Ref get() = KnownVerTypes.METHOD_HANDLE

        /** Тип значения по дескриптору поля / возврата. */
        fun of(descriptor: String): VerType = when (descriptor[0]) {
            'Z', 'B', 'C', 'S', 'I' -> IntT
            'J' -> LongT
            'F' -> FloatT
            'D' -> DoubleT
            'L', '[' -> Ref(descriptor)
            else -> throw IllegalArgumentException("Некорректный дескриптор типа: $descriptor")
        }

        /** Дескриптор по имени из CONSTANT_Class (`java/lang/String` или `[I`). */
        fun ofClassName(name: String): Ref = Ref(if (name.startsWith("[")) name else "L$name;")
    }
}

/** Часто нужные ссылочные типы (отдельно от [VerType] — см. комментарий в его companion). */
private object KnownVerTypes {
    val OBJECT = VerType.Ref("Ljava/lang/Object;")
    val STRING = VerType.Ref("Ljava/lang/String;")
    val CLASS = VerType.Ref("Ljava/lang/Class;")
    val THROWABLE = VerType.Ref("Ljava/lang/Throwable;")
    val METHOD_TYPE = VerType.Ref("Ljava/lang/invoke/MethodType;")
    val METHOD_HANDLE = VerType.Ref("Ljava/lang/invoke/MethodHandle;")
}
