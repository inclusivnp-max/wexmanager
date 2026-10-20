package com.tim.dex.model

/**
 * Элемент дескриптора метода - ссылка на дескриптор метода для invokedynamic.
 * 
 * Поддерживается в версиях DEX 038+.
 * 
 * @property methodHandleType Тип дескриптора метода (REF_getField, REF_invokeStatic и т.д.)
 * @property unused1 Неиспользуемое выравнивание
 * @property fieldOrMethodId Индекс в таблице полей или методов, в зависимости от типа дескриптора
 * @property unused2 Неиспользуемое выравнивание
 */
data class MethodHandleItem(
    val methodHandleType: Int,
    val unused1: Int,
    val fieldOrMethodId: Int,
    val unused2: Int
)
