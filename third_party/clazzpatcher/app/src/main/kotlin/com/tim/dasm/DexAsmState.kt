package com.tim.dasm

import com.tim.dasm.model.ProtoKey
import com.tim.dex.data.DexMagic
import com.tim.dex.model.*

/**
 * Мутабельная модель DEX.
 *
 * Инвариант: все изменения — append (add) или замена элемента по индексу.
 * Никаких сортировок, ремапов, удалений.
 *
 * Для пулов с дедупликацией (строки, типы, type_list, proto, field, method, class_def,
 * method handle) поддерживаются хэш-индексы «значение -> первый индекс». Они строятся
 * один раз из загруженного [DexFile] и обновляются пулами при каждом add/replace, поэтому
 * списки id-секций нужно менять только через пулы.
 */
class DexAsmState(val dexFile: DexFile) {

    val strings: MutableList<String> = ArrayList(dexFile.strings)
    val typeIds: MutableList<TypeIdItem> = ArrayList(dexFile.typeIds)
    val protoIds: MutableList<ProtoIdItem> = ArrayList(dexFile.protoIds)
    val fieldIds: MutableList<FieldIdItem> = ArrayList(dexFile.fieldIds)
    val methodIds: MutableList<MethodIdItem> = ArrayList(dexFile.methodIds)
    val classDefs: MutableList<ClassDefItem> = ArrayList(dexFile.classDefs)
    val callSiteIds: MutableList<CallSiteIdItem> = ArrayList(dexFile.callSiteIds)
    val methodHandles: MutableList<MethodHandleItem> = ArrayList(dexFile.methodHandles)

    val typeLists: MutableList<TypeListItem> = ArrayList(dexFile.typeLists)
    val classDataItems: MutableList<ClassDataItem> = ArrayList(dexFile.classDataItems)
    val codeItems: MutableList<CodeItem> = ArrayList(dexFile.codeItems)
    val debugInfoItems: MutableList<DebugInfoItem> = ArrayList(dexFile.debugInfoItems)
    val annotationItems: MutableList<AnnotationItem> = ArrayList(dexFile.annotationItems)
    val annotationSetItems: MutableList<AnnotationSetItem> = ArrayList(dexFile.annotationSetItems)
    val annotationSetRefLists: MutableList<AnnotationSetRefList> = ArrayList(dexFile.annotationSetRefLists)
    val annotationsDirectoryItems: MutableList<AnnotationsDirectoryItem> =
        ArrayList(dexFile.annotationsDirectoryItems)
    val encodedArrayItems: MutableList<List<EncodedValue>> = ArrayList(dexFile.encodedArrayItems)
    val callSiteItems: MutableList<CallSite> = ArrayList(dexFile.callSiteItems)
    val hiddenApiClassDataItems: MutableList<HiddenApiClassData> =
        ArrayList(dexFile.hiddenApiClassDataItems)

    /** Флаги hidden API классов, созданных через DSL: class_def_idx → флаги в порядке class_data. */
    val newHiddenApi: MutableMap<Int, IntArray> = HashMap()

    val header: DexHeader = dexFile.header

    /**
     * Минимальная версия формата, которую требует добавленное содержимое
     * (35, 37, 38, 39). Итоговая версия — максимум из неё и версии исходного файла.
     */
    var requiredDexVersion: Int = 35
        private set

    fun requireDexVersion(version: Int) {
        if (version > requiredDexVersion) requiredDexVersion = version
    }

    /** Версия формата исходного файла (35, если magic не распознан или пуст). */
    val sourceDexVersion: Int = DexMagic.getVersion(dexFile.header.magic).toIntOrNull() ?: 35

    // ---- Хэш-индексы: значение -> первый индекс (как indexOf) ----

    internal val stringIndex = HashMap<String, Int>(capacity(strings.size))
    internal val typeIndex = HashMap<String, Int>(capacity(typeIds.size))
    internal val typeListIndex = HashMap<List<Int>, Int>(capacity(typeLists.size))
    internal val protoIndex = HashMap<ProtoKey, Int>(capacity(protoIds.size))
    internal val fieldIndex = HashMap<FieldIdItem, Int>(capacity(fieldIds.size))
    internal val methodIndex = HashMap<MethodIdItem, Int>(capacity(methodIds.size))
    internal val classDefIndex = HashMap<Int, Int>(capacity(classDefs.size))
    internal val methodHandleIndex = HashMap<MethodHandleItem, Int>(capacity(methodHandles.size))

    init {
        for (i in strings.indices) stringIndex.putIfAbsent(strings[i], i)
        for (i in typeIds.indices) {
            val desc = strings.getOrNull(typeIds[i].descriptorIdx)
                ?: throw IllegalStateException("type_id #$i ссылается на несуществующую строку ${typeIds[i].descriptorIdx}")
            typeIndex.putIfAbsent(desc, i)
        }
        for (i in typeLists.indices) typeListIndex.putIfAbsent(typeLists[i].typeIdx, i)
        for (i in protoIds.indices) protoIndex.putIfAbsent(protoKeyOf(protoIds[i]), i)
        for (i in fieldIds.indices) fieldIndex.putIfAbsent(fieldIds[i], i)
        for (i in methodIds.indices) methodIndex.putIfAbsent(methodIds[i], i)
        for (i in classDefs.indices) classDefIndex.putIfAbsent(classDefs[i].classIdx, i)
        for (i in methodHandles.indices) methodHandleIndex.putIfAbsent(methodHandles[i], i)
    }

    internal fun protoKeyOf(p: ProtoIdItem): ProtoKey {
        val params = if (p.parametersTypeListIdx < 0) emptyList()
        else typeLists.getOrNull(p.parametersTypeListIdx)?.typeIdx
            ?: throw IllegalStateException("proto ссылается на несуществующий type_list ${p.parametersTypeListIdx}")
        return ProtoKey(p.returnTypeIdx, params)
    }

    private fun capacity(size: Int): Int = maxOf(16, (size / 0.75f).toInt() + 1)
}
