package com.tim.clazz.model

import com.tim.clazz.model.attributes.AttributeInfo

data class FieldInfo(
    val accessFlags: Int,
    val nameIndex: Int,
    val descriptorIndex: Int,
    val attributes: List<AttributeInfo>
) {
    val attributesCount: Int get() = attributes.size
    
    companion object {
        const val ACC_PUBLIC = 0x0001
        const val ACC_PRIVATE = 0x0002
        const val ACC_PROTECTED = 0x0004
        const val ACC_STATIC = 0x0008
        const val ACC_FINAL = 0x0010
        const val ACC_VOLATILE = 0x0040
        const val ACC_TRANSIENT = 0x0080
        const val ACC_SYNTHETIC = 0x1000
        const val ACC_ENUM = 0x4000
    }
}