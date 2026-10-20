package com.tim.dex.model

/**
 * Список типов - список индексов типов.
 * 
 * Используется для списков параметров и реализуемых интерфейсов.
 * 
 * @property size Количество индексов типов в списке
 * @property typeIdx Список индексов типов
 */
data class TypeListItem(
    val size: Int,
    val typeIdx: List<Int>
)
