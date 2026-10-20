package com.tim.csmali.model

import com.tim.clazz.model.attributes.RuntimeVisibleTypeAnnotations.*

data class SmaliTypeAnnotation(
    var targetType: Int = 0,
    var targetInfo: Any? = null,
    var typePath: TypePath = TypePath.EMPTY,
    var annotation: SmaliAnnotation = SmaliAnnotation(isSub = true)
)

data class SmaliInnerClass(
    var innerInfo: String = "",
    var outerInfo: String? = null,
    var innerName: String? = null,
    var flags: List<String> = emptyList()
)

data class SmaliRecordComponent(
    var name: String = "",
    var descriptor: String = "",
    var signature: String? = null,
    val annotations: MutableList<SmaliAnnotation> = mutableListOf(),
    val typeAnnotations: MutableList<SmaliTypeAnnotation> = mutableListOf()
)

data class SmaliModule(
    var name: String = "",
    var version: String? = null,
    var flags: Int = 0,
    val requires: MutableList<Requires> = mutableListOf(),
    val exports: MutableList<Exports> = mutableListOf(),
    val opens: MutableList<Opens> = mutableListOf(),
    val uses: MutableList<String> = mutableListOf(),
    val provides: MutableList<Provides> = mutableListOf(),
    /** Атрибут ModulePackages (внутренние имена пакетов). */
    val packages: MutableList<String> = mutableListOf(),
    /** Атрибут ModuleMainClass. */
    var mainClass: String? = null
) {
    data class Requires(val module: String, val flags: Int, val version: String?)
    data class Exports(val pkg: String, val flags: Int, val to: List<String>)
    data class Opens(val pkg: String, val flags: Int, val to: List<String>)
    data class Provides(val service: String, val with: List<String>)
}
/** Нестандартный атрибут (например, ScalaSig), переносимый байт-в-байт: `.attribute "Имя", "hex"`. */
data class SmaliRawAttribute(val name: String, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is SmaliRawAttribute && name == other.name && data.contentEquals(other.data)

    override fun hashCode(): Int = name.hashCode() * 31 + data.contentHashCode()
}
