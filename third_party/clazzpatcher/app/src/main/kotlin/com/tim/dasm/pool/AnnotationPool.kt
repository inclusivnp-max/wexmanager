package com.tim.dasm.pool

import com.tim.dasm.DexAsmState
import com.tim.dex.model.*

/**
 * Пул аннотаций: annotation_item, annotation_set_item, annotation_set_ref_list,
 * annotations_directory_item. Одинаковые item/set/ref_list переиспользуются.
 */
class AnnotationPool(private val state: DexAsmState) {

    private var indexed = false
    private val itemIndex = HashMap<AnnotationItem, Int>()
    private val setIndex = HashMap<List<Int>, Int>()
    private val refListIndex = HashMap<List<Int>, Int>()

    private fun ensureIndexed() {
        if (indexed) return
        indexed = true
        for (i in state.annotationItems.indices) itemIndex.putIfAbsent(state.annotationItems[i], i)
        for (i in state.annotationSetItems.indices) setIndex.putIfAbsent(state.annotationSetItems[i].annotationIdxs, i)
        for (i in state.annotationSetRefLists.indices) refListIndex.putIfAbsent(state.annotationSetRefLists[i].annotationSetIdxs, i)
    }

    fun addItem(item: AnnotationItem): Int {
        ensureIndexed()
        itemIndex[item]?.let { return it }
        val idx = state.annotationItems.size
        state.annotationItems.add(item)
        itemIndex[item] = idx
        return idx
    }

    fun addSet(annotationIdxs: List<Int>): Int {
        ensureIndexed()
        setIndex[annotationIdxs]?.let { return it }
        val copy = ArrayList(annotationIdxs)
        val idx = state.annotationSetItems.size
        state.annotationSetItems.add(AnnotationSetItem(copy))
        setIndex[copy] = idx
        return idx
    }

    fun addSetRefList(annotationSetIdxs: List<Int>): Int {
        ensureIndexed()
        refListIndex[annotationSetIdxs]?.let { return it }
        val copy = ArrayList(annotationSetIdxs)
        val idx = state.annotationSetRefLists.size
        state.annotationSetRefLists.add(AnnotationSetRefList(copy))
        refListIndex[copy] = idx
        return idx
    }

    fun addDirectory(item: AnnotationsDirectoryItem): Int {
        val idx = state.annotationsDirectoryItems.size
        state.annotationsDirectoryItems.add(item)
        return idx
    }

    fun getDirectory(idx: Int): AnnotationsDirectoryItem? =
        state.annotationsDirectoryItems.getOrNull(idx)
}
