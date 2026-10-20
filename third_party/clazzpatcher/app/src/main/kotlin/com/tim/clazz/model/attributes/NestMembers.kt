package com.tim.clazz.model.attributes

data class NestMembers(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val classes: List<Int>
) : AttributeInfo(attributeNameIndex, attributeLength)