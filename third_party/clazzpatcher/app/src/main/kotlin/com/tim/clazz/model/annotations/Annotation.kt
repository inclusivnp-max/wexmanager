package com.tim.clazz.model.annotations

/**
 * Структура аннотации
 */
data class Annotation(
    val typeIndex: Int,
    val elementValuePairs: List<ElementValuePair>
)

/**
 * Пара "имя-значение" в аннотации
 */
data class ElementValuePair(
    val elementNameIndex: Int,
    val value: ElementValue
)

/**
 * Значения элементов аннотации
 */
sealed class ElementValue {
    abstract val tag: Char
}

data class ConstValueIndexElement(
    override val tag: Char,
    val constValueIndex: Int
) : ElementValue()

data class Utf8ValueIndexElement(
    override val tag: Char,
    val utf8ValueIndex: Int
) : ElementValue()

data class EnumConstValueElement(
    override val tag: Char,
    val typeNameIndex: Int,
    val constNameIndex: Int
) : ElementValue()

data class ClassInfoIndexElement(
    override val tag: Char,
    val classInfoIndex: Int
) : ElementValue()

data class AnnotationValueElement(
    override val tag: Char,
    val annotation: Annotation
) : ElementValue()

data class ArrayValueElement(
    override val tag: Char,
    val values: List<ElementValue>
) : ElementValue()