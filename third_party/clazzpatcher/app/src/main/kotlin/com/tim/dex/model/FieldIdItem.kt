package com.tim.dex.model

/**
 * Идентификатор поля - идентифицирует поле по классу, типу и имени.
 * 
 * @property classIdx Индекс в таблице типов для содержащего класса
 * @property typeIdx Индекс в таблице типов для типа поля
 * @property nameIdx Индекс в таблице строк для имени поля
 */
data class FieldIdItem(
    val classIdx: Int,
    val typeIdx: Int,
    val nameIdx: Int
)
