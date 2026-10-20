package com.tim.clazz.util

import com.tim.clazz.model.ConstantClassInfo
import com.tim.clazz.model.ConstantPoolEntry
import com.tim.clazz.model.ConstantUtf8Info

/**
 * Текущее место разбора/записи для сообщений об ошибках: член класса и цепочка атрибутов.
 * Имена разрешаются только при формировании сообщения.
 */
internal class Location(private val pool: List<ConstantPoolEntry?>?) {
    private var memberKind: String? = null
    private var memberIndex = 0
    private var memberName = 0
    private var memberDescriptor = 0
    private val path = IntArray(16)
    private var depth = 0

    fun member(kind: String, index: Int, nameIndex: Int, descriptorIndex: Int) {
        memberKind = kind
        memberIndex = index
        memberName = nameIndex
        memberDescriptor = descriptorIndex
    }

    fun clearMember() {
        memberKind = null
    }

    fun pushAttribute(nameIndex: Int) {
        if (depth < path.size) path[depth] = nameIndex
        depth++
    }

    fun pushRecordComponent(nameIndex: Int) = pushAttribute(RECORD_COMPONENT or nameIndex)

    fun pop() {
        depth--
    }

    fun describe(): String {
        val parts = ArrayList<String>()
        memberKind?.let { parts.add("$it #$memberIndex ${utf8(memberName)}${if (it == "метод") "" else ":"}${utf8(memberDescriptor)}") }
        for (i in 0 until minOf(depth, path.size)) {
            val p = path[i]
            parts.add(
                if (p and RECORD_COMPONENT != 0) "компонент записи ${utf8(p and 0xFFFF)}"
                else "атрибут ${utf8(p)}"
            )
        }
        return if (parts.isEmpty()) "заголовок класса" else parts.joinToString(" → ")
    }

    /** Имя класса по индексу CONSTANT_Class, если его можно разрешить. */
    fun className(classIndex: Int): String? {
        val cls = pool?.getOrNull(classIndex) as? ConstantClassInfo ?: return null
        return utf8OrNull(cls.nameIndex)
    }

    private fun utf8(index: Int): String = utf8OrNull(index) ?: "#$index"

    private fun utf8OrNull(index: Int): String? {
        val e = pool?.getOrNull(index) as? ConstantUtf8Info ?: return null
        return ModifiedUtf8.decodeOrNull(e.bytes)
    }

    private companion object {
        const val RECORD_COMPONENT = 0x10000
    }
}
