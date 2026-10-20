package com.tim.dasm.model

/**
 * Отложенный switch-payload: targets задаются метками и превращаются в относительные
 * смещения после раскладки кода.
 *
 * @property index      индекс payload'а в списке инструкций метода.
 * @property keys       ключи sparse-switch (`null` для packed-switch).
 * @property firstKey   первый ключ packed-switch.
 * @property baseLabel  метка switch-инструкции, от которой считаются смещения;
 *                      `null` — база берётся из switch-инструкции, ссылающейся на этот payload.
 */
class SwitchPayloadDef(
    val index: Int,
    val packed: Boolean,
    val firstKey: Int,
    val keys: IntArray?,
    val targetLabels: List<String>,
    val baseLabel: String?
)
