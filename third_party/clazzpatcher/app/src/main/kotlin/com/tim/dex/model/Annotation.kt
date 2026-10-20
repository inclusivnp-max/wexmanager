package com.tim.dex.model

/**
 * Аннотация - содержит тип и элементы аннотации.
 * 
 * @property typeIdx Индекс типа аннотации
 * @property elements Список элементов аннотации (пары имя-значение)
 */
data class Annotation(
    val typeIdx: Int,
    val elements: List<AnnotationElement>
)
