package com.tim.dex.model

/**
 * Точка вызова - данные для invokedynamic.
 * 
 * @property methodHandle Дескриптор метода
 * @property arguments Аргументы для вызова
 */
data class CallSite(
    val methodHandle: EncodedValue,
    val arguments: List<EncodedValue>
)
