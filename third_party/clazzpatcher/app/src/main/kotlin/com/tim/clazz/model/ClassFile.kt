package com.tim.clazz.model

import com.tim.clazz.model.attributes.AttributeInfo

/**
 * Главная структура файла класса (JVMS §4.1).
 * Все ссылки — индексы в [constantPool]; индекс 0 и второй слот Long/Double — null.
 */
data class ClassFile(
    val magic: Long,
    val minorVersion: Int,
    val majorVersion: Int,
    val constantPool: List<ConstantPoolEntry?>,
    val accessFlags: Int,
    val thisClass: Int,
    val superClass: Int,
    val interfaces: List<Int>,
    val fields: List<FieldInfo>,
    val methods: List<MethodInfo>,
    val attributes: List<AttributeInfo>
) {
    val constantPoolCount: Int get() = constantPool.size
    val interfacesCount: Int get() = interfaces.size
    val fieldsCount: Int get() = fields.size
    val methodsCount: Int get() = methods.size
    val attributesCount: Int get() = attributes.size

    /** Запись пула с проверкой типа; [expected] — имя ожидаемого вида для сообщения об ошибке. */
    inline fun <reified T : ConstantPoolEntry> entry(index: Int, expected: String = "запись другого вида"): T {
        val e = constantPool.getOrNull(index)
        return e as? T ?: throw IllegalArgumentException(
            "#$index в пуле констант: ожидался $expected, найдено ${e?.tagName ?: "пусто"}"
        )
    }

    fun utf8(index: Int): String = entry<ConstantUtf8Info>(index, "CONSTANT_Utf8").getString()

    /** Внутреннее имя класса из CONSTANT_Class (например, `java/lang/String`). */
    fun className(index: Int): String = utf8(entry<ConstantClassInfo>(index, "CONSTANT_Class").nameIndex)

    val thisClassName: String get() = className(thisClass)

    /** null только у java/lang/Object и module-info. */
    val superClassName: String? get() = if (superClass == 0) null else className(superClass)

    fun attributeName(attribute: AttributeInfo): String = utf8(attribute.attributeNameIndex)
}
