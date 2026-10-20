package com.tim.csmali.disasm

import com.tim.clazz.model.*
import com.tim.clazz.model.annotations.Annotation
import com.tim.clazz.model.annotations.AnnotationValueElement
import com.tim.clazz.model.annotations.ArrayValueElement
import com.tim.clazz.model.annotations.ClassInfoIndexElement
import com.tim.clazz.model.annotations.ConstValueIndexElement
import com.tim.clazz.model.annotations.ElementValue
import com.tim.clazz.model.annotations.ElementValuePair
import com.tim.clazz.model.annotations.EnumConstValueElement
import com.tim.clazz.model.annotations.Utf8ValueIndexElement
import com.tim.clazz.model.attributes.*
import com.tim.clazz.model.attributes.Deprecated
import com.tim.clazz.model.attributes.RuntimeVisibleTypeAnnotations.TypeAnnotation
import com.tim.csmali.SmaliFormatter
import com.tim.csmali.model.*

/**
 * [ClassFile] → [SmaliTree]. Каждый поддерживаемый атрибут переносится в дерево без потерь;
 * атрибуты, которые csmali не умеет выразить, — ошибка с указанием места, а не молчаливый пропуск.
 */
class ClassToTree {

    fun build(cf: ClassFile): SmaliTree {
        val tree = SmaliTree()
        tree.classes.add(ClassBuilder(cf).build())
        return tree
    }

    private class ClassBuilder(private val cf: ClassFile) {
        private val names = ClassNames(cf)

        fun build(): SmaliClass {
            val descriptor = "L${cf.thisClassName};"
            try {
                return buildClass(descriptor)
            } catch (e: Exception) {
                throw IllegalStateException("Класс $descriptor: ${e.message}", e)
            }
        }

        private fun buildClass(descriptor: String): SmaliClass {
            val cls = SmaliClass(descriptor = descriptor)
            cls.majorVersion = cf.majorVersion
            cls.minorVersion = cf.minorVersion
            cls.accessFlags.addAll(classFlags(cf.accessFlags))
            cls.superClass = cf.superClassName?.let { ClassNames.classDescriptor(it) }
            for (i in cf.interfaces) cls.interfaces.add(names.typeText(i))

            for (a in cf.attributes) {
                when (a) {
                    is SourceFile -> cls.sourceFile = cf.utf8(a.sourceFileIndex)
                    is Signature -> cls.signature = cf.utf8(a.signatureIndex)
                    is SourceDebugExtension -> cls.sourceDebugExtension = String(a.debugExtension, Charsets.UTF_8)
                    is RuntimeVisibleAnnotations -> a.annotations.mapTo(cls.annotations) { annotation(it, visibility(a)) }
                    is RuntimeVisibleTypeAnnotations -> a.annotations.mapTo(cls.typeAnnotations) { typeAnnotation(it, visibility(a)) }
                    is InnerClasses -> for (e in a.classes) cls.innerClasses.add(SmaliInnerClass(
                        innerInfo = names.typeText(e.innerClassInfoIndex),
                        outerInfo = if (e.outerClassInfoIndex == 0) null else names.typeText(e.outerClassInfoIndex),
                        innerName = if (e.innerNameIndex == 0) null else cf.utf8(e.innerNameIndex),
                        flags = innerClassFlags(e.innerClassAccessFlags)
                    ))
                    is EnclosingMethod -> cls.enclosingMethod =
                        names.typeText(a.classIndex) to (if (a.methodIndex == 0) null else names.nameAndType(a.methodIndex))
                    is NestHost -> cls.nestHost = names.typeText(a.hostClassIndex)
                    is NestMembers -> a.classes.mapTo(cls.nestMembers) { names.typeText(it) }
                    is PermittedSubclasses -> a.classes.mapTo(cls.permittedSubclasses) { names.typeText(it) }
                    is Record -> { cls.isRecord = true; a.components.mapTo(cls.recordComponents) { recordComponent(it) } }
                    is Module -> cls.module = module(a)
                    is ModulePackages -> moduleOf(cls).packages.addAll(a.packageIndex.map { packageName(it) })
                    is ModuleMainClass -> moduleOf(cls).mainClass = names.typeText(a.mainClassIndex)
                    is Deprecated -> cls.accessFlags.add(DEPRECATED)
                    is Synthetic -> cls.accessFlags.add(SYNTHETIC_ATTRIBUTE)
                    is BootstrapMethods -> {} // восстанавливается из invokedynamic/ldc при сборке
                    is UnknownAttribute -> cls.rawAttributes.add(raw(a))
                    else -> unsupported(a, "класса")
                }
            }
            for (f in cf.fields) cls.fields.add(field(f))
            for (m in cf.methods) cls.methods.add(method(m))
            return cls
        }

        private fun moduleOf(cls: SmaliClass): SmaliModule =
            cls.module ?: throw IllegalStateException("ModulePackages/ModuleMainClass без атрибута Module")

        private fun field(f: FieldInfo): SmaliField {
            val name = cf.utf8(f.nameIndex)
            try {
                val field = SmaliField(fieldFlags(f.accessFlags).toMutableList(), name, cf.utf8(f.descriptorIndex))
                for (a in f.attributes) {
                    when (a) {
                        is ConstantValue -> field.initialValue = constantValue(a.constantValueIndex)
                        is Signature -> field.signature = cf.utf8(a.signatureIndex)
                        is RuntimeVisibleAnnotations -> a.annotations.mapTo(field.annotations) { annotation(it, visibility(a)) }
                        is RuntimeVisibleTypeAnnotations -> a.annotations.mapTo(field.typeAnnotations) { typeAnnotation(it, visibility(a)) }
                        is Deprecated -> field.accessFlags.add(DEPRECATED)
                        is Synthetic -> field.accessFlags.add(SYNTHETIC_ATTRIBUTE)
                        is UnknownAttribute -> field.rawAttributes.add(raw(a))
                        else -> unsupported(a, "поля")
                    }
                }
                return field
            } catch (e: Exception) {
                throw IllegalStateException("Поле $name: ${e.message}", e)
            }
        }

        private fun constantValue(idx: Int): String = when (val e = cf.constantPool.getOrNull(idx)) {
            is ConstantIntegerInfo, is ConstantFloatInfo, is ConstantLongInfo, is ConstantDoubleInfo, is ConstantStringInfo ->
                names.constantText(idx)
            else -> throw IllegalStateException("ConstantValue #$idx: недопустимая запись ${e?.tagName ?: "пусто"}")
        }

        private fun method(m: MethodInfo): SmaliMethod {
            val name = cf.utf8(m.nameIndex)
            val desc = cf.utf8(m.descriptorIndex)
            try {
                val (params, ret) = parseMethodDescriptor(desc)
                val method = SmaliMethod(methodFlags(m.accessFlags).toMutableList(), name, ret)
                method.paramTypes.addAll(params)
                val isStatic = m.accessFlags and 0x0008 != 0
                for (a in m.attributes) {
                    when (a) {
                        is Code -> MethodBodyBuilder(cf, names, a, params, isStatic).build(method) { ta ->
                            ta.annotations.map { typeAnnotation(it, visibility(ta)) }
                        }
                        is Signature -> method.signature = cf.utf8(a.signatureIndex)
                        is Exceptions -> a.exceptionIndexTable.mapTo(method.exceptions) { names.typeText(it) }
                        is RuntimeVisibleAnnotations -> a.annotations.mapTo(method.annotations) { annotation(it, visibility(a)) }
                        is RuntimeVisibleParameterAnnotations -> {
                            val vis = visibility(a)
                            for ((i, pa) in a.parameterAnnotations.withIndex()) {
                                if (pa.annotations.isEmpty()) continue
                                val list = method.parameterAnnotations.getOrPut(i) { ArrayList() }
                                pa.annotations.mapTo(list) { annotation(it, vis) }
                            }
                        }
                        is RuntimeVisibleTypeAnnotations -> a.annotations.mapTo(method.typeAnnotations) { typeAnnotation(it, visibility(a)) }
                        is AnnotationDefault -> method.annotationDefault = elementValue(a.defaultValue)
                        is MethodParameters -> a.parameters.mapTo(method.methodParameters) {
                            SmaliMethodParameter(if (it.nameIndex == 0) null else cf.utf8(it.nameIndex), it.accessFlags)
                        }
                        is Deprecated -> method.accessFlags.add(DEPRECATED)
                        is Synthetic -> method.accessFlags.add(SYNTHETIC_ATTRIBUTE)
                        is UnknownAttribute -> method.rawAttributes.add(raw(a))
                        else -> unsupported(a, "метода")
                    }
                }
                return method
            } catch (e: Exception) {
                throw IllegalStateException("Метод $name$desc: ${e.message}", e)
            }
        }

        private fun raw(a: UnknownAttribute) = SmaliRawAttribute(cf.utf8(a.attributeNameIndex), a.data)

        private fun unsupported(a: AttributeInfo, where: String): Nothing =
            throw IllegalStateException("атрибут ${cf.attributeName(a)} у $where не поддерживается csmali")

        /** Видимость по имени атрибута: Runtime*Invisible* → build, иначе runtime. */
        private fun visibility(a: AttributeInfo): String =
            if (cf.attributeName(a).startsWith("RuntimeInvisible")) "build" else "runtime"

        private fun annotation(ann: Annotation, visibility: String): SmaliAnnotation {
            val out = SmaliAnnotation(visibility = visibility, type = names.descriptorText(ann.typeIndex))
            for (p in ann.elementValuePairs) out.elements.add(element(p))
            return out
        }

        private fun element(p: ElementValuePair) = SmaliAnnotationElement(cf.utf8(p.elementNameIndex), elementValue(p.value))

        private fun typeAnnotation(ann: TypeAnnotation, visibility: String): SmaliTypeAnnotation {
            val inner = SmaliAnnotation(visibility = visibility, type = names.descriptorText(ann.typeIndex), isSub = true)
            for (p in ann.elementValuePairs) inner.elements.add(element(p))
            return SmaliTypeAnnotation(ann.targetType, ann.targetInfo, ann.typePath, inner)
        }

        private fun recordComponent(rc: Record.RecordComponentInfo): SmaliRecordComponent {
            val out = SmaliRecordComponent(cf.utf8(rc.nameIndex), cf.utf8(rc.descriptorIndex))
            for (a in rc.attributes) {
                when (a) {
                    is Signature -> out.signature = cf.utf8(a.signatureIndex)
                    is RuntimeVisibleAnnotations -> a.annotations.mapTo(out.annotations) { annotation(it, visibility(a)) }
                    is RuntimeVisibleTypeAnnotations -> a.annotations.mapTo(out.typeAnnotations) { typeAnnotation(it, visibility(a)) }
                    else -> unsupported(a, "компонента record ${out.name}")
                }
            }
            return out
        }

        private fun moduleName(idx: Int): String = cf.utf8((cf.constantPool[idx] as ConstantModuleInfo).nameIndex)
        private fun packageName(idx: Int): String = cf.utf8((cf.constantPool[idx] as ConstantPackageInfo).nameIndex)

        private fun module(m: Module): SmaliModule {
            val out = SmaliModule(
                name = moduleName(m.moduleNameIndex),
                version = if (m.moduleVersionIndex == 0) null else cf.utf8(m.moduleVersionIndex),
                flags = m.moduleFlags
            )
            for (r in m.requires) out.requires.add(SmaliModule.Requires(
                moduleName(r.requiresIndex), r.requiresFlags,
                if (r.requiresVersionIndex == 0) null else cf.utf8(r.requiresVersionIndex)
            ))
            for (e in m.exports) out.exports.add(SmaliModule.Exports(packageName(e.exportsIndex), e.exportsFlags, e.exportsTo.map { moduleName(it) }))
            for (o in m.opens) out.opens.add(SmaliModule.Opens(packageName(o.opensIndex), o.opensFlags, o.opensTo.map { moduleName(it) }))
            m.uses.mapTo(out.uses) { names.typeText(it) }
            for (p in m.provides) out.provides.add(SmaliModule.Provides(names.typeText(p.providesIndex), p.with.map { names.typeText(it) }))
            return out
        }

        private fun elementValue(v: ElementValue): String = when (v) {
            is ConstValueIndexElement -> {
                val e = cf.constantPool.getOrNull(v.constValueIndex)
                    ?: throw IllegalStateException("element_value: #${v.constValueIndex} вне пула")
                fun int(): Int = (e as? ConstantIntegerInfo)?.value
                    ?: throw IllegalStateException("element_value '${v.tag}': ожидался CONSTANT_Integer, найдено ${e.tagName}")
                when (v.tag) {
                    'B' -> SmaliFormatter.appendIntegral(StringBuilder(), int().toByte().toLong(), 't').toString()
                    'S' -> SmaliFormatter.appendIntegral(StringBuilder(), int().toShort().toLong(), 's').toString()
                    'C' -> StringBuilder(8).also { SmaliFormatter.appendChar(it, int().toChar()) }.toString()
                    'I' -> SmaliFormatter.toHex(int().toLong())
                    'Z' -> when (int()) { 0 -> "false"; 1 -> "true"; else -> throw IllegalStateException("boolean-значение ${int()} вне 0..1") }
                    'J', 'F', 'D' -> names.constantText(v.constValueIndex)
                    else -> throw IllegalStateException("element_value: неизвестный тег '${v.tag}'")
                }
            }
            is Utf8ValueIndexElement -> names.quotedString(v.utf8ValueIndex)
            is EnumConstValueElement -> {
                val type = names.descriptorText(v.typeNameIndex)
                ".enum $type->${SmaliFormatter.simpleName(cf.utf8(v.constNameIndex))}:$type"
            }
            is ClassInfoIndexElement -> names.descriptorText(v.classInfoIndex)
            is AnnotationValueElement -> subannotation(v.annotation)
            is ArrayValueElement -> if (v.values.isEmpty()) "{}" else v.values.joinToString(", ", "{ ", " }") { elementValue(it) }
        }

        private fun subannotation(ann: Annotation): String {
            val sb = StringBuilder(64)
            sb.append(".subannotation ").append(names.descriptorText(ann.typeIndex))
            for (p in ann.elementValuePairs) {
                sb.append('\n').append(SmaliFormatter.simpleName(cf.utf8(p.elementNameIndex))).append(" = ").append(elementValue(p.value))
            }
            return sb.append("\n.end subannotation").toString()
        }
    }

    companion object {
        /** Псевдофлаги: атрибуты Deprecated и Synthetic (не биты access_flags). */
        const val DEPRECATED = "deprecated"
        const val SYNTHETIC_ATTRIBUTE = "synthetic-attribute"

        fun classFlags(flags: Int): List<String> = flagNames(flags, CLASS_FLAGS)
        fun fieldFlags(flags: Int): List<String> = flagNames(flags, FIELD_FLAGS)
        fun methodFlags(flags: Int): List<String> = flagNames(flags, METHOD_FLAGS)
        fun innerClassFlags(flags: Int): List<String> = flagNames(flags, INNER_FLAGS)

        private val CLASS_FLAGS = listOf(0x0001 to "public", 0x0010 to "final", 0x0020 to "super", 0x0200 to "interface",
            0x0400 to "abstract", 0x1000 to "synthetic", 0x2000 to "annotation", 0x4000 to "enum", 0x8000 to "module")
        private val FIELD_FLAGS = listOf(0x0001 to "public", 0x0002 to "private", 0x0004 to "protected", 0x0008 to "static",
            0x0010 to "final", 0x0040 to "volatile", 0x0080 to "transient", 0x1000 to "synthetic", 0x4000 to "enum")
        private val METHOD_FLAGS = listOf(0x0001 to "public", 0x0002 to "private", 0x0004 to "protected", 0x0008 to "static",
            0x0010 to "final", 0x0020 to "synchronized", 0x0040 to "bridge", 0x0080 to "varargs", 0x0100 to "native",
            0x0400 to "abstract", 0x0800 to "strict", 0x1000 to "synthetic")
        private val INNER_FLAGS = listOf(0x0001 to "public", 0x0002 to "private", 0x0004 to "protected", 0x0008 to "static",
            0x0010 to "final", 0x0200 to "interface", 0x0400 to "abstract", 0x1000 to "synthetic", 0x2000 to "annotation",
            0x4000 to "enum")

        /** Известные биты — именами, остальные — одним числом `0x…` (чтобы не терять). */
        private fun flagNames(flags: Int, table: List<Pair<Int, String>>): List<String> {
            val out = ArrayList<String>(4)
            var rest = flags
            for ((bit, name) in table) if (flags and bit != 0) { out.add(name); rest = rest and bit.inv() }
            if (rest != 0) out.add("0x" + Integer.toHexString(rest))
            return out
        }

        fun parseMethodDescriptor(desc: String): Pair<List<String>, String> {
            require(desc.startsWith("(")) { "Дескриптор метода должен начинаться с '(': $desc" }
            val params = ArrayList<String>()
            var i = 1
            while (i < desc.length) {
                if (desc[i] == ')') return params to desc.substring(i + 1)
                val start = i
                while (i < desc.length && desc[i] == '[') i++
                require(i < desc.length) { "Некорректный дескриптор метода: $desc" }
                if (desc[i] == 'L') {
                    i = desc.indexOf(';', i)
                    require(i > 0) { "Дескриптор класса без ';': $desc" }
                } else {
                    require(desc[i] in "ZBCSIJFD") { "Некорректный дескриптор типа: $desc (позиция $i)" }
                }
                i++
                params.add(desc.substring(start, i))
            }
            throw IllegalArgumentException("Дескриптор метода без ')': $desc")
        }
    }
}
