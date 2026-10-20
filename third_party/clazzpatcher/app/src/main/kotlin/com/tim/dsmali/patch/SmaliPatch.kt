package com.tim.dsmali.patch

import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.model.SmaliClass
import com.tim.dsmali.model.SmaliField
import com.tim.dsmali.model.SmaliMethod
import com.tim.dsmali.model.SmaliTree

/**
 * Правки [SmaliTree] текстом smali.
 *
 * Методы сопоставляются по имени + типам параметров + типу результата, поля — по имени + типу,
 * аннотации — по типу. Отсутствующий класс или ошибка разбора — исключение.
 */
class SmaliPatch(
    private val tree: SmaliTree
) {
    /** Добавить класс из текста smali (класс с тем же дескриптором заменяется целиком). */
    fun applyText(smali: String) {
        tree.addClass(parse(smali))
    }

    /**
     * Слить в класс [descriptor] методы, поля и аннотации класса из текста [smali]:
     * совпадающие члены заменяются на месте, новые добавляются в конец.
     */
    fun patchClass(descriptor: String, smali: String) {
        val patch = parse(smali)
        val existing = findClass(descriptor)
        for (m in patch.methods) {
            val i = existing.methods.indexOfFirst { sameMethod(it, m) }
            if (i >= 0) existing.methods[i] = m else existing.methods.add(m)
        }
        for (f in patch.fields) {
            val i = existing.fields.indexOfFirst { sameField(it, f) }
            if (i >= 0) existing.fields[i] = f else existing.fields.add(f)
        }
        for (a in patch.annotations) {
            val i = existing.annotations.indexOfFirst { it.type == a.type }
            if (i >= 0) existing.annotations[i] = a else existing.annotations.add(a)
        }
    }

    /** Удалить класс; отсутствующий класс — ошибка. */
    fun removeClass(descriptor: String) {
        if (!tree.removeClass(descriptor)) throw IllegalArgumentException("Класс не найден: $descriptor")
    }

    /** Добавить методы из текста smali в класс [descriptor]; уже существующий метод — ошибка. */
    fun addMethod(descriptor: String, smali: String) {
        val cls = findClass(descriptor)
        val tmp = parse(smali)
        for (m in tmp.methods) {
            if (cls.methods.any { sameMethod(it, m) }) {
                throw IllegalArgumentException(
                    "Метод ${m.name}(${m.paramTypes.joinToString("")})${m.returnType} уже есть в $descriptor"
                )
            }
            cls.methods.add(m)
        }
    }

    private fun parse(smali: String): SmaliClass =
        SmaliParser.parseClass(smali) ?: throw IllegalArgumentException("В тексте нет класса smali (.class)")

    private fun findClass(descriptor: String): SmaliClass =
        tree.findClass(descriptor) ?: throw IllegalArgumentException("Класс не найден: $descriptor")

    private fun sameMethod(a: SmaliMethod, b: SmaliMethod) =
        a.name == b.name && a.paramTypes == b.paramTypes && a.returnType == b.returnType

    private fun sameField(a: SmaliField, b: SmaliField) = a.name == b.name && a.type == b.type
}
