package com.tim.dsmali.asm

import com.tim.dasm.DexAsm
import com.tim.dasm.model.MethodHandleKind
import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.Annotation
import com.tim.dex.model.AnnotationElement
import com.tim.dex.model.AnnotationItem
import com.tim.dex.model.EncodedValue

/**
 * Ссылки smali (методы, поля, прототипы, method handle, call site, строки) → индексы пулов
 * с кэшем по исходному тексту: повторная ссылка — один поиск в хэш-таблице.
 */
class SmaliRefs(val asm: DexAsm) {
    private val methodCache = HashMap<String, Int>()
    private val fieldCache = HashMap<String, Int>()
    private val protoCache = HashMap<String, Int>()
    private val stringCache = HashMap<String, Int>()
    private val handleCache = HashMap<String, Int>()
    private val callSiteCache = HashMap<String, Int>()

    val values = SmaliValueBuilder(asm, this)

    /** Строковый литерал в кавычках (с экранированием) → string_idx. */
    fun string(raw: String): Int = stringCache[raw] ?: run {
        val idx = asm.strings.add(SmaliLiterals.unescape(raw, 1, raw.length - 1))
        stringCache[raw] = idx
        idx
    }

    /** Дескриптор типа → type_idx (проверяется при первом добавлении). */
    fun type(desc: String): Int {
        asm.types.find(desc)?.let { return it }
        val s = SmaliScanner(desc)
        s.type()
        s.expectEnd()
        return asm.types.add(desc)
    }

    /** `Lcls;->name(params)ret` → method_idx. */
    fun method(text: String): Int = methodCache[text] ?: run {
        val s = SmaliScanner(text)
        val idx = method(s)
        s.expectEnd()
        methodCache[text] = idx
        idx
    }

    fun method(s: SmaliScanner): Int {
        val cls = s.type()
        if (!s.tryConsume("->")) s.error("Ожидалось '->'")
        val name = s.memberName()
        val (params, ret) = s.proto()
        return asm.methods.add(cls, name, params, ret)
    }

    /** `Lcls;->name:type` → field_idx. */
    fun field(text: String): Int = fieldCache[text] ?: run {
        val s = SmaliScanner(text)
        val idx = field(s)
        s.expectEnd()
        fieldCache[text] = idx
        idx
    }

    fun field(s: SmaliScanner): Int {
        val cls = s.type()
        if (!s.tryConsume("->")) s.error("Ожидалось '->'")
        val name = s.memberName()
        s.expect(':')
        return asm.fields.add(cls, name, s.type())
    }

    /** `(params)ret` → proto_idx. */
    fun proto(text: String): Int = protoCache[text] ?: run {
        val s = SmaliScanner(text)
        val (params, ret) = s.proto()
        s.expectEnd()
        val idx = asm.protos.add(ret, params)
        protoCache[text] = idx
        idx
    }

    /** `invoke-static@Lcls;->m()V`, `static-get@Lcls;->f:I` и т.п. → индекс method handle. */
    fun methodHandle(text: String): Int = handleCache[text] ?: run {
        val s = SmaliScanner(text)
        val idx = methodHandle(s)
        s.expectEnd()
        handleCache[text] = idx
        idx
    }

    fun methodHandle(s: SmaliScanner): Int {
        s.skipWs()
        val at = s.text.indexOf('@', s.pos)
        if (at < 0) s.error("Ожидался method handle вида тип@ссылка")
        val kindName = s.text.substring(s.pos, at)
        val kind = MethodHandleKind.byName(kindName) ?: s.error("Неизвестный тип method handle: $kindName")
        s.pos = at + 1
        return if (MethodHandleKind.isField(kind)) {
            asm.methodHandles.add(kind, field(s))
        } else {
            asm.methodHandles.add(kind, method(s))
        }
    }

    /**
     * Call site: `name("methodName", (proto)ret, args...)@Lbsm;->m(...)ret`.
     * Одинаковые call site с разными именами остаются разными (как в исходном DEX).
     */
    fun callSite(text: String): Int = callSiteCache[text] ?: run {
        val s = SmaliScanner(text)
        val name = s.simpleName()
        s.expect('(')
        val methodName = s.stringLiteral()
        s.expect(',')
        s.skipWs()
        val (params, ret) = s.proto()
        val args = ArrayList<EncodedValue>()
        while (s.tryConsume(',')) args.add(values.value(s))
        s.expect(')')
        s.expect('@')
        val bootstrap = asm.methodHandles.add(MethodHandleKind.INVOKE_STATIC, method(s))
        s.expectEnd()
        val idx = asm.callSites.add(bootstrap, methodName, ret, params, args, name)
        callSiteCache[text] = idx
        idx
    }
}

/**
 * Закодированные значения в синтаксисе smali (значения полей, элементов аннотаций,
 * аргументов call site) → [EncodedValue] в представлении [com.tim.dex.parser.DexReader]:
 * Byte/Short/Char/Int/Long/Float/Double/Boolean, индексы пулов, List для массивов,
 * [Annotation] для вложенных аннотаций, `null` для null.
 */
class SmaliValueBuilder(private val asm: DexAsm, refs: SmaliRefs? = null) {

    private val refs: SmaliRefs = refs ?: SmaliRefs(asm)

    fun build(text: String): EncodedValue {
        val s = SmaliScanner(text)
        val v = try {
            value(s).also { s.expectEnd() }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Некорректное значение \"${text.trim()}\": ${e.message}", e)
        }
        return v
    }

    fun value(s: SmaliScanner): EncodedValue {
        when (val c = s.peek()) {
            '"' -> return EncodedValue(EncodedValueType.VALUE_STRING, refs.string(s.stringLiteralRaw()))
            '\'' -> return EncodedValue(EncodedValueType.VALUE_CHAR, SmaliLiterals.parseChar(s.charLiteralRaw()))
            '{' -> return EncodedValue(EncodedValueType.VALUE_ARRAY, array(s))
            '(' -> {
                val start = s.pos
                s.proto()
                return EncodedValue(EncodedValueType.VALUE_METHOD_TYPE, refs.proto(s.text.substring(start, s.pos)))
            }
            '.' -> {
                if (s.tryConsume(".subannotation")) return EncodedValue(EncodedValueType.VALUE_ANNOTATION, subannotation(s))
                if (s.tryConsume(".enum")) return EncodedValue(EncodedValueType.VALUE_ENUM, refs.field(s))
                s.error("Неизвестное значение")
            }
            'L', '[' -> {
                val start = s.pos
                s.type()
                if (!s.text.startsWith("->", s.pos)) {
                    return EncodedValue(EncodedValueType.VALUE_TYPE, asm.types.add(s.text.substring(start, s.pos)))
                }
                s.pos = start
                return memberReference(s)
            }
            else -> {
                if (c in 'a'..'z' && isMethodHandleAhead(s)) {
                    return EncodedValue(EncodedValueType.VALUE_METHOD_HANDLE, refs.methodHandle(s))
                }
                val w = s.word()
                return when {
                    w == "null" -> EncodedValue(EncodedValueType.VALUE_NULL, null)
                    w.length == 1 && w[0] in "VZBSCIJFD" -> EncodedValue(EncodedValueType.VALUE_TYPE, asm.types.add(w))
                    else -> literal(w)
                }
            }
        }
    }

    private fun memberReference(s: SmaliScanner): EncodedValue {
        val start = s.pos
        s.type()
        s.tryConsume("->")
        s.memberName()
        return if (s.pos < s.text.length && s.text[s.pos] == '(') {
            s.pos = start
            EncodedValue(EncodedValueType.VALUE_METHOD, refs.method(s))
        } else {
            s.pos = start
            EncodedValue(EncodedValueType.VALUE_FIELD, refs.field(s))
        }
    }

    private fun isMethodHandleAhead(s: SmaliScanner): Boolean {
        var i = s.pos
        while (i < s.text.length && (s.text[i] in 'a'..'z' || s.text[i] == '-')) i++
        return i < s.text.length && s.text[i] == '@'
    }

    fun literal(w: String): EncodedValue {
        val lit = SmaliLiterals.parse(w)
        return when (lit.kind) {
            SmaliLiterals.Kind.INT -> EncodedValue(EncodedValueType.VALUE_INT, lit.intValue)
            SmaliLiterals.Kind.LONG -> EncodedValue(EncodedValueType.VALUE_LONG, lit.bits)
            SmaliLiterals.Kind.SHORT -> EncodedValue(EncodedValueType.VALUE_SHORT, lit.bits.toShort())
            SmaliLiterals.Kind.BYTE -> EncodedValue(EncodedValueType.VALUE_BYTE, lit.bits.toByte())
            SmaliLiterals.Kind.FLOAT -> EncodedValue(EncodedValueType.VALUE_FLOAT, lit.floatValue)
            SmaliLiterals.Kind.DOUBLE -> EncodedValue(EncodedValueType.VALUE_DOUBLE, lit.doubleValue)
            SmaliLiterals.Kind.CHAR -> EncodedValue(EncodedValueType.VALUE_CHAR, lit.bits.toInt().toChar())
            SmaliLiterals.Kind.BOOLEAN -> EncodedValue(EncodedValueType.VALUE_BOOLEAN, lit.bits != 0L)
        }
    }

    private fun array(s: SmaliScanner): List<EncodedValue> {
        s.expect('{')
        val out = ArrayList<EncodedValue>()
        if (s.tryConsume('}')) return out
        while (true) {
            out.add(value(s))
            if (s.tryConsume('}')) return out
            s.expect(',')
        }
    }

    private fun subannotation(s: SmaliScanner): Annotation {
        val typeIdx = asm.types.add(s.type())
        val elements = ArrayList<Pair<String, EncodedValue>>()
        while (!s.tryConsume(".end subannotation")) {
            if (s.atEnd()) s.error("Нет .end subannotation")
            val name = s.simpleName()
            s.expect('=')
            elements.add(name to value(s))
        }
        return Annotation(typeIdx, sortedElements(elements))
    }

    private fun sortedElements(elements: List<Pair<String, EncodedValue>>): List<AnnotationElement> {
        val sorted = elements.sortedBy { it.first }
        for (i in 1 until sorted.size) {
            require(sorted[i].first != sorted[i - 1].first) { "Элемент аннотации ${sorted[i].first} указан дважды" }
        }
        return sorted.map { AnnotationElement(asm.strings.add(it.first), it.second) }
    }

    /**
     * Аннотация: видимость `build`/`runtime`/`system`, тип и элементы (имя → текст значения).
     */
    fun buildAnnotation(visibility: String, type: String, elements: List<Pair<String, String>>): AnnotationItem {
        val vis = when (visibility) {
            "build" -> 0
            "runtime" -> 1
            "system" -> 2
            else -> throw IllegalArgumentException("Неизвестная видимость аннотации: $visibility")
        }.toByte()
        val typeIdx = refs.type(type)
        val values = elements.map { (name, value) ->
            try {
                name to build(value)
            } catch (e: Exception) {
                throw IllegalArgumentException("Аннотация $type, элемент $name: ${e.message}", e)
            }
        }
        return AnnotationItem(vis, Annotation(typeIdx, sortedElements(values)))
    }
}
