package com.tim.dex.model

/**
 * Идентификатор метода - идентифицирует метод по классу, прототипу и имени.
 * 
 * @property classIdx Индекс в таблице типов для содержащего класса
 * @property protoIdx Индекс в таблице прототипов для прототипа метода
 * @property nameIdx Индекс в таблице строк для имени метода
 */
data class MethodIdItem(
    val classIdx: Int,
    val protoIdx: Int,
    val nameIdx: Int
)
