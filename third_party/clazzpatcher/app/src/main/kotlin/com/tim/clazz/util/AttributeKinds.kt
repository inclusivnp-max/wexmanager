package com.tim.clazz.util

import com.tim.clazz.model.ConstantPoolEntry
import com.tim.clazz.model.ConstantUtf8Info

/**
 * Стандартные атрибуты (JVMS §4.7, таблица 4.7-C) и места, где они распознаются.
 * Атрибут со стандартным именем вне своего места JVM игнорирует (JVMS §4.7.1),
 * поэтому такой атрибут читается как неизвестный и сохраняется байт-в-байт.
 */
object AttributeKinds {
    // Места (битовая маска)
    const val IN_CLASS = 1
    const val IN_FIELD = 2
    const val IN_METHOD = 4
    const val IN_CODE = 8
    const val IN_RECORD_COMPONENT = 16

    // Виды атрибутов
    const val UNKNOWN = 0
    const val CODE = 1
    const val EXCEPTIONS = 2
    const val SOURCE_FILE = 3
    const val LINE_NUMBER_TABLE = 4
    const val LOCAL_VARIABLE_TABLE = 5
    const val CONSTANT_VALUE = 6
    const val STACK_MAP_TABLE = 7
    const val BOOTSTRAP_METHODS = 8
    const val INNER_CLASSES = 9
    const val ENCLOSING_METHOD = 10
    const val SIGNATURE = 11
    const val SOURCE_DEBUG_EXTENSION = 12
    const val LOCAL_VARIABLE_TYPE_TABLE = 13
    const val METHOD_PARAMETERS = 14
    const val ANNOTATIONS = 15
    const val PARAMETER_ANNOTATIONS = 16
    const val TYPE_ANNOTATIONS = 17
    const val ANNOTATION_DEFAULT = 18
    const val MODULE = 19
    const val MODULE_PACKAGES = 20
    const val MODULE_MAIN_CLASS = 21
    const val NEST_HOST = 22
    const val NEST_MEMBERS = 23
    const val RECORD = 24
    const val PERMITTED_SUBCLASSES = 25
    const val SYNTHETIC = 26
    const val DEPRECATED = 27

    /** Имя вида атрибута для сообщений (аннотации — общим именем видимых). */
    fun kindName(kind: Int): String = when (kind) {
        CODE -> "Code"
        EXCEPTIONS -> "Exceptions"
        SOURCE_FILE -> "SourceFile"
        LINE_NUMBER_TABLE -> "LineNumberTable"
        LOCAL_VARIABLE_TABLE -> "LocalVariableTable"
        CONSTANT_VALUE -> "ConstantValue"
        STACK_MAP_TABLE -> "StackMapTable"
        BOOTSTRAP_METHODS -> "BootstrapMethods"
        INNER_CLASSES -> "InnerClasses"
        ENCLOSING_METHOD -> "EnclosingMethod"
        SIGNATURE -> "Signature"
        SOURCE_DEBUG_EXTENSION -> "SourceDebugExtension"
        LOCAL_VARIABLE_TYPE_TABLE -> "LocalVariableTypeTable"
        METHOD_PARAMETERS -> "MethodParameters"
        ANNOTATIONS -> "Runtime(In)VisibleAnnotations"
        PARAMETER_ANNOTATIONS -> "Runtime(In)VisibleParameterAnnotations"
        TYPE_ANNOTATIONS -> "Runtime(In)VisibleTypeAnnotations"
        ANNOTATION_DEFAULT -> "AnnotationDefault"
        MODULE -> "Module"
        MODULE_PACKAGES -> "ModulePackages"
        MODULE_MAIN_CLASS -> "ModuleMainClass"
        NEST_HOST -> "NestHost"
        NEST_MEMBERS -> "NestMembers"
        RECORD -> "Record"
        PERMITTED_SUBCLASSES -> "PermittedSubclasses"
        SYNTHETIC -> "Synthetic"
        DEPRECATED -> "Deprecated"
        else -> "нестандартный атрибут"
    }

    private const val CFM = IN_CLASS or IN_FIELD or IN_METHOD
    private const val CFMR = CFM or IN_RECORD_COMPONENT

    private val NAMES = arrayOf(
        "Code", "Exceptions", "SourceFile", "LineNumberTable", "LocalVariableTable", "ConstantValue",
        "StackMapTable", "BootstrapMethods", "InnerClasses", "EnclosingMethod", "Signature",
        "SourceDebugExtension", "LocalVariableTypeTable", "MethodParameters",
        "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations",
        "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations",
        "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations", "AnnotationDefault",
        "Module", "ModulePackages", "ModuleMainClass", "NestHost", "NestMembers", "Record",
        "PermittedSubclasses", "Synthetic", "Deprecated"
    )

    /** (вид or (маска мест shl 8)) для имён из [NAMES] в том же порядке. */
    private val SPECS = intArrayOf(
        spec(CODE, IN_METHOD), spec(EXCEPTIONS, IN_METHOD), spec(SOURCE_FILE, IN_CLASS),
        spec(LINE_NUMBER_TABLE, IN_CODE), spec(LOCAL_VARIABLE_TABLE, IN_CODE), spec(CONSTANT_VALUE, IN_FIELD),
        spec(STACK_MAP_TABLE, IN_CODE), spec(BOOTSTRAP_METHODS, IN_CLASS), spec(INNER_CLASSES, IN_CLASS),
        spec(ENCLOSING_METHOD, IN_CLASS), spec(SIGNATURE, CFMR),
        spec(SOURCE_DEBUG_EXTENSION, IN_CLASS), spec(LOCAL_VARIABLE_TYPE_TABLE, IN_CODE), spec(METHOD_PARAMETERS, IN_METHOD),
        spec(ANNOTATIONS, CFMR), spec(ANNOTATIONS, CFMR),
        spec(PARAMETER_ANNOTATIONS, IN_METHOD), spec(PARAMETER_ANNOTATIONS, IN_METHOD),
        spec(TYPE_ANNOTATIONS, CFMR or IN_CODE), spec(TYPE_ANNOTATIONS, CFMR or IN_CODE), spec(ANNOTATION_DEFAULT, IN_METHOD),
        spec(MODULE, IN_CLASS), spec(MODULE_PACKAGES, IN_CLASS), spec(MODULE_MAIN_CLASS, IN_CLASS),
        spec(NEST_HOST, IN_CLASS), spec(NEST_MEMBERS, IN_CLASS), spec(RECORD, IN_CLASS),
        spec(PERMITTED_SUBCLASSES, IN_CLASS), spec(SYNTHETIC, CFM), spec(DEPRECATED, CFM)
    )

    private val NAME_BYTES = Array(NAMES.size) { NAMES[it].toByteArray(Charsets.ISO_8859_1) }

    /** Длина имени → индексы в [NAMES] с такой длиной. */
    private val BY_LENGTH: Array<IntArray?> = arrayOfNulls<IntArray>(64).also { table ->
        for (i in NAMES.indices) {
            val len = NAMES[i].length
            table[len] = (table[len] ?: IntArray(0)) + i
        }
    }

    private fun spec(kind: Int, places: Int) = kind or (places shl 8)

    /**
     * Вид и маска мест для имени в байтах Modified UTF-8: `вид or (маска shl 8)`, 0 — неизвестное имя.
     * Все стандартные имена ASCII, поэтому байты сравниваются без декодирования.
     */
    fun specOf(nameBytes: ByteArray): Int {
        if (nameBytes.size >= BY_LENGTH.size) return 0
        val candidates = BY_LENGTH[nameBytes.size] ?: return 0
        for (i in candidates) {
            if (nameBytes.contentEquals(NAME_BYTES[i])) return SPECS[i]
        }
        return 0
    }

    fun kindOf(spec: Int): Int = spec and 0xFF

    fun placesOf(spec: Int): Int = spec ushr 8
}

/**
 * Кэш вида атрибута по индексу имени в пуле (таблица создаётся при первом обращении).
 */
internal class AttributeSpecCache(private val pool: List<ConstantPoolEntry?>) {
    private var table: IntArray? = null

    /** `вид or (маска мест shl 8)` для имени #[nameIndex]; не CONSTANT_Utf8 — [ClassFormatException]. */
    fun specOf(nameIndex: Int): Int {
        val t = table ?: IntArray(pool.size).also { table = it }
        if (nameIndex < t.size) {
            val cached = t[nameIndex]
            if (cached != 0) return cached - 1
        }
        val entry = pool.getOrNull(nameIndex) as? ConstantUtf8Info
            ?: throw ClassFormatException("attribute_name_index #$nameIndex не указывает на CONSTANT_Utf8")
        val spec = AttributeKinds.specOf(entry.bytes)
        t[nameIndex] = spec + 1
        return spec
    }
}
