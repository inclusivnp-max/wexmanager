package com.tim.c2d.meta

import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.ConstantDoubleInfo
import com.tim.clazz.model.ConstantFloatInfo
import com.tim.clazz.model.ConstantIntegerInfo
import com.tim.clazz.model.ConstantLongInfo
import com.tim.clazz.model.ConstantStringInfo
import com.tim.clazz.model.attributes.AttributeInfo
import com.tim.clazz.model.attributes.ConstantValue
import com.tim.dasm.DexAsm
import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.EncodedValue

/** ConstantValue статического поля → элемент `static_values` (тип значения — по дескриптору поля). */
object StaticValues {

    fun of(asm: DexAsm, cf: ClassFile, fieldType: String, attributes: List<AttributeInfo>): EncodedValue? {
        val attr = attributes.firstOrNull { it is ConstantValue } as ConstantValue? ?: return null
        val idx = attr.constantValueIndex
        val e = cf.constantPool.getOrNull(idx)
        fun bad(): Nothing = throw IllegalArgumentException(
            "ConstantValue #$idx ($e) не подходит полю типа $fieldType"
        )
        fun int(): Int = (e as? ConstantIntegerInfo)?.value ?: bad()
        return when (fieldType) {
            // Как HotSpot (и d8): в boolean попадает младший бит (JVMS §2.3.4 — сужение boolean до бита).
            "Z" -> EncodedValue(EncodedValueType.VALUE_BOOLEAN, int() and 1 != 0)
            "B" -> EncodedValue(EncodedValueType.VALUE_BYTE, int().toByte())
            "S" -> EncodedValue(EncodedValueType.VALUE_SHORT, int().toShort())
            "C" -> EncodedValue(EncodedValueType.VALUE_CHAR, int().toChar())
            "I" -> EncodedValue(EncodedValueType.VALUE_INT, int())
            "J" -> EncodedValue(EncodedValueType.VALUE_LONG, (e as? ConstantLongInfo)?.value ?: bad())
            "F" -> EncodedValue(EncodedValueType.VALUE_FLOAT, (e as? ConstantFloatInfo)?.value ?: bad())
            "D" -> EncodedValue(EncodedValueType.VALUE_DOUBLE, (e as? ConstantDoubleInfo)?.value ?: bad())
            "Ljava/lang/String;" -> EncodedValue(
                EncodedValueType.VALUE_STRING,
                asm.strings.add(cf.utf8((e as? ConstantStringInfo)?.stringIndex ?: bad()))
            )
            else -> bad()
        }
    }
}
