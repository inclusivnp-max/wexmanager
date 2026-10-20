package com.tim.casm.dsl

import com.tim.casm.Casm
import com.tim.casm.model.*

class ClassDsl(
    private val className: String,
    private val casm: Casm
) {
    private var superClass: String? = "java/lang/Object"
    private var accessFlags: Int = AccessFlags.ACC_PUBLIC or AccessFlags.ACC_SUPER
    private var sourceFile: String? = null
    private var signature: String? = null
    private val interfaces = ArrayList<String>()
    private val annotations = ArrayList<AnnotationInfo>()
    private val typeAnnotations = ArrayList<TypeAnnotationInfo>()
    private val fields = ArrayList<FieldInfo>()
    private val methods = ArrayList<MethodInfo>()
    private val recordComponents = ArrayList<RecordComponentSpec>()
    private var isRecord = false
    private var module: ModuleSpec? = null
    private val modulePackages = ArrayList<String>()
    private var moduleMainClass: String? = null
    private var nestHost: String? = null
    private val nestMembers = ArrayList<String>()
    private val permittedSubclasses = ArrayList<String>()
    private var enclosingMethod: Pair<String, String?>? = null
    private val innerClasses = ArrayList<InnerClassSpec>()

    fun superClass(name: String?) { superClass = name }
    fun extends_(name: String) { superClass = name }
    fun implements_(name: String) { if (name !in interfaces) interfaces.add(name) }
    fun interfaces(vararg names: String) = names.forEach { implements_(it) }

    fun public() { accessFlags = accessFlags or AccessFlags.ACC_PUBLIC }
    fun final() { accessFlags = accessFlags or AccessFlags.ACC_FINAL }
    fun abstract() { accessFlags = accessFlags or AccessFlags.ACC_ABSTRACT }
    fun interface_() { accessFlags = accessFlags or AccessFlags.ACC_INTERFACE or AccessFlags.ACC_ABSTRACT }
    fun annotation_() { accessFlags = accessFlags or AccessFlags.ACC_ANNOTATION or AccessFlags.ACC_INTERFACE or AccessFlags.ACC_ABSTRACT }
    fun enum_() { accessFlags = accessFlags or AccessFlags.ACC_ENUM }
    fun synthetic() { accessFlags = accessFlags or AccessFlags.ACC_SYNTHETIC }
    fun accessFlags(f: Int) { accessFlags = f }

    fun sourceFile(name: String) { sourceFile = name }
    fun signature(s: String) { signature = s }

    fun annotation(block: AnnotationDsl.() -> Unit) {
        val dsl = AnnotationDsl(); dsl.block(); annotations.add(dsl.buildInfo())
    }

    fun typeAnnotation(targetType: Int, block: TypeAnnotationDsl.() -> Unit) {
        val dsl = TypeAnnotationDsl(targetType); dsl.block(); typeAnnotations.add(dsl.build())
    }

    fun field(name: String, block: FieldDsl.() -> Unit) {
        val dsl = FieldDsl(name); dsl.block(); fields.add(dsl.build())
    }

    fun method(name: String, block: MethodDsl.() -> Unit) {
        val dsl = MethodDsl(name); dsl.block()
        methods.add(dsl.build(casm.state, className))
    }

    fun constructor(block: MethodDsl.() -> Unit) = method("<init>") { constructor(); block() }

    fun staticConstructor(block: MethodDsl.() -> Unit) = method("<clinit>") {
        static(); returnType("V"); block()
    }

    fun component(name: String, block: RecordDsl.() -> Unit) {
        val dsl = RecordDsl(name); dsl.block(); recordComponents.add(dsl.build())
        isRecord = true
    }

    /** Класс — record (атрибут Record пишется и без компонент). */
    fun record() { isRecord = true }

    fun module(name: String, block: ModuleDsl.() -> Unit = {}) {
        val dsl = ModuleDsl(name); dsl.block(); module = dsl.build()
        accessFlags = accessFlags or AccessFlags.ACC_MODULE
        superClass = null
    }

    fun modulePackage(pkg: String) { modulePackages.add(pkg) }
    fun moduleMainClass(cls: String) { moduleMainClass = cls }
    fun nestHost(host: String) { nestHost = host }
    fun nestMember(m: String) { nestMembers.add(m) }
    fun permittedSubclass(c: String) { permittedSubclasses.add(c) }
    fun enclosingMethod(cls: String, method: String?) { enclosingMethod = cls to method }
    fun innerClass(inner: String, outer: String?, innerName: String?, flags: Int) {
        innerClasses.add(InnerClassSpec(inner, outer, innerName, flags))
    }

    internal fun build(): ClassInfo = ClassInfo(
        name = className, superClass = superClass, accessFlags = accessFlags,
        sourceFile = sourceFile, signature = signature, interfaces = interfaces.toList(),
        annotations = annotations.toList(), typeAnnotations = typeAnnotations.toList(),
        fields = fields.toList(), methods = methods.toList(),
        recordComponents = recordComponents.toList(),
        isRecord = isRecord,
        module = module, modulePackages = modulePackages.toList(),
        moduleMainClass = moduleMainClass, nestHost = nestHost,
        nestMembers = nestMembers.toList(), permittedSubclasses = permittedSubclasses.toList(),
        enclosingMethod = enclosingMethod, innerClasses = innerClasses.toList()
    )
}
