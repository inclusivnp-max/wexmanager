package com.tim.dex.model

/**
 * Модель DEX-файла.
 *
 * Контракт индексов: `-1` означает «отсутствует», `>= 0` — валидный индекс
 * в соответствующем списке.
 */
data class DexFile(
    val header: DexHeader,

    /**
     * Единственный источник правды для строк.
     * Индекс = string_idx (порядок записей в string_ids).
     *
     * Может содержать дубликаты — два string_id могут ссылаться на одну
     * и ту же строку. Дедупликация string_data_item — задача DexWriter.
     */
    val strings: List<String>,

    val typeIds: List<TypeIdItem>,            // index = type_idx
    val protoIds: List<ProtoIdItem>,          // index = proto_idx
    val fieldIds: List<FieldIdItem>,          // index = field_idx
    val methodIds: List<MethodIdItem>,        // index = method_idx
    val classDefs: List<ClassDefItem>,        // index = class_def_idx
    val callSiteIds: List<CallSiteIdItem>,
    val methodHandles: List<MethodHandleItem>,
    val mapList: List<MapItem>,

    val typeLists: List<TypeListItem>,
    val classDataItems: List<ClassDataItem>,
    val codeItems: List<CodeItem>,
    val debugInfoItems: List<DebugInfoItem>,
    val annotationItems: List<AnnotationItem>,
    val annotationSetItems: List<AnnotationSetItem>,
    val annotationSetRefLists: List<AnnotationSetRefList>,
    val annotationsDirectoryItems: List<AnnotationsDirectoryItem>,
    val encodedArrayItems: List<List<EncodedValue>>,
    val callSiteItems: List<CallSite>,
    val hiddenApiClassDataItems: List<HiddenApiClassData>
)