package com.tim.dasm.model

import com.tim.dex.model.AnnotationItem
import com.tim.dex.model.EncodedValue

data class FieldInfo(
    val name: String,
    val type: String,
    val accessFlags: Int,
    val annotations: List<AnnotationItem> = emptyList(),
    val initialValue: EncodedValue? = null,
    /** Флаги ограничений hidden API (hiddenapi_class_data), `-1` — не заданы. */
    val hiddenApi: Int = -1
)