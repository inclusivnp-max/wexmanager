package com.tim.dex.writer

import com.tim.dex.model.DexFile
import com.tim.dex.util.IntComparator
import com.tim.dex.util.IntSort

/**
 * Пулы идентификаторов в порядке, которого требует DEX (и верификатор ART):
 * отсортированы и без дубликатов. Для каждого пула есть массив ремапа
 * «индекс в модели -> новый индекс».
 *
 * - строки: по UTF-16 code units (= [String.compareTo]);
 * - типы: по новому индексу строки-дескриптора;
 * - прототипы: по (тип возврата, параметры лексикографически, короткий список раньше);
 * - поля: по (класс, имя, тип); методы: по (класс, имя, прототип).
 *
 * С [live] в пулы попадают только используемые записи, у остальных в ремапе `-1`: ссылка на такую
 * запись — ошибка разметки, и писатель на ней падает (а не пишет висячий индекс).
 */
internal class IdPools(private val dex: DexFile, val live: LiveIds? = null) {
    val strings: Array<String>
    val stringMap: IntArray

    /** Новый type -> новый индекс строки-дескриптора. */
    val typeDescriptor: IntArray
    val typeMap: IntArray

    val protoShorty: IntArray
    val protoReturn: IntArray
    val protoParams: Array<IntArray>
    val protoMap: IntArray

    val fieldClass: IntArray
    val fieldType: IntArray
    val fieldName: IntArray
    val fieldMap: IntArray

    val methodClass: IntArray
    val methodProto: IntArray
    val methodName: IntArray
    val methodMap: IntArray

    init {
        // ---- строки ----
        val src = dex.strings.toTypedArray()
        stringMap = IntArray(src.size)
        val sFirst = FirstIndices(src.size)
        remapSorted(src.size, live?.strings, { a, b -> src[a].compareTo(src[b]) }, stringMap, sFirst)
        strings = if (sFirst.size == src.size && sFirst.isIdentity()) src else Array(sFirst.size) { src[sFirst.first[it]] }

        // ---- типы: ранжирование по индексу строки (подсчётом, без сортировки) ----
        val typeIds = dex.typeIds
        val typeKey = IntArray(typeIds.size) {
            if (live == null || live.types[it]) string(typeIds[it].descriptorIdx, "type_id.descriptor_idx") else -1
        }
        val present = BooleanArray(strings.size)
        for (key in typeKey) if (key >= 0) present[key] = true
        val rank = IntArray(strings.size)
        var nTypes = 0
        for (s in strings.indices) if (present[s]) rank[s] = nTypes++
        // ART: CheckSizeLimit(..., kDexNoIndex16) — не больше 65535 (индекс 0xFFFF зарезервирован как «нет»).
        if (nTypes > 0xFFFF) throw IllegalArgumentException("Слишком много типов: $nTypes (максимум 65535 в одном DEX)")
        typeMap = IntArray(typeIds.size) { if (typeKey[it] < 0) -1 else rank[typeKey[it]] }
        typeDescriptor = IntArray(nTypes)
        for (s in strings.indices) if (present[s]) typeDescriptor[rank[s]] = s

        // ---- прототипы ----
        val protos = dex.protoIds
        val pRet = IntArray(protos.size)
        val pParams = Array(protos.size) { EMPTY }
        for (i in protos.indices) {
            if (live != null && !live.protos[i]) continue
            pRet[i] = type(protos[i].returnTypeIdx, "proto_id.return_type_idx")
            pParams[i] = typeList(protos[i].parametersTypeListIdx, "proto_id.parameters_off")
        }
        protoMap = IntArray(protos.size)
        val pFirst = FirstIndices(protos.size)
        remapSorted(protos.size, live?.protos, { a, b -> compareProto(pRet, pParams, a, b) }, protoMap, pFirst)
        if (pFirst.size > 0xFFFF) throw IllegalArgumentException("Слишком много прототипов: ${pFirst.size} (максимум 65535 в одном DEX)")
        protoShorty = IntArray(pFirst.size) { string(protos[pFirst.first[it]].shortyIdx, "proto_id.shorty_idx") }
        protoReturn = IntArray(pFirst.size) { pRet[pFirst.first[it]] }
        protoParams = Array(pFirst.size) { pParams[pFirst.first[it]] }

        // ---- поля ----
        val fields = dex.fieldIds
        val fLive = live?.fields
        val fc = IntArray(fields.size) { if (fLive == null || fLive[it]) type(fields[it].classIdx, "field_id.class_idx") else -1 }
        val fn = IntArray(fields.size) { if (fLive == null || fLive[it]) string(fields[it].nameIdx, "field_id.name_idx") else -1 }
        val ft = IntArray(fields.size) { if (fLive == null || fLive[it]) type(fields[it].typeIdx, "field_id.type_idx") else -1 }
        fieldMap = IntArray(fields.size)
        val fFirst = FirstIndices(fields.size)
        remapSorted(fields.size, fLive, { a, b -> compare3(fc, fn, ft, a, b) }, fieldMap, fFirst)
        fieldClass = IntArray(fFirst.size) { fc[fFirst.first[it]] }
        fieldName = IntArray(fFirst.size) { fn[fFirst.first[it]] }
        fieldType = IntArray(fFirst.size) { ft[fFirst.first[it]] }

        // ---- методы ----
        val methods = dex.methodIds
        val mLive = live?.methods
        val mc = IntArray(methods.size) { if (mLive == null || mLive[it]) type(methods[it].classIdx, "method_id.class_idx") else -1 }
        val mn = IntArray(methods.size) { if (mLive == null || mLive[it]) string(methods[it].nameIdx, "method_id.name_idx") else -1 }
        val mp = IntArray(methods.size) { if (mLive == null || mLive[it]) proto(methods[it].protoIdx, "method_id.proto_idx") else -1 }
        methodMap = IntArray(methods.size)
        val mFirst = FirstIndices(methods.size)
        remapSorted(methods.size, mLive, { a, b -> compare3(mc, mn, mp, a, b) }, methodMap, mFirst)
        methodClass = IntArray(mFirst.size) { mc[mFirst.first[it]] }
        methodName = IntArray(mFirst.size) { mn[mFirst.first[it]] }
        methodProto = IntArray(mFirst.size) { mp[mFirst.first[it]] }
    }

    val typeCount: Int get() = typeDescriptor.size
    val protoCount: Int get() = protoShorty.size
    val fieldCount: Int get() = fieldClass.size
    val methodCount: Int get() = methodClass.size

    fun string(old: Int, what: String): Int {
        if (old < 0 || old >= stringMap.size) badIndex(what, old, stringMap.size)
        return stringMap[old].also { if (it < 0) unmarked(what, "строку", old) }
    }

    /** `-1` (нет) остаётся `-1`. */
    fun stringOrNone(old: Int, what: String): Int = if (old == -1) -1 else string(old, what)

    fun type(old: Int, what: String): Int {
        if (old < 0 || old >= typeMap.size) badIndex(what, old, typeMap.size)
        return typeMap[old].also { if (it < 0) unmarked(what, "тип", old) }
    }

    fun typeOrNone(old: Int, what: String): Int = if (old == -1) -1 else type(old, what)

    fun proto(old: Int, what: String): Int {
        if (old < 0 || old >= protoMap.size) badIndex(what, old, protoMap.size)
        return protoMap[old].also { if (it < 0) unmarked(what, "прототип", old) }
    }

    fun field(old: Int, what: String): Int {
        if (old < 0 || old >= fieldMap.size) badIndex(what, old, fieldMap.size)
        return fieldMap[old].also { if (it < 0) unmarked(what, "поле", old) }
    }

    fun method(old: Int, what: String): Int {
        if (old < 0 || old >= methodMap.size) badIndex(what, old, methodMap.size)
        return methodMap[old].also { if (it < 0) unmarked(what, "метод", old) }
    }

    /** type_list модели в новых индексах типов; `-1` — пустой список. */
    fun typeList(listIdx: Int, what: String): IntArray {
        if (listIdx == -1) return EMPTY
        if (listIdx < 0 || listIdx >= dex.typeLists.size) badIndex(what, listIdx, dex.typeLists.size)
        val list = dex.typeLists[listIdx]
        val types = list.typeIdx
        if (list.size != types.size) {
            throw IllegalArgumentException("$what: type_list #$listIdx size=${list.size}, а элементов ${types.size}")
        }
        if (types.isEmpty()) return EMPTY
        return IntArray(types.size) { type(types[it], what) }
    }

    /**
     * Сортирует `0 until n` по [cmp] и склеивает равные: `map[old] = new`,
     * в [first] — индекс первого (в исходном порядке) из равных для каждого нового.
     */
    private fun remapSorted(n: Int, live: BooleanArray?, cmp: IntComparator, map: IntArray, first: FirstIndices) {
        if (live != null && !live.all { it }) {
            remapLive(n, live, cmp, map, first)
            return
        }
        // Прочитанный DEX уже строго упорядочен — тождественный ремап без сортировки
        var strict = true
        for (i in 1 until n) {
            if (cmp.compare(i - 1, i) >= 0) {
                strict = false
                break
            }
        }
        if (strict) {
            for (i in 0 until n) {
                map[i] = i
                first.add(i)
            }
            return
        }
        val order = IntSort.sortedIndices(n, cmp)
        for (k in 0 until n) {
            val i = order[k]
            if (k == 0 || cmp.compare(order[k - 1], i) != 0) first.add(i)
            map[i] = first.size - 1
        }
    }

    /** [remapSorted] только по используемым записям; у остальных в [map] `-1`. */
    private fun remapLive(n: Int, live: BooleanArray, cmp: IntComparator, map: IntArray, first: FirstIndices) {
        val idx = IntArray(live.count { it })
        var m = 0
        for (i in 0 until n) if (live[i]) idx[m++] = i else map[i] = -1
        var strict = true
        for (k in 1 until m) {
            if (cmp.compare(idx[k - 1], idx[k]) >= 0) {
                strict = false
                break
            }
        }
        if (strict) {
            for (k in 0 until m) {
                map[idx[k]] = k
                first.add(idx[k])
            }
            return
        }
        val order = IntSort.sortedIndices(m) { a, b -> cmp.compare(idx[a], idx[b]) }
        for (k in 0 until m) {
            val i = idx[order[k]]
            if (k == 0 || cmp.compare(idx[order[k - 1]], i) != 0) first.add(i)
            map[i] = first.size - 1
        }
    }

    private fun unmarked(what: String, kind: String, old: Int): Nothing =
        throw IllegalStateException("$what: ссылка на $kind #$old, которую разметка используемого ([LiveIds]) не отметила")

    private fun compareProto(ret: IntArray, params: Array<IntArray>, a: Int, b: Int): Int {
        if (ret[a] != ret[b]) return if (ret[a] < ret[b]) -1 else 1
        val pa = params[a]
        val pb = params[b]
        val n = minOf(pa.size, pb.size)
        for (i in 0 until n) {
            if (pa[i] != pb[i]) return if (pa[i] < pb[i]) -1 else 1
        }
        return pa.size - pb.size
    }

    private fun compare3(x: IntArray, y: IntArray, z: IntArray, a: Int, b: Int): Int {
        if (x[a] != x[b]) return if (x[a] < x[b]) -1 else 1
        if (y[a] != y[b]) return if (y[a] < y[b]) -1 else 1
        if (z[a] != z[b]) return if (z[a] < z[b]) -1 else 1
        return 0
    }

    /** Индексы первых вхождений уникальных элементов. */
    private class FirstIndices(capacity: Int) {
        val first = IntArray(capacity)
        var size = 0
        fun add(v: Int) {
            first[size++] = v
        }

        fun isIdentity(): Boolean {
            for (i in 0 until size) if (first[i] != i) return false
            return true
        }
    }

    companion object {
        val EMPTY = IntArray(0)
    }
}

internal fun badIndex(what: String, idx: Int, size: Int): Nothing =
    throw IllegalArgumentException("$what: индекс $idx вне диапазона [0, $size)")
