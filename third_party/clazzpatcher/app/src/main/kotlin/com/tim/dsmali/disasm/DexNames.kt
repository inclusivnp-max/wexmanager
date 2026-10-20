package com.tim.dsmali.disasm

import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.Annotation
import com.tim.dex.model.DexFile
import com.tim.dex.model.EncodedValue
import com.tim.dsmali.SmaliFormatter
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Разрешение ссылок DEX в smali-текст (строки, типы, поля, методы, прототипы,
 * method handle'ы, call site'ы, encoded values) с кэшами по индексам.
 *
 * Потокобезопасен: кэши заполняются лениво. Гонка при записи строк безвредна (String публикуется
 * безопасно через final-поля), списки публикуются через [AtomicReferenceArray].
 */
class DexNames(val dex: DexFile) {
    private val types: Array<String> = Array(dex.typeIds.size) { i -> string(dex.typeIds[i].descriptorIdx) }
    private val typeTexts = arrayOfNulls<String>(types.size)
    private val quotedStrings = arrayOfNulls<String>(dex.strings.size)
    private val fieldRefs = arrayOfNulls<String>(dex.fieldIds.size)
    private val methodRefs = arrayOfNulls<String>(dex.methodIds.size)
    private val protoRefs = arrayOfNulls<String>(dex.protoIds.size)
    private val protoParams = AtomicReferenceArray<List<String>>(dex.protoIds.size)
    private val methodHandles = arrayOfNulls<String>(dex.methodHandles.size)
    private val callSites = arrayOfNulls<String>(dex.callSiteIds.size)

    fun string(idx: Int): String {
        if (idx < 0 || idx >= dex.strings.size) {
            throw IllegalStateException("Индекс строки $idx вне диапазона [0, ${dex.strings.size})")
        }
        return dex.strings[idx]
    }

    /** Дескриптор типа как есть. */
    fun type(idx: Int): String {
        if (idx < 0 || idx >= types.size) throw IllegalStateException("Индекс типа $idx вне диапазона [0, ${types.size})")
        return types[idx]
    }

    /** Тип в синтаксисе smali. */
    fun typeText(idx: Int): String {
        typeTexts.getOrNull(idx)?.let { return it }
        val t = SmaliFormatter.type(type(idx))
        typeTexts[idx] = t
        return t
    }

    fun quotedString(idx: Int): String {
        quotedStrings.getOrNull(idx)?.let { return it }
        val s = SmaliFormatter.quote(string(idx))
        quotedStrings[idx] = s
        return s
    }

    fun fieldRef(idx: Int): String {
        fieldRefs.getOrNull(idx)?.let { return it }
        if (idx < 0 || idx >= dex.fieldIds.size) throw IllegalStateException("Индекс поля $idx вне диапазона [0, ${dex.fieldIds.size})")
        val f = dex.fieldIds[idx]
        val sb = StringBuilder(64).append(typeText(f.classIdx)).append("->")
        SmaliFormatter.appendSimpleName(sb, string(f.nameIdx)).append(':').append(typeText(f.typeIdx))
        val s = sb.toString()
        fieldRefs[idx] = s
        return s
    }

    fun methodRef(idx: Int): String {
        methodRefs.getOrNull(idx)?.let { return it }
        if (idx < 0 || idx >= dex.methodIds.size) throw IllegalStateException("Индекс метода $idx вне диапазона [0, ${dex.methodIds.size})")
        val m = dex.methodIds[idx]
        val sb = StringBuilder(96).append(typeText(m.classIdx)).append("->")
        SmaliFormatter.appendSimpleName(sb, string(m.nameIdx)).append(protoRef(m.protoIdx))
        val s = sb.toString()
        methodRefs[idx] = s
        return s
    }

    /** Прототип `(params)ret`. */
    fun protoRef(idx: Int): String {
        protoRefs.getOrNull(idx)?.let { return it }
        checkProto(idx)
        val p = dex.protoIds[idx]
        val sb = StringBuilder(48).append('(')
        if (p.parametersTypeListIdx >= 0) {
            for (t in typeList(p.parametersTypeListIdx)) sb.append(typeText(t))
        }
        sb.append(')').append(typeText(p.returnTypeIdx))
        val s = sb.toString()
        protoRefs[idx] = s
        return s
    }

    /** Типы параметров прототипа (дескрипторы как есть). */
    fun protoParamTypes(idx: Int): List<String> {
        checkProto(idx)
        protoParams.get(idx)?.let { return it }
        val listIdx = dex.protoIds[idx].parametersTypeListIdx
        val params = if (listIdx < 0) emptyList() else typeList(listIdx).map { type(it) }
        protoParams.set(idx, params)
        return params
    }

    fun protoReturnType(idx: Int): String {
        checkProto(idx)
        return type(dex.protoIds[idx].returnTypeIdx)
    }

    fun typeList(idx: Int): List<Int> {
        if (idx < 0 || idx >= dex.typeLists.size) {
            throw IllegalStateException("Индекс type_list $idx вне диапазона [0, ${dex.typeLists.size})")
        }
        return dex.typeLists[idx].typeIdx
    }

    private fun checkProto(idx: Int) {
        if (idx < 0 || idx >= dex.protoIds.size) throw IllegalStateException("Индекс прототипа $idx вне диапазона [0, ${dex.protoIds.size})")
    }

    /** Method handle: `invoke-static@Lcls;->m()V`, `static-get@Lcls;->f:I`, ... */
    fun methodHandle(idx: Int): String {
        methodHandles.getOrNull(idx)?.let { return it }
        if (idx < 0 || idx >= dex.methodHandles.size) {
            throw IllegalStateException("Индекс method handle $idx вне диапазона [0, ${dex.methodHandles.size})")
        }
        val mh = dex.methodHandles[idx]
        val kind = METHOD_HANDLE_TYPES.getOrNull(mh.methodHandleType)
            ?: throw IllegalStateException("Неизвестный тип method handle ${mh.methodHandleType} (method_handle #$idx)")
        val member = if (mh.methodHandleType <= 3) fieldRef(mh.fieldOrMethodId) else methodRef(mh.fieldOrMethodId)
        val s = "$kind@$member"
        methodHandles[idx] = s
        return s
    }

    /** Call site: `call_site_N("name", (proto)ret, args...)@Lbootstrap;->m(...)...`. */
    fun callSite(idx: Int): String {
        callSites.getOrNull(idx)?.let { return it }
        if (idx < 0 || idx >= dex.callSiteIds.size) {
            throw IllegalStateException("Индекс call site $idx вне диапазона [0, ${dex.callSiteIds.size})")
        }
        val itemIdx = dex.callSiteIds[idx].callSiteIdx
        if (itemIdx < 0 || itemIdx >= dex.callSiteItems.size) {
            throw IllegalStateException("call_site #$idx ссылается на отсутствующий call_site_item $itemIdx")
        }
        val cs = dex.callSiteItems[itemIdx]
        val args = cs.arguments
        if (args.size < 2 || args[0].type != EncodedValueType.VALUE_STRING || args[1].type != EncodedValueType.VALUE_METHOD_TYPE) {
            throw IllegalStateException("call_site #$idx: ожидаются имя метода (string) и тип (method type) после bootstrap-метода")
        }
        val bootstrap = cs.methodHandle
        if (bootstrap.type != EncodedValueType.VALUE_METHOD_HANDLE) {
            throw IllegalStateException("call_site #$idx: первый элемент не method handle")
        }
        val mh = dex.methodHandles.getOrNull(index(bootstrap))
            ?: throw IllegalStateException("call_site #$idx: method handle ${index(bootstrap)} вне диапазона")
        if (mh.methodHandleType != 4) {
            throw IllegalStateException("call_site #$idx: bootstrap method handle должен быть invoke-static, а не ${mh.methodHandleType}")
        }
        val sb = StringBuilder(128).append("call_site_").append(idx).append('(')
        appendEncodedValue(sb, args[0], 0)
        sb.append(", ")
        appendEncodedValue(sb, args[1], 0)
        for (i in 2 until args.size) {
            sb.append(", ")
            appendEncodedValue(sb, args[i], 0)
        }
        sb.append(")@").append(methodRef(mh.fieldOrMethodId))
        val s = sb.toString()
        callSites[idx] = s
        return s
    }

    fun encodedValue(value: EncodedValue): String {
        val sb = StringBuilder(32)
        appendEncodedValue(sb, value, 0)
        return sb.toString()
    }

    /**
     * Encoded value в синтаксисе baksmali. Многострочные значения (массивы, subannotation)
     * записываются с отступами относительно строки, где начинается значение ([indent] — её отступ).
     */
    fun appendEncodedValue(sb: StringBuilder, value: EncodedValue, indent: Int) {
        val v = value.value
        when (value.type) {
            EncodedValueType.VALUE_BYTE -> SmaliFormatter.appendIntegral(sb, number(value).toLong(), 't')
            EncodedValueType.VALUE_SHORT -> SmaliFormatter.appendIntegral(sb, number(value).toLong(), 's')
            EncodedValueType.VALUE_CHAR -> SmaliFormatter.appendChar(sb, when (v) {
                is Char -> v
                is Number -> v.toInt().toChar()
                else -> throw badValue(value)
            })
            EncodedValueType.VALUE_INT -> SmaliFormatter.appendIntegral(sb, number(value).toInt().toLong(), null)
            EncodedValueType.VALUE_LONG -> SmaliFormatter.appendIntegral(sb, number(value).toLong(), 'L')
            EncodedValueType.VALUE_FLOAT -> sb.append(java.lang.Float.toString((v as? Float) ?: throw badValue(value))).append('f')
            EncodedValueType.VALUE_DOUBLE -> sb.append(java.lang.Double.toString((v as? Double) ?: throw badValue(value)))
            EncodedValueType.VALUE_METHOD_TYPE -> sb.append(protoRef(index(value)))
            EncodedValueType.VALUE_METHOD_HANDLE -> sb.append(methodHandle(index(value)))
            EncodedValueType.VALUE_STRING -> sb.append(quotedString(index(value)))
            EncodedValueType.VALUE_TYPE -> sb.append(typeText(index(value)))
            EncodedValueType.VALUE_FIELD -> sb.append(fieldRef(index(value)))
            EncodedValueType.VALUE_METHOD -> sb.append(methodRef(index(value)))
            EncodedValueType.VALUE_ENUM -> sb.append(".enum ").append(fieldRef(index(value)))
            EncodedValueType.VALUE_ARRAY -> {
                val list = v as? List<*> ?: throw badValue(value)
                if (list.isEmpty()) {
                    sb.append("{}")
                } else {
                    sb.append('{')
                    for (i in list.indices) {
                        if (i > 0) sb.append(',')
                        newLine(sb, indent + 4)
                        appendEncodedValue(sb, list[i] as? EncodedValue ?: throw badValue(value), indent + 4)
                    }
                    newLine(sb, indent)
                    sb.append('}')
                }
            }
            EncodedValueType.VALUE_ANNOTATION -> {
                val ann = v as? Annotation ?: throw badValue(value)
                sb.append(".subannotation ").append(typeText(ann.typeIdx))
                for (e in ann.elements) {
                    newLine(sb, indent + 4)
                    SmaliFormatter.appendSimpleName(sb, string(e.nameIdx)).append(" = ")
                    appendEncodedValue(sb, e.value, indent + 4)
                }
                newLine(sb, indent)
                sb.append(".end subannotation")
            }
            EncodedValueType.VALUE_NULL -> sb.append("null")
            EncodedValueType.VALUE_BOOLEAN -> sb.append(if ((v as? Boolean) ?: throw badValue(value)) "true" else "false")
            else -> throw IllegalStateException("Неизвестный тип encoded value: 0x${Integer.toHexString(value.type)}")
        }
    }

    private fun newLine(sb: StringBuilder, indent: Int) {
        sb.append('\n')
        for (i in 0 until indent) sb.append(' ')
    }

    private fun number(value: EncodedValue): Number = value.value as? Number ?: throw badValue(value)

    private fun index(value: EncodedValue): Int = (value.value as? Number ?: throw badValue(value)).toInt()

    private fun badValue(value: EncodedValue) = IllegalStateException(
        "Encoded value типа 0x${Integer.toHexString(value.type)} содержит неожиданное значение: ${value.value}"
    )

    companion object {
        private val METHOD_HANDLE_TYPES = arrayOf(
            "static-put", "static-get", "instance-put", "instance-get",
            "invoke-static", "invoke-instance", "invoke-constructor", "invoke-direct", "invoke-interface"
        )
    }
}
