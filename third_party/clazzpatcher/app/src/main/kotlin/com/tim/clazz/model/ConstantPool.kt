package com.tim.clazz.model

import com.tim.clazz.util.ModifiedUtf8

/**
 * Запись в пуле констант (JVMS §4.4).
 * В списке пула индекс 0 и второй слот Long/Double хранятся как null.
 */
sealed class ConstantPoolEntry {
    abstract val tag: Int

    /** Имя вида записи (`CONSTANT_Utf8`, …) для сообщений об ошибках. */
    val tagName: String get() = ConstantPoolTags.name(tag)

    /** Сколько слотов пула занимает запись: 2 для Long/Double (JVMS §4.4.5), иначе 1. */
    open val slots: Int get() = 1
}

/**
 * Строка в Modified UTF-8 (JVMS §4.4.7). Хранятся исходные байты — запись всегда байт-в-байт.
 * Массив [bytes] не изменять: декодированная строка кэшируется.
 */
data class ConstantUtf8Info(
    val bytes: ByteArray
) : ConstantPoolEntry() {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_Utf8

    private var decoded: String? = null

    /** Хеш содержимого (записи общие для пулов многих классов — считается один раз). */
    private var hash = 0

    /** Декодированная строка; некорректный Modified UTF-8 — ClassFormatException. */
    fun getString(): String = decoded ?: ModifiedUtf8.decode(bytes).also { decoded = it }

    val value: String get() = getString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConstantUtf8Info) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var h = hash
        if (h == 0) {
            h = bytes.contentHashCode()
            hash = h
        }
        return h
    }

    override fun toString(): String =
        "ConstantUtf8Info(${ModifiedUtf8.decodeOrNull(bytes) ?: "<некорректный Modified UTF-8, ${bytes.size} байт>"})"

    companion object {
        /** Запись из строки (кодируется в Modified UTF-8). */
        fun of(value: String): ConstantUtf8Info {
            val bytes = ModifiedUtf8.encode(value)
            require(bytes.size <= 0xFFFF) { "Строка длиннее 65535 байт в Modified UTF-8 (${bytes.size})" }
            return ConstantUtf8Info(bytes).also { it.decoded = value }
        }
    }
}

data class ConstantClassInfo(
    val nameIndex: Int
) : ConstantPoolEntry() {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_Class

    override fun hashCode(): Int = mixHash(tag, nameIndex)
}

sealed class ConstantRefInfo(
    open val classIndex: Int,
    open val nameAndTypeIndex: Int
) : ConstantPoolEntry()

data class ConstantFieldrefInfo(
    override val classIndex: Int,
    override val nameAndTypeIndex: Int
) : ConstantRefInfo(classIndex, nameAndTypeIndex) {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_Fieldref

    override fun hashCode(): Int = mixHash(tag, classIndex, nameAndTypeIndex)
}

data class ConstantMethodrefInfo(
    override val classIndex: Int,
    override val nameAndTypeIndex: Int
) : ConstantRefInfo(classIndex, nameAndTypeIndex) {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_Methodref

    override fun hashCode(): Int = mixHash(tag, classIndex, nameAndTypeIndex)
}

data class ConstantInterfaceMethodrefInfo(
    override val classIndex: Int,
    override val nameAndTypeIndex: Int
) : ConstantRefInfo(classIndex, nameAndTypeIndex) {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_InterfaceMethodref

    override fun hashCode(): Int = mixHash(tag, classIndex, nameAndTypeIndex)
}

data class ConstantStringInfo(
    val stringIndex: Int
) : ConstantPoolEntry() {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_String

    override fun hashCode(): Int = mixHash(tag, stringIndex)
}

data class ConstantIntegerInfo(
    val value: Int
) : ConstantPoolEntry() {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_Integer

    override fun hashCode(): Int = mixHash(tag, value)
}

/**
 * Float хранится битами: так сохраняются -0.0 и полезная нагрузка NaN.
 */
data class ConstantFloatInfo(
    val bits: Int
) : ConstantPoolEntry() {
    constructor(value: Float) : this(value.toRawBits())

    override val tag: Int get() = ConstantPoolTags.CONSTANT_Float

    override fun hashCode(): Int = mixHash(tag, bits)

    val value: Float get() = Float.fromBits(bits)
}

data class ConstantLongInfo(
    val value: Long
) : ConstantPoolEntry() {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_Long

    override fun hashCode(): Int = mixHash(tag, value.toInt(), (value ushr 32).toInt())
    override val slots: Int get() = 2
}

/**
 * Double хранится битами: так сохраняются -0.0 и полезная нагрузка NaN.
 */
data class ConstantDoubleInfo(
    val bits: Long
) : ConstantPoolEntry() {
    constructor(value: Double) : this(value.toRawBits())

    override val tag: Int get() = ConstantPoolTags.CONSTANT_Double

    override fun hashCode(): Int = mixHash(tag, bits.toInt(), (bits ushr 32).toInt())
    override val slots: Int get() = 2

    val value: Double get() = Double.fromBits(bits)
}

data class ConstantNameAndTypeInfo(
    val nameIndex: Int,
    val descriptorIndex: Int
) : ConstantPoolEntry() {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_NameAndType

    override fun hashCode(): Int = mixHash(tag, nameIndex, descriptorIndex)
}

data class ConstantMethodHandleInfo(
    val referenceKind: Int,
    val referenceIndex: Int
) : ConstantPoolEntry() {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_MethodHandle

    override fun hashCode(): Int = mixHash(tag, referenceKind, referenceIndex)
}

data class ConstantMethodTypeInfo(
    val descriptorIndex: Int
) : ConstantPoolEntry() {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_MethodType

    override fun hashCode(): Int = mixHash(tag, descriptorIndex)
}

/** CONSTANT_Dynamic (Java 11+, JVMS §4.4.10). */
data class ConstantDynamicInfo(
    val bootstrapMethodAttrIndex: Int,
    val nameAndTypeIndex: Int
) : ConstantPoolEntry() {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_Dynamic

    override fun hashCode(): Int = mixHash(tag, bootstrapMethodAttrIndex, nameAndTypeIndex)
}

data class ConstantInvokeDynamicInfo(
    val bootstrapMethodAttrIndex: Int,
    val nameAndTypeIndex: Int
) : ConstantPoolEntry() {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_InvokeDynamic

    override fun hashCode(): Int = mixHash(tag, bootstrapMethodAttrIndex, nameAndTypeIndex)
}

data class ConstantModuleInfo(
    val nameIndex: Int
) : ConstantPoolEntry() {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_Module

    override fun hashCode(): Int = mixHash(tag, nameIndex)
}

data class ConstantPackageInfo(
    val nameIndex: Int
) : ConstantPoolEntry() {
    override val tag: Int get() = ConstantPoolTags.CONSTANT_Package

    override fun hashCode(): Int = mixHash(tag, nameIndex)
}

/**
 * Хеш записи пула: тег и поля перемешиваются (у data class записи с разным тегом и одинаковыми
 * индексами давали бы один хеш, а близкие индексы — цепочки коллизий в HashMap).
 */
internal fun mixHash(tag: Int, a: Int, b: Int = 0): Int {
    var h = tag * -0x61c88647 + a
    h = h * -0x61c88647 + b
    return h xor (h ushr 16)
}

/**
 * Теги для пула констант
 */
object ConstantPoolTags {
    const val CONSTANT_Utf8 = 1
    const val CONSTANT_Integer = 3
    const val CONSTANT_Float = 4
    const val CONSTANT_Long = 5
    const val CONSTANT_Double = 6
    const val CONSTANT_Class = 7
    const val CONSTANT_String = 8
    const val CONSTANT_Fieldref = 9
    const val CONSTANT_Methodref = 10
    const val CONSTANT_InterfaceMethodref = 11
    const val CONSTANT_NameAndType = 12
    const val CONSTANT_MethodHandle = 15
    const val CONSTANT_MethodType = 16
    const val CONSTANT_Dynamic = 17
    const val CONSTANT_InvokeDynamic = 18
    const val CONSTANT_Module = 19
    const val CONSTANT_Package = 20

    fun name(tag: Int): String = when (tag) {
        CONSTANT_Utf8 -> "CONSTANT_Utf8"
        CONSTANT_Integer -> "CONSTANT_Integer"
        CONSTANT_Float -> "CONSTANT_Float"
        CONSTANT_Long -> "CONSTANT_Long"
        CONSTANT_Double -> "CONSTANT_Double"
        CONSTANT_Class -> "CONSTANT_Class"
        CONSTANT_String -> "CONSTANT_String"
        CONSTANT_Fieldref -> "CONSTANT_Fieldref"
        CONSTANT_Methodref -> "CONSTANT_Methodref"
        CONSTANT_InterfaceMethodref -> "CONSTANT_InterfaceMethodref"
        CONSTANT_NameAndType -> "CONSTANT_NameAndType"
        CONSTANT_MethodHandle -> "CONSTANT_MethodHandle"
        CONSTANT_MethodType -> "CONSTANT_MethodType"
        CONSTANT_Dynamic -> "CONSTANT_Dynamic"
        CONSTANT_InvokeDynamic -> "CONSTANT_InvokeDynamic"
        CONSTANT_Module -> "CONSTANT_Module"
        CONSTANT_Package -> "CONSTANT_Package"
        else -> "тег $tag"
    }
}
