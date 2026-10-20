package com.tim.dex.model

/**
 * Элемент аннотации - содержит одну аннотацию.
 * 
 * @property visibility Видимость аннотации (VISIBILITY_BUILD, VISIBILITY_RUNTIME, VISIBILITY_SYSTEM)
 * @property annotation Сама аннотация
 */
data class AnnotationItem(
    val visibility: Byte,
    val annotation: Annotation
)
