package org.wexclient.app.apk

import org.wexclient.app.arsc.ArscParser

/** Изменение одного ресурса между версиями. */
data class ResourceChange(
    val key: String,
    val kind: DiffKind,
    val oldValue: String,
    val newValue: String
)

/** Итог сравнения resources.arsc. */
data class ResourceDiff(
    val changes: List<ResourceChange>,
    val oldCount: Int,
    val newCount: Int
) {
    val added: Int get() = changes.count { it.kind == DiffKind.ADDED }
    val removed: Int get() = changes.count { it.kind == DiffKind.REMOVED }
    val changed: Int get() = changes.count { it.kind == DiffKind.CHANGED }
}

/**
 * Сравнение таблицы ресурсов. Ключ — «пакет:тип/имя» вместе с квалификатором конфигурации
 * (ru, xhdpi, night…), поэтому перевод на один язык не выглядит как правка всех остальных.
 */
object ApkResourceDiff {

    /** Плоская карта «ключ ресурса → значение». Пустая, если arsc не разобрался. */
    fun read(bytes: ByteArray): Map<String, String> = runCatching {
        val table = ArscParser.parse(bytes)
        val pool = table.globalStrings
        val result = LinkedHashMap<String, String>()
        for (pkg in table.packages) {
            for (type in pkg.types) {
                for (config in type.configs) {
                    val qualifier = config.qualifier.takeIf { it.isNotBlank() }
                    for (entry in config.entries) {
                        if (entry.name.isBlank()) continue
                        val key = buildString {
                            append(pkg.name).append(':').append(type.name).append('/').append(entry.name)
                            if (qualifier != null) append(" (").append(qualifier).append(')')
                        }
                        val value = runCatching { entry.displayValue(pool) }.getOrDefault("")
                        // Один ключ встречается один раз; при дублях берём первое значение
                        result.putIfAbsent(key, value)
                    }
                }
            }
        }
        result
    }.getOrDefault(emptyMap())

    fun compare(old: Map<String, String>, new: Map<String, String>): ResourceDiff {
        val keys = (old.keys + new.keys).toSortedSet()
        val changes = ArrayList<ResourceChange>()
        for (key in keys) {
            val o = old[key]
            val n = new[key]
            when {
                o == null && n != null -> changes += ResourceChange(key, DiffKind.ADDED, "", n)
                n == null && o != null -> changes += ResourceChange(key, DiffKind.REMOVED, o, "")
                o != null && n != null && o != n -> changes += ResourceChange(key, DiffKind.CHANGED, o, n)
            }
        }
        return ResourceDiff(
            changes = changes.sortedWith(compareBy({ order(it.kind) }, { it.key })),
            oldCount = old.size,
            newCount = new.size
        )
    }

    private fun order(kind: DiffKind): Int = when (kind) {
        DiffKind.CHANGED -> 0
        DiffKind.ADDED -> 1
        DiffKind.REMOVED -> 2
        DiffKind.SAME -> 3
    }
}
