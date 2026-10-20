package com.tim.dex.model

/**
 * Элемент карты - запись в секции карты, описывающая секцию DEX файла.
 * 
 * Секция карты используется для верификации и содержит по одной записи
 * для каждой секции DEX файла.
 * 
 * @property type Тип секции (HEADER_ITEM, STRING_ID_ITEM и т.д.)
 * @property unused Неиспользуемое выравнивание
 * @property size Количество элементов в секции
 * @property offset Смещение к началу секции
 */
data class MapItem(
    val type: Int,
    val unused: Int,
    val size: Int,
    val offset: Int
)
