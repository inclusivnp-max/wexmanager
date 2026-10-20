package com.tim.dex.model

/**
 * Блок обработки исключений.
 *
 * Больше никаких сырых оффсетов! Теперь мы используем чистый индекс.
 * 
 * @property startAddr Адрес начала блока инструкций
 * @property insnCount Количество инструкций в блоке
 * @property handlerIdx Индекс обработчика в списке catchHandlers текущего CodeItem
 */
data class TryItem(
    val startAddr: Int,
    val insnCount: Int,
    val handlerIdx: Int
)