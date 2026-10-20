package com.tim.clazz.model.attributes

import com.tim.clazz.model.annotations.ElementValuePair

/**
 * RuntimeVisibleTypeAnnotations и RuntimeInvisibleTypeAnnotations (JVMS §4.7.20):
 * вид определяется именем атрибута ([attributeNameIndex]).
 */
data class RuntimeVisibleTypeAnnotations(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val annotations: List<TypeAnnotation>
) : AttributeInfo(attributeNameIndex, attributeLength) {

    data class TypeAnnotation(
        val targetType: Int,
        val targetInfo: TargetInfo,
        val typePath: TypePath,
        val typeIndex: Int,
        val elementValuePairs: List<ElementValuePair>
    )

    /** target_info; вариант задаётся target_type (JVMS таблицы 4.7.20-A/B/C). */
    sealed class TargetInfo

    /** 0x13, 0x14, 0x15 */
    object EmptyTarget : TargetInfo() {
        override fun toString(): String = "EmptyTarget"
    }

    /** 0x00, 0x01 */
    data class TypeParameterTarget(val typeParameterIndex: Int) : TargetInfo()

    /** 0x10: 65535 — суперкласс, иначе индекс в interfaces */
    data class SuperTypeTarget(val supertypeIndex: Int) : TargetInfo()

    /** 0x11, 0x12 */
    data class TypeParameterBoundTarget(
        val typeParameterIndex: Int,
        val boundIndex: Int
    ) : TargetInfo()

    /** 0x16 */
    data class FormalParameterTarget(val formalParameterIndex: Int) : TargetInfo()

    /** 0x17 */
    data class ThrowsTarget(val throwsTypeIndex: Int) : TargetInfo()

    /** 0x40, 0x41 */
    data class LocalvarTarget(val entries: List<LocalvarEntry>) : TargetInfo() {
        data class LocalvarEntry(
            val startPc: Int,
            val length: Int,
            val index: Int
        )
    }

    /** 0x42 */
    data class CatchTarget(val exceptionTableIndex: Int) : TargetInfo()

    /** 0x43..0x46 */
    data class OffsetTarget(val offset: Int) : TargetInfo()

    /** 0x47..0x4B */
    data class TypeArgumentTarget(
        val offset: Int,
        val typeArgumentIndex: Int
    ) : TargetInfo()

    data class TypePath(
        val entries: List<TypePathEntry>
    ) {
        data class TypePathEntry(
            val typePathKind: Int,
            val typeArgumentIndex: Int
        )

        companion object {
            val EMPTY = TypePath(emptyList())
        }
    }

    companion object {
        /** Соответствует ли вид target_info значению target_type (JVMS таблицы 4.7.20-A/B/C). */
        fun targetInfoMatches(targetType: Int, info: TargetInfo): Boolean = when (targetType) {
            0x00, 0x01 -> info is TypeParameterTarget
            0x10 -> info is SuperTypeTarget
            0x11, 0x12 -> info is TypeParameterBoundTarget
            0x13, 0x14, 0x15 -> info is EmptyTarget
            0x16 -> info is FormalParameterTarget
            0x17 -> info is ThrowsTarget
            0x40, 0x41 -> info is LocalvarTarget
            0x42 -> info is CatchTarget
            in 0x43..0x46 -> info is OffsetTarget
            in 0x47..0x4B -> info is TypeArgumentTarget
            else -> false
        }

        /** Имя вида target_info для сообщений. */
        fun targetInfoName(info: TargetInfo): String = when (info) {
            is TypeParameterTarget -> "type_parameter_target"
            is SuperTypeTarget -> "supertype_target"
            is TypeParameterBoundTarget -> "type_parameter_bound_target"
            is EmptyTarget -> "empty_target"
            is FormalParameterTarget -> "formal_parameter_target"
            is ThrowsTarget -> "throws_target"
            is LocalvarTarget -> "localvar_target"
            is CatchTarget -> "catch_target"
            is OffsetTarget -> "offset_target"
            is TypeArgumentTarget -> "type_argument_target"
        }
    }
}
