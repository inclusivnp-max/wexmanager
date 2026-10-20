package com.tim.clazz.model.attributes

data class Record(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val components: List<RecordComponentInfo>
) : AttributeInfo(attributeNameIndex, attributeLength) {
    
    data class RecordComponentInfo(
        val nameIndex: Int,
        val descriptorIndex: Int,
        val attributes: List<AttributeInfo>
    )
}