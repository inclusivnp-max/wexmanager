package com.tim.dex.writer

import com.tim.dex.data.EncodedValueType
import com.tim.dex.data.IndexPool
import com.tim.dex.data.Opcode
import com.tim.dex.model.Annotation
import com.tim.dex.model.DebugOpcode
import com.tim.dex.model.DexFile
import com.tim.dex.model.EncodedValue

/**
 * Записи пулов id, на которые ссылается записываемое содержимое: классы (и всё достижимое из них),
 * а также все call site и method handle (их [DexWriter] пишет всегда). Остальные — например, строки
 * и методы класса, заменённого другим телом, — при сжатой записи в файл не попадают.
 *
 * Отмечается то же, что писатель затем перенумеровывает; если разметка что-то пропустила, писатель
 * не запишет висячий индекс, а упадёт на нём ([IdPools] проверяет каждую ссылку).
 */
internal class LiveIds(private val dex: DexFile) {
    val strings = BooleanArray(dex.strings.size)
    val types = BooleanArray(dex.typeIds.size)
    val protos = BooleanArray(dex.protoIds.size)
    val fields = BooleanArray(dex.fieldIds.size)
    val methods = BooleanArray(dex.methodIds.size)

    private val seenCode = BooleanArray(dex.codeItems.size)
    private val seenDebug = BooleanArray(dex.debugInfoItems.size)
    private val seenDirectory = BooleanArray(dex.annotationsDirectoryItems.size)
    private val seenSet = BooleanArray(dex.annotationSetItems.size)
    private val seenRefList = BooleanArray(dex.annotationSetRefLists.size)
    private val seenItem = BooleanArray(dex.annotationItems.size)
    private val seenArray = BooleanArray(dex.encodedArrayItems.size)

    init {
        for (cd in dex.classDefs) {
            type(cd.classIdx)
            if (cd.superclassIdx != -1) type(cd.superclassIdx)
            typeList(cd.interfacesTypeListIdx)
            if (cd.sourceFileIdx != -1) string(cd.sourceFileIdx)
            if (cd.annotationsDirectoryIdx != -1) directory(cd.annotationsDirectoryIdx)
            if (cd.staticValuesIdx != -1) array(cd.staticValuesIdx)
            if (cd.classDataIdx != -1) {
                val data = dex.classDataItems.getOrNull(cd.classDataIdx) ?: continue
                for (f in data.staticFields) field(f.fieldIdx)
                for (f in data.instanceFields) field(f.fieldIdx)
                for (m in data.directMethods) { method(m.methodIdx); code(m.codeIdx) }
                for (m in data.virtualMethods) { method(m.methodIdx); code(m.codeIdx) }
            }
        }
        for (cs in dex.callSiteIds) {
            dex.callSiteItems.getOrNull(cs.callSiteIdx)?.let { item ->
                value(item.methodHandle)
                for (a in item.arguments) value(a)
            }
        }
        for (mh in dex.methodHandles) {
            // Типы 0..3 — поля (static-put … instance-get), остальные — методы
            if (mh.methodHandleType <= 3) field(mh.fieldOrMethodId) else method(mh.fieldOrMethodId)
        }
    }

    // ---- id: отметка тянет за собой то, на что ссылается сама запись ----

    private fun string(i: Int) {
        if (i in strings.indices) strings[i] = true
    }

    private fun type(i: Int) {
        if (i !in types.indices || types[i]) return
        types[i] = true
        string(dex.typeIds[i].descriptorIdx)
    }

    private fun typeList(i: Int) {
        if (i == -1) return
        dex.typeLists.getOrNull(i)?.typeIdx?.forEach { type(it) }
    }

    private fun proto(i: Int) {
        if (i !in protos.indices || protos[i]) return
        protos[i] = true
        val p = dex.protoIds[i]
        string(p.shortyIdx)
        type(p.returnTypeIdx)
        typeList(p.parametersTypeListIdx)
    }

    private fun field(i: Int) {
        if (i !in fields.indices || fields[i]) return
        fields[i] = true
        val f = dex.fieldIds[i]
        type(f.classIdx)
        type(f.typeIdx)
        string(f.nameIdx)
    }

    private fun method(i: Int) {
        if (i !in methods.indices || methods[i]) return
        methods[i] = true
        val m = dex.methodIds[i]
        type(m.classIdx)
        proto(m.protoIdx)
        string(m.nameIdx)
    }

    // ---- данные ----

    private fun code(i: Int) {
        if (i !in seenCode.indices || seenCode[i]) return
        seenCode[i] = true
        val c = dex.codeItems[i]
        if (c.debugInfoIdx != -1) debug(c.debugInfoIdx)
        for (h in c.catchHandlers) for (p in h.handlers) type(p.typeIdx)
        insns(c.insns)
    }

    /** Ссылки байткода — линейный проход, как у [CodeRemapper]; некорректный код разбирает (и отвергает) писатель. */
    private fun insns(code: ByteArray) {
        val units = code.size ushr 1
        var pc = 0
        while (pc < units) {
            val p = pc shl 1
            val unit = u2(code, p)
            val op = unit and 0xFF
            if (op == 0 && unit != 0) {
                pc += payloadUnits(code, pc, units, unit) ?: return
                continue
            }
            val opcode = Opcode.of(op) ?: return
            if (pc + opcode.sizeUnits > units) return
            if (opcode.indexPool != IndexPool.NONE) {
                val idx = if (opcode == Opcode.CONST_STRING_JUMBO) u2(code, p + 2) or (u2(code, p + 4) shl 16) else u2(code, p + 2)
                when (opcode.indexPool) {
                    IndexPool.STRING -> string(idx)
                    IndexPool.TYPE -> type(idx)
                    IndexPool.FIELD -> field(idx)
                    IndexPool.METHOD -> {
                        method(idx)
                        if (opcode == Opcode.INVOKE_POLYMORPHIC || opcode == Opcode.INVOKE_POLYMORPHIC_RANGE) proto(u2(code, p + 6))
                    }
                    IndexPool.PROTO -> proto(idx)
                    // call site и method handle — корни разметки (пишутся все)
                    IndexPool.CALL_SITE, IndexPool.METHOD_HANDLE, IndexPool.NONE -> {}
                }
            }
            pc += opcode.sizeUnits
        }
    }

    private fun payloadUnits(code: ByteArray, pc: Int, units: Int, unit: Int): Int? {
        val p = pc shl 1
        val size = when (unit) {
            0x0100 -> if (pc + 2 > units) return null else 4L + 2L * u2(code, p + 2)
            0x0200 -> if (pc + 2 > units) return null else 2L + 4L * u2(code, p + 2)
            0x0300 -> {
                if (pc + 4 > units) return null
                val width = u2(code, p + 2).toLong()
                val count = (u2(code, p + 4).toLong() or (u2(code, p + 6).toLong() shl 16)) and 0xFFFFFFFFL
                4L + (width * count + 1L) / 2L
            }
            else -> return null
        }
        return if (pc + size > units) null else size.toInt()
    }

    private fun debug(i: Int) {
        if (i !in seenDebug.indices || seenDebug[i]) return
        seenDebug[i] = true
        val d = dex.debugInfoItems[i]
        for (n in d.parameterNames) if (n != -1) string(n)
        for (op in d.opcodes) {
            when (op) {
                is DebugOpcode.StartLocal -> {
                    if (op.nameIdx != -1) string(op.nameIdx)
                    if (op.typeIdx != -1) type(op.typeIdx)
                }
                is DebugOpcode.StartLocalExtended -> {
                    if (op.nameIdx != -1) string(op.nameIdx)
                    if (op.typeIdx != -1) type(op.typeIdx)
                    if (op.sigIdx != -1) string(op.sigIdx)
                }
                is DebugOpcode.SetFile -> if (op.nameIdx != -1) string(op.nameIdx)
                else -> {}
            }
        }
    }

    private fun directory(i: Int) {
        if (i !in seenDirectory.indices || seenDirectory[i]) return
        seenDirectory[i] = true
        val d = dex.annotationsDirectoryItems[i]
        if (d.classAnnotationsSetIdx != -1) set(d.classAnnotationsSetIdx)
        for (f in d.fieldAnnotations) { field(f.fieldIdx); set(f.annotationsSetIdx) }
        for (m in d.methodAnnotations) { method(m.methodIdx); set(m.annotationsSetIdx) }
        for (p in d.parameterAnnotations) {
            method(p.methodIdx)
            if (p.annotationsSetRefListIdx !in seenRefList.indices || seenRefList[p.annotationsSetRefListIdx]) continue
            seenRefList[p.annotationsSetRefListIdx] = true
            for (s in dex.annotationSetRefLists[p.annotationsSetRefListIdx].annotationSetIdxs) set(s)
        }
    }

    private fun set(i: Int) {
        if (i !in seenSet.indices || seenSet[i]) return
        seenSet[i] = true
        for (a in dex.annotationSetItems[i].annotationIdxs) {
            if (a !in seenItem.indices || seenItem[a]) continue
            seenItem[a] = true
            annotation(dex.annotationItems[a].annotation)
        }
    }

    private fun annotation(a: Annotation) {
        type(a.typeIdx)
        for (e in a.elements) {
            string(e.nameIdx)
            value(e.value)
        }
    }

    private fun array(i: Int) {
        if (i !in seenArray.indices || seenArray[i]) return
        seenArray[i] = true
        for (v in dex.encodedArrayItems[i]) value(v)
    }

    private fun value(v: EncodedValue) {
        val idx = (v.value as? Number)?.toInt()
        when (v.type) {
            EncodedValueType.VALUE_STRING -> idx?.let { string(it) }
            EncodedValueType.VALUE_TYPE -> idx?.let { type(it) }
            EncodedValueType.VALUE_FIELD, EncodedValueType.VALUE_ENUM -> idx?.let { field(it) }
            EncodedValueType.VALUE_METHOD -> idx?.let { method(it) }
            EncodedValueType.VALUE_METHOD_TYPE -> idx?.let { proto(it) }
            EncodedValueType.VALUE_ARRAY -> (v.value as? List<*>)?.forEach { (it as? EncodedValue)?.let { e -> value(e) } }
            EncodedValueType.VALUE_ANNOTATION -> (v.value as? Annotation)?.let { annotation(it) }
            else -> {} // числа, null, boolean; method handle — корень
        }
    }

    private fun u2(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
}
