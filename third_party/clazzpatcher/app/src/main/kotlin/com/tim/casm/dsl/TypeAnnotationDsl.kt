package com.tim.casm.dsl

import com.tim.casm.model.AnnotationInfo
import com.tim.casm.model.TypeAnnotationInfo
import com.tim.clazz.model.attributes.RuntimeVisibleTypeAnnotations.*

class TypeAnnotationDsl(private val targetType: Int) {
    private var targetInfo: Any? = null
    private val typePath = ArrayList<Pair<Int, Int>>()
    private var annotation: AnnotationInfo? = null

    fun emptyTarget() { targetInfo = EmptyTarget }
    fun typeParameter(index: Int) { targetInfo = TypeParameterTarget(index) }
    fun superType(index: Int) { targetInfo = SuperTypeTarget(index) }
    fun typeParameterBound(tp: Int, bound: Int) { targetInfo = TypeParameterBoundTarget(tp, bound) }
    fun formalParameter(index: Int) { targetInfo = FormalParameterTarget(index) }
    fun throwsType(index: Int) { targetInfo = ThrowsTarget(index) }
    fun localVar(vararg entries: LocalvarTarget.LocalvarEntry) { targetInfo = LocalvarTarget(entries.toList()) }
    fun catchType(index: Int) { targetInfo = CatchTarget(index) }
    fun offset(pc: Int) { targetInfo = OffsetTarget(pc) }
    fun typeArgument(offset: Int, argIndex: Int) { targetInfo = TypeArgumentTarget(offset, argIndex) }

    fun typePath(kind: Int, argIndex: Int) { typePath.add(kind to argIndex) }

    fun annotation(block: AnnotationDsl.() -> Unit) {
        val dsl = AnnotationDsl(); dsl.block(); annotation = dsl.buildInfo()
    }

    internal fun build(): TypeAnnotationInfo {
        require(targetInfo != null) { "TypeAnnotation: не задан targetInfo" }
        require(annotation != null) { "TypeAnnotation: не задана аннотация" }
        return TypeAnnotationInfo(targetType, targetInfo, typePath.toList(), annotation!!)
    }
}
