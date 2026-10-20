package com.tim.testutil

import com.tim.dex.model.Annotation
import com.tim.dex.model.ClassDataItem
import com.tim.dex.model.CodeItem
import com.tim.dex.model.DebugInfoItem
import com.tim.dex.model.DebugOpcode
import com.tim.dex.model.DexFile
import com.tim.dex.model.EncodedMethod
import com.tim.dex.model.EncodedValue
import com.tim.dex.parser.DexReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

/**
 * Семантическое представление DEX для сравнения, не зависящее от порядка пулов, смещений и раскладки секций.
 *
 * Классы сравниваются по дескриптору; члены — по полной ссылке; байткод печатается собственным декодером
 * ([InsnDecoder]) с разрешёнными индексами; отладочная информация — как последовательность событий
 * (адрес -> строка/локальная переменная/...), флаги hidden API разбираются из байтов самостоятельно.
 */
class DexSemantics private constructor(private val bytes: ByteArray, val dex: DexFile) : InsnDecoder.Refs {

    data class Options(
        /** Сравнивать отладочную информацию (.line/.local/.param-имена/...). */
        val debugInfo: Boolean = true,
        /** Считать DBG_SET_EPILOGUE_BEGIN равным DBG_SET_PROLOGUE_END (эталонный baksmali 3.0.9 печатает оба как .prologue). */
        val mergePrologueEpilogue: Boolean = false,
        /** Сравнивать версию DEX (magic). */
        val version: Boolean = false,
        /** Сравнивать содержимое пулов (строки, типы, прототипы, поля, методы, call site, method handle). */
        val pools: Boolean = false,
        /**
         * outs_size сравнивается точно. Иначе проверяется только достаточность для invoke-*: в smali его нет,
         * ассемблер вычисляет сам (d8 учитывает и filled-new-array, эталонный smali — только invoke).
         */
        val exactOuts: Boolean = true
    ) {
        companion object {
            /** Для конвейеров через smali-текст. */
            val SMALI = Options(exactOuts = false)
        }
    }

    val version: String = String(bytes, 4, 3, Charsets.US_ASCII)

    private val decoder = InsnDecoder(this)

    /** Дескриптор -> индекс class_def. */
    val classIndex: Map<String, Int> = HashMap<String, Int>().also { map ->
        dex.classDefs.forEachIndexed { i, cd ->
            val d = type(cd.classIdx)
            if (map.put(d, i) != null) throw IllegalStateException("дубликат class_def для $d")
        }
    }

    /** Флаги hidden API по class_def (в порядке членов class_data) или null, если флагов нет. */
    private val hiddenApi: Array<IntArray?> = parseHiddenApi()

    // ===== Разрешение ссылок =====

    private fun <T> at(list: List<T>, idx: Int, what: String): T {
        if (idx < 0 || idx >= list.size) throw IllegalStateException("$what: индекс $idx вне пула (размер ${list.size})")
        return list[idx]
    }

    override fun string(idx: Int): String = quote(at(dex.strings, idx, "string_id"))
    private fun rawString(idx: Int): String = at(dex.strings, idx, "string_id")
    override fun type(idx: Int): String = rawString(at(dex.typeIds, idx, "type_id").descriptorIdx)

    override fun field(idx: Int): String {
        val f = at(dex.fieldIds, idx, "field_id")
        return "${type(f.classIdx)}->${rawString(f.nameIdx)}:${type(f.typeIdx)}"
    }

    override fun method(idx: Int): String {
        val m = at(dex.methodIds, idx, "method_id")
        return "${type(m.classIdx)}->${rawString(m.nameIdx)}${proto(m.protoIdx)}"
    }

    override fun proto(idx: Int): String {
        val p = at(dex.protoIds, idx, "proto_id")
        return "(" + typeList(p.parametersTypeListIdx).joinToString("") + ")" + type(p.returnTypeIdx)
    }

    private fun typeList(idx: Int): List<String> =
        if (idx < 0) emptyList() else at(dex.typeLists, idx, "type_list").typeIdx.map { type(it) }

    override fun callSite(idx: Int): String {
        val id = at(dex.callSiteIds, idx, "call_site_id")
        val cs = at(dex.callSiteItems, id.callSiteIdx, "call_site_item")
        return "callsite(" + (listOf(cs.methodHandle) + cs.arguments).joinToString(", ") { value(it) } + ")"
    }

    override fun methodHandle(idx: Int): String {
        val mh = at(dex.methodHandles, idx, "method_handle")
        val kind = MH_KINDS.getOrNull(mh.methodHandleType)
            ?: throw IllegalStateException("method_handle $idx: неизвестный тип ${mh.methodHandleType}")
        val target = if (mh.methodHandleType <= 3) field(mh.fieldOrMethodId) else method(mh.fieldOrMethodId)
        return "$kind@$target"
    }

    fun value(v: EncodedValue): String {
        val x = v.value
        fun idx(): Int = (x as Number).toInt()
        return when (v.type) {
            0x00 -> "byte:" + (x as Number).toByte()
            0x02 -> "short:" + (x as Number).toShort()
            0x03 -> "char:" + fmt("\\u%04x", if (x is Char) x.code else (x as Number).toInt() and 0xFFFF)
            0x04 -> "int:" + (x as Number).toInt()
            0x06 -> "long:" + (x as Number).toLong()
            0x10 -> "float:" + fmt("%08x", java.lang.Float.floatToRawIntBits((x as Number).toFloat()))
            0x11 -> "double:" + fmt("%016x", java.lang.Double.doubleToRawLongBits((x as Number).toDouble()))
            0x15 -> "methodtype:" + proto(idx())
            0x16 -> "methodhandle:" + methodHandle(idx())
            0x17 -> "string:" + string(idx())
            0x18 -> "type:" + type(idx())
            0x19 -> "field:" + field(idx())
            0x1a -> "method:" + method(idx())
            0x1b -> "enum:" + field(idx())
            0x1c -> (x as List<*>).joinToString(", ", "[", "]") { value(it as EncodedValue) }
            0x1d -> annotation(x as Annotation)
            0x1e -> "null"
            0x1f -> "boolean:" + (x as Boolean)
            else -> throw IllegalStateException(fmt("неизвестный тип encoded_value 0x%02x", v.type))
        }
    }

    private fun annotation(a: Annotation): String =
        "@" + type(a.typeIdx) + a.elements.map { rawString(it.nameIdx) + "=" + value(it.value) }.sorted().joinToString(", ", "(", ")")

    private fun annotationSet(idx: Int): List<String> {
        if (idx < 0) return emptyList()
        return at(dex.annotationSetItems, idx, "annotation_set_item").annotationIdxs.map { ai ->
            val item = at(dex.annotationItems, ai, "annotation_item")
            val vis = when (item.visibility.toInt()) {
                0 -> "build"
                1 -> "runtime"
                2 -> "system"
                else -> throw IllegalStateException("неизвестная видимость аннотации ${item.visibility}")
            }
            "$vis " + annotation(item.annotation)
        }.sorted()
    }

    // ===== Дамп класса =====

    /** Текстовый дамп класса; равенство дампов = семантическое равенство классов. */
    fun dumpClass(descriptor: String, options: Options): String {
        val idx = classIndex[descriptor] ?: throw IllegalArgumentException("класс $descriptor не найден")
        return try {
            buildClassDump(idx, options)
        } catch (e: RuntimeException) {
            throw IllegalStateException("класс $descriptor: ${e.message}", e)
        }
    }

    private fun buildClassDump(idx: Int, options: Options): String {
        val cd = dex.classDefs[idx]
        val sb = StringBuilder()
        sb.append("class ").append(type(cd.classIdx)).append(" flags=0x").append(Integer.toHexString(cd.accessFlags)).append('\n')
        sb.append("  super ").append(if (cd.superclassIdx < 0) "-" else type(cd.superclassIdx)).append('\n')
        sb.append("  source ").append(if (cd.sourceFileIdx < 0) "-" else string(cd.sourceFileIdx)).append('\n')
        typeList(cd.interfacesTypeListIdx).forEach { sb.append("  implements ").append(it).append('\n') }

        if (cd.annotationsDirectoryIdx >= 0) {
            val dir = at(dex.annotationsDirectoryItems, cd.annotationsDirectoryIdx, "annotations_directory_item")
            annotationSet(dir.classAnnotationsSetIdx).forEach { sb.append("  annotation ").append(it).append('\n') }
            dir.fieldAnnotations.map { field(it.fieldIdx) to annotationSet(it.annotationsSetIdx) }.sortedBy { it.first }
                .forEach { (f, set) -> sb.append("  field-annotations ").append(f).append(' ').append(set).append('\n') }
            dir.methodAnnotations.map { method(it.methodIdx) to annotationSet(it.annotationsSetIdx) }.sortedBy { it.first }
                .forEach { (m, set) -> sb.append("  method-annotations ").append(m).append(' ').append(set).append('\n') }
            dir.parameterAnnotations.map { pa ->
                val sets = if (pa.annotationsSetRefListIdx < 0) emptyList() else
                    at(dex.annotationSetRefLists, pa.annotationsSetRefListIdx, "annotation_set_ref_list").annotationSetIdxs.map { annotationSet(it) }
                method(pa.methodIdx) to sets
            }.sortedBy { it.first }
                .forEach { (m, sets) -> sb.append("  param-annotations ").append(m).append(' ').append(sets).append('\n') }
        }

        val staticValues = if (cd.staticValuesIdx < 0) emptyList() else at(dex.encodedArrayItems, cd.staticValuesIdx, "encoded_array_item")
        if (cd.classDataIdx < 0) {
            if (staticValues.isNotEmpty()) throw IllegalStateException("static_values без class_data")
            return sb.toString()
        }
        val data = at(dex.classDataItems, cd.classDataIdx, "class_data_item")
        checkCounts(data)
        if (staticValues.size > data.staticFields.size) {
            throw IllegalStateException("static_values: ${staticValues.size} значений на ${data.staticFields.size} статических полей")
        }
        val flags = hiddenApi[idx]
        var member = 0
        fun hidden(): String = if (flags == null) "" else " hiddenapi=0x" + Integer.toHexString(flags[member++])

        val blocks = ArrayList<Pair<String, String>>()
        data.staticFields.forEachIndexed { i, f ->
            val ref = field(f.fieldIdx)
            val v = staticValues.getOrNull(i)?.let { value(it) } ?: defaultValue(ref.substringAfterLast(':'))
            blocks.add("1 $ref" to "  static-field $ref flags=0x${Integer.toHexString(f.accessFlags)}${hidden()} = $v\n")
        }
        data.instanceFields.forEach { f ->
            val ref = field(f.fieldIdx)
            blocks.add("2 $ref" to "  instance-field $ref flags=0x${Integer.toHexString(f.accessFlags)}${hidden()}\n")
        }
        data.directMethods.forEach { m -> blocks.add(methodBlock("3", "direct-method", m, hidden(), options)) }
        data.virtualMethods.forEach { m -> blocks.add(methodBlock("4", "virtual-method", m, hidden(), options)) }
        blocks.sortedBy { it.first }.forEach { sb.append(it.second) }
        return sb.toString()
    }

    private fun checkCounts(data: ClassDataItem) {
        if (data.staticFieldsSize != data.staticFields.size || data.instanceFieldsSize != data.instanceFields.size ||
            data.directMethodsSize != data.directMethods.size || data.virtualMethodsSize != data.virtualMethods.size
        ) throw IllegalStateException("class_data: размеры не совпадают со списками")
    }

    private fun defaultValue(type: String): String = when (type) {
        "Z" -> "boolean:false"
        "B" -> "byte:0"
        "S" -> "short:0"
        "C" -> "char:\\u0000"
        "I" -> "int:0"
        "J" -> "long:0"
        "F" -> "float:00000000"
        "D" -> "double:0000000000000000"
        else -> "null"
    }

    private fun methodBlock(order: String, kind: String, m: EncodedMethod, hidden: String, options: Options): Pair<String, String> {
        val ref = method(m.methodIdx)
        val sb = StringBuilder()
        sb.append("  ").append(kind).append(' ').append(ref).append(" flags=0x").append(Integer.toHexString(m.accessFlags)).append(hidden).append('\n')
        if (m.codeIdx >= 0) {
            try {
                appendCode(sb, at(dex.codeItems, m.codeIdx, "code_item"), options)
            } catch (e: RuntimeException) {
                throw IllegalStateException("метод $ref: ${e.message}", e)
            }
        }
        return "$order $ref" to sb.toString()
    }

    private fun appendCode(sb: StringBuilder, code: CodeItem, options: Options) {
        if (code.insns.size != code.insnsSize * 2) {
            throw IllegalStateException("insns_size=${code.insnsSize}, а байт кода ${code.insns.size}")
        }
        if (code.triesSize != code.tries.size) throw IllegalStateException("tries_size=${code.triesSize}, а блоков ${code.tries.size}")
        val decoded = decoder.decode(code.insns)
        val outs = when {
            options.exactOuts -> code.outsSize.toString()
            code.outsSize >= decoded.invokeRegs -> "ok"
            else -> "${code.outsSize} (меньше ${decoded.invokeRegs}, нужных invoke)"
        }
        sb.append("    code registers=").append(code.registersSize).append(" ins=").append(code.insSize)
            .append(" outs=").append(outs).append('\n')
        decoded.lines.forEach { sb.append("      ").append(it).append('\n') }
        code.tries.forEach { t ->
            val h = at(code.catchHandlers, t.handlerIdx, "catch_handler")
            sb.append("      try ").append(fmt("%04x..%04x", t.startAddr, t.startAddr + t.insnCount)).append(':')
            h.handlers.forEach { p -> sb.append(' ').append(type(p.typeIdx)).append(fmt(" -> %04x;", p.addr)) }
            if (h.catchAllAddr >= 0) sb.append(fmt(" catch-all -> %04x", h.catchAllAddr))
            sb.append('\n')
        }
        if (options.debugInfo && code.debugInfoIdx >= 0) {
            appendDebug(sb, at(dex.debugInfoItems, code.debugInfoIdx, "debug_info_item"), options)
        }
    }

    private fun appendDebug(sb: StringBuilder, di: DebugInfoItem, options: Options) {
        if (di.parametersSize != di.parameterNames.size) {
            throw IllegalStateException("debug_info: parameters_size=${di.parametersSize}, а имён ${di.parameterNames.size}")
        }
        val params = di.parameterNames.map { if (it < 0) "-" else string(it) }
        val events = ArrayList<Triple<Int, Int, String>>()
        var addr = 0
        var line = di.lineStart
        fun name(i: Int) = if (i < 0) "-" else string(i)
        fun typeOrNone(i: Int) = if (i < 0) "-" else type(i)
        loop@ for (op in di.opcodes) {
            when (op) {
                is DebugOpcode.EndSequence -> break@loop
                is DebugOpcode.AdvancePc -> addr += op.addressDelta
                is DebugOpcode.AdvanceLine -> line += op.lineDelta
                is DebugOpcode.StartLocal -> events.add(Triple(addr, 3, "local v${op.registerNum} ${name(op.nameIdx)}:${typeOrNone(op.typeIdx)}"))
                is DebugOpcode.StartLocalExtended -> events.add(
                    Triple(addr, 3, "local v${op.registerNum} ${name(op.nameIdx)}:${typeOrNone(op.typeIdx)} sig=${name(op.sigIdx)}")
                )
                is DebugOpcode.EndLocal -> events.add(Triple(addr, 3, "end-local v${op.registerNum}"))
                is DebugOpcode.RestartLocal -> events.add(Triple(addr, 3, "restart-local v${op.registerNum}"))
                is DebugOpcode.SetPrologueEnd -> events.add(Triple(addr, 0, "prologue"))
                is DebugOpcode.SetEpilogueBegin -> events.add(Triple(addr, 0, if (options.mergePrologueEpilogue) "prologue" else "epilogue"))
                is DebugOpcode.SetFile -> events.add(Triple(addr, 1, "source ${name(op.nameIdx)}"))
                is DebugOpcode.SpecialOpcode -> {
                    line += op.lineDelta
                    addr += op.addressDelta
                    events.add(Triple(addr, 2, "line $line"))
                }
            }
        }
        // Пустая отладочная информация без имён параметров эквивалентна её отсутствию
        if (events.isEmpty() && params.all { it == "-" }) return
        sb.append("      debug params=").append(params).append('\n')
        // Порядок событий на одном адресе нормализуется как в baksmali: пролог/эпилог, файл, строка, переменные
        events.sortedWith(compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second })
            .forEach { sb.append("        ").append(fmt("%04x ", it.first)).append(it.third).append('\n') }
    }

    // ===== Пулы =====

    /** Содержимое пулов (для сырого round-trip): имя пула -> отсортированные элементы. */
    fun pools(): Map<String, List<String>> = linkedMapOf(
        "strings" to dex.strings.map { quote(it) }.sorted(),
        "types" to dex.typeIds.indices.map { type(it) }.sorted(),
        "protos" to dex.protoIds.indices.map { proto(it) + " shorty=" + rawString(dex.protoIds[it].shortyIdx) }.sorted(),
        "fields" to dex.fieldIds.indices.map { field(it) }.sorted(),
        "methods" to dex.methodIds.indices.map { method(it) }.sorted(),
        "method_handles" to dex.methodHandles.indices.map { methodHandle(it) }.sorted(),
        // Номера call site видны в smali (call_site_N): порядок сохраняется
        "call_sites" to dex.callSiteIds.indices.map { "$it: ${callSite(it)}" }
    )

    // ===== hidden API =====

    private fun parseHiddenApi(): Array<IntArray?> {
        val result = arrayOfNulls<IntArray>(dex.classDefs.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val mapOff = b.getInt(52)
        if (mapOff <= 0 || mapOff + 4 > bytes.size) throw IllegalStateException("map_off 0x${Integer.toHexString(mapOff)} вне файла")
        val count = b.getInt(mapOff)
        var section = -1
        for (i in 0 until count) {
            val p = mapOff + 4 + i * 12
            if ((b.getShort(p).toInt() and 0xFFFF) == 0xF000) section = b.getInt(p + 8)
        }
        if (section < 0) return result
        dex.classDefs.forEachIndexed { ci, cd ->
            val off = b.getInt(section + 4 + ci * 4)
            if (off == 0) return@forEachIndexed
            val members = if (cd.classDataIdx < 0) 0 else dex.classDataItems[cd.classDataIdx].let {
                it.staticFields.size + it.instanceFields.size + it.directMethods.size + it.virtualMethods.size
            }
            var pos = section + off
            result[ci] = IntArray(members) {
                var v = 0
                var shift = 0
                while (true) {
                    val x = bytes[pos++].toInt() and 0xFF
                    v = v or ((x and 0x7F) shl shift)
                    if (x and 0x80 == 0) break
                    shift += 7
                    if (shift > 28) throw IllegalStateException("hiddenapi: слишком длинный uleb128 по смещению 0x${Integer.toHexString(pos)}")
                }
                v
            }
        }
        return result
    }

    // ===== Сравнение =====

    companion object {
        private val MH_KINDS = listOf(
            "static-put", "static-get", "instance-put", "instance-get",
            "invoke-static", "invoke-instance", "invoke-constructor", "invoke-direct", "invoke-interface"
        )

        fun of(bytes: ByteArray): DexSemantics = DexSemantics(bytes, DexReader().read(bytes))

        private fun fmt(format: String, vararg args: Any): String = String.format(Locale.ROOT, format, *args)

        /** Строка в кавычках; всё, кроме печатного ASCII, — в виде \uXXXX. */
        fun quote(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c.code in 0x20..0x7e -> sb.append(c)
                    else -> sb.append(fmt("\\u%04x", c.code))
                }
            }
            return sb.append('"').toString()
        }

        private fun hash(text: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            return d.joinToString("") { fmt("%02x", it.toInt() and 0xFF) }
        }

        /**
         * Сравнивает два DEX. Возвращает null, если они эквивалентны, иначе отчёт о расхождениях
         * (первые [maxClasses] различающихся классов с фрагментами дампов).
         */
        fun diff(expected: ByteArray, actual: ByteArray, options: Options = Options(), maxClasses: Int = 3): String? {
            val a = of(expected)
            val b = try {
                of(actual)
            } catch (e: RuntimeException) {
                return "результат не читается DexReader: $e"
            }
            val report = StringBuilder()
            if (options.version && a.version != b.version) report.append("версия DEX: ${a.version} -> ${b.version}\n")
            val missing = a.classIndex.keys - b.classIndex.keys
            val extra = b.classIndex.keys - a.classIndex.keys
            if (missing.isNotEmpty()) report.append("пропали классы (${missing.size}): ${missing.sorted().take(10)}\n")
            if (extra.isNotEmpty()) report.append("лишние классы (${extra.size}): ${extra.sorted().take(10)}\n")

            val differing = ArrayList<String>()
            for (d in a.classIndex.keys.sorted()) {
                if (d !in b.classIndex) continue
                val ha = hash(a.dumpClass(d, options))
                val hb = try {
                    hash(b.dumpClass(d, options))
                } catch (e: RuntimeException) {
                    differing.add(d)
                    report.append("класс $d в результате не разбирается: ${e.message}\n")
                    continue
                }
                if (ha != hb) differing.add(d)
            }
            var shown = 0
            for (d in differing) {
                val da = a.dumpClass(d, options)
                val db = try {
                    b.dumpClass(d, options)
                } catch (e: RuntimeException) {
                    continue
                }
                if (shown++ >= maxClasses) break
                report.append("класс $d отличается:\n").append(TextDiff.snippet(da, db))
            }
            if (differing.isNotEmpty()) report.insert(0, "различаются классы: ${differing.size} из ${a.classIndex.size}\n")

            if (options.pools) {
                val pa = a.pools()
                val pb = b.pools()
                for ((name, la) in pa) {
                    val lb = pb.getValue(name)
                    if (la == lb) continue
                    val onlyA = la - lb.toSet()
                    val onlyB = lb - la.toSet()
                    report.append("пул $name: ${la.size} -> ${lb.size}")
                    if (onlyA.isNotEmpty()) report.append("; пропали: ${onlyA.take(5)}")
                    if (onlyB.isNotEmpty()) report.append("; появились: ${onlyB.take(5)}")
                    if (onlyA.isEmpty() && onlyB.isEmpty()) report.append("; изменился порядок или кратность")
                    report.append('\n')
                }
            }
            return if (report.isEmpty()) null else report.toString()
        }
    }
}
