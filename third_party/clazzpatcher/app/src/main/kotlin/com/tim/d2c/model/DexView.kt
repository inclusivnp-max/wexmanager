package com.tim.d2c.model

import com.tim.dex.model.AnnotationItem
import com.tim.dex.model.AnnotationsDirectoryItem
import com.tim.dex.model.ClassDefItem
import com.tim.dex.model.DexFile

/** Ссылка на поле: дескриптор владельца, имя, дескриптор типа. */
class FieldRef(val owner: String, val name: String, val type: String)

/** Ссылка на метод: дескриптор владельца, имя, прототип. */
class MethodRef(val owner: String, val name: String, val proto: Proto)

/** Прототип метода: дескрипторы параметров и возврата, JVM-дескриптор `(…)R`. */
class Proto(val params: List<String>, val returnType: String) {
    val descriptor: String = buildString {
        append('(')
        for (p in params) append(p)
        append(')').append(returnType)
    }

    /** Число регистров DEX / слотов JVM под параметры (без this). */
    val paramWords: Int = params.size + params.count { it == "J" || it == "D" }
}

/**
 * Разрешение индексов [DexFile] в имена и дескрипторы (с кэшами прототипов и ссылок).
 * Только чтение — безопасно для параллельной конвертации классов после построения.
 */
class DexView(val dex: DexFile) {

    private val protos = arrayOfNulls<Proto>(dex.protoIds.size)
    private val methods = arrayOfNulls<MethodRef>(dex.methodIds.size)
    private val fields = arrayOfNulls<FieldRef>(dex.fieldIds.size)

    /** Дескриптор типа → индекс class_def. */
    val classDefByType: Map<String, Int> = HashMap<String, Int>(dex.classDefs.size * 2).also { m ->
        for ((i, c) in dex.classDefs.withIndex()) m.putIfAbsent(type(c.classIdx), i)
    }

    init {
        // Дескрипторы проверяет и загрузчик ART: дальше переводчик опирается на их синтаксис.
        for (i in dex.typeIds.indices) {
            val d = type(i)
            if (!Descriptors.isType(d)) throw IllegalArgumentException("type_id #$i: некорректный дескриптор «$d»")
        }
        // Заполняем кэши заранее: дальше экземпляр только читается (в том числе из потоков).
        for (i in protos.indices) {
            val p = proto(i)
            if (p.params.any { it == "V" }) throw IllegalArgumentException("proto_id #$i: параметр типа V (${p.descriptor})")
        }
        for (i in methods.indices) method(i)
        for (i in fields.indices) {
            val f = field(i)
            if (f.type == "V") throw IllegalArgumentException("field_id #$i: поле ${f.owner}->${f.name} типа V")
        }
    }

    fun string(idx: Int): String = dex.strings.getOrNull(idx)
        ?: throw IllegalArgumentException("Нет строки #$idx")

    /** Дескриптор типа по type_idx. */
    fun type(idx: Int): String {
        val t = dex.typeIds.getOrNull(idx) ?: throw IllegalArgumentException("Нет типа #$idx")
        return string(t.descriptorIdx)
    }

    fun typeList(idx: Int): List<String> {
        if (idx < 0) return emptyList()
        val l = dex.typeLists.getOrNull(idx) ?: throw IllegalArgumentException("Нет type_list #$idx")
        return l.typeIdx.map { type(it) }
    }

    fun proto(idx: Int): Proto {
        protos.getOrNull(idx)?.let { return it }
        val p = dex.protoIds.getOrNull(idx) ?: throw IllegalArgumentException("Нет прототипа #$idx")
        val proto = Proto(typeList(p.parametersTypeListIdx), type(p.returnTypeIdx))
        protos[idx] = proto
        return proto
    }

    fun method(idx: Int): MethodRef {
        methods.getOrNull(idx)?.let { return it }
        val m = dex.methodIds.getOrNull(idx) ?: throw IllegalArgumentException("Нет метода #$idx")
        val ref = MethodRef(type(m.classIdx), string(m.nameIdx), proto(m.protoIdx))
        methods[idx] = ref
        return ref
    }

    fun field(idx: Int): FieldRef {
        fields.getOrNull(idx)?.let { return it }
        val f = dex.fieldIds.getOrNull(idx) ?: throw IllegalArgumentException("Нет поля #$idx")
        val ref = FieldRef(type(f.classIdx), string(f.nameIdx), type(f.typeIdx))
        fields[idx] = ref
        return ref
    }

    fun classDef(idx: Int): ClassDefItem = dex.classDefs[idx]

    fun annotationsDirectory(c: ClassDefItem): AnnotationsDirectoryItem? =
        if (c.annotationsDirectoryIdx < 0) null else dex.annotationsDirectoryItems[c.annotationsDirectoryIdx]

    /** Аннотации набора (annotation_set_item); -1 — пустой набор. */
    fun annotationSet(idx: Int): List<AnnotationItem> {
        if (idx < 0) return emptyList()
        val set = dex.annotationSetItems[idx]
        val out = ArrayList<AnnotationItem>(set.annotationIdxs.size)
        for (a in set.annotationIdxs) if (a >= 0) out.add(dex.annotationItems[a])
        return out
    }

    /** Наборы аннотаций параметров (annotation_set_ref_list); -1 — нет. */
    fun annotationSetRefList(idx: Int): List<List<AnnotationItem>> {
        if (idx < 0) return emptyList()
        return dex.annotationSetRefLists[idx].annotationSetIdxs.map { annotationSet(it) }
    }

    companion object {
        /** `Lcom/foo/Bar;` → `com/foo/Bar`; массивы (`[I`) остаются дескрипторами (так их пишет CONSTANT_Class). */
        fun internalName(descriptor: String): String =
            if (descriptor.startsWith("L") && descriptor.endsWith(";")) descriptor.substring(1, descriptor.length - 1)
            else if (descriptor.startsWith("[")) descriptor
            else throw IllegalArgumentException("Не ссылочный тип: $descriptor")

        fun isWide(descriptor: String): Boolean = descriptor == "J" || descriptor == "D"
    }
}
