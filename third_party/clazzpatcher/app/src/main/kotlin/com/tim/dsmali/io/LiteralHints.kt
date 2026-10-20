package com.tim.dsmali.io

import com.tim.dsmali.asm.SmaliLiterals
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Подсказки baksmali у числовых литералов: `const v0, 0x3f800000    # 1.0f`, `const-wide v0, 0x…L    # 6.9`
 * и у элементов `.array-data 4/8` — если значение похоже на float/double (эвристика NumberUtils из smali:
 * запись вещественным числом короче целой, «круглые» константы; идентификаторы ресурсов 0x7f…/0x01… — нет).
 * Только в тексте для чтения: разбор отбрасывает комментарии, модель и сборка их не видят.
 */
internal object LiteralHints {

    /** Подсказка к строке инструкции `const*` (без `    # `) или null. */
    fun forInsn(text: String): String? {
        val space = text.indexOf(' ')
        if (space < 0) return null
        val wide = when (text.substring(0, space)) {
            "const/4", "const/16", "const", "const/high16" -> false
            "const-wide/16", "const-wide/32", "const-wide", "const-wide/high16" -> true
            else -> return null
        }
        val comma = text.lastIndexOf(", ")
        if (comma < 0) return null
        val literal = text.substring(comma + 2).trim()
        return try {
            if (wide) doubleHint(SmaliLiterals.parseLong(literal)) else floatHint(SmaliLiterals.parseInt(literal))
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Текст payload `.array-data` с подсказками у 4- и 8-байтовых элементов. */
    fun arrayData(text: String): String {
        val nl = text.indexOf('\n')
        if (nl < 0) return text
        val width = text.substring(0, nl).removePrefix(".array-data").trim()
        if (width != "4" && width != "8") return text
        val out = StringBuilder(text.length + 64)
        for ((i, line) in text.split('\n').withIndex()) {
            if (i > 0) out.append('\n')
            out.append(line)
            val v = line.trim()
            if (i == 0 || v.isEmpty() || v.startsWith(".")) continue
            val hint = try {
                if (width == "8") doubleHint(SmaliLiterals.parseLong(v)) else floatHint(SmaliLiterals.parseInt(v))
            } catch (_: RuntimeException) {
                null
            }
            if (hint != null) out.append("    # ").append(hint)
        }
        return out.toString()
    }

    fun floatHint(bits: Int): String? {
        if (!isLikelyFloat(bits)) return null
        val f = java.lang.Float.intBitsToFloat(bits)
        return when {
            f == Float.POSITIVE_INFINITY -> "Float.POSITIVE_INFINITY"
            f == Float.NEGATIVE_INFINITY -> "Float.NEGATIVE_INFINITY"
            f.isNaN() -> "Float.NaN"
            f == Float.MAX_VALUE -> "Float.MAX_VALUE"
            f == Math.PI.toFloat() -> "(float)Math.PI"
            f == Math.E.toFloat() -> "(float)Math.E"
            else -> f.toString() + "f"
        }
    }

    fun doubleHint(bits: Long): String? {
        if (!isLikelyDouble(bits)) return null
        val d = java.lang.Double.longBitsToDouble(bits)
        return when {
            d == Double.POSITIVE_INFINITY -> "Double.POSITIVE_INFINITY"
            d == Double.NEGATIVE_INFINITY -> "Double.NEGATIVE_INFINITY"
            d.isNaN() -> "Double.NaN"
            d == Double.MAX_VALUE -> "Double.MAX_VALUE"
            d == Math.PI -> "Math.PI"
            d == Math.E -> "Math.E"
            else -> d.toString()
        }
    }

    private val canonicalFloatNaN = java.lang.Float.floatToRawIntBits(Float.NaN)
    private val maxFloat = java.lang.Float.floatToRawIntBits(Float.MAX_VALUE)
    private val piFloat = java.lang.Float.floatToRawIntBits(Math.PI.toFloat())
    private val eFloat = java.lang.Float.floatToRawIntBits(Math.E.toFloat())
    private val canonicalDoubleNaN = java.lang.Double.doubleToRawLongBits(Double.NaN)
    private val maxDouble = java.lang.Double.doubleToLongBits(Double.MAX_VALUE)
    private val piDouble = java.lang.Double.doubleToLongBits(Math.PI)
    private val eDouble = java.lang.Double.doubleToLongBits(Math.E)

    // DecimalFormat не потокобезопасен, а текст классов пишется параллельно; точка — при любой локали
    private val format = object : ThreadLocal<DecimalFormat>() {
        override fun initialValue() = DecimalFormat("0.####################E0", DecimalFormatSymbols(Locale.ROOT))
    }

    fun isLikelyFloat(value: Int): Boolean {
        if (value == canonicalFloatNaN || value == maxFloat || value == piFloat || value == eFloat) return true
        if (value == Int.MAX_VALUE || value == Int.MIN_VALUE) return false
        val packageId = value shr 24
        val resourceType = (value shr 16) and 0xFF
        val resourceId = value and 0xFFFF
        if ((packageId == 0x7f || packageId == 1) && resourceType < 0x1f && resourceId < 0xfff) return false
        val f = java.lang.Float.intBitsToFloat(value)
        if (f.isNaN()) return false
        val fmt = format.get()
        return shorter(fmt.format(f.toDouble()), fmt.format(value.toLong()))
    }

    fun isLikelyDouble(value: Long): Boolean {
        if (value == canonicalDoubleNaN || value == maxDouble || value == piDouble || value == eDouble) return true
        if (value == Long.MAX_VALUE || value == Long.MIN_VALUE) return false
        val d = java.lang.Double.longBitsToDouble(value)
        if (d.isNaN()) return false
        val fmt = format.get()
        return shorter(fmt.format(d), fmt.format(value))
    }

    /** Вещественная запись (с отброшенным хвостом 000…/999… в мантиссе) короче целой. */
    private fun shorter(real: String, integral: String): Boolean {
        var r = real
        val point = r.indexOf('.')
        val exp = r.indexOf('E')
        val zeros = r.indexOf("000")
        if (zeros in (point + 1) until exp) {
            r = r.substring(0, zeros) + r.substring(exp)
        } else {
            val nines = r.indexOf("999")
            if (nines in (point + 1) until exp) r = r.substring(0, nines) + r.substring(exp)
        }
        return r.length < integral.length
    }
}
