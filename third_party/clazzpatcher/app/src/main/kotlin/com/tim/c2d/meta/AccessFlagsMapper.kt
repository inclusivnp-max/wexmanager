package com.tim.c2d.meta

import com.tim.clazz.model.attributes.AttributeInfo
import com.tim.clazz.model.attributes.Synthetic
import com.tim.dasm.model.AccessFlags

/**
 * Флаги доступа JVM → DEX.
 *
 * Отличия DEX: у класса нет ACC_SUPER; `<init>`/`<clinit>` помечаются ACC_CONSTRUCTOR;
 * ACC_SYNCHRONIZED допустим только у native-методов — у остальных он становится
 * ACC_DECLARED_SYNCHRONIZED, а блокировку делает сам код. Атрибут Synthetic — флаг ACC_SYNTHETIC.
 */
object AccessFlagsMapper {
    private const val ACC_SUPER = 0x0020
    private const val ACC_MODULE = 0x8000

    fun classFlags(jvm: Int, attributes: List<AttributeInfo>): Int =
        withSynthetic(jvm and 0xFFFF and (ACC_SUPER or ACC_MODULE).inv(), attributes)

    fun fieldFlags(jvm: Int, attributes: List<AttributeInfo>): Int = withSynthetic(jvm and 0xFFFF, attributes)

    fun methodFlags(jvm: Int, name: String, attributes: List<AttributeInfo>): Int {
        var f = withSynthetic(jvm and 0xFFFF, attributes)
        if (name == "<init>" || name == "<clinit>") f = f or AccessFlags.CONSTRUCTOR
        if (isSynchronizedCode(jvm)) f = (f and AccessFlags.SYNCHRONIZED.inv()) or AccessFlags.DECLARED_SYNCHRONIZED
        return f
    }

    /** synchronized-метод с кодом: блокировку нужно сгенерировать явно. */
    fun isSynchronizedCode(jvm: Int): Boolean =
        jvm and AccessFlags.SYNCHRONIZED != 0 && jvm and AccessFlags.NATIVE == 0

    private fun withSynthetic(flags: Int, attributes: List<AttributeInfo>): Int =
        if (attributes.any { it is Synthetic }) flags or AccessFlags.SYNTHETIC else flags
}
