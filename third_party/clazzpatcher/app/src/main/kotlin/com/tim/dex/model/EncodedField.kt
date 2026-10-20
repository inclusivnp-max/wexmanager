package com.tim.dex.model

/**
 * Закодированное поле - компактное представление поля.
 * 
 * Индекс поля хранится как разность с предыдущим индексом (дельта-кодирование).
 * 
 * @property fieldIdx Индекс поля (абсолютный, вычисляется при разборе)
 * @property accessFlags Флаги доступа поля
 */
data class EncodedField(
    val fieldIdx: Int,
    val accessFlags: Int
)

