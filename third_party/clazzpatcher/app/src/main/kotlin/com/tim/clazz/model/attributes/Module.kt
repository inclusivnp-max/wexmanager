package com.tim.clazz.model.attributes

data class Module(
    override val attributeNameIndex: Int,
    override val attributeLength: Long,
    val moduleNameIndex: Int,
    val moduleFlags: Int,
    val moduleVersionIndex: Int,
    val requires: List<RequiresEntry>,
    val exports: List<ExportsEntry>,
    val opens: List<OpensEntry>,
    val uses: List<Int>,
    val provides: List<ProvidesEntry>
) : AttributeInfo(attributeNameIndex, attributeLength) {
    
    data class RequiresEntry(
        val requiresIndex: Int,
        val requiresFlags: Int,
        val requiresVersionIndex: Int
    )
    
    data class ExportsEntry(
        val exportsIndex: Int,
        val exportsFlags: Int,
        val exportsTo: List<Int>
    )
    
    data class OpensEntry(
        val opensIndex: Int,
        val opensFlags: Int,
        val opensTo: List<Int>
    )
    
    data class ProvidesEntry(
        val providesIndex: Int,
        val with: List<Int>
    )
}