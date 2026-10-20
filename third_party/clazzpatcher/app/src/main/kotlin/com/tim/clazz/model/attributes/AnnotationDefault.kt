package com.tim.clazz.model.attributes

import com.tim.clazz.model.annotations.ElementValue

data class AnnotationDefault(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val defaultValue: ElementValue
) : AttributeInfo(attributeNameIndex, attributeLength)