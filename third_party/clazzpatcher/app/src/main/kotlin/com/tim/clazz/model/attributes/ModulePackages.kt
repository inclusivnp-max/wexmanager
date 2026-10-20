package com.tim.clazz.model.attributes

data class ModulePackages(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val packageIndex: List<Int>
) : AttributeInfo(attributeNameIndex, attributeLength)