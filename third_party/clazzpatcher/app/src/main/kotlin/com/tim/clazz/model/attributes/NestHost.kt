package com.tim.clazz.model.attributes

data class NestHost(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val hostClassIndex: Int
) : AttributeInfo(attributeNameIndex, attributeLength)