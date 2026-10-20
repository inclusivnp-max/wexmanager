package com.tim.dex.data

object MapItemType {
    const val HEADER_ITEM = 0x0000
    const val STRING_ID_ITEM = 0x0001
    const val TYPE_ID_ITEM = 0x0002
    const val PROTO_ID_ITEM = 0x0003
    const val FIELD_ID_ITEM = 0x0004
    const val METHOD_ID_ITEM = 0x0005
    const val CLASS_DEF_ITEM = 0x0006
    const val CALL_SITE_ID_ITEM = 0x0007
    const val METHOD_HANDLE_ITEM = 0x0008
    const val MAP_LIST = 0x1000
    const val TYPE_LIST = 0x1001
    const val ANNOTATION_SET_REF_LIST = 0x1002
    const val ANNOTATION_SET_ITEM = 0x1003
    const val CLASS_DATA_ITEM = 0x2000
    const val CODE_ITEM = 0x2001
    const val STRING_DATA_ITEM = 0x2002
    const val DEBUG_INFO_ITEM = 0x2003
    const val ANNOTATION_ITEM = 0x2004
    const val ENCODED_ARRAY_ITEM = 0x2005
    const val ANNOTATIONS_DIRECTORY_ITEM = 0x2006
    const val HIDDENAPI_CLASS_DATA_ITEM = 0xF000
}
