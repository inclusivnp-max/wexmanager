package com.tim.dasm.util

import com.tim.dasm.DexAsm
import com.tim.dasm.model.ClassInfo
import com.tim.dasm.model.FieldInfo
import com.tim.dasm.model.Ins21c
import com.tim.dasm.model.Ins22c
import com.tim.dasm.model.Ins31c
import com.tim.dasm.model.Ins35c
import com.tim.dasm.model.Ins3rc
import com.tim.dasm.model.Ins45cc
import com.tim.dasm.model.Ins4rcc
import com.tim.dasm.model.Instruction
import com.tim.dasm.model.MethodHandleKind
import com.tim.dasm.model.MethodInfo
import com.tim.dasm.model.ProtoKey
import com.tim.dex.data.EncodedValueType
import com.tim.dex.data.IndexPool
import com.tim.dex.data.Opcode
import com.tim.dex.model.Annotation
import com.tim.dex.model.AnnotationElement
import com.tim.dex.model.AnnotationItem
import com.tim.dex.model.CatchHandler
import com.tim.dex.model.DebugInfoItem
import com.tim.dex.model.DebugOpcode
import com.tim.dex.model.EncodedValue
import com.tim.dex.model.FieldIdItem
import com.tim.dex.model.MethodIdItem
import com.tim.dex.model.TypeAddrPair
import com.tim.dex.writer.CodeRemapper

/**
 * Перенос класса, собранного в одном [DexAsm] ([DexAsm.buildClass]), в другой: индексы пулов в коде,
 * отладочной информации, обработчиках исключений, аннотациях и начальных значениях полей
 * переводятся в пулы [to].
 *
 * Записи пулов [from] добавляются в [to] все и по порядку, поэтому состояние [to] после переноса
 * зависит только от содержимого класса, а не от того, где и когда он собирался: классы можно
 * собирать параллельно, каждый в своём DexAsm, и сливать в одном потоке детерминированно.
 * До переноса известно, сколько id каждого вида он добавит ([newStrings] и т. д.) — по этим
 * точным числам классы делятся на несколько DEX.
 *
 * Один экземпляр — на один исходный DexAsm; [transfer] вызывается один раз.
 */
class ClassTransfer(private val from: DexAsm, private val to: DexAsm) {

    /** DexAsm, в который переносится класс. */
    val target: DexAsm get() = to

    /** `const-string` после переноса не помещается в 16 бит: класс нужно собрать прямо в [to] (с /jumbo). */
    class StringIndexOverflow(message: String) : IllegalStateException(message)

    private val fs = from.state

    // Индекс в [to] для записи пула [from]; -1 — такой записи в [to] ещё нет.
    private val stringMap = IntArray(fs.strings.size)
    private val typeMap = IntArray(fs.typeIds.size)
    private val protoMap = IntArray(fs.protoIds.size)
    private val fieldMap = IntArray(fs.fieldIds.size)
    private val methodMap = IntArray(fs.methodIds.size)
    private val handleMap = IntArray(fs.methodHandles.size)
    private val callSiteMap = IntArray(fs.callSiteIds.size)

    /** Сколько записей каждого пула id перенос добавит в [to]. */
    val newStrings: Int
    val newTypes: Int
    val newProtos: Int
    val newFields: Int
    val newMethods: Int

    private var done = false

    init {
        val ts = to.state
        var n = 0
        for (i in stringMap.indices) {
            stringMap[i] = ts.stringIndex[fs.strings[i]] ?: -1.also { n++ }
        }
        newStrings = n

        n = 0
        for (i in typeMap.indices) {
            typeMap[i] = ts.typeIndex[typeDescriptor(i)] ?: -1.also { n++ }
        }
        newTypes = n

        n = 0
        for (i in protoMap.indices) {
            val key = fs.protoKeyOf(fs.protoIds[i])
            val ret = typeMap[key.returnTypeIdx]
            var found = -1
            if (ret >= 0) {
                val params = ArrayList<Int>(key.paramTypeIdxs.size)
                for (p in key.paramTypeIdxs) {
                    val t = typeMap[p]
                    if (t < 0) break
                    params.add(t)
                }
                if (params.size == key.paramTypeIdxs.size) found = ts.protoIndex[ProtoKey(ret, params)] ?: -1
            }
            protoMap[i] = found
            if (found < 0) n++
        }
        newProtos = n

        n = 0
        for (i in fieldMap.indices) {
            val f = fs.fieldIds[i]
            val c = typeMap[f.classIdx]
            val t = typeMap[f.typeIdx]
            val name = stringMap[f.nameIdx]
            fieldMap[i] = if (c >= 0 && t >= 0 && name >= 0) ts.fieldIndex[FieldIdItem(c, t, name)] ?: -1 else -1
            if (fieldMap[i] < 0) n++
        }
        newFields = n

        n = 0
        for (i in methodMap.indices) {
            val m = fs.methodIds[i]
            val c = typeMap[m.classIdx]
            val p = protoMap[m.protoIdx]
            val name = stringMap[m.nameIdx]
            methodMap[i] = if (c >= 0 && p >= 0 && name >= 0) ts.methodIndex[MethodIdItem(c, p, name)] ?: -1 else -1
            if (methodMap[i] < 0) n++
        }
        newMethods = n
    }

    /** После переноса ни в одном пуле id не станет больше [limit] записей. */
    fun fits(limit: Int): Boolean {
        val ts = to.state
        return ts.strings.size + newStrings <= limit && ts.typeIds.size + newTypes <= limit &&
            ts.protoIds.size + newProtos <= limit && ts.fieldIds.size + newFields <= limit &&
            ts.methodIds.size + newMethods <= limit
    }

    /** [info] (собранный в [from]) с индексами пулов [to]; записи пулов [from] добавляются в [to]. */
    fun transfer(info: ClassInfo): ClassInfo {
        check(!done) { "ClassTransfer: перенос уже выполнен" }
        done = true
        addPools()
        return info.copy(
            annotations = info.annotations.map { annotationItem(it) },
            fields = info.fields.map { field(it) },
            methods = info.methods.map { method(it) }
        )
    }

    // ---- Пулы ----

    private fun addPools() {
        for (i in stringMap.indices) if (stringMap[i] < 0) stringMap[i] = to.strings.add(fs.strings[i])
        for (i in typeMap.indices) if (typeMap[i] < 0) typeMap[i] = to.types.add(typeDescriptor(i))
        for (i in protoMap.indices) {
            if (protoMap[i] < 0) protoMap[i] = to.protos.add(returnType(i), paramTypes(i))
        }
        for (i in fieldMap.indices) {
            if (fieldMap[i] >= 0) continue
            val f = fs.fieldIds[i]
            fieldMap[i] = to.fields.add(typeDescriptor(f.classIdx), fs.strings[f.nameIdx], typeDescriptor(f.typeIdx))
        }
        for (i in methodMap.indices) {
            if (methodMap[i] >= 0) continue
            val m = fs.methodIds[i]
            methodMap[i] = to.methods.add(typeDescriptor(m.classIdx), fs.strings[m.nameIdx], paramTypes(m.protoIdx), returnType(m.protoIdx))
        }
        for (i in handleMap.indices) {
            val h = fs.methodHandles[i]
            val member = if (MethodHandleKind.isField(h.methodHandleType)) fieldMap[h.fieldOrMethodId] else methodMap[h.fieldOrMethodId]
            handleMap[i] = to.methodHandles.add(h.methodHandleType, member)
        }
        for (i in callSiteMap.indices) {
            val cs = from.callSites.get(i) ?: throw IllegalStateException("нет call site #$i")
            val args = cs.arguments
            require(args.size >= 2 && args[0].type == EncodedValueType.VALUE_STRING && args[1].type == EncodedValueType.VALUE_METHOD_TYPE) {
                "call site #$i: ожидались имя и method type"
            }
            val proto = index(args[1])
            callSiteMap[i] = to.callSites.add(
                handleMap[index(cs.methodHandle)], fs.strings[index(args[0])],
                returnType(proto), paramTypes(proto), args.subList(2, args.size).map { value(it) }
            )
        }
        to.state.requireDexVersion(fs.requiredDexVersion)
    }

    private fun typeDescriptor(type: Int): String = fs.strings[fs.typeIds[type].descriptorIdx]

    private fun returnType(proto: Int): String = typeDescriptor(fs.protoIds[proto].returnTypeIdx)

    private fun paramTypes(proto: Int): List<String> = fs.protoKeyOf(fs.protoIds[proto]).paramTypeIdxs.map { typeDescriptor(it) }

    // ---- Содержимое класса ----

    private fun field(f: FieldInfo): FieldInfo =
        f.copy(annotations = f.annotations.map { annotationItem(it) }, initialValue = f.initialValue?.let { value(it) })

    private fun method(m: MethodInfo): MethodInfo {
        val debugInfoIdx = if (m.debugInfoIdx < 0) -1 else to.debugInfo.add(debugInfo(fs.debugInfoItems[m.debugInfoIdx]))
        val code = m.code
        return m.copy(
            instructions = if (code != null) m.instructions else m.instructions.map { instruction(it, m) },
            annotations = m.annotations.map { annotationItem(it) },
            debugInfoIdx = debugInfoIdx,
            catchHandlers = catchHandlers(m.catchHandlers),
            parameterAnnotations = m.parameterAnnotations.mapValues { (_, items) -> items.map { annotationItem(it) } },
            code = code?.let {
                val codeDebug = if (it.debugInfoIdx == m.debugInfoIdx) debugInfoIdx
                else if (it.debugInfoIdx < 0) -1 else to.debugInfo.add(debugInfo(fs.debugInfoItems[it.debugInfoIdx]))
                it.copy(
                    insns = codeRemapper(m).remap(it.insns, 0),
                    debugInfoIdx = codeDebug,
                    catchHandlers = catchHandlers(it.catchHandlers)
                )
            }
        )
    }

    private fun catchHandlers(list: List<CatchHandler>): List<CatchHandler> = list.map { h ->
        CatchHandler(h.handlers.map { TypeAddrPair(typeMap[it.typeIdx], it.addr) }, h.catchAllAddr)
    }

    /** Перенумерация индексов прямо в байткоде собранного code_item. */
    private fun codeRemapper(m: MethodInfo): CodeRemapper = CodeRemapper(
        stringMap, typeMap, fieldMap, methodMap, protoMap, callSiteMap.size, handleMap.size,
        describe = { "метода ${m.name}" }, callSiteMap = callSiteMap, methodHandleMap = handleMap,
        stringOverflow = { _, pc ->
            throw StringIndexOverflow("${m.name}: const-string по смещению $pc, индекс строки не помещается в 16 бит")
        }
    )

    private fun instruction(insn: Instruction, m: MethodInfo): Instruction = when (insn) {
        is Ins21c -> {
            val index = index(insn.opcode, insn.index)
            if (insn.opcode == Opcode.CONST_STRING && index > 0xFFFF) {
                throw StringIndexOverflow("${m.name}: const-string, индекс строки $index не помещается в 16 бит")
            }
            insn.copy(index = index)
        }
        is Ins22c -> insn.copy(index = index(insn.opcode, insn.index))
        is Ins31c -> insn.copy(index = index(insn.opcode, insn.index))
        is Ins35c -> insn.copy(index = index(insn.opcode, insn.index))
        is Ins3rc -> insn.copy(index = index(insn.opcode, insn.index))
        is Ins45cc -> insn.copy(methodIdx = methodMap[insn.methodIdx], protoIdx = protoMap[insn.protoIdx])
        is Ins4rcc -> insn.copy(methodIdx = methodMap[insn.methodIdx], protoIdx = protoMap[insn.protoIdx])
        else -> insn
    }

    private fun index(op: Opcode, index: Int): Int = when (op.indexPool) {
        IndexPool.NONE -> index
        IndexPool.STRING -> stringMap[index]
        IndexPool.TYPE -> typeMap[index]
        IndexPool.FIELD -> fieldMap[index]
        IndexPool.METHOD -> methodMap[index]
        IndexPool.PROTO -> protoMap[index]
        IndexPool.CALL_SITE -> callSiteMap[index]
        IndexPool.METHOD_HANDLE -> handleMap[index]
    }

    private fun debugInfo(d: DebugInfoItem): DebugInfoItem = d.copy(
        parameterNames = d.parameterNames.map { string(it) },
        opcodes = d.opcodes.map { op ->
            when (op) {
                is DebugOpcode.StartLocal -> op.copy(nameIdx = string(op.nameIdx), typeIdx = type(op.typeIdx))
                is DebugOpcode.StartLocalExtended ->
                    op.copy(nameIdx = string(op.nameIdx), typeIdx = type(op.typeIdx), sigIdx = string(op.sigIdx))
                is DebugOpcode.SetFile -> op.copy(nameIdx = string(op.nameIdx))
                else -> op
            }
        }
    )

    /** `-1` (нет) остаётся `-1`. */
    private fun string(index: Int): Int = if (index < 0) index else stringMap[index]

    private fun type(index: Int): Int = if (index < 0) index else typeMap[index]

    private fun annotationItem(item: AnnotationItem): AnnotationItem = AnnotationItem(item.visibility, annotation(item.annotation))

    private fun annotation(a: Annotation): Annotation =
        Annotation(typeMap[a.typeIdx], a.elements.map { AnnotationElement(stringMap[it.nameIdx], value(it.value)) })

    private fun value(v: EncodedValue): EncodedValue = when (v.type) {
        EncodedValueType.VALUE_STRING -> EncodedValue(v.type, stringMap[index(v)])
        EncodedValueType.VALUE_TYPE -> EncodedValue(v.type, typeMap[index(v)])
        EncodedValueType.VALUE_FIELD, EncodedValueType.VALUE_ENUM -> EncodedValue(v.type, fieldMap[index(v)])
        EncodedValueType.VALUE_METHOD -> EncodedValue(v.type, methodMap[index(v)])
        EncodedValueType.VALUE_METHOD_TYPE -> EncodedValue(v.type, protoMap[index(v)])
        EncodedValueType.VALUE_METHOD_HANDLE -> EncodedValue(v.type, handleMap[index(v)])
        EncodedValueType.VALUE_ARRAY -> EncodedValue(v.type, (v.value as List<*>).map { value(it as EncodedValue) })
        EncodedValueType.VALUE_ANNOTATION -> EncodedValue(v.type, annotation(v.value as Annotation))
        else -> v
    }

    private fun index(v: EncodedValue): Int = (v.value as Number).toInt()
}
