package com.tim.dasm.util

/**
 * Порядок классов «суперкласс и интерфейсы раньше наследника» (требование формата DEX
 * к class_defs). Внутри этого ограничения сохраняется исходный порядок.
 */
object HierarchyOrder {

    fun <T> sort(items: List<T>, name: (T) -> String, deps: (T) -> List<String>): List<T> {
        if (items.size < 2) return items
        val byName = HashMap<String, T>(items.size * 2)
        for (item in items) {
            require(byName.put(name(item), item) == null) { "Класс ${name(item)} определён дважды" }
        }
        val out = ArrayList<T>(items.size)
        val state = HashMap<String, Int>(items.size * 2) // 1 — в обработке, 2 — готов
        for (root in items) {
            if (state[name(root)] != null) continue
            // Итеративный DFS: глубокие иерархии не переполняют стек
            val stack = ArrayList<Pair<T, Iterator<String>>>()
            state[name(root)] = 1
            stack.add(root to deps(root).iterator())
            while (stack.isNotEmpty()) {
                val (item, it) = stack[stack.size - 1]
                if (it.hasNext()) {
                    val dep = byName[it.next()] ?: continue
                    when (state[name(dep)]) {
                        2 -> {}
                        1 -> throw IllegalStateException("Циклическое наследование: ${name(item)} -> ${name(dep)}")
                        else -> {
                            state[name(dep)] = 1
                            stack.add(dep to deps(dep).iterator())
                        }
                    }
                } else {
                    stack.removeAt(stack.size - 1)
                    state[name(item)] = 2
                    out.add(item)
                }
            }
        }
        return out
    }
}
