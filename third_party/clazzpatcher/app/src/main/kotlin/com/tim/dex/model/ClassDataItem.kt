package com.tim.dex.model

/**
 * Данные класса - содержит поля и методы класса.
 * 
 * Все поля и методы закодированы с использованием ULEB128 для компактности.
 * 
 * @property staticFieldsSize Количество статических полей
 * @property instanceFieldsSize Количество полей экземпляра
 * @property directMethodsSize Количество прямых методов (конструкторы, статические, private)
 * @property virtualMethodsSize Количество виртуальных методов
 * @property staticFields Список статических полей
 * @property instanceFields Список полей экземпляра
 * @property directMethods Список прямых методов
 * @property virtualMethods Список виртуальных методов
 */
data class ClassDataItem(
    val staticFieldsSize: Int,
    val instanceFieldsSize: Int,
    val directMethodsSize: Int,
    val virtualMethodsSize: Int,
    val staticFields: List<EncodedField>,
    val instanceFields: List<EncodedField>,
    val directMethods: List<EncodedMethod>,
    val virtualMethods: List<EncodedMethod>
)
