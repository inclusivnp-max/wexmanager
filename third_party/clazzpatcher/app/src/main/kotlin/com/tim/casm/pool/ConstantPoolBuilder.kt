package com.tim.casm.pool

import com.tim.clazz.model.*

/**
 * Изменяемый пул констант с дедупликацией: одинаковые записи получают один индекс.
 * Второй слот Long/Double хранится как null (JVMS §4.4.5).
 */
class ConstantPoolBuilder {
    private val entries = ArrayList<ConstantPoolEntry?>().apply { add(null) }
    private val index = HashMap<ConstantPoolEntry, Int>()

    /** Строка → индекс CONSTANT_Utf8: без повторного кодирования и хеширования байт. */
    private val utf8Index = HashMap<String, Int>()

    constructor()

    /** Пул существующего класса: индексы записей сохраняются как есть. */
    constructor(existing: List<ConstantPoolEntry?>) {
        entries.ensureCapacity(existing.size)
        for (i in 1 until existing.size) {
            val e = existing[i]
            entries.add(e)
            if (e == null) continue
            index.putIfAbsent(e, i)
            if (e is ConstantUtf8Info) {
                val s = try { e.getString() } catch (ex: Exception) { null }
                if (s != null) utf8Index.putIfAbsent(s, i)
            }
        }
    }

    val size: Int get() = entries.size

    fun add(entry: ConstantPoolEntry): Int {
        index[entry]?.let { return it }
        val idx = entries.size
        entries.add(entry)
        if (entry is ConstantLongInfo || entry is ConstantDoubleInfo) entries.add(null)
        index[entry] = idx
        if (entry is ConstantUtf8Info) {
            val s = try { entry.getString() } catch (e: Exception) { null }
            if (s != null) utf8Index.putIfAbsent(s, idx)
        }
        return idx
    }

    fun utf8(value: String): Int {
        utf8Index[value]?.let { return it }
        // Все CONSTANT_Utf8 пула уже есть в utf8Index — новая строка всегда новая запись.
        val entry = Utf8Cache.entry(value)
        val idx = entries.size
        entries.add(entry)
        index.putIfAbsent(entry, idx)
        utf8Index[value] = idx
        return idx
    }

    fun classInfo(internalName: String): Int {
        if (deferring && internalName !in utf8Index) return deferred(internalName, pendingClasses) { ConstantClassInfo(it) }
        val nameIdx = utf8(internalName)
        return add(ConstantClassInfo(nameIdx))
    }

    // ---- Константы ldc раньше своих Utf8 ----

    private var deferring = false
    private val pendingStrings = LinkedHashMap<String, Int>()
    private val pendingClasses = LinkedHashMap<String, Int>()

    /**
     * Внутри [block] записи String и Class, чьих Utf8 ещё нет, занимают индексы сразу, а сами Utf8 добавляются
     * после блока: так ниже 256 (операнд `ldc` — u1) помещается вдвое больше констант, как в пулах javac.
     */
    fun <T> deferUtf8(block: () -> T): T {
        deferring = true
        try {
            return block()
        } finally {
            deferring = false
            for ((value, idx) in pendingStrings) place(idx, ConstantStringInfo(utf8(value)))
            for ((value, idx) in pendingClasses) place(idx, ConstantClassInfo(utf8(value)))
            pendingStrings.clear()
            pendingClasses.clear()
        }
    }

    private inline fun deferred(value: String, pending: MutableMap<String, Int>, entry: (Int) -> ConstantPoolEntry): Int {
        pending[value]?.let { return it }
        val idx = entries.size
        entries.add(entry(0)) // заглушка до flush: индекс Utf8 ещё неизвестен
        pending[value] = idx
        return idx
    }

    private fun place(idx: Int, entry: ConstantPoolEntry) {
        entries[idx] = entry
        index.putIfAbsent(entry, idx)
    }

    fun nameAndType(name: String, descriptor: String): Int {
        val n = utf8(name)
        val d = utf8(descriptor)
        return add(ConstantNameAndTypeInfo(n, d))
    }

    fun string(value: String): Int =
        if (deferring && value !in utf8Index) deferred(value, pendingStrings) { ConstantStringInfo(it) }
        else add(ConstantStringInfo(utf8(value)))
    fun integer(v: Int): Int = add(ConstantIntegerInfo(v))
    fun float(bits: Int): Int = add(ConstantFloatInfo(bits))
    fun long(v: Long): Int = add(ConstantLongInfo(v))
    fun double(bits: Long): Int = add(ConstantDoubleInfo(bits))

    fun fieldref(cls: String, name: String, descriptor: String): Int {
        val c = classInfo(cls)
        val nat = nameAndType(name, descriptor)
        return add(ConstantFieldrefInfo(c, nat))
    }

    fun methodref(cls: String, name: String, descriptor: String): Int {
        val c = classInfo(cls)
        val nat = nameAndType(name, descriptor)
        return add(ConstantMethodrefInfo(c, nat))
    }

    fun interfaceMethodref(cls: String, name: String, descriptor: String): Int {
        val c = classInfo(cls)
        val nat = nameAndType(name, descriptor)
        return add(ConstantInterfaceMethodrefInfo(c, nat))
    }

    fun methodHandle(kind: Int, refIndex: Int): Int = add(ConstantMethodHandleInfo(kind, refIndex))
    fun methodType(descriptor: String): Int = add(ConstantMethodTypeInfo(utf8(descriptor)))
    fun dynamic(bsmIndex: Int, natIndex: Int): Int = add(ConstantDynamicInfo(bsmIndex, natIndex))
    fun invokeDynamic(bsmIndex: Int, natIndex: Int): Int = add(ConstantInvokeDynamicInfo(bsmIndex, natIndex))
    fun module(name: String): Int = add(ConstantModuleInfo(utf8(name)))
    fun package_(name: String): Int = add(ConstantPackageInfo(utf8(name)))

    fun get(idx: Int): ConstantPoolEntry? = entries.getOrNull(idx)

    fun entry(idx: Int): ConstantPoolEntry =
        entries.getOrNull(idx) ?: throw IllegalArgumentException("Нет записи пула #$idx")

    fun utf8At(idx: Int): String =
        (entry(idx) as? ConstantUtf8Info)?.getString()
            ?: throw IllegalArgumentException("#$idx не CONSTANT_Utf8")

    fun constant(value: Any): Int = when (value) {
        is Int -> integer(value)
        is Long -> long(value)
        is Float -> float(value.toRawBits())
        is Double -> double(value.toRawBits())
        is String -> string(value)
        is Boolean -> integer(if (value) 1 else 0)
        is Byte -> integer(value.toInt())
        is Char -> integer(value.code)
        is Short -> integer(value.toInt())
        else -> throw IllegalArgumentException("Неподдерживаемый ConstantValue: $value")
    }

    fun build(): List<ConstantPoolEntry?> = entries.toList()
}

/**
 * Готовые (закодированные в Modified UTF-8) записи CONSTANT_Utf8, общие для классов одного потока:
 * одни и те же строки («Code», «java/lang/Object», дескрипторы) не кодируются заново в каждом классе.
 * Записи неизменяемы, поэтому одна запись может принадлежать пулам разных классов.
 */
internal object Utf8Cache {
    private const val LIMIT = 1 shl 16

    private val cache = object : ThreadLocal<HashMap<String, ConstantUtf8Info>>() {
        override fun initialValue() = HashMap<String, ConstantUtf8Info>(1024)
    }

    fun entry(value: String): ConstantUtf8Info {
        val map = cache.get()
        map[value]?.let { return it }
        if (map.size >= LIMIT) map.clear()
        val e = ConstantUtf8Info.of(value)
        map[value] = e
        return e
    }
}
