package com.tim.csmali.asm

import com.tim.casm.assembler.CodeAssembler
import com.tim.casm.builder.CodeAttributes
import com.tim.casm.frames.FrameMode
import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.pool.BootstrapMethodPool
import com.tim.casm.pool.ConstantPoolBuilder
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.FieldInfo
import com.tim.clazz.model.MethodInfo
import com.tim.clazz.model.annotations.Annotation
import com.tim.clazz.model.annotations.ElementValuePair
import com.tim.clazz.model.attributes.*
import com.tim.clazz.model.attributes.Deprecated
import com.tim.csmali.disasm.ClassToTree
import com.tim.csmali.model.*

/**
 * [SmaliClass] → [ClassFile]. Фреймы StackMapTable считаются заново (casm) для версий ≥ 50;
 * [hierarchy] нужна для точного слияния ссылочных типов (иначе — `java/lang/Object`).
 */
class TreeToClass(private val hierarchy: ClassHierarchy = ClassHierarchy.EMPTY) {

    fun build(tree: SmaliTree): ClassFile {
        require(tree.classes.size == 1) { "csmali: один SmaliTree = один класс (в дереве ${tree.classes.size})" }
        return build(tree.classes[0])
    }

    fun build(cls: SmaliClass): ClassFile = try {
        Builder(cls).build()
    } catch (e: Exception) {
        throw IllegalArgumentException("Класс ${cls.descriptor}: ${e.message}", e)
    }

    private inner class Builder(private val cls: SmaliClass) {
        private val pool = ConstantPoolBuilder()
        private val bootstrap = BootstrapMethodPool(pool)
        private val refs = SmaliRefs(pool, bootstrap)
        private val values = SmaliValueBuilder(pool)
        private val owner = SmaliRefs.internalName(cls.descriptor)

        fun build(): ClassFile {
            require(cls.majorVersion in 45..0xFFFF) { "Некорректная версия класса ${cls.majorVersion}" }
            reserveLdcConstants()
            val thisIdx = pool.classInfo(owner)
            val superIdx = cls.superClass?.let { refs.type(it) } ?: 0
            val interfaces = cls.interfaces.map { refs.type(it) }
            val fields = cls.fields.map { field(it) }
            val methods = cls.methods.map { method(it) }

            val attrs = ArrayList<AttributeInfo>()
            cls.sourceFile?.let { attrs.add(SourceFile(pool.utf8("SourceFile"), 0, pool.utf8(it))) }
            cls.signature?.let { attrs.add(Signature(pool.utf8("Signature"), 0, pool.utf8(it))) }
            cls.sourceDebugExtension?.let {
                attrs.add(SourceDebugExtension(pool.utf8("SourceDebugExtension"), 0, it.toByteArray(Charsets.UTF_8)))
            }
            addAnnotations(attrs, cls.annotations)
            addTypeAnnotations(attrs, cls.typeAnnotations)
            addFlagAttributes(attrs, cls.accessFlags)
            if (cls.innerClasses.isNotEmpty()) attrs.add(innerClasses(cls.innerClasses))
            cls.enclosingMethod?.let { (outer, method) ->
                attrs.add(EnclosingMethod(pool.utf8("EnclosingMethod"), 0, refs.type(outer), method?.let { nameAndType(it) } ?: 0))
            }
            cls.nestHost?.let { attrs.add(NestHost(pool.utf8("NestHost"), 0, refs.type(it))) }
            if (cls.nestMembers.isNotEmpty()) {
                attrs.add(NestMembers(pool.utf8("NestMembers"), 0, cls.nestMembers.map { refs.type(it) }))
            }
            if (cls.permittedSubclasses.isNotEmpty()) {
                attrs.add(PermittedSubclasses(pool.utf8("PermittedSubclasses"), 0, cls.permittedSubclasses.map { refs.type(it) }))
            }
            if (cls.isRecord || cls.recordComponents.isNotEmpty()) attrs.add(record(cls.recordComponents))
            cls.module?.let { module(attrs, it) }
            addRawAttributes(attrs, cls.rawAttributes)
            bootstrap.build(pool.utf8("BootstrapMethods"))?.let { attrs.add(it) }

            return ClassFile(
                magic = 0xCAFEBABEL,
                minorVersion = cls.minorVersion,
                majorVersion = cls.majorVersion,
                constantPool = pool.build(),
                accessFlags = flagsOf(cls.accessFlags),
                thisClass = thisIdx,
                superClass = superIdx,
                interfaces = interfaces,
                fields = fields,
                methods = methods,
                attributes = attrs
            )
        }

        /** Операнд `ldc` — u1: его константы регистрируются в пуле первыми, чтобы `ldc` не стал `ldc_w`. */
        private fun reserveLdcConstants() = pool.deferUtf8 {
            for (m in cls.methods) for (el in m.body) {
                if (el is SmaliInsn && el.text.startsWith("ldc ")) {
                    try {
                        refs.constant(SmaliScanner(el.text, 4), allowLong = false)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Метод ${m.name}: ${el.text}: ${e.message}", e)
                    }
                }
            }
        }

        private fun field(f: SmaliField): FieldInfo = try {
            val attrs = ArrayList<AttributeInfo>()
            f.initialValue?.let { attrs.add(ConstantValue(pool.utf8("ConstantValue"), 0, constantValue(it, f.type))) }
            f.signature?.let { attrs.add(Signature(pool.utf8("Signature"), 0, pool.utf8(it))) }
            addAnnotations(attrs, f.annotations)
            addTypeAnnotations(attrs, f.typeAnnotations)
            addFlagAttributes(attrs, f.accessFlags)
            addRawAttributes(attrs, f.rawAttributes)
            FieldInfo(flagsOf(f.accessFlags), pool.utf8(f.name), pool.utf8(f.type), attrs)
        } catch (e: Exception) {
            throw IllegalArgumentException("Поле ${f.name}:${f.type}: ${e.message}", e)
        }

        /** ConstantValue: тип записи пула по дескриптору поля (JVMS §4.7.2). */
        private fun constantValue(text: String, type: String): Int {
            if (text.startsWith("\"")) {
                require(type == "Ljava/lang/String;") { "строковое значение у поля типа $type" }
                return pool.string(SmaliLiterals.parseString(text))
            }
            val lit = SmaliLiterals.parse(text)
            return when (type) {
                "J" -> pool.long(if (lit.kind == SmaliLiterals.Kind.LONG) lit.bits else error("long", text))
                "F" -> pool.float(if (lit.kind == SmaliLiterals.Kind.FLOAT) lit.bits.toInt() else error("float", text))
                "D" -> pool.double(if (lit.kind == SmaliLiterals.Kind.DOUBLE) lit.bits else error("double", text))
                "I", "S", "C", "B", "Z" -> when (lit.kind) {
                    SmaliLiterals.Kind.LONG, SmaliLiterals.Kind.FLOAT, SmaliLiterals.Kind.DOUBLE -> error("int", text)
                    else -> pool.integer(lit.intValue)
                }
                else -> throw IllegalArgumentException("ConstantValue у поля типа $type")
            }
        }

        private fun error(expected: String, text: String): Nothing =
            throw IllegalArgumentException("ConstantValue: ожидалось значение $expected, получено $text")

        private fun method(m: SmaliMethod): MethodInfo = try {
            val desc = "(${m.paramTypes.joinToString("")})${m.returnType}"
            val attrs = ArrayList<AttributeInfo>()
            val hasBody = m.body.isNotEmpty() || m.catches.isNotEmpty() || m.maxStack >= 0 || m.maxLocals >= 0
            if (hasBody) {
                val isStatic = "static" in m.accessFlags
                val assembled = MethodAssembler(pool, refs, owner) { method ->
                    CodeAssembler.Options(method.maxStack, method.maxLocals, FrameMode.forVersion(cls.majorVersion), hierarchy)
                }.assemble(m, isStatic)
                val codeAttrs = ArrayList<AttributeInfo>()
                addTypeAnnotations(codeAttrs, m.typeAnnotations.filter { isCodeTarget(it.targetType) })
                addRawAttributes(codeAttrs, m.rawCodeAttributes)
                attrs.add(CodeAttributes.build(pool, assembled, codeAttrs))
            } else {
                require(m.rawCodeAttributes.isEmpty() && m.typeAnnotations.none { isCodeTarget(it.targetType) }) {
                    "атрибуты кода (.codeattribute, type-аннотации 0x40–0x4B) у метода без кода"
                }
            }
            m.signature?.let { attrs.add(Signature(pool.utf8("Signature"), 0, pool.utf8(it))) }
            if (m.exceptions.isNotEmpty()) attrs.add(Exceptions(pool.utf8("Exceptions"), 0, m.exceptions.map { refs.type(it) }))
            addAnnotations(attrs, m.annotations)
            val mixed = m.parameterAnnotations.keys.sorted().map { m.parameterAnnotations.getValue(it) }
                .firstOrNull { list -> list.any { visible(it) } && list.any { !visible(it) } }
            for (vis in visibilityOrder(mixed?.first())) addParameterAnnotations(attrs, m, vis)
            addTypeAnnotations(attrs, m.typeAnnotations.filterNot { isCodeTarget(it.targetType) })
            m.annotationDefault?.let { attrs.add(AnnotationDefault(pool.utf8("AnnotationDefault"), 0, values.build(it))) }
            if (m.methodParameters.isNotEmpty()) {
                attrs.add(MethodParameters(pool.utf8("MethodParameters"), 0, m.methodParameters.map {
                    MethodParameters.MethodParameter(it?.name?.let { n -> pool.utf8(n) } ?: 0, it?.flags ?: 0)
                }))
            }
            addFlagAttributes(attrs, m.accessFlags)
            addRawAttributes(attrs, m.rawAttributes)
            MethodInfo(flagsOf(m.accessFlags), pool.utf8(m.name), pool.utf8(desc), attrs)
        } catch (e: Exception) {
            throw IllegalArgumentException("Метод ${m.name}(${m.paramTypes.joinToString("")})${m.returnType}: ${e.message}", e)
        }

        /** target_type 0x40–0x4B относятся к коду метода (атрибут внутри Code, JVMS §4.7.20). */
        private fun isCodeTarget(targetType: Int): Boolean = targetType in 0x40..0x4B

        // ---- Аннотации ----

        private fun visible(a: SmaliAnnotation): Boolean = when (a.visibility) {
            "runtime" -> true
            "build" -> false
            else -> throw IllegalArgumentException("Видимость аннотации ${a.visibility} не поддерживается в .class (только runtime/build)")
        }

        /**
         * Видимые и невидимые аннотации — два атрибута, дизассемблер перечисляет их в порядке атрибутов.
         * Первым пишется атрибут той видимости, с которой начинается список: текст → class → текст
         * сохраняет порядок (у kotlin-reflect `build`-аннотации стоят перед `runtime`).
         */
        private fun visibilityOrder(first: SmaliAnnotation?): BooleanArray =
            if (first != null && !visible(first)) booleanArrayOf(false, true) else booleanArrayOf(true, false)

        private fun addAnnotations(out: MutableList<AttributeInfo>, annotations: List<SmaliAnnotation>) {
            for (vis in visibilityOrder(annotations.firstOrNull())) {
                val list = annotations.filter { visible(it) == vis }
                if (list.isEmpty()) continue
                val name = if (vis) "RuntimeVisibleAnnotations" else "RuntimeInvisibleAnnotations"
                out.add(RuntimeVisibleAnnotations(pool.utf8(name), 0, list.map { annotation(it) }))
            }
        }

        private fun addParameterAnnotations(out: MutableList<AttributeInfo>, m: SmaliMethod, visible: Boolean) {
            if (m.parameterAnnotations.values.none { list -> list.any { visible(it) == visible } }) return
            val maxIndex = m.parameterAnnotations.keys.maxOrNull() ?: return
            require(maxIndex < m.paramTypes.size) { "аннотация параметра $maxIndex, а параметров ${m.paramTypes.size}" }
            val name = if (visible) "RuntimeVisibleParameterAnnotations" else "RuntimeInvisibleParameterAnnotations"
            out.add(RuntimeVisibleParameterAnnotations(pool.utf8(name), 0, List(m.paramTypes.size) { i ->
                RuntimeVisibleParameterAnnotations.ParameterAnnotation(
                    (m.parameterAnnotations[i] ?: emptyList()).filter { visible(it) == visible }.map { annotation(it) }
                )
            }))
        }

        private fun addTypeAnnotations(out: MutableList<AttributeInfo>, annotations: List<SmaliTypeAnnotation>) {
            for (vis in visibilityOrder(annotations.firstOrNull()?.annotation)) {
                val list = annotations.filter { visible(it.annotation) == vis }
                if (list.isEmpty()) continue
                val name = if (vis) "RuntimeVisibleTypeAnnotations" else "RuntimeInvisibleTypeAnnotations"
                out.add(RuntimeVisibleTypeAnnotations(pool.utf8(name), 0, list.map {
                    val target = it.targetInfo as? RuntimeVisibleTypeAnnotations.TargetInfo
                        ?: throw IllegalArgumentException("type-аннотация ${it.annotation.type}: не задан target_info")
                    val a = annotation(it.annotation)
                    RuntimeVisibleTypeAnnotations.TypeAnnotation(it.targetType, target, it.typePath, a.typeIndex, a.elementValuePairs)
                }))
            }
        }

        private fun annotation(a: SmaliAnnotation): Annotation =
            Annotation(pool.utf8(a.type), a.elements.map { ElementValuePair(pool.utf8(it.name), values.build(it.value)) })

        // ---- Прочее ----

        private fun addFlagAttributes(out: MutableList<AttributeInfo>, flags: List<String>) {
            if (ClassToTree.DEPRECATED in flags) out.add(Deprecated(pool.utf8("Deprecated"), 0))
            if (ClassToTree.SYNTHETIC_ATTRIBUTE in flags) out.add(Synthetic(pool.utf8("Synthetic"), 0))
        }

        private fun addRawAttributes(out: MutableList<AttributeInfo>, raw: List<SmaliRawAttribute>) {
            for (a in raw) out.add(UnknownAttribute(pool.utf8(a.name), a.data.size.toLong(), a.data))
        }

        private fun innerClasses(inner: List<SmaliInnerClass>) = InnerClasses(pool.utf8("InnerClasses"), 0, inner.map {
            InnerClasses.InnerClassEntry(
                innerClassInfoIndex = refs.type(it.innerInfo),
                outerClassInfoIndex = it.outerInfo?.let { o -> refs.type(o) } ?: 0,
                innerNameIndex = it.innerName?.let { n -> pool.utf8(n) } ?: 0,
                innerClassAccessFlags = flagsOf(it.flags)
            )
        })

        private fun record(components: List<SmaliRecordComponent>) = Record(pool.utf8("Record"), 0, components.map { c ->
            val attrs = ArrayList<AttributeInfo>()
            c.signature?.let { attrs.add(Signature(pool.utf8("Signature"), 0, pool.utf8(it))) }
            addAnnotations(attrs, c.annotations)
            addTypeAnnotations(attrs, c.typeAnnotations)
            Record.RecordComponentInfo(pool.utf8(c.name), pool.utf8(c.descriptor), attrs)
        })

        /** `имя(дескриптор)` → CONSTANT_NameAndType (EnclosingMethod). */
        private fun nameAndType(text: String): Int {
            val paren = text.indexOf('(')
            require(paren > 0) { "EnclosingMethod: ожидалось имя(дескриптор), получено $text" }
            return pool.nameAndType(text.substring(0, paren), text.substring(paren))
        }

        private fun module(out: MutableList<AttributeInfo>, m: SmaliModule) {
            out.add(Module(
                pool.utf8("Module"), 0,
                pool.module(m.name), m.flags,
                m.version?.let { pool.utf8(it) } ?: 0,
                m.requires.map { Module.RequiresEntry(pool.module(it.module), it.flags, it.version?.let { v -> pool.utf8(v) } ?: 0) },
                m.exports.map { Module.ExportsEntry(pool.package_(it.pkg), it.flags, it.to.map { s -> pool.module(s) }) },
                m.opens.map { Module.OpensEntry(pool.package_(it.pkg), it.flags, it.to.map { s -> pool.module(s) }) },
                m.uses.map { refs.type(it) },
                m.provides.map { p -> Module.ProvidesEntry(refs.type(p.service), p.with.map { refs.type(it) }) }
            ))
            if (m.packages.isNotEmpty()) out.add(ModulePackages(pool.utf8("ModulePackages"), 0, m.packages.map { pool.package_(it) }))
            m.mainClass?.let { out.add(ModuleMainClass(pool.utf8("ModuleMainClass"), 0, refs.type(it))) }
        }
    }

    companion object {
        private val FLAGS = mapOf(
            "public" to 0x0001, "private" to 0x0002, "protected" to 0x0004, "static" to 0x0008,
            "final" to 0x0010, "super" to 0x0020, "synchronized" to 0x0020, "volatile" to 0x0040,
            "bridge" to 0x0040, "transient" to 0x0080, "varargs" to 0x0080, "native" to 0x0100,
            "interface" to 0x0200, "abstract" to 0x0400, "strict" to 0x0800, "synthetic" to 0x1000,
            "annotation" to 0x2000, "enum" to 0x4000, "module" to 0x8000
        )

        /** Имена флагов (и `0x…` для прочих битов) → access_flags; псевдофлаги атрибутов пропускаются. */
        fun flagsOf(flags: List<String>): Int {
            var f = 0
            for (s in flags) {
                if (s == ClassToTree.DEPRECATED || s == ClassToTree.SYNTHETIC_ATTRIBUTE) continue
                f = f or (FLAGS[s] ?: if (s.startsWith("0x")) SmaliLiterals.parseInt(s) else throw IllegalArgumentException("Неизвестный флаг $s"))
            }
            return f
        }
    }
}
