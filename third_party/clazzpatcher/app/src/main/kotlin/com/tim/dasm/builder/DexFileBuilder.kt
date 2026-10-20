package com.tim.dasm.builder

import com.tim.dasm.DexAsmState
import com.tim.dasm.materializer.DeferredAnnotations
import com.tim.dasm.pool.AnnotationPool
import com.tim.dasm.pool.ClassDataPool
import com.tim.dex.data.DexMagic
import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.*
import com.tim.dex.model.Annotation

/**
 * Собирает финальный [DexFile] из мутабельного состояния.
 *
 * Материализует отложенные аннотации в annotations_directory_item
 * (единственная работа, которую нельзя делегировать
 * [com.tim.dex.writer.DexWriter]) и выставляет версию формата по содержимому.
 */
internal class DexFileBuilder(
    private val state: DexAsmState,
    private val deferred: DeferredAnnotations,
    private val annotations: AnnotationPool,
    @Suppress("unused") private val classData: ClassDataPool
) {

    /** Секция hidden API: флаги загруженных классов + флаги классов, созданных через DSL. */
    private fun hiddenApiSection(): List<HiddenApiClassData> {
        if (state.newHiddenApi.isEmpty()) return state.hiddenApiClassDataItems
        require(state.hiddenApiClassDataItems.size <= 1) { "Секций hidden API больше одной" }
        val loaded = state.hiddenApiClassDataItems.firstOrNull()?.flags
        val flags = List(state.classDefs.size) { i -> state.newHiddenApi[i] ?: loaded?.getOrNull(i) }
        return listOf(HiddenApiClassData(flags))
    }

    fun build(): DexFile {
        materializeAnnotations()

        return DexFile(
            header = headerWithVersion(),
            strings = state.strings,
            typeIds = state.typeIds,
            protoIds = state.protoIds,
            fieldIds = state.fieldIds,
            methodIds = state.methodIds,
            classDefs = state.classDefs,
            callSiteIds = state.callSiteIds,
            methodHandles = state.methodHandles,
            mapList = emptyList(), // writer построит сам
            typeLists = state.typeLists,
            classDataItems = state.classDataItems,
            codeItems = state.codeItems,
            debugInfoItems = state.debugInfoItems,
            annotationItems = state.annotationItems,
            annotationSetItems = state.annotationSetItems,
            annotationSetRefLists = state.annotationSetRefLists,
            annotationsDirectoryItems = state.annotationsDirectoryItems,
            encodedArrayItems = state.encodedArrayItems,
            callSiteItems = state.callSiteItems,
            hiddenApiClassDataItems = hiddenApiSection()
        )
    }

    /** Версия: максимум из версии исходного файла и минимально требуемой содержимым. */
    private fun headerWithVersion(): DexHeader {
        val version = maxOf(state.sourceDexVersion, state.requiredDexVersion)
        val magic = when (version) {
            35 -> DexMagic.DEX_035
            37 -> DexMagic.DEX_037
            38 -> DexMagic.DEX_038
            39 -> DexMagic.DEX_039
            40 -> DexMagic.DEX_040
            41 -> DexMagic.DEX_041
            42 -> DexMagic.DEX_042
            else -> throw IllegalStateException("Неподдерживаемая версия DEX: $version")
        }
        if (magic.contentEquals(state.header.magic)) return state.header
        return state.header.copy(magic = magic.copyOf())
    }

    private class Pending {
        var classAnnotations: List<AnnotationItem> = emptyList()
        val fields = LinkedHashMap<Int, List<AnnotationItem>>()
        val methods = LinkedHashMap<Int, List<AnnotationItem>>()
        val parameters = LinkedHashMap<Int, Map<Int, List<AnnotationItem>>>()
    }

    private fun materializeAnnotations() {
        if (deferred.isEmpty()) return

        val byClass = LinkedHashMap<Int, Pending>()
        for ((classDefIdx, anns) in deferred.classAnnotations()) {
            byClass.getOrPut(classDefIdx) { Pending() }.classAnnotations = anns
        }
        for ((fieldIdx, anns) in deferred.fieldAnnotations()) {
            val owner = ownerClass(state.fieldIds[fieldIdx].classIdx, "поля #$fieldIdx")
            byClass.getOrPut(owner) { Pending() }.fields[fieldIdx] = anns
        }
        for ((methodIdx, anns) in deferred.methodAnnotations()) {
            val owner = ownerClass(state.methodIds[methodIdx].classIdx, "метода #$methodIdx")
            byClass.getOrPut(owner) { Pending() }.methods[methodIdx] = anns
        }
        for ((methodIdx, byParam) in deferred.parameterAnnotations()) {
            val owner = ownerClass(state.methodIds[methodIdx].classIdx, "метода #$methodIdx")
            byClass.getOrPut(owner) { Pending() }.parameters[methodIdx] = byParam
        }

        for ((classDefIdx, p) in byClass) {
            val classDef = state.classDefs[classDefIdx]
            val old = if (classDef.annotationsDirectoryIdx >= 0) {
                state.annotationsDirectoryItems[classDef.annotationsDirectoryIdx]
            } else null

            val classSet = mergeSet(old?.classAnnotationsSetIdx ?: -1, p.classAnnotations)

            val fieldSets = LinkedHashMap<Int, Int>()
            old?.fieldAnnotations?.forEach { fieldSets[it.fieldIdx] = it.annotationsSetIdx }
            for ((fieldIdx, anns) in p.fields) fieldSets[fieldIdx] = mergeSet(fieldSets[fieldIdx] ?: -1, anns)

            val methodSets = LinkedHashMap<Int, Int>()
            old?.methodAnnotations?.forEach { methodSets[it.methodIdx] = it.annotationsSetIdx }
            for ((methodIdx, anns) in p.methods) methodSets[methodIdx] = mergeSet(methodSets[methodIdx] ?: -1, anns)

            val paramLists = LinkedHashMap<Int, Int>()
            old?.parameterAnnotations?.forEach { paramLists[it.methodIdx] = it.annotationsSetRefListIdx }
            for ((methodIdx, byParam) in p.parameters) {
                val paramCount = paramCountOf(methodIdx)
                val refs = ArrayList<Int>(paramCount)
                val oldList = paramLists[methodIdx]?.takeIf { it >= 0 }?.let { state.annotationSetRefLists[it].annotationSetIdxs }
                for (i in 0 until maxOf(paramCount, oldList?.size ?: 0)) refs.add(oldList?.getOrNull(i) ?: -1)
                for ((paramIdx, anns) in byParam) {
                    require(paramIdx in 0 until paramCount) {
                        "Аннотация параметра $paramIdx у метода #$methodIdx с $paramCount параметрами"
                    }
                    refs[paramIdx] = mergeSet(refs[paramIdx], anns)
                }
                paramLists[methodIdx] = annotations.addSetRefList(refs)
            }

            val dirIdx = annotations.addDirectory(
                AnnotationsDirectoryItem(
                    classAnnotationsSetIdx = classSet,
                    fieldAnnotations = fieldSets.map { FieldAnnotation(it.key, it.value) },
                    methodAnnotations = methodSets.map { MethodAnnotation(it.key, it.value) },
                    parameterAnnotations = paramLists.map { ParameterAnnotation(it.key, it.value) }
                )
            )
            state.classDefs[classDefIdx] = classDef.copy(annotationsDirectoryIdx = dirIdx)
        }

        deferred.clear()
    }

    private fun ownerClass(typeIdx: Int, what: String): Int =
        state.classDefIndex[typeIdx] ?: throw IllegalStateException(
            "Аннотация $what: класс ${descriptor(typeIdx)} не определён в этом DEX"
        )

    private fun paramCountOf(methodIdx: Int): Int {
        val proto = state.protoIds[state.methodIds[methodIdx].protoIdx]
        return if (proto.parametersTypeListIdx < 0) 0 else state.typeLists[proto.parametersTypeListIdx].typeIdx.size
    }

    private fun descriptor(typeIdx: Int): String =
        state.typeIds.getOrNull(typeIdx)?.let { state.strings.getOrNull(it.descriptorIdx) } ?: "#$typeIdx"

    /**
     * Набор = прежний набор (если есть) + новые аннотации; элементы отсортированы по типу
     * (порядок type_idx = порядок дескрипторов), повтор типа — ошибка.
     */
    private fun mergeSet(oldSetIdx: Int, added: List<AnnotationItem>): Int {
        if (added.isEmpty()) return oldSetIdx
        val items = ArrayList<AnnotationItem>()
        if (oldSetIdx >= 0) state.annotationSetItems[oldSetIdx].annotationIdxs.forEach { items.add(state.annotationItems[it]) }
        added.forEach { items.add(normalize(it)) }
        val sorted = items.sortedWith(compareBy { descriptor(it.annotation.typeIdx) })
        for (i in 1 until sorted.size) {
            require(sorted[i].annotation.typeIdx != sorted[i - 1].annotation.typeIdx) {
                "Аннотация ${descriptor(sorted[i].annotation.typeIdx)} указана дважды"
            }
        }
        return annotations.addSet(sorted.map { annotations.addItem(it) })
    }

    /** Элементы аннотации (и вложенных) — по возрастанию имени (порядок name_idx); повтор имени — ошибка. */
    private fun normalize(item: AnnotationItem): AnnotationItem =
        AnnotationItem(item.visibility, normalizeAnnotation(item.annotation))

    private fun normalizeAnnotation(a: Annotation): Annotation {
        val elements = a.elements.map { AnnotationElement(it.nameIdx, normalizeValue(it.value)) }
            .sortedWith(compareBy { state.strings[it.nameIdx] })
        for (i in 1 until elements.size) {
            require(elements[i].nameIdx != elements[i - 1].nameIdx) {
                "Элемент ${state.strings[elements[i].nameIdx]} аннотации ${descriptor(a.typeIdx)} указан дважды"
            }
        }
        return Annotation(a.typeIdx, elements)
    }

    @Suppress("UNCHECKED_CAST")
    private fun normalizeValue(v: EncodedValue): EncodedValue = when (v.type) {
        EncodedValueType.VALUE_ANNOTATION -> EncodedValue(v.type, normalizeAnnotation(v.value as Annotation))
        EncodedValueType.VALUE_ARRAY -> EncodedValue(v.type, (v.value as List<EncodedValue>).map { normalizeValue(it) })
        else -> v
    }
}
