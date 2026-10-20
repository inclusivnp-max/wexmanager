package com.tim.dasm.model

import com.tim.dex.model.CatchHandler
import com.tim.dex.model.TryItem

/**
 * Замена тела существующего метода.
 *
 * [paramTypes]/[returnType] = `null` — метод ищется только по имени (оно должно быть однозначным).
 */
data class MethodPatch(
    val name: String,
    val instructions: List<Instruction>,
    val registers: Int,
    val paramTypes: List<String>? = null,
    val returnType: String? = null,
    val tries: List<TryItem> = emptyList(),
    val catchHandlers: List<CatchHandler> = emptyList()
)
