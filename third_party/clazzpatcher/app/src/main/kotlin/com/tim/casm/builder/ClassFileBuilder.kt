package com.tim.casm.builder

import com.tim.casm.CasmState
import com.tim.casm.model.AnnotationInfo
import com.tim.casm.model.AnnotationValue
import com.tim.casm.model.ClassInfo
import com.tim.casm.model.FieldInfo
import com.tim.casm.model.MethodInfo
import com.tim.casm.model.ModuleSpec
import com.tim.casm.model.TypeAnnotationInfo
import com.tim.casm.pool.ConstantPoolBuilder
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.attributes.*
import com.tim.clazz.model.annotations.Annotation
import com.tim.clazz.model.annotations.AnnotationValueElement
import com.tim.clazz.model.annotations.ArrayValueElement
import com.tim.clazz.model.annotations.ClassInfoIndexElement
import com.tim.clazz.model.annotations.ConstValueIndexElement
import com.tim.clazz.model.annotations.ElementValue
import com.tim.clazz.model.annotations.ElementValuePair
import com.tim.clazz.model.annotations.EnumConstValueElement
import com.tim.clazz.model.annotations.Utf8ValueIndexElement

/** Модель casm ([ClassInfo]) → [ClassFile] на пуле констант состояния. */
class ClassFileBuilder(private val state: CasmState) {

    private val pool: ConstantPoolBuilder get() = state.pool

    fun materializeClass(info: ClassInfo): ClassFile {
        val thisIdx = pool.classInfo(info.name)
        val superIdx = info.superClass?.let { pool.classInfo(it) } ?: 0
        val interfaces = info.interfaces.map { pool.classInfo(it) }
        val fields = info.fields.map { materializeField(it) }
        val methods = info.methods.map { materializeMethod(it) }

        val attrs = ArrayList<AttributeInfo>()
        info.sourceFile?.let { attrs.add(SourceFile(pool.utf8("SourceFile"), 0, pool.utf8(it))) }
        info.signature?.let { attrs.add(Signature(pool.utf8("Signature"), 0, pool.utf8(it))) }
        addAnnotations(attrs, info.annotations)
        addTypeAnnotations(attrs, info.typeAnnotations)
        attrs.addAll(extraAttributes(info))
        state.bootstrapPool.build(pool.utf8("BootstrapMethods"))?.let { attrs.add(it) }

        return ClassFile(
            magic = 0xCAFEBABEL,
            minorVersion = 0,
            majorVersion = state.majorVersion,
            constantPool = pool.build(),
            accessFlags = info.accessFlags,
            thisClass = thisIdx,
            superClass = superIdx,
            interfaces = interfaces,
            fields = fields,
            methods = methods,
            attributes = attrs
        )
    }

    /** Текущий класс состояния с актуальными пулом и BootstrapMethods. */
    fun build(): ClassFile {
        val src = state.source
        check(src.thisClass != 0) { "Класс не создан: вызовите Casm.useDsl { createClass(...) }" }
        val attrs = src.attributes.filterNot { it is BootstrapMethods }.toMutableList()
        state.bootstrapPool.build(pool.utf8("BootstrapMethods"))?.let { attrs.add(it) }
        return src.copy(majorVersion = state.majorVersion, constantPool = pool.build(), attributes = attrs)
    }

    private fun materializeField(f: FieldInfo): com.tim.clazz.model.FieldInfo {
        val attrs = ArrayList<AttributeInfo>()
        f.constantValue?.let { attrs.add(ConstantValue(pool.utf8("ConstantValue"), 0, pool.constant(it))) }
        f.signature?.let { attrs.add(Signature(pool.utf8("Signature"), 0, pool.utf8(it))) }
        addAnnotations(attrs, f.annotations)
        addTypeAnnotations(attrs, f.typeAnnotations)
        return com.tim.clazz.model.FieldInfo(f.accessFlags, pool.utf8(f.name), pool.utf8(f.descriptor), attrs)
    }

    private fun materializeMethod(m: MethodInfo): com.tim.clazz.model.MethodInfo {
        val attrs = ArrayList<AttributeInfo>()
        if (m.hasCode) {
            attrs.add(CodeAttributes.build(
                pool, m.code, m.maxStack, m.maxLocals, m.exceptionTable,
                m.lineNumbers, m.localVariables, m.stackMapFrames
            ))
        }
        m.signature?.let { attrs.add(Signature(pool.utf8("Signature"), 0, pool.utf8(it))) }
        if (m.exceptions.isNotEmpty()) {
            attrs.add(Exceptions(pool.utf8("Exceptions"), 0, m.exceptions.map { pool.classInfo(it) }))
        }
        addAnnotations(attrs, m.annotations)
        addParameterAnnotations(attrs, m.parameterAnnotations, visible = true)
        addParameterAnnotations(attrs, m.parameterAnnotations, visible = false)
        addTypeAnnotations(attrs, m.typeAnnotations)
        m.annotationDefault?.let { attrs.add(AnnotationDefault(pool.utf8("AnnotationDefault"), 0, value(it))) }
        if (m.methodParameters.isNotEmpty()) {
            attrs.add(MethodParameters(pool.utf8("MethodParameters"), 0, m.methodParameters.map {
                MethodParameters.MethodParameter(it.name?.let { n -> pool.utf8(n) } ?: 0, it.flags)
            }))
        }
        return com.tim.clazz.model.MethodInfo(m.accessFlags, pool.utf8(m.name), pool.utf8(m.descriptor), attrs)
    }

    // ---- Аннотации ----

    /** RuntimeVisibleAnnotations и RuntimeInvisibleAnnotations — по видимости каждой аннотации. */
    private fun addAnnotations(out: MutableList<AttributeInfo>, annotations: List<AnnotationInfo>) {
        for (visible in booleanArrayOf(true, false)) {
            val list = annotations.filter { it.visible == visible }
            if (list.isEmpty()) continue
            val name = if (visible) "RuntimeVisibleAnnotations" else "RuntimeInvisibleAnnotations"
            out.add(RuntimeVisibleAnnotations(pool.utf8(name), 0, list.map { annotation(it) }))
        }
    }

    private fun addParameterAnnotations(out: MutableList<AttributeInfo>, params: List<List<AnnotationInfo>>, visible: Boolean) {
        if (params.none { p -> p.any { it.visible == visible } }) return
        val name = if (visible) "RuntimeVisibleParameterAnnotations" else "RuntimeInvisibleParameterAnnotations"
        out.add(RuntimeVisibleParameterAnnotations(pool.utf8(name), 0, params.map { p ->
            RuntimeVisibleParameterAnnotations.ParameterAnnotation(p.filter { it.visible == visible }.map { annotation(it) })
        }))
    }

    private fun addTypeAnnotations(out: MutableList<AttributeInfo>, annotations: List<TypeAnnotationInfo>) {
        for (visible in booleanArrayOf(true, false)) {
            val list = annotations.filter { it.annotation.visible == visible }
            if (list.isEmpty()) continue
            val name = if (visible) "RuntimeVisibleTypeAnnotations" else "RuntimeInvisibleTypeAnnotations"
            out.add(RuntimeVisibleTypeAnnotations(pool.utf8(name), 0, list.map { typeAnnotation(it) }))
        }
    }

    private fun typeAnnotation(t: TypeAnnotationInfo): RuntimeVisibleTypeAnnotations.TypeAnnotation {
        val target = t.targetInfo as? RuntimeVisibleTypeAnnotations.TargetInfo
            ?: throw IllegalArgumentException("type-аннотация ${t.annotation.type}: не задан target_info")
        val path = RuntimeVisibleTypeAnnotations.TypePath(t.typePath.map {
            RuntimeVisibleTypeAnnotations.TypePath.TypePathEntry(it.first, it.second)
        })
        val a = annotation(t.annotation)
        return RuntimeVisibleTypeAnnotations.TypeAnnotation(t.targetType, target, path, a.typeIndex, a.elementValuePairs)
    }

    private fun annotation(a: AnnotationInfo): Annotation =
        Annotation(pool.utf8(a.type), a.elements.map { ElementValuePair(pool.utf8(it.name), value(it.value)) })

    private fun value(v: AnnotationValue): ElementValue = when (v) {
        is AnnotationValue.Const -> when (val x = v.value) {
            is String -> Utf8ValueIndexElement('s', pool.utf8(x))
            is Int -> ConstValueIndexElement('I', pool.integer(x))
            is Boolean -> ConstValueIndexElement('Z', pool.integer(if (x) 1 else 0))
            is Byte -> ConstValueIndexElement('B', pool.integer(x.toInt()))
            is Char -> ConstValueIndexElement('C', pool.integer(x.code))
            is Short -> ConstValueIndexElement('S', pool.integer(x.toInt()))
            is Long -> ConstValueIndexElement('J', pool.long(x))
            is Float -> ConstValueIndexElement('F', pool.float(x.toRawBits()))
            is Double -> ConstValueIndexElement('D', pool.double(x.toRawBits()))
            null -> throw IllegalArgumentException("Значение элемента аннотации не может быть null")
            else -> throw IllegalArgumentException("Неподдерживаемое значение аннотации: $x")
        }
        is AnnotationValue.Enum -> EnumConstValueElement('e', pool.utf8(v.typeDesc), pool.utf8(v.constName))
        is AnnotationValue.ClassRef -> ClassInfoIndexElement('c', pool.utf8(v.descriptor))
        is AnnotationValue.Nested -> AnnotationValueElement('@', annotation(v.annotation))
        is AnnotationValue.Array -> ArrayValueElement('[', v.values.map { value(it) })
    }

    // ---- Прочие атрибуты класса ----

    private fun extraAttributes(info: ClassInfo): List<AttributeInfo> {
        val out = ArrayList<AttributeInfo>()
        if (info.innerClasses.isNotEmpty()) {
            out.add(InnerClasses(pool.utf8("InnerClasses"), 0, info.innerClasses.map {
                InnerClasses.InnerClassEntry(
                    pool.classInfo(it.innerInfo),
                    it.outerInfo?.let { o -> pool.classInfo(o) } ?: 0,
                    it.innerName?.let { n -> pool.utf8(n) } ?: 0,
                    it.flags
                )
            }))
        }
        info.enclosingMethod?.let { (owner, method) ->
            out.add(EnclosingMethod(pool.utf8("EnclosingMethod"), 0, pool.classInfo(owner), method?.let { nameAndType(it) } ?: 0))
        }
        info.nestHost?.let { out.add(NestHost(pool.utf8("NestHost"), 0, pool.classInfo(it))) }
        if (info.nestMembers.isNotEmpty()) {
            out.add(NestMembers(pool.utf8("NestMembers"), 0, info.nestMembers.map { pool.classInfo(it) }))
        }
        if (info.permittedSubclasses.isNotEmpty()) {
            out.add(PermittedSubclasses(pool.utf8("PermittedSubclasses"), 0, info.permittedSubclasses.map { pool.classInfo(it) }))
        }
        if (info.isRecord || info.recordComponents.isNotEmpty()) {
            out.add(Record(pool.utf8("Record"), 0, info.recordComponents.map { c ->
                val attrs = ArrayList<AttributeInfo>()
                c.signature?.let { attrs.add(Signature(pool.utf8("Signature"), 0, pool.utf8(it))) }
                addAnnotations(attrs, c.annotations)
                Record.RecordComponentInfo(pool.utf8(c.name), pool.utf8(c.descriptor), attrs)
            }))
        }
        info.module?.let { out.add(module(it)) }
        if (info.modulePackages.isNotEmpty()) {
            out.add(ModulePackages(pool.utf8("ModulePackages"), 0, info.modulePackages.map { pool.package_(it) }))
        }
        info.moduleMainClass?.let { out.add(ModuleMainClass(pool.utf8("ModuleMainClass"), 0, pool.classInfo(it))) }
        return out
    }

    /** `имя(дескриптор)` → CONSTANT_NameAndType. */
    private fun nameAndType(method: String): Int {
        val paren = method.indexOf('(')
        require(paren > 0) { "EnclosingMethod: ожидалось имя(дескриптор), получено $method" }
        return pool.nameAndType(method.substring(0, paren), method.substring(paren))
    }

    private fun module(m: ModuleSpec): Module = Module(
        pool.utf8("Module"), 0,
        pool.module(m.name), m.flags,
        m.version?.let { pool.utf8(it) } ?: 0,
        m.requires.map { Module.RequiresEntry(pool.module(it.module), it.flags, it.version?.let { v -> pool.utf8(v) } ?: 0) },
        m.exports.map { Module.ExportsEntry(pool.package_(it.pkg), it.flags, it.to.map { s -> pool.module(s) }) },
        m.opens.map { Module.OpensEntry(pool.package_(it.pkg), it.flags, it.to.map { s -> pool.module(s) }) },
        m.uses.map { pool.classInfo(it) },
        m.provides.map { p -> Module.ProvidesEntry(pool.classInfo(p.service), p.with.map { pool.classInfo(it) }) }
    )
}
