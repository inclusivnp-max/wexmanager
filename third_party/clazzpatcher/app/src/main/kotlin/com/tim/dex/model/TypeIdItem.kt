package com.tim.dex.model

/**
 * Идентификатор типа - ссылка на строку-дескриптор типа.
 * 
 * @property descriptorIdx Индекс в таблице строк для дескриптора типа
 *                         (например, "Ljava/lang/Object;" или "I" для int)
 */
data class TypeIdItem(
    val descriptorIdx: Int
)
