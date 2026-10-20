package com.tim.clazz.model.attributes

import com.tim.clazz.model.annotations.Annotation

/**
 * RuntimeVisibleAnnotations и RuntimeInvisibleAnnotations (JVMS §4.7.16, §4.7.17):
 * вид определяется именем атрибута ([attributeNameIndex]).
 */
data class RuntimeVisibleAnnotations(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val annotations: List<Annotation>
) : AttributeInfo(attributeNameIndex, attributeLength)
