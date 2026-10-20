package com.tim.dex.model

/**
 * Обработчик исключений - содержит список обработчиков для catch.
 * 
 * @property handlers Список пар (тип исключения, адрес обработчика)
 * @property catchAllAddr Адрес обработчика catch-all (-1 если отсутствует)
 */
data class CatchHandler(
    val handlers: List<TypeAddrPair>,
    val catchAllAddr: Int
)
