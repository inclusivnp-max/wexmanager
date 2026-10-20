package com.tim.c2d.meta

import com.tim.c2d.bytecode.ConstantPoolView
import com.tim.clazz.model.ConstantDoubleInfo
import com.tim.clazz.model.ConstantFloatInfo
import com.tim.clazz.model.ConstantIntegerInfo
import com.tim.clazz.model.ConstantLongInfo
import com.tim.clazz.model.annotations.AnnotationValueElement
import com.tim.clazz.model.annotations.ArrayValueElement
import com.tim.clazz.model.annotations.ClassInfoIndexElement
import com.tim.clazz.model.annotations.ConstValueIndexElement
import com.tim.clazz.model.annotations.ElementValue
import com.tim.clazz.model.annotations.EnumConstValueElement
import com.tim.clazz.model.annotations.Utf8ValueIndexElement
import com.tim.clazz.model.attributes.AttributeInfo
import com.tim.clazz.model.attributes.RuntimeVisibleAnnotations
import com.tim.clazz.model.attributes.RuntimeVisibleParameterAnnotations
import com.tim.dasm.DexAsm
import com.tim.dex.data.AnnotationVisibility
import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.Annotation
import com.tim.dex.model.AnnotationElement
import com.tim.dex.model.AnnotationItem
import com.tim.dex.model.EncodedValue
import com.tim.clazz.model.annotations.Annotation as JvmAnnotation

/**
 * Аннотации JVM (RuntimeVisible/Invisible*) → аннотации DEX.
 * Видимые получают видимость `runtime`, невидимые — `build` или отбрасываются ([keepInvisible]).
 */
class AnnotationConverter(
    private val asm: DexAsm,
    private val pool: ConstantPoolView,
    private val keepInvisible: Boolean = true
) {

    /** Аннотации элемента (класса, поля, метода) из его атрибутов. */
    fun annotations(attributes: List<AttributeInfo>): List<AnnotationItem> {
        var out: ArrayList<AnnotationItem>? = null
        for (a in attributes) {
            if (a !is RuntimeVisibleAnnotations) continue
            val visibility = visibility(a.attributeNameIndex, "RuntimeVisibleAnnotations") ?: continue
            val list = out ?: ArrayList<AnnotationItem>().also { out = it }
            for (ann in a.annotations) list.add(AnnotationItem(visibility, annotation(ann)))
        }
        return out ?: emptyList()
    }

    /**
     * Аннотации параметров: индекс → список. javac не пишет синтетические параметры
     * (например, внешний `this` конструктора внутреннего класса), поэтому при нехватке записей
     * они относятся к последним параметрам дескриптора.
     */
    fun parameterAnnotations(attributes: List<AttributeInfo>, paramCount: Int): Map<Int, List<AnnotationItem>> {
        val out = LinkedHashMap<Int, MutableList<AnnotationItem>>()
        for (a in attributes) {
            if (a !is RuntimeVisibleParameterAnnotations) continue
            val visibility = visibility(a.attributeNameIndex, "RuntimeVisibleParameterAnnotations") ?: continue
            val shift = maxOf(0, paramCount - a.parameterAnnotations.size)
            for ((i, p) in a.parameterAnnotations.withIndex()) {
                if (p.annotations.isEmpty()) continue
                val index = i + shift
                if (index >= paramCount) {
                    throw IllegalArgumentException("аннотации параметра #$index, а параметров $paramCount")
                }
                val list = out.getOrPut(index) { ArrayList() }
                for (ann in p.annotations) list.add(AnnotationItem(visibility, annotation(ann)))
            }
        }
        return out
    }

    fun annotation(a: JvmAnnotation): Annotation =
        Annotation(asm.types.add(pool.utf8(a.typeIndex)), a.elementValuePairs.map {
            AnnotationElement(asm.strings.add(pool.utf8(it.elementNameIndex)), value(it.value))
        })

    fun value(v: ElementValue): EncodedValue = when (v) {
        is ConstValueIndexElement -> constant(v)
        is Utf8ValueIndexElement -> EncodedValue(EncodedValueType.VALUE_STRING, asm.strings.add(pool.utf8(v.utf8ValueIndex)))
        is EnumConstValueElement -> {
            val type = pool.utf8(v.typeNameIndex)
            EncodedValue(EncodedValueType.VALUE_ENUM, asm.fields.add(type, pool.utf8(v.constNameIndex), type))
        }
        is ClassInfoIndexElement -> EncodedValue(EncodedValueType.VALUE_TYPE, asm.types.add(pool.utf8(v.classInfoIndex)))
        is AnnotationValueElement -> EncodedValue(EncodedValueType.VALUE_ANNOTATION, annotation(v.annotation))
        is ArrayValueElement -> EncodedValue(EncodedValueType.VALUE_ARRAY, v.values.map { value(it) })
    }

    private fun constant(v: ConstValueIndexElement): EncodedValue {
        val e = pool.cf.constantPool.getOrNull(v.constValueIndex)
        fun int(): Int = (e as? ConstantIntegerInfo)?.value
            ?: throw IllegalArgumentException("element_value '${v.tag}': #${v.constValueIndex} не CONSTANT_Integer")
        return when (v.tag) {
            'B' -> EncodedValue(EncodedValueType.VALUE_BYTE, int().toByte())
            'C' -> EncodedValue(EncodedValueType.VALUE_CHAR, int().toChar())
            'S' -> EncodedValue(EncodedValueType.VALUE_SHORT, int().toShort())
            'I' -> EncodedValue(EncodedValueType.VALUE_INT, int())
            'Z' -> EncodedValue(EncodedValueType.VALUE_BOOLEAN, int() != 0)
            'J' -> EncodedValue(EncodedValueType.VALUE_LONG, (e as? ConstantLongInfo)?.value
                ?: throw IllegalArgumentException("element_value 'J': #${v.constValueIndex} не CONSTANT_Long"))
            'F' -> EncodedValue(EncodedValueType.VALUE_FLOAT, (e as? ConstantFloatInfo)?.value
                ?: throw IllegalArgumentException("element_value 'F': #${v.constValueIndex} не CONSTANT_Float"))
            'D' -> EncodedValue(EncodedValueType.VALUE_DOUBLE, (e as? ConstantDoubleInfo)?.value
                ?: throw IllegalArgumentException("element_value 'D': #${v.constValueIndex} не CONSTANT_Double"))
            's' -> EncodedValue(EncodedValueType.VALUE_STRING, asm.strings.add(pool.utf8(v.constValueIndex)))
            else -> throw IllegalArgumentException("неизвестный тег element_value '${v.tag}'")
        }
    }

    /** Видимость по имени атрибута `Runtime{Visible|Invisible}…`; `null` — невидимые не переносятся. */
    private fun visibility(nameIndex: Int, visibleName: String): Byte? {
        val name = pool.utf8(nameIndex)
        return when (name) {
            visibleName -> AnnotationVisibility.VISIBILITY_RUNTIME.toByte()
            visibleName.replace("Visible", "Invisible") -> if (keepInvisible) AnnotationVisibility.VISIBILITY_BUILD.toByte() else null
            else -> throw IllegalArgumentException("неожиданное имя атрибута аннотаций: $name")
        }
    }
}
