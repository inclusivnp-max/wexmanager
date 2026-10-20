package com.tim.dex.model

/**
 * Закодированное значение - значение в закодированном виде.
 * 
 * Используется для статических полей, аннотаций и точек вызова.
 * 
 * @property type Тип закодированного значения (VALUE_INT, VALUE_STRING и т.д.)
 * @property value Фактическое значение (тип зависит от type)
 */
data class EncodedValue(
    val type: Int,
    val value: Any?
)
