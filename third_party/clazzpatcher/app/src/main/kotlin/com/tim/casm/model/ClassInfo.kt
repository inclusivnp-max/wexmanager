package com.tim.casm.model

data class ClassInfo(
    val name: String,
    val superClass: String?,
    val accessFlags: Int,
    val sourceFile: String?,
    val signature: String? = null,
    val interfaces: List<String>,
    val annotations: List<AnnotationInfo>,
    val typeAnnotations: List<TypeAnnotationInfo>,
    val fields: List<FieldInfo>,
    val methods: List<MethodInfo>,
    val recordComponents: List<RecordComponentSpec> = emptyList(),
    /** Класс — record (атрибут Record есть и при нуле компонент: `record R() {}`). */
    val isRecord: Boolean = recordComponents.isNotEmpty(),
    val module: ModuleSpec? = null,
    val modulePackages: List<String> = emptyList(),
    val moduleMainClass: String? = null,
    val nestHost: String? = null,
    val nestMembers: List<String> = emptyList(),
    val permittedSubclasses: List<String> = emptyList(),
    /** Внешний класс и метод `имя(дескриптор)` (`null` — метод не указан). */
    val enclosingMethod: Pair<String, String?>? = null,
    val innerClasses: List<InnerClassSpec> = emptyList()
)

data class InnerClassSpec(
    val innerInfo: String,
    val outerInfo: String?,
    val innerName: String?,
    val flags: Int
)

data class RecordComponentSpec(
    val name: String,
    val descriptor: String,
    val signature: String?,
    val annotations: List<AnnotationInfo>
)

data class ModuleSpec(
    val name: String,
    val version: String?,
    val flags: Int,
    val requires: List<RequiresSpec>,
    val exports: List<ExportsSpec>,
    val opens: List<OpensSpec>,
    val uses: List<String>,
    val provides: List<ProvidesSpec>
)

data class RequiresSpec(val module: String, val flags: Int, val version: String?)
data class ExportsSpec(val pkg: String, val flags: Int, val to: List<String>)
data class OpensSpec(val pkg: String, val flags: Int, val to: List<String>)
data class ProvidesSpec(val service: String, val with: List<String>)
