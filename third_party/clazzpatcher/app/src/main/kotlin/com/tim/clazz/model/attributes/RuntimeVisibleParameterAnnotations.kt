package com.tim.clazz.model.attributes

import com.tim.clazz.model.annotations.Annotation

/**
 * RuntimeVisibleParameterAnnotations и RuntimeInvisibleParameterAnnotations (JVMS §4.7.18, §4.7.19):
 * вид определяется именем атрибута ([attributeNameIndex]).
 */
data class RuntimeVisibleParameterAnnotations(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val parameterAnnotations: List<ParameterAnnotation>
) : AttributeInfo(attributeNameIndex, attributeLength) {

    data class ParameterAnnotation(
        val annotations: List<Annotation>
    )
}
