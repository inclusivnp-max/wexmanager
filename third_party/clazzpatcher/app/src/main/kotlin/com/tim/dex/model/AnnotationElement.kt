package com.tim.dex.model

/**
 * Элемент аннотации - пара (имя, значение).
 * 
 * @property nameIdx Индекс строки для имени элемента
 * @property value Закодированное значение
 */
data class AnnotationElement(
    val nameIdx: Int,
    val value: EncodedValue
)
