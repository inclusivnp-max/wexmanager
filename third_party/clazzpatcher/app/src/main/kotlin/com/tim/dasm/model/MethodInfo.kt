package com.tim.dasm.model

import com.tim.dex.model.AnnotationItem
import com.tim.dex.model.CatchHandler
import com.tim.dex.model.CodeItem
import com.tim.dex.model.TryItem

/**
 * Собранный метод.
 *
 * @property registers            число регистров; -1 — вычислить по использованным.
 * @property parameterAnnotations аннотации параметров по индексу параметра (0 — первый объявленный).
 * @property code                 готовый code_item (собран заранее, см. [com.tim.dasm.DexAsm.buildClass]):
 *                                при добавлении класса берётся он, а не сборка из [instructions].
 */
data class MethodInfo(
    val name: String,
    val returnType: String,
    val paramTypes: List<String>,
    val accessFlags: Int,
    val instructions: List<Instruction>,
    val registers: Int,
    val annotations: List<AnnotationItem> = emptyList(),
    val debugInfoIdx: Int = -1,
    val tries: List<TryItem> = emptyList(),
    val catchHandlers: List<CatchHandler> = emptyList(),
    val parameterAnnotations: Map<Int, List<AnnotationItem>> = emptyMap(),
    val hasCode: Boolean = instructions.isNotEmpty(),
    /** Флаги ограничений hidden API (hiddenapi_class_data), `-1` — не заданы. */
    val hiddenApi: Int = -1,
    val code: CodeItem? = null
)
