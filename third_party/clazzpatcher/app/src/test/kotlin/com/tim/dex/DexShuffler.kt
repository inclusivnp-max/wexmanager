package com.tim.dex

import com.tim.dex.data.EncodedValueType
import com.tim.dex.model.*
import com.tim.dex.model.Annotation
import com.tim.dex.writer.CodeRemapper
import kotlin.random.Random

/**
 * Строит семантически эквивалентную модель с «грязными» пулами, как у asm-слоя:
 * пулы идентификаторов перемешаны и содержат дубликаты, списки элементов данных
 * перемешаны и дополнены недостижимым мусором, члены class_data, элементы аннотаций,
 * наборов и directory, блоки try — не по порядку (static_values и hidden API
 * переставлены согласованно). Порядок class_defs, call_site_id и method handle сохраняется.
 *
 * DexWriter обязан записать такую модель побайтно так же, как исходную.
 */
class DexShuffler(private val dex: DexFile, seed: Long) {
    private val rnd = Random(seed)

    /** Пул: новый список + копии (новые индексы) каждого старого элемента. */
    private class Pool(val order: IntArray, val copies: Array<IntArray>) {
        /** Первая копия — для байткода (фиксированный ремап). */
        val first = IntArray(copies.size) { copies[it][0] }
    }

    private fun pool(size: Int, bytecodeLimit: Boolean): Pool {
        val dupCount = size / 8
        // Пулы, на которые ссылается 16-битный байткод, при переполнении не перемешиваются:
        // дубликаты только в конце, байткод ссылается на оригиналы.
        val keepOrder = bytecodeLimit && size + dupCount > 0x10000
        val items = IntArray(size + dupCount)
        for (i in 0 until size) items[i] = i
        for (k in 0 until dupCount) items[size + k] = rnd.nextInt(size)
        if (!keepOrder) {
            for (i in items.size - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val t = items[i]; items[i] = items[j]; items[j] = t
            }
        }
        val lists = Array(size) { ArrayList<Int>(2) }
        for (pos in items.indices) lists[items[pos]].add(pos)
        if (keepOrder) for (i in 0 until size) lists[i].sort()
        return Pool(items, Array(size) { lists[it].toIntArray() })
    }

    private fun Pool.pick(old: Int): Int = if (old == -1) -1 else copies[old][rnd.nextInt(copies[old].size)]

    /** Перестановка списка элементов данных: order[new] = old, map[old] = new. */
    private class Perm(val order: IntArray) {
        val map = IntArray(order.size).also { m -> for (i in order.indices) m[order[i]] = i }
    }

    private fun perm(size: Int): Perm {
        val order = IntArray(size) { it }
        for (i in size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = order[i]; order[i] = order[j]; order[j] = t
        }
        return Perm(order)
    }

    private fun Perm.at(old: Int): Int = if (old == -1) -1 else map[old]

    private val strings = pool(dex.strings.size, true)
    private val types = pool(dex.typeIds.size, true)
    private val protos = pool(dex.protoIds.size, true)
    private val fields = pool(dex.fieldIds.size, true)
    private val methods = pool(dex.methodIds.size, true)

    private val typeListPerm = perm(dex.typeLists.size)
    private val classDataPerm = perm(dex.classDataItems.size)
    private val codePerm = perm(dex.codeItems.size)
    private val debugPerm = perm(dex.debugInfoItems.size)
    private val itemPerm = perm(dex.annotationItems.size)
    private val setPerm = perm(dex.annotationSetItems.size)
    private val refPerm = perm(dex.annotationSetRefLists.size)
    private val dirPerm = perm(dex.annotationsDirectoryItems.size)
    private val arrayPerm = perm(dex.encodedArrayItems.size)
    private val callSitePerm = perm(dex.callSiteItems.size)

    fun shuffle(): DexFile {
        val newStrings = strings.order.map { dex.strings[it] }
        val newTypes = types.order.map { TypeIdItem(strings.pick(dex.typeIds[it].descriptorIdx)) }
        val newProtos = protos.order.map {
            val p = dex.protoIds[it]
            ProtoIdItem(strings.pick(p.shortyIdx), types.pick(p.returnTypeIdx), typeListPerm.at(p.parametersTypeListIdx))
        }
        val newFields = fields.order.map {
            val f = dex.fieldIds[it]
            FieldIdItem(types.pick(f.classIdx), types.pick(f.typeIdx), strings.pick(f.nameIdx))
        }
        val newMethods = methods.order.map {
            val m = dex.methodIds[it]
            MethodIdItem(types.pick(m.classIdx), protos.pick(m.protoIdx), strings.pick(m.nameIdx))
        }
        val newTypeLists = typeListPerm.order.map { old ->
            val tl = dex.typeLists[old]
            TypeListItem(tl.size, tl.typeIdx.map { types.pick(it) })
        } + TypeListItem(1, listOf(Int.MAX_VALUE)) // недостижимый мусор

        // class_data, static_values и hidden API переставляются согласованно по классам.
        // Общий для нескольких классов массив static_values получает копию на каждого следующего владельца.
        val newClassData = arrayOfNulls<ClassDataItem>(dex.classDataItems.size)
        val newArrays = arrayOfNulls<List<EncodedValue>>(dex.encodedArrayItems.size)
        val extraArrays = ArrayList<List<EncodedValue>>()
        val staticValuesIdx = IntArray(dex.classDefs.size) { -1 }
        val hidden = dex.hiddenApiClassDataItems.singleOrNull()?.flags?.toMutableList()
        fun putArray(classDef: Int, old: Int, values: List<EncodedValue>) {
            if (newArrays[old] == null) {
                newArrays[old] = values
                staticValuesIdx[classDef] = arrayPerm.map[old]
            } else {
                extraArrays.add(values)
                staticValuesIdx[classDef] = dex.encodedArrayItems.size + extraArrays.size - 1
            }
        }
        for ((c, def) in dex.classDefs.withIndex()) {
            if (def.classDataIdx == -1) {
                if (def.staticValuesIdx != -1) putArray(c, def.staticValuesIdx, dex.encodedArrayItems[def.staticValuesIdx].map(::value))
                continue
            }
            val data = dex.classDataItems[def.classDataIdx]
            val values = if (def.staticValuesIdx == -1) null else dex.encodedArrayItems[def.staticValuesIdx]
            // статические поля: перестановка отдельно внутри префикса со значениями и хвоста
            val prefix = values?.size ?: 0
            val sPerm = concat(perm(prefix).order, perm(data.staticFields.size - prefix).order.map { it + prefix }.toIntArray())
            val iPerm = perm(data.instanceFields.size).order
            val dPerm = perm(data.directMethods.size).order
            val vPerm = perm(data.virtualMethods.size).order
            newClassData[def.classDataIdx] = ClassDataItem(
                data.staticFieldsSize, data.instanceFieldsSize, data.directMethodsSize, data.virtualMethodsSize,
                sPerm.map { EncodedField(fields.pick(data.staticFields[it].fieldIdx), data.staticFields[it].accessFlags) },
                iPerm.map { EncodedField(fields.pick(data.instanceFields[it].fieldIdx), data.instanceFields[it].accessFlags) },
                dPerm.map { method(data.directMethods[it]) },
                vPerm.map { method(data.virtualMethods[it]) }
            )
            if (values != null) putArray(c, def.staticValuesIdx, List(prefix) { value(values[sPerm[it]]) })
            val flags = hidden?.get(c)
            if (flags != null) {
                val s = data.staticFields.size
                val i = data.instanceFields.size
                val d = data.directMethods.size
                hidden[c] = concat(
                    IntArray(s) { flags[sPerm[it]] },
                    IntArray(i) { flags[s + iPerm[it]] },
                    IntArray(d) { flags[s + i + dPerm[it]] },
                    IntArray(data.virtualMethods.size) { flags[s + i + d + vPerm[it]] }
                )
            }
        }
        for (a in newArrays.indices) if (newArrays[a] == null) newArrays[a] = dex.encodedArrayItems[a].map(::value)

        val remapper = CodeRemapper(
            strings.first, types.first, fields.first, methods.first, protos.first,
            dex.callSiteIds.size, dex.methodHandles.size
        ) { "code #$it" }
        val newCodes = codePerm.order.map { old ->
            val code = dex.codeItems[old]
            code.copy(
                debugInfoIdx = debugPerm.at(code.debugInfoIdx),
                insns = remapper.remap(code.insns, old),
                tries = code.tries.reversed(),
                catchHandlers = code.catchHandlers.map { h -> CatchHandler(h.handlers.map { TypeAddrPair(types.pick(it.typeIdx), it.addr) }, h.catchAllAddr) }
            )
        } + CodeItem(1, 0, 0, 0, -1, 1, byteArrayOf(0x73, 0), emptyList(), emptyList()) // недостижимый мусор

        val newDebug = debugPerm.order.map { old ->
            val d = dex.debugInfoItems[old]
            DebugInfoItem(d.lineStart, d.parametersSize, d.parameterNames.map { strings.pick(it) }, d.opcodes.map { op ->
                when (op) {
                    is DebugOpcode.StartLocal -> DebugOpcode.StartLocal(op.registerNum, strings.pick(op.nameIdx), types.pick(op.typeIdx))
                    is DebugOpcode.StartLocalExtended -> DebugOpcode.StartLocalExtended(
                        op.registerNum, strings.pick(op.nameIdx), types.pick(op.typeIdx), strings.pick(op.sigIdx)
                    )
                    is DebugOpcode.SetFile -> DebugOpcode.SetFile(strings.pick(op.nameIdx))
                    else -> op
                }
            })
        }
        val newItems = itemPerm.order.map { old ->
            val item = dex.annotationItems[old]
            AnnotationItem(item.visibility, annotation(item.annotation))
        } + AnnotationItem(1, Annotation(Int.MAX_VALUE, emptyList())) // недостижимый мусор
        val newSets = setPerm.order.map { old ->
            val entries = dex.annotationSetItems[old].annotationIdxs.map { itemPerm.at(it) }.reversed().toMutableList()
            entries.add(rnd.nextInt(entries.size + 1), -1)
            AnnotationSetItem(entries)
        }
        val newRefs = refPerm.order.map { old -> AnnotationSetRefList(dex.annotationSetRefLists[old].annotationSetIdxs.map { setPerm.at(it) }) }
        val newDirs = dirPerm.order.map { old ->
            val dir = dex.annotationsDirectoryItems[old]
            AnnotationsDirectoryItem(
                setPerm.at(dir.classAnnotationsSetIdx),
                dir.fieldAnnotations.map { FieldAnnotation(fields.pick(it.fieldIdx), setPerm.at(it.annotationsSetIdx)) }.reversed() +
                    FieldAnnotation(Int.MAX_VALUE, -1),
                dir.methodAnnotations.map { MethodAnnotation(methods.pick(it.methodIdx), setPerm.at(it.annotationsSetIdx)) }.reversed(),
                dir.parameterAnnotations.map { ParameterAnnotation(methods.pick(it.methodIdx), refPerm.at(it.annotationsSetRefListIdx)) }.reversed()
            )
        }
        val newCallSites = callSitePerm.order.map { old ->
            val cs = dex.callSiteItems[old]
            CallSite(value(cs.methodHandle), cs.arguments.map(::value))
        }
        val newClassDefs = dex.classDefs.mapIndexed { c, def ->
            ClassDefItem(
                types.pick(def.classIdx), def.accessFlags, types.pick(def.superclassIdx), typeListPerm.at(def.interfacesTypeListIdx),
                strings.pick(def.sourceFileIdx), dirPerm.at(def.annotationsDirectoryIdx), classDataPerm.at(def.classDataIdx),
                staticValuesIdx[c]
            )
        }
        val newMethodHandles = dex.methodHandles.map { mh ->
            val member = if (mh.methodHandleType <= 3) fields.pick(mh.fieldOrMethodId) else methods.pick(mh.fieldOrMethodId)
            MethodHandleItem(mh.methodHandleType, mh.unused1, member, mh.unused2)
        }
        return DexFile(
            header = dex.header,
            strings = newStrings,
            typeIds = newTypes,
            protoIds = newProtos,
            fieldIds = newFields,
            methodIds = newMethods,
            classDefs = newClassDefs,
            callSiteIds = dex.callSiteIds.map { CallSiteIdItem(callSitePerm.at(it.callSiteIdx)) },
            methodHandles = newMethodHandles,
            mapList = emptyList(),
            typeLists = newTypeLists,
            classDataItems = classDataPerm.order.map { newClassData[it] ?: error("class_data #$it без владельца") },
            codeItems = newCodes,
            debugInfoItems = newDebug,
            annotationItems = newItems,
            annotationSetItems = newSets,
            annotationSetRefLists = newRefs,
            annotationsDirectoryItems = newDirs,
            encodedArrayItems = arrayPerm.order.map { newArrays[it]!! } + extraArrays,
            callSiteItems = newCallSites,
            hiddenApiClassDataItems = if (hidden == null) emptyList() else listOf(HiddenApiClassData(hidden))
        )
    }

    private fun method(m: EncodedMethod) = EncodedMethod(methods.pick(m.methodIdx), m.accessFlags, codePerm.at(m.codeIdx))

    private fun annotation(a: Annotation): Annotation =
        Annotation(types.pick(a.typeIdx), a.elements.map { AnnotationElement(strings.pick(it.nameIdx), value(it.value)) }.reversed())

    private fun value(v: EncodedValue): EncodedValue = when (v.type) {
        EncodedValueType.VALUE_STRING -> EncodedValue(v.type, strings.pick(v.value as Int))
        EncodedValueType.VALUE_TYPE -> EncodedValue(v.type, types.pick(v.value as Int))
        EncodedValueType.VALUE_FIELD, EncodedValueType.VALUE_ENUM -> EncodedValue(v.type, fields.pick(v.value as Int))
        EncodedValueType.VALUE_METHOD -> EncodedValue(v.type, methods.pick(v.value as Int))
        EncodedValueType.VALUE_METHOD_TYPE -> EncodedValue(v.type, protos.pick(v.value as Int))
        EncodedValueType.VALUE_ARRAY -> EncodedValue(v.type, (v.value as List<*>).map { value(it as EncodedValue) })
        EncodedValueType.VALUE_ANNOTATION -> EncodedValue(v.type, annotation(v.value as Annotation))
        else -> v
    }

    private fun concat(vararg parts: IntArray): IntArray {
        val out = IntArray(parts.sumOf { it.size })
        var p = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, p, part.size)
            p += part.size
        }
        return out
    }
}
