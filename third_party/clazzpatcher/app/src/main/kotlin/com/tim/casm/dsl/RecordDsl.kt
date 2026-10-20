package com.tim.casm.dsl

import com.tim.casm.model.AnnotationInfo
import com.tim.casm.model.RecordComponentSpec

class RecordDsl(private val name: String) {
    var descriptor: String = ""
    var signature: String? = null
    private val annotations = ArrayList<AnnotationInfo>()

    fun descriptor(d: String) { descriptor = d }
    fun type(canonical: String) { descriptor = com.tim.casm.model.JvmType.descriptorOf(canonical) }
    fun signature(s: String) { signature = s }

    fun annotation(block: AnnotationDsl.() -> Unit) {
        val dsl = AnnotationDsl(); dsl.block(); annotations.add(dsl.buildInfo())
    }

    internal fun build(): RecordComponentSpec {
        require(descriptor.isNotEmpty()) { "Record-компонент $name: не задан дескриптор" }
        return RecordComponentSpec(name, descriptor, signature, annotations.toList())
    }
}
