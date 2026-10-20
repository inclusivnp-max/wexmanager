package com.tim.clazz.model.attributes

/**
 * Базовый sealed класс для всех атрибутов (JVMS §4.7).
 * [attributeLength] — длина из исходного файла; при записи длина всегда пересчитывается.
 */
sealed class AttributeInfo(
    open val attributeNameIndex: Int,
    open val attributeLength: Long
)

/**
 * Атрибут, который не распознаётся в своём месте (нестандартный или стандартный вне своего
 * контекста, JVMS §4.7.1): хранится и записывается байт-в-байт.
 */
data class UnknownAttribute(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val data: ByteArray = ByteArray(0)
) : AttributeInfo(attributeNameIndex, attributeLength) {
    override fun equals(other: Any?): Boolean =
        other is UnknownAttribute && attributeNameIndex == other.attributeNameIndex &&
            attributeLength == other.attributeLength && data.contentEquals(other.data)

    override fun hashCode(): Int = attributeNameIndex * 31 + data.contentHashCode()
}

/**
 * Code атрибут
 */
data class Code(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val maxStack: Int,
    val maxLocals: Int,
    val code: ByteArray,
    val exceptionTable: List<ExceptionTableEntry>,
    val attributes: List<AttributeInfo>
) : AttributeInfo(attributeNameIndex, attributeLength) {

    data class ExceptionTableEntry(
        val startPc: Int,
        val endPc: Int,
        val handlerPc: Int,
        val catchType: Int
    )

    override fun equals(other: Any?): Boolean =
        other is Code && attributeNameIndex == other.attributeNameIndex &&
            attributeLength == other.attributeLength && maxStack == other.maxStack &&
            maxLocals == other.maxLocals && code.contentEquals(other.code) &&
            exceptionTable == other.exceptionTable && attributes == other.attributes

    override fun hashCode(): Int =
        ((attributeNameIndex * 31 + maxStack) * 31 + maxLocals) * 31 + code.contentHashCode()
}

/**
 * Exceptions атрибут
 */
data class Exceptions(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val exceptionIndexTable: List<Int>
) : AttributeInfo(attributeNameIndex, attributeLength)

/**
 * SourceFile атрибут
 */
data class SourceFile(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val sourceFileIndex: Int
) : AttributeInfo(attributeNameIndex, attributeLength)

/**
 * LineNumberTable атрибут
 */
data class LineNumberTable(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val entries: List<LineNumberTableEntry>
) : AttributeInfo(attributeNameIndex, attributeLength) {

    data class LineNumberTableEntry(
        val startPc: Int,
        val lineNumber: Int
    )
}

/**
 * LocalVariableTable атрибут
 */
data class LocalVariableTable(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val entries: List<LocalVariableTableEntry>
) : AttributeInfo(attributeNameIndex, attributeLength) {

    data class LocalVariableTableEntry(
        val startPc: Int,
        val length: Int,
        val nameIndex: Int,
        val descriptorIndex: Int,
        val index: Int
    )
}

/**
 * ConstantValue атрибут
 */
data class ConstantValue(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val constantValueIndex: Int
) : AttributeInfo(attributeNameIndex, attributeLength)

/**
 * StackMapTable атрибут (JVMS §4.7.4)
 */
data class StackMapTable(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val entries: List<StackMapFrame>
) : AttributeInfo(attributeNameIndex, attributeLength)

/**
 * Фрейм StackMapTable. [frameType] — байт типа фрейма, [offsetDelta] — смещение относительно
 * предыдущего фрейма (для same/same_locals_1_stack_item закодировано в самом frameType).
 */
sealed interface StackMapFrame {
    val frameType: Int
    val offsetDelta: Int
}

/** frame_type 0..63 */
data class SameFrame(
    override val frameType: Int
) : StackMapFrame {
    override val offsetDelta: Int get() = frameType
}

/** frame_type 64..127 */
data class SameLocals1StackItemFrame(
    override val frameType: Int,
    val stack: VerificationTypeInfo
) : StackMapFrame {
    override val offsetDelta: Int get() = frameType - 64
}

/** frame_type 247 */
data class SameLocals1StackItemFrameExtended(
    override val frameType: Int,
    override val offsetDelta: Int,
    val stack: VerificationTypeInfo
) : StackMapFrame

/** frame_type 248..250 */
data class ChopFrame(
    override val frameType: Int,
    override val offsetDelta: Int
) : StackMapFrame

/** frame_type 251 */
data class SameFrameExtended(
    override val frameType: Int,
    override val offsetDelta: Int
) : StackMapFrame

/** frame_type 252..254, locals.size == frameType - 251 */
data class AppendFrame(
    override val frameType: Int,
    override val offsetDelta: Int,
    val locals: List<VerificationTypeInfo>
) : StackMapFrame

/** frame_type 255 */
data class FullFrame(
    override val frameType: Int,
    override val offsetDelta: Int,
    val locals: List<VerificationTypeInfo>,
    val stack: List<VerificationTypeInfo>
) : StackMapFrame

/**
 * verification_type_info. [value] — индекс CONSTANT_Class для ITEM_Object,
 * смещение инструкции new для ITEM_Uninitialized, иначе 0.
 */
data class VerificationTypeInfo(
    val tag: Int,
    val value: Int = 0
) {
    val hasValue: Boolean get() = tag == ITEM_Object || tag == ITEM_Uninitialized

    companion object {
        const val ITEM_Top = 0
        const val ITEM_Integer = 1
        const val ITEM_Float = 2
        const val ITEM_Double = 3
        const val ITEM_Long = 4
        const val ITEM_Null = 5
        const val ITEM_UninitializedThis = 6
        const val ITEM_Object = 7
        const val ITEM_Uninitialized = 8

        private val SIMPLE = Array(7) { VerificationTypeInfo(it) }

        /** Общий экземпляр для типов без данных (теги 0..6). */
        fun simple(tag: Int): VerificationTypeInfo = SIMPLE[tag]
    }
}

/**
 * BootstrapMethods атрибут
 */
data class BootstrapMethods(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val bootstrapMethods: List<BootstrapMethod>
) : AttributeInfo(attributeNameIndex, attributeLength) {

    data class BootstrapMethod(
        val bootstrapMethodRef: Int,
        val bootstrapArguments: List<Int>
    )
}

/**
 * InnerClasses атрибут
 */
data class InnerClasses(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val classes: List<InnerClassEntry>
) : AttributeInfo(attributeNameIndex, attributeLength) {

    data class InnerClassEntry(
        val innerClassInfoIndex: Int,
        val outerClassInfoIndex: Int,
        val innerNameIndex: Int,
        val innerClassAccessFlags: Int
    )
}

/**
 * EnclosingMethod атрибут
 */
data class EnclosingMethod(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val classIndex: Int,
    val methodIndex: Int
) : AttributeInfo(attributeNameIndex, attributeLength)

/**
 * Signature атрибут
 */
data class Signature(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val signatureIndex: Int
) : AttributeInfo(attributeNameIndex, attributeLength)

/**
 * SourceDebugExtension атрибут (произвольные байты, обычно SMAP в Modified UTF-8)
 */
data class SourceDebugExtension(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val debugExtension: ByteArray
) : AttributeInfo(attributeNameIndex, attributeLength) {
    override fun equals(other: Any?): Boolean =
        other is SourceDebugExtension && attributeNameIndex == other.attributeNameIndex &&
            attributeLength == other.attributeLength && debugExtension.contentEquals(other.debugExtension)

    override fun hashCode(): Int = attributeNameIndex * 31 + debugExtension.contentHashCode()
}

/**
 * LocalVariableTypeTable атрибут
 */
data class LocalVariableTypeTable(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val entries: List<LocalVariableTypeTableEntry>
) : AttributeInfo(attributeNameIndex, attributeLength) {

    data class LocalVariableTypeTableEntry(
        val startPc: Int,
        val length: Int,
        val nameIndex: Int,
        val signatureIndex: Int,
        val index: Int
    )
}

/**
 * MethodParameters атрибут
 */
data class MethodParameters(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val parameters: List<MethodParameter>
) : AttributeInfo(attributeNameIndex, attributeLength) {

    data class MethodParameter(
        val nameIndex: Int,
        val accessFlags: Int
    )
}

/**
 * Synthetic атрибут (без данных)
 */
data class Synthetic(
    override val attributeNameIndex: Int,
    override val attributeLength: Long
) : AttributeInfo(attributeNameIndex, attributeLength)

/**
 * Deprecated атрибут (без данных)
 */
data class Deprecated(
    override val attributeNameIndex: Int,
    override val attributeLength: Long
) : AttributeInfo(attributeNameIndex, attributeLength)
