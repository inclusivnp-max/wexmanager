package com.tim.casm.dsl

import com.tim.casm.model.AccessFlags
import com.tim.casm.model.AnnotationInfo
import com.tim.casm.model.FieldInfo

class FieldDsl(private val name: String) {
    var descriptor: String = ""
    var accessFlags: Int = AccessFlags.ACC_PUBLIC
    var constantValue: Any? = null
    var signature: String? = null
    private val annotations = ArrayList<AnnotationInfo>()

    fun descriptor(d: String) { descriptor = d }
    fun type(canonical: String) { descriptor = com.tim.casm.model.JvmType.descriptorOf(canonical) }

    /** Видимость взаимоисключающая: новая заменяет прежнюю. */
    fun public() = visibility(AccessFlags.ACC_PUBLIC)
    fun private() = visibility(AccessFlags.ACC_PRIVATE)
    fun protected() = visibility(AccessFlags.ACC_PROTECTED)
    fun packagePrivate() = visibility(0)

    private fun visibility(flag: Int) {
        accessFlags = (accessFlags and VISIBILITY.inv()) or flag
    }
    fun static() { accessFlags = accessFlags or AccessFlags.ACC_STATIC }
    fun final() { accessFlags = accessFlags or AccessFlags.ACC_FINAL }
    fun volatile() { accessFlags = accessFlags or AccessFlags.ACC_VOLATILE }
    fun transient() { accessFlags = accessFlags or AccessFlags.ACC_TRANSIENT }
    fun synthetic() { accessFlags = accessFlags or AccessFlags.ACC_SYNTHETIC }
    fun enum_() { accessFlags = accessFlags or AccessFlags.ACC_ENUM }
    fun accessFlags(f: Int) { accessFlags = f }

    fun constant(value: Any?) { constantValue = value }
    fun signature(s: String) { signature = s }

    fun annotation(block: AnnotationDsl.() -> Unit) {
        val dsl = AnnotationDsl(); dsl.block(); annotations.add(dsl.buildInfo())
    }

    internal fun build(): FieldInfo {
        require(descriptor.isNotEmpty()) { "Поле $name: не задан дескриптор" }
        return FieldInfo(
            name = name, descriptor = descriptor, accessFlags = accessFlags,
            annotations = annotations.toList(), constantValue = constantValue, signature = signature
        )
    }

    private companion object {
        const val VISIBILITY = AccessFlags.ACC_PUBLIC or AccessFlags.ACC_PRIVATE or AccessFlags.ACC_PROTECTED
    }
}
