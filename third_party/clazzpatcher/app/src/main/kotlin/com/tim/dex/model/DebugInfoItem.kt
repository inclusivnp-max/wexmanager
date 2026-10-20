package com.tim.dex.model

/**
 * Информация об отладке - содержит отладочную информацию для метода.
 * 
 * @property lineStart Номер начальной строки
 * @property parametersSize Количество параметров
 * @property parameterNames Список индексов строк для имён параметров (-1 если имя отсутствует)
 * @property opcodes Список опкодов отладки
 */
data class DebugInfoItem(
    val lineStart: Int,
    val parametersSize: Int,
    val parameterNames: List<Int>,
    val opcodes: List<DebugOpcode>
)
