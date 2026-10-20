package com.tim.dex.model

class DexFileBuilder {
    var header = DexHeader()
    var strings: List<String> = emptyList()
    var typeIds: List<TypeIdItem> = emptyList()
    var protoIds: List<ProtoIdItem> = emptyList()
    var fieldIds: List<FieldIdItem> = emptyList()
    var methodIds: List<MethodIdItem> = emptyList()
    var classDefs: List<ClassDefItem> = emptyList()
    var callSiteIds: List<CallSiteIdItem> = emptyList()
    var methodHandles: List<MethodHandleItem> = emptyList()
    var mapList: List<MapItem> = emptyList()

    val typeLists = ArrayList<TypeListItem>()
    val classDataItems = ArrayList<ClassDataItem>()
    val codeItems = ArrayList<CodeItem>()
    val debugInfoItems = ArrayList<DebugInfoItem>()
    val annotationItems = ArrayList<AnnotationItem>()
    val annotationSetItems = ArrayList<AnnotationSetItem>()
    val annotationSetRefLists = ArrayList<AnnotationSetRefList>()
    val annotationsDirectoryItems = ArrayList<AnnotationsDirectoryItem>()
    val encodedArrayItems = ArrayList<List<EncodedValue>>()
    val callSiteItems = ArrayList<CallSite>()
    val hiddenApiClassDataItems = ArrayList<HiddenApiClassData>()

    fun clear() {
        typeLists.clear(); classDataItems.clear()
        codeItems.clear(); debugInfoItems.clear(); annotationItems.clear()
        annotationSetItems.clear(); annotationSetRefLists.clear()
        annotationsDirectoryItems.clear(); encodedArrayItems.clear()
        callSiteItems.clear(); hiddenApiClassDataItems.clear()
    }

    fun build(): DexFile = DexFile(
        header = header,
        strings = strings,
        typeIds = typeIds,
        protoIds = protoIds,
        fieldIds = fieldIds,
        methodIds = methodIds,
        classDefs = classDefs,
        callSiteIds = callSiteIds,
        methodHandles = methodHandles,
        mapList = mapList,
        typeLists = typeLists,
        classDataItems = classDataItems,
        codeItems = codeItems,
        debugInfoItems = debugInfoItems,
        annotationItems = annotationItems,
        annotationSetItems = annotationSetItems,
        annotationSetRefLists = annotationSetRefLists,
        annotationsDirectoryItems = annotationsDirectoryItems,
        encodedArrayItems = encodedArrayItems,
        callSiteItems = callSiteItems,
        hiddenApiClassDataItems = hiddenApiClassDataItems
    )
}
