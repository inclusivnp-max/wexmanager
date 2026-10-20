package com.tim.csmali.disasm

import com.tim.clazz.model.*
import com.tim.clazz.model.attributes.BootstrapMethods
import com.tim.csmali.SmaliFormatter

/**
 * Текстовые формы ссылок пула констант для csmali (с кэшем по индексу пула).
 *
 * Синтаксис:
 * - тип: `Ljava/lang/String;`, `[I`;
 * - поле: `Lowner;->name:Ltype;`, метод: `Lowner;->name(params)ret`;
 *   ссылка на метод интерфейса вне invokeinterface предваряется словом `interface`;
 * - method handle: `kind@ссылка`, kind — instance-get … invoke-interface;
 * - bootstrap: `handle, { арг, … }`, аргументы — литералы, строки, типы, method type `(..)R`,
 *   handle и динамические константы `.dynamic имя:тип, handle, { … }`.
 */
class ClassNames(val cf: ClassFile) {
    private val pool = cf.constantPool
    private val bootstrap: List<BootstrapMethods.BootstrapMethod> =
        cf.attributes.filterIsInstance<BootstrapMethods>().firstOrNull()?.bootstrapMethods ?: emptyList()
    private val typeTexts = arrayOfNulls<String>(pool.size)
    private val fieldRefs = arrayOfNulls<String>(pool.size)
    private val methodRefs = arrayOfNulls<String>(pool.size)

    /** Внутреннее имя класса (`java/io/File` или `[I`) по индексу CONSTANT_Class. */
    fun type(idx: Int): String = cf.className(idx)

    /** Тип по CONSTANT_Class в виде дескриптора (`Ljava/io/File;`, `[I`). */
    fun typeText(idx: Int): String {
        typeTexts.getOrNull(idx)?.let { return it }
        val t = SmaliFormatter.type(classDescriptor(type(idx)))
        typeTexts[idx] = t
        return t
    }

    /** Дескриптор из CONSTANT_Utf8 (аннотации, LocalVariableTable, class-значения). */
    fun descriptorText(utf8Idx: Int): String = SmaliFormatter.type(cf.utf8(utf8Idx))

    fun quotedString(utf8Idx: Int): String = SmaliFormatter.quote(cf.utf8(utf8Idx))

    fun fieldRef(idx: Int): String {
        fieldRefs.getOrNull(idx)?.let { return it }
        val f = pool.getOrNull(idx) as? ConstantFieldrefInfo
            ?: throw IllegalStateException("#$idx не CONSTANT_Fieldref")
        val s = member(f.classIndex, f.nameAndTypeIndex, isField = true)
        fieldRefs[idx] = s
        return s
    }

    /** Ссылка на метод; для InterfaceMethodref (кроме [implicitInterface]) — с префиксом `interface `. */
    fun methodRef(idx: Int, implicitInterface: Boolean = false): String {
        val m = pool.getOrNull(idx) as? ConstantRefInfo
        if (m == null || m is ConstantFieldrefInfo) throw IllegalStateException("#$idx не CONSTANT_Methodref/InterfaceMethodref")
        val cached = methodRefs.getOrNull(idx) ?: member(m.classIndex, m.nameAndTypeIndex, isField = false).also { methodRefs[idx] = it }
        return if (m is ConstantInterfaceMethodrefInfo && !implicitInterface) "interface $cached" else cached
    }

    fun memberRef(idx: Int): String =
        if (pool.getOrNull(idx) is ConstantFieldrefInfo) fieldRef(idx) else methodRef(idx)

    private fun member(classIdx: Int, natIdx: Int, isField: Boolean): String {
        val nat = pool.getOrNull(natIdx) as? ConstantNameAndTypeInfo
            ?: throw IllegalStateException("NameAndType #$natIdx отсутствует")
        val sb = StringBuilder(64).append(typeText(classIdx)).append("->")
        SmaliFormatter.appendSimpleName(sb, cf.utf8(nat.nameIndex))
        if (isField) sb.append(':').append(SmaliFormatter.type(cf.utf8(nat.descriptorIndex)))
        else sb.append(cf.utf8(nat.descriptorIndex))
        return sb.toString()
    }

    /** `имя(дескриптор)` из CONSTANT_NameAndType (EnclosingMethod). */
    fun nameAndType(natIdx: Int): String {
        val nat = pool.getOrNull(natIdx) as? ConstantNameAndTypeInfo
            ?: throw IllegalStateException("#$natIdx не CONSTANT_NameAndType")
        return cf.utf8(nat.nameIndex) + cf.utf8(nat.descriptorIndex)
    }

    fun methodHandleRef(idx: Int): String {
        val mh = pool.getOrNull(idx) as? ConstantMethodHandleInfo
            ?: throw IllegalStateException("#$idx не CONSTANT_MethodHandle")
        val kind = METHOD_HANDLE_KINDS.getOrNull(mh.referenceKind)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("MethodHandle #$idx: неизвестный reference_kind ${mh.referenceKind}")
        return "$kind@${memberRef(mh.referenceIndex)}"
    }

    /** `имя(дескриптор), handle, { аргументы }` для invokedynamic. */
    fun invokeDynamicRef(idx: Int): String {
        val dyn = pool.getOrNull(idx) as? ConstantInvokeDynamicInfo
            ?: throw IllegalStateException("#$idx не CONSTANT_InvokeDynamic")
        val nat = pool.getOrNull(dyn.nameAndTypeIndex) as? ConstantNameAndTypeInfo
            ?: throw IllegalStateException("InvokeDynamic #$idx: NameAndType отсутствует")
        val sb = StringBuilder(128)
        SmaliFormatter.appendSimpleName(sb, cf.utf8(nat.nameIndex)).append(cf.utf8(nat.descriptorIndex)).append(", ")
        appendBootstrap(sb, dyn.bootstrapMethodAttrIndex, HashSet())
        return sb.toString()
    }

    /** `.dynamic имя:тип, handle, { аргументы }` для ldc/аргументов bootstrap. */
    fun dynamicRef(idx: Int): String = StringBuilder(96).also { appendDynamic(it, idx, HashSet()) }.toString()

    private fun appendDynamic(sb: StringBuilder, idx: Int, visiting: MutableSet<Int>) {
        val dyn = pool.getOrNull(idx) as? ConstantDynamicInfo
            ?: throw IllegalStateException("#$idx не CONSTANT_Dynamic")
        if (!visiting.add(idx)) throw IllegalStateException("CONSTANT_Dynamic #$idx ссылается сам на себя")
        val nat = pool.getOrNull(dyn.nameAndTypeIndex) as? ConstantNameAndTypeInfo
            ?: throw IllegalStateException("Dynamic #$idx: NameAndType отсутствует")
        sb.append(".dynamic ")
        SmaliFormatter.appendSimpleName(sb, cf.utf8(nat.nameIndex)).append(':')
            .append(SmaliFormatter.type(cf.utf8(nat.descriptorIndex))).append(", ")
        appendBootstrap(sb, dyn.bootstrapMethodAttrIndex, visiting)
        visiting.remove(idx)
    }

    private fun appendBootstrap(sb: StringBuilder, index: Int, visiting: MutableSet<Int>) {
        val bm = bootstrap.getOrNull(index)
            ?: throw IllegalStateException("BootstrapMethods #$index вне диапазона (их ${bootstrap.size})")
        sb.append(methodHandleRef(bm.bootstrapMethodRef)).append(", {")
        for ((i, arg) in bm.bootstrapArguments.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(' ')
            appendConstant(sb, arg, visiting)
        }
        sb.append(if (bm.bootstrapArguments.isEmpty()) "}" else " }")
    }

    /** Загружаемая константа (ldc, аргумент bootstrap). */
    fun constantText(idx: Int): String = StringBuilder(32).also { appendConstant(it, idx, HashSet()) }.toString()

    private fun appendConstant(sb: StringBuilder, idx: Int, visiting: MutableSet<Int>) {
        when (val e = pool.getOrNull(idx) ?: throw IllegalStateException("Константа #$idx вне пула")) {
            is ConstantIntegerInfo -> SmaliFormatter.appendIntegral(sb, e.value.toLong(), null)
            is ConstantFloatInfo -> sb.append(floatText(e.bits))
            is ConstantLongInfo -> SmaliFormatter.appendIntegral(sb, e.value, 'L')
            is ConstantDoubleInfo -> sb.append(doubleText(e.bits))
            is ConstantStringInfo -> sb.append(quotedString(e.stringIndex))
            is ConstantClassInfo -> sb.append(typeText(idx))
            is ConstantMethodHandleInfo -> sb.append(methodHandleRef(idx))
            is ConstantMethodTypeInfo -> sb.append(cf.utf8(e.descriptorIndex))
            is ConstantDynamicInfo -> appendDynamic(sb, idx, visiting)
            else -> throw IllegalStateException("#$idx: ${e.tagName} не является загружаемой константой")
        }
    }

    companion object {
        val METHOD_HANDLE_KINDS = arrayOf(
            "",
            "instance-get",
            "static-get",
            "instance-put",
            "static-put",
            "invoke-instance",
            "invoke-static",
            "invoke-direct",
            "invoke-constructor",
            "invoke-interface"
        )

        /** Внутреннее имя из CONSTANT_Class → дескриптор. */
        fun classDescriptor(internalName: String): String =
            if (internalName.startsWith("[")) internalName else "L$internalName;"

        /** Float без потерь: канонический NaN — `NaNf`, NaN с иной полезной нагрузкой — `NaNf:0x…` (биты). */
        fun floatText(bits: Int): String {
            val v = Float.fromBits(bits)
            return if (v.isNaN() && bits != java.lang.Float.floatToIntBits(Float.NaN)) "NaNf:0x${Integer.toHexString(bits)}"
            else "${v}f"
        }

        /** Double без потерь: NaN с неканонической полезной нагрузкой — `NaN:0x…` (биты). */
        fun doubleText(bits: Long): String {
            val v = Double.fromBits(bits)
            return if (v.isNaN() && bits != java.lang.Double.doubleToLongBits(Double.NaN)) "NaN:0x${java.lang.Long.toHexString(bits)}"
            else v.toString()
        }
    }
}
