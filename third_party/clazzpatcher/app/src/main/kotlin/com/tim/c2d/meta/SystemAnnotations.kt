package com.tim.c2d.meta

import com.tim.c2d.bytecode.ConstantPoolView
import com.tim.c2d.bytecode.Descriptors
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.ConstantNameAndTypeInfo
import com.tim.clazz.model.attributes.AnnotationDefault
import com.tim.clazz.model.attributes.AttributeInfo
import com.tim.clazz.model.attributes.EnclosingMethod
import com.tim.clazz.model.attributes.Exceptions
import com.tim.clazz.model.attributes.InnerClasses
import com.tim.clazz.model.attributes.MethodParameters
import com.tim.clazz.model.attributes.PermittedSubclasses
import com.tim.clazz.model.attributes.Record
import com.tim.clazz.model.attributes.Signature
import com.tim.dasm.DexAsm
import com.tim.dex.data.AnnotationVisibility
import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.Annotation
import com.tim.dex.model.AnnotationElement
import com.tim.dex.model.AnnotationItem
import com.tim.dex.model.EncodedValue

/**
 * Атрибуты JVM, у которых в DEX есть только системные аннотации `dalvik.annotation.*`:
 * Signature, Exceptions, InnerClasses, EnclosingMethod, AnnotationDefault, MethodParameters,
 * Record и PermittedSubclasses (их ART читает с Android 14). NestHost/NestMembers, как и d8, не
 * переносятся: доступ внутри гнезда переводится через [NestAccess], а d8/R8 требуют хозяина гнезда
 * в том же наборе входных DEX (ломается обработка отдельных classesN.dex).
 */
class SystemAnnotations(
    private val asm: DexAsm,
    private val pool: ConstantPoolView,
    private val annotations: AnnotationConverter
) {
    private val cf: ClassFile = pool.cf

    /** `dalvik/annotation/Signature` для любого элемента с атрибутом Signature. */
    fun signature(attributes: List<AttributeInfo>): AnnotationItem? {
        val attr = attributes.firstOrNull { it is Signature } as Signature? ?: return null
        val parts = splitSignature(pool.utf8(attr.signatureIndex))
        return system("Ldalvik/annotation/Signature;", "value" to array(parts.map { string(it) }))
    }

    /** `dalvik/annotation/Throws` по атрибуту Exceptions. */
    fun throwsOf(attributes: List<AttributeInfo>): AnnotationItem? {
        val attr = attributes.firstOrNull { it is Exceptions } as Exceptions? ?: return null
        if (attr.exceptionIndexTable.isEmpty()) return null
        return system("Ldalvik/annotation/Throws;", "value" to array(attr.exceptionIndexTable.map { type(pool.classDescriptor(it)) }))
    }

    /** `dalvik/annotation/MethodParameters` по атрибуту MethodParameters. */
    fun methodParameters(attributes: List<AttributeInfo>): AnnotationItem? {
        val attr = attributes.firstOrNull { it is MethodParameters } as MethodParameters? ?: return null
        val names = attr.parameters.map {
            if (it.nameIndex == 0) EncodedValue(EncodedValueType.VALUE_NULL, null) else string(pool.utf8(it.nameIndex))
        }
        val flags = attr.parameters.map { EncodedValue(EncodedValueType.VALUE_INT, it.accessFlags) }
        return system("Ldalvik/annotation/MethodParameters;", "names" to array(names), "accessFlags" to array(flags))
    }

    /**
     * Аннотации класса из InnerClasses/EnclosingMethod (как у dx/d8): InnerClass и EnclosingClass
     * или EnclosingMethod для самого класса, MemberClasses — для его вложенных классов;
     * AnnotationDefault — для значений по умолчанию элементов аннотации.
     */
    fun classAnnotations(): List<AnnotationItem> {
        val out = ArrayList<AnnotationItem>()
        val inner = cf.attributes.firstOrNull { it is InnerClasses } as InnerClasses?
        val enclosing = cf.attributes.firstOrNull { it is EnclosingMethod } as EnclosingMethod?
        var enclosingClass: String? = null
        if (inner != null) {
            val members = ArrayList<EncodedValue>()
            for (e in inner.classes) {
                if (e.innerClassInfoIndex == 0) continue
                val innerName = pool.className(e.innerClassInfoIndex)
                if (innerName == pool.thisClass) {
                    // Локальный/анонимный класс без EnclosingMethod (старые компиляторы): как d8, запись отбрасывается —
                    // без парной EnclosingClass/EnclosingMethod аннотация InnerClass противоречива.
                    if (e.outerClassInfoIndex == 0 && enclosing == null) continue
                    val name = if (e.innerNameIndex == 0) EncodedValue(EncodedValueType.VALUE_NULL, null)
                    else string(pool.utf8(e.innerNameIndex))
                    out.add(system(
                        "Ldalvik/annotation/InnerClass;",
                        "accessFlags" to EncodedValue(EncodedValueType.VALUE_INT, e.innerClassAccessFlags),
                        "name" to name
                    ))
                    if (e.outerClassInfoIndex != 0) enclosingClass = pool.classDescriptor(e.outerClassInfoIndex)
                } else if (e.outerClassInfoIndex != 0 && pool.className(e.outerClassInfoIndex) == pool.thisClass &&
                    e.innerNameIndex != 0) {
                    members.add(type(Descriptors.ofClass(innerName)))
                }
            }
            if (members.isNotEmpty()) out.add(system("Ldalvik/annotation/MemberClasses;", "value" to array(members)))
        }

        when {
            enclosingClass != null ->
                out.add(system("Ldalvik/annotation/EnclosingClass;", "value" to type(enclosingClass)))
            enclosing != null && enclosing.methodIndex != 0 -> {
                val nat = cf.entry<ConstantNameAndTypeInfo>(enclosing.methodIndex)
                val owner = pool.classDescriptor(enclosing.classIndex)
                val methodType = Descriptors.method(pool.utf8(nat.descriptorIndex))
                val method = asm.methods.add(owner, pool.utf8(nat.nameIndex), methodType.params, methodType.returnType)
                out.add(system("Ldalvik/annotation/EnclosingMethod;", "value" to EncodedValue(EncodedValueType.VALUE_METHOD, method)))
            }
            enclosing != null ->
                out.add(system("Ldalvik/annotation/EnclosingClass;", "value" to type(pool.classDescriptor(enclosing.classIndex))))
        }

        annotationDefaults()?.let { out.add(it) }
        record()?.let { out.add(it) }
        (cf.attributes.firstOrNull { it is PermittedSubclasses } as PermittedSubclasses?)?.let { p ->
            out.add(system("Ldalvik/annotation/PermittedSubclasses;", "value" to array(p.classes.map { type(pool.classDescriptor(it)) })))
        }
        return out
    }

    /**
     * `dalvik/annotation/Record` (ART 14+: Class.isRecord/getRecordComponents): имена и типы компонент,
     * при наличии — сигнатуры (вложенные Signature, у компоненты без сигнатуры — null), видимости и аннотации.
     */
    private fun record(): AnnotationItem? {
        val attr = cf.attributes.firstOrNull { it is Record } as Record? ?: return null
        val c = attr.components
        val elements = ArrayList<Pair<String, EncodedValue>>()
        elements.add("componentNames" to array(c.map { string(pool.utf8(it.nameIndex)) }))
        elements.add("componentTypes" to array(c.map { type(pool.utf8(it.descriptorIndex)) }))
        val signatures = c.map { comp -> signature(comp.attributes)?.annotation }
        if (signatures.any { it != null }) {
            elements.add("componentSignatures" to array(signatures.map { s ->
                if (s == null) EncodedValue(EncodedValueType.VALUE_NULL, null) else EncodedValue(EncodedValueType.VALUE_ANNOTATION, s)
            }))
        }
        val componentAnnotations = c.map { annotations.annotations(it.attributes) }
        if (componentAnnotations.any { it.isNotEmpty() }) {
            elements.add("componentAnnotationVisibilities" to array(componentAnnotations.map { list ->
                array(list.map { EncodedValue(EncodedValueType.VALUE_BYTE, it.visibility) })
            }))
            elements.add("componentAnnotations" to array(componentAnnotations.map { list ->
                array(list.map { EncodedValue(EncodedValueType.VALUE_ANNOTATION, it.annotation) })
            }))
        }
        return system("Ldalvik/annotation/Record;", *elements.toTypedArray())
    }

    private fun annotationDefaults(): AnnotationItem? {
        val defaults = ArrayList<AnnotationElement>()
        for (m in cf.methods) {
            val attr = m.attributes.firstOrNull { it is AnnotationDefault } as AnnotationDefault? ?: continue
            defaults.add(AnnotationElement(asm.strings.add(pool.utf8(m.nameIndex)), annotations.value(attr.defaultValue)))
        }
        if (defaults.isEmpty()) return null
        val value = Annotation(asm.types.add(Descriptors.ofClass(pool.thisClass)), defaults)
        return system("Ldalvik/annotation/AnnotationDefault;", "value" to EncodedValue(EncodedValueType.VALUE_ANNOTATION, value))
    }

    private fun system(type: String, vararg elements: Pair<String, EncodedValue>): AnnotationItem =
        AnnotationItem(
            AnnotationVisibility.VISIBILITY_SYSTEM.toByte(),
            Annotation(asm.types.add(type), elements.map { AnnotationElement(asm.strings.add(it.first), it.second) })
        )

    private fun string(s: String) = EncodedValue(EncodedValueType.VALUE_STRING, asm.strings.add(s))
    private fun type(desc: String) = EncodedValue(EncodedValueType.VALUE_TYPE, asm.types.add(desc))
    private fun array(values: List<EncodedValue>) = EncodedValue(EncodedValueType.VALUE_ARRAY, values)

    companion object {
        /**
         * Разбиение сигнатуры на части как в d8: имя класса `L…` до `;` или `<` включительно,
         * остальное — до следующего `L`. Так одинаковые имена классов разделяют строки пула.
         */
        fun splitSignature(signature: String): List<String> {
            val parts = ArrayList<String>()
            var at = 0
            val n = signature.length
            while (at < n) {
                var end = at + 1
                if (signature[at] == 'L') {
                    while (end < n) {
                        val c = signature[end]
                        end++
                        if (c == ';' || c == '<') break
                    }
                } else {
                    while (end < n && signature[end] != 'L') end++
                }
                parts.add(signature.substring(at, end))
                at = end
            }
            return parts
        }
    }
}
