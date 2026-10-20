package com.tim.casm.model

data class MethodInfo(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
    val code: ByteArray = ByteArray(0),
    val maxStack: Int = 0,
    val maxLocals: Int = 0,
    val exceptionTable: List<ExceptionTableEntry> = emptyList(),
    val lineNumbers: List<LineNumberEntry> = emptyList(),
    val localVariables: List<LocalVariableEntry> = emptyList(),
    val annotations: List<AnnotationInfo> = emptyList(),
    val parameterAnnotations: List<List<AnnotationInfo>> = emptyList(),
    val typeAnnotations: List<TypeAnnotationInfo> = emptyList(),
    val signature: String? = null,
    val exceptions: List<String> = emptyList(),
    val annotationDefault: AnnotationValue? = null,
    val stackMapFrames: List<RawStackMapFrame> = emptyList(),
    val methodParameters: List<MethodParameterSpec> = emptyList(),
    val hasCode: Boolean = code.isNotEmpty()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MethodInfo) return false
        return name == other.name && descriptor == other.descriptor &&
        accessFlags == other.accessFlags && code.contentEquals(other.code) &&
        maxStack == other.maxStack && maxLocals == other.maxLocals &&
        exceptionTable == other.exceptionTable
    }

    override fun hashCode(): Int {
        var r = name.hashCode()
        r = 31 * r + descriptor.hashCode()
        r = 31 * r + accessFlags
        r = 31 * r + code.contentHashCode()
        return r
    }
}

data class ExceptionTableEntry(
    val startPc: Int,
    val endPc: Int,
    val handlerPc: Int,
    val catchType: String?
)

data class LineNumberEntry(val startPc: Int, val lineNumber: Int)

data class LocalVariableEntry(
    val startPc: Int,
    val length: Int,
    val name: String,
    val descriptor: String,
    val index: Int,
    /** Обобщённая сигнатура для LocalVariableTypeTable, `null` — нет. */
    val signature: String? = null
)

data class MethodParameterSpec(val name: String?, val flags: Int)
