package com.tim.dasm.materializer

import com.tim.dasm.pool.AnnotationPool
import com.tim.dex.model.*

/**
 * Хранит отложенные аннотации (class / method / field / parameter) до момента сборки.
 *
 * Материализация в annotations_directory_item — на этапе [DexFileBuilder.build].
 */
class DeferredAnnotations(private val pool: AnnotationPool) {

    private val classAnnotations = mutableMapOf<Int, MutableList<AnnotationItem>>()
    private val methodAnnotations = mutableMapOf<Int, MutableList<AnnotationItem>>()
    private val fieldAnnotations = mutableMapOf<Int, MutableList<AnnotationItem>>()
    private val parameterAnnotations =
        mutableMapOf<Int, MutableMap<Int, MutableList<AnnotationItem>>>()

    fun addClassAnnotation(classDefIdx: Int, item: AnnotationItem) {
        classAnnotations.getOrPut(classDefIdx) { mutableListOf() }.add(item)
    }

    fun addMethodAnnotation(methodIdx: Int, item: AnnotationItem) {
        methodAnnotations.getOrPut(methodIdx) { mutableListOf() }.add(item)
    }

    fun addFieldAnnotation(fieldIdx: Int, item: AnnotationItem) {
        fieldAnnotations.getOrPut(fieldIdx) { mutableListOf() }.add(item)
    }

    fun addParameterAnnotation(methodIdx: Int, paramIndex: Int, item: AnnotationItem) {
        parameterAnnotations
            .getOrPut(methodIdx) { mutableMapOf() }
            .getOrPut(paramIndex) { mutableListOf() }
            .add(item)
    }

    fun isEmpty(): Boolean =
        classAnnotations.isEmpty() && methodAnnotations.isEmpty() &&
        fieldAnnotations.isEmpty() && parameterAnnotations.isEmpty()

    fun classAnnotations(): Map<Int, List<AnnotationItem>> = classAnnotations
    fun methodAnnotations(): Map<Int, List<AnnotationItem>> = methodAnnotations
    fun fieldAnnotations(): Map<Int, List<AnnotationItem>> = fieldAnnotations
    fun parameterAnnotations(): Map<Int, Map<Int, List<AnnotationItem>>> = parameterAnnotations

    fun clear() {
        classAnnotations.clear()
        methodAnnotations.clear()
        fieldAnnotations.clear()
        parameterAnnotations.clear()
    }
}