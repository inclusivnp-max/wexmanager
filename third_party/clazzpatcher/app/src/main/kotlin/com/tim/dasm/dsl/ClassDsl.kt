package com.tim.dasm.dsl

import com.tim.dasm.DexAsm
import com.tim.dasm.model.*
import com.tim.dex.model.AnnotationItem
import com.tim.dex.model.EncodedValue

class ClassDsl(
    private val className: String,
    private val asm: DexAsm
) {
    private var superClass: String? = "Ljava/lang/Object;"
    private var accessFlags: Int = AccessFlags.PUBLIC
    private var sourceFile: String? = null
    private val interfaces = mutableListOf<String>()
    private val classAnnotations = mutableListOf<AnnotationItem>()
    private val methods = mutableListOf<MethodInfo>()
    private val fields = mutableListOf<FieldInfo>()

    // ---- super / interfaces ----

    /** `null` — только для `Ljava/lang/Object;`. */
    fun superClass(name: String?) { superClass = name }
    fun extends(name: String) { superClass = name }

    fun implements(name: String) { if (name !in interfaces) interfaces.add(name) }
    fun interfaces(vararg names: String) = names.forEach { implements(it) }

    // ---- access flags ----

    fun public() { accessFlags = accessFlags or AccessFlags.PUBLIC }
    fun private() { accessFlags = accessFlags or AccessFlags.PRIVATE }
    fun protected() { accessFlags = accessFlags or AccessFlags.PROTECTED }
    fun final() { accessFlags = accessFlags or AccessFlags.FINAL }
    fun abstract() { accessFlags = accessFlags or AccessFlags.ABSTRACT }
    fun annotation() { accessFlags = accessFlags or AccessFlags.ANNOTATION }
    fun enum_() { accessFlags = accessFlags or AccessFlags.ENUM }
    fun interface_() { accessFlags = accessFlags or AccessFlags.INTERFACE }
    fun synthetic() { accessFlags = accessFlags or AccessFlags.SYNTHETIC }

    fun accessFlags(flags: Int) { this.accessFlags = flags }
    fun flags(flags: Int) { this.accessFlags = flags }

    fun sourceFile(name: String) { sourceFile = name }

    // ---- annotations ----

    fun annotation(item: AnnotationItem) {
        classAnnotations.add(item)
    }

    fun annotation(block: AnnotationDsl.() -> Unit) {
        val dsl = AnnotationDsl(asm)
        dsl.block()
        classAnnotations.add(dsl.build())
    }

    fun signature(vararg parts: String) {
        val dsl = AnnotationDsl(asm)
        dsl.type("Ldalvik/annotation/Signature;")
        dsl.systemVisibility()
        dsl.stringArray("value", parts.toList())
        classAnnotations.add(dsl.build())
    }

    // ---- members ----

    fun method(name: String, block: MethodDsl.() -> Unit) {
        val dsl = MethodDsl(name, asm)
        dsl.block()
        methods.add(dsl.build())
    }

    fun constructor(block: MethodDsl.() -> Unit) {
        method("<init>") {
            constructor()
            block()
        }
    }

    fun staticConstructor(block: MethodDsl.() -> Unit) {
        method("<clinit>") {
            staticConstructor()
            block()
        }
    }

    fun field(name: String, type: String, accessFlags: Int = AccessFlags.PUBLIC): Int {
        val info = FieldInfo(name, type, accessFlags)
        fields.add(info)
        return fields.size - 1
    }

    fun field(name: String, type: String, accessFlags: Int, block: AnnotationDsl.() -> Unit): Int {
        val dsl = AnnotationDsl(asm)
        dsl.block()
        val info = FieldInfo(name, type, accessFlags, listOf(dsl.build()))
        fields.add(info)
        return fields.size - 1
    }

    /**
     * Добавляет поле с готовым списком аннотаций.
     */
    fun field(
        name: String,
        type: String,
        accessFlags: Int,
        annotations: List<AnnotationItem>
    ): Int {
        val info = FieldInfo(name, type, accessFlags, annotations)
        fields.add(info)
        return fields.size - 1
    }

    /**
     * Добавляет поле с аннотациями и инициализатором (для static_values).
     *
     * Используется [com.tim.smali.asm.TreeToDex] для сохранения
     * аннотаций и initial value, прочитанных из smali.
     */
    fun field(
        name: String,
        type: String,
        accessFlags: Int,
        annotations: List<AnnotationItem>,
        initialValue: EncodedValue?,
        hiddenApi: Int = -1
    ): Int {
        val info = FieldInfo(name, type, accessFlags, annotations, initialValue, hiddenApi)
        fields.add(info)
        return fields.size - 1
    }

    /**
     * Добавляет аннотацию к уже созданному полю по его индексу в [ClassDsl.fields].
     */
    fun fieldAnnotation(fieldIndex: Int, block: AnnotationDsl.() -> Unit): Int {
        require(fieldIndex in fields.indices) { "Field index $fieldIndex out of bounds (size=${fields.size})" }
        val dsl = AnnotationDsl(asm)
        dsl.block()
        val old = fields[fieldIndex]
        fields[fieldIndex] = old.copy(annotations = old.annotations + dsl.build())
        return fieldIndex
    }

    fun publicField(n: String, t: String) = field(n, t, AccessFlags.PUBLIC)
    fun privateField(n: String, t: String) = field(n, t, AccessFlags.PRIVATE)
    fun protectedField(n: String, t: String) = field(n, t, AccessFlags.PROTECTED)
    fun staticField(n: String, t: String) = field(n, t, AccessFlags.PUBLIC or AccessFlags.STATIC)
    fun privateStaticField(n: String, t: String) =
        field(n, t, AccessFlags.PRIVATE or AccessFlags.STATIC)
    fun staticFinalField(n: String, t: String) =
        field(n, t, AccessFlags.PUBLIC or AccessFlags.STATIC or AccessFlags.FINAL)
    fun privateStaticFinalField(n: String, t: String) =
        field(n, t, AccessFlags.PRIVATE or AccessFlags.STATIC or AccessFlags.FINAL)
    fun finalField(n: String, t: String) =
        field(n, t, AccessFlags.PUBLIC or AccessFlags.FINAL)
    fun volatileField(n: String, t: String) =
        field(n, t, AccessFlags.PUBLIC or AccessFlags.VOLATILE)
    fun transientField(n: String, t: String) =
        field(n, t, AccessFlags.PUBLIC or AccessFlags.TRANSIENT)
    fun syntheticField(n: String, t: String) =
        field(n, t, AccessFlags.PUBLIC or AccessFlags.SYNTHETIC)

    // ---- build ----

    internal fun build(): ClassInfo = ClassInfo(
        name = className,
        superClass = superClass,
        accessFlags = accessFlags,
        sourceFile = sourceFile,
        interfaces = interfaces.toList(),
        annotations = classAnnotations.toList(),
        methods = methods.toList(),
        fields = fields.toList()
    )
}