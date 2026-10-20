package com.tim.casm.hierarchy

/**
 * Сведения об иерархии классов для вычисления фреймов StackMapTable.
 *
 * Имена — внутренние (`java/lang/String`). Неизвестный класс — `null` из [superClass]:
 * общий предок с ним считается `java/lang/Object` (это всегда корректно для фрейма,
 * но может быть слишком общо для последующих инструкций).
 */
interface ClassHierarchy {

    /** Внутреннее имя суперкласса, `null` — класс неизвестен или это `java/lang/Object`. */
    fun superClass(internalName: String): String?

    /** `true`/`false` — известно, `null` — класс неизвестен. */
    fun isInterface(internalName: String): Boolean?

    /** Прямые суперинтерфейсы; `null` — неизвестны. */
    fun interfaces(internalName: String): List<String>? = null

    /**
     * Класс [cls] реализует интерфейс [iface] (напрямую, через суперклассы или суперинтерфейсы):
     * `true` — доказано, `false` — вся иерархия известна и его там нет, `null` — сведений не хватает.
     */
    fun implements(cls: String, iface: String): Boolean? {
        val seen = HashSet<String>()
        val work = ArrayDeque<String>()
        work.add(cls)
        var complete = true
        while (work.isNotEmpty()) {
            val c = work.removeFirst()
            if (!seen.add(c)) continue
            if (c == iface) return true
            val direct = interfaces(c)
            if (direct == null) complete = false else work.addAll(direct)
            val sup = superClass(c)
            if (sup != null) work.add(sup) else if (c != OBJECT && isInterface(c) != true) complete = false
        }
        return if (complete) false else null
    }

    companion object {
        const val OBJECT = "java/lang/Object"

        /** Иерархия без сведений: общий предок разных классов — всегда `java/lang/Object`. */
        val EMPTY: ClassHierarchy = object : ClassHierarchy {
            override fun superClass(internalName: String): String? = null
            override fun isInterface(internalName: String): Boolean? = null
        }
    }
}

/** Первая иерархия, знающая класс, отвечает за него. */
class CompositeHierarchy(private val parts: List<ClassHierarchy>) : ClassHierarchy {
    override fun superClass(internalName: String): String? {
        for (p in parts) p.superClass(internalName)?.let { return it }
        return null
    }

    override fun isInterface(internalName: String): Boolean? {
        for (p in parts) p.isInterface(internalName)?.let { return it }
        return null
    }

    override fun interfaces(internalName: String): List<String>? {
        for (p in parts) p.interfaces(internalName)?.let { return it }
        return null
    }
}

/** Иерархия из явного набора классов (например, классов одного DEX или jar). */
class MapHierarchy : ClassHierarchy {
    private class Entry(val superName: String?, val isInterface: Boolean, val interfaces: List<String>?)

    private val entries = HashMap<String, Entry>()

    /** [interfaces] — прямые суперинтерфейсы; `null` — неизвестны. */
    fun add(internalName: String, superName: String?, isInterface: Boolean, interfaces: List<String>? = null): MapHierarchy {
        entries[internalName] = Entry(superName, isInterface, interfaces)
        return this
    }

    val size: Int get() = entries.size

    override fun superClass(internalName: String): String? = entries[internalName]?.superName

    override fun isInterface(internalName: String): Boolean? = entries[internalName]?.isInterface

    override fun interfaces(internalName: String): List<String>? = entries[internalName]?.interfaces
}

/**
 * Общий предок двух классов (LUB для верификатора) с кэшем.
 * Интерфейсы для верификатора JVM равносильны `java/lang/Object`.
 */
class CommonSuperClass(private val hierarchy: ClassHierarchy) {
    private val cache = HashMap<String, HashMap<String, String>>()

    fun of(a: String, b: String): String {
        if (a == b) return a
        if (a == ClassHierarchy.OBJECT || b == ClassHierarchy.OBJECT) return ClassHierarchy.OBJECT
        val row = cache.getOrPut(a) { HashMap(4) }
        row[b]?.let { return it }
        val result = compute(a, b)
        row[b] = result
        return result
    }

    private fun compute(a: String, b: String): String {
        val ia = hierarchy.isInterface(a)
        val ib = hierarchy.isInterface(b)
        if (ia == true || ib == true) return ClassHierarchy.OBJECT
        val chainA = chain(a)
        // Неизвестный класс — общий предок, только если он суперкласс другого: суперкласс всегда
        // класс, а не интерфейс (JVMS §5.3.5), так что фрейм с ним верен (например, классы
        // приложения и их предок из android.jar, которого нет в иерархии).
        if (ib == null) return if (b in chainA) b else ClassHierarchy.OBJECT
        if (ia == null) return if (a in chain(b)) a else ClassHierarchy.OBJECT
        var c: String? = b
        var guard = 0
        while (c != null && guard++ < MAX_DEPTH) {
            if (c in chainA) return c
            c = hierarchy.superClass(c)
        }
        return ClassHierarchy.OBJECT
    }

    /** Класс и его суперклассы (сколько известно). */
    private fun chain(name: String): HashSet<String> {
        val out = HashSet<String>()
        var c: String? = name
        var guard = 0
        while (c != null && guard++ < MAX_DEPTH) {
            out.add(c)
            c = hierarchy.superClass(c)
        }
        return out
    }

    private companion object {
        /** Защита от циклов в некорректной иерархии. */
        const val MAX_DEPTH = 256
    }
}
