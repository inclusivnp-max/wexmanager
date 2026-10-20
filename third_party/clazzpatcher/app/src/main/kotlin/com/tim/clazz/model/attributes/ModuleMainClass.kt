package com.tim.clazz.model.attributes

data class ModuleMainClass(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val mainClassIndex: Int
) : AttributeInfo(attributeNameIndex, attributeLength)