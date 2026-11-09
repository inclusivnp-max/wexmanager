package org.wexclient.app.dex

import org.wexclient.app.util.NaturalOrder

internal data class ClassFamily<T>(val root: T, val members: List<T>) {
    val isSplit: Boolean get() = members.size > 1
}

/** Связи вложенности важнее имён; префикс с '$' нужен для классов без метаданных. */
internal class ClassFamilyIndex<T>(
    items: Collection<T>,
    private val nameOf: (T) -> String,
    enclosingName: (T) -> String? = { null }
) {
    private val byName = items.associateBy(nameOf)
    private val parents = byName.mapValues { (name, item) ->
        val explicit = enclosingName(item)
        if (explicit != null) explicit.takeIf { it != name && it in byName }
        else {
            var prefix = name
            var found: String? = null
            while ('$' in prefix) {
                prefix = prefix.substringBeforeLast('$')
                if (prefix in byName) { found = prefix; break }
            }
            found
        }
    }
    private val roots = HashMap<String, String>()

    private fun rootName(name: String): String {
        roots[name]?.let { return it }
        val path = ArrayList<String>()
        val seen = HashSet<String>()
        var current = name
        while (true) {
            roots[current]?.let { root -> path.forEach { roots[it] = root }; return root }
            if (!seen.add(current)) {
                // Повреждённый цикл не должен ни скрывать классы, ни зацикливать дерево.
                path.forEach { roots[it] = it }
                return name
            }
            path.add(current)
            val parent = parents[current]
            if (parent == null) { path.forEach { roots[it] = current }; return current }
            current = parent
        }
    }

    val families: List<ClassFamily<T>>
    private val familyByName: Map<String, ClassFamily<T>>

    init {
        val grouped = byName.keys.groupBy(::rootName)
        families = grouped.map { (root, names) ->
            val ordered = names.filter { it != root }.sortedWith { a, b -> NaturalOrder.compare(a, b) }
            ClassFamily(byName.getValue(root), listOf(byName.getValue(root)) + ordered.map(byName::getValue))
        }.sortedWith { a, b -> NaturalOrder.compare(nameOf(a.root), nameOf(b.root)) }
        familyByName = families.flatMap { family -> family.members.map { nameOf(it) to family } }.toMap()
    }

    fun familyOf(item: T): ClassFamily<T> = familyByName.getValue(nameOf(item))
    fun isRoot(item: T): Boolean = familyOf(item).root == item

    fun isWithin(name: String, ancestor: String): Boolean {
        if (name !in byName || ancestor !in byName || rootName(name) != rootName(ancestor)) return false
        var current: String? = name
        val seen = HashSet<String>()
        while (current != null && seen.add(current)) {
            if (current == ancestor) return true
            current = parents[current]
        }
        return false
    }
}

internal object ClassFamilyLimits {
    const val MAX_MEMBERS = 2048
    const val MAX_BYTES = 64 * 1024 * 1024
}

internal fun joinClassSources(sources: List<Pair<String, String>>): String = sources.mapIndexed { index, (name, code) ->
    if (index == 0) code else "\n\n// ----- $name -----\n$code"
}.joinToString("")
