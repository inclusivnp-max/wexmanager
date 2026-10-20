package com.tim.csmali.model

class SmaliTree(
    val classes: MutableList<SmaliClass> = mutableListOf()
) {
    fun findClass(descriptor: String): SmaliClass? =
        classes.firstOrNull { it.descriptor == descriptor }

    fun addClass(cls: SmaliClass) {
        val existing = findClass(cls.descriptor)
        if (existing != null) classes.remove(existing)
        classes.add(cls)
    }

    fun removeClass(descriptor: String): Boolean =
        classes.removeAll { it.descriptor == descriptor }

    fun isEmpty(): Boolean = classes.isEmpty()
    fun size(): Int = classes.size
}