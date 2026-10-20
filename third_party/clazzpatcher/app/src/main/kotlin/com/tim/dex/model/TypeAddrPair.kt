package com.tim.dex.model

/**
 * Пара (тип, адрес) для обработчика исключений.
 * 
 * @property typeIdx Индекс типа исключения (0 для catch-all)
 * @property addr Адрес обработчика
 */
data class TypeAddrPair(
    val typeIdx: Int,
    val addr: Int
)
