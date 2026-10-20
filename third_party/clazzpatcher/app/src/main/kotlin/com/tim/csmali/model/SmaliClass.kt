package com.tim.csmali.model

data class SmaliClass(
    val descriptor: String,
    var accessFlags: MutableList<String> = mutableListOf(),
    var superClass: String? = "Ljava/lang/Object;",
    var sourceFile: String? = null,
    val interfaces: MutableList<String> = mutableListOf(),
    val annotations: MutableList<SmaliAnnotation> = mutableListOf(),
    val typeAnnotations: MutableList<SmaliTypeAnnotation> = mutableListOf(),
    val fields: MutableList<SmaliField> = mutableListOf(),
    val methods: MutableList<SmaliMethod> = mutableListOf(),
    val innerClasses: MutableList<SmaliInnerClass> = mutableListOf(),
    var nestHost: String? = null,
    val nestMembers: MutableList<String> = mutableListOf(),
    val permittedSubclasses: MutableList<String> = mutableListOf(),
    val recordComponents: MutableList<SmaliRecordComponent> = mutableListOf(),
    /** Record (атрибут Record); без компонент — директива `.record`. */
    var isRecord: Boolean = false,
    var module: SmaliModule? = null,
    /** Внешний класс и метод `имя(дескриптор)` (атрибут EnclosingMethod). */
    var enclosingMethod: Pair<String, String?>? = null,
    /** Версия формата класса: major (52 — Java 8) и minor. */
    var majorVersion: Int = 52,
    var minorVersion: Int = 0,
    /** Обобщённая сигнатура класса (атрибут Signature). */
    var signature: String? = null,
    /** Атрибут SourceDebugExtension (например, SMAP Kotlin). */
    var sourceDebugExtension: String? = null,
    /** Нестандартные атрибуты, переносимые байт-в-байт. */
    val rawAttributes: MutableList<SmaliRawAttribute> = mutableListOf()
)
