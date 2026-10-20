package com.tim.csmali.patch

import com.tim.csmali.io.SmaliParser
import com.tim.csmali.model.SmaliClass
import com.tim.csmali.model.SmaliField
import com.tim.csmali.model.SmaliMethod
import com.tim.csmali.model.SmaliTree

class SmaliPatch(private val tree: SmaliTree) {

    fun applyText(smali: String) {
        tree.addClass(parse(smali))
    }

    fun patchClass(descriptor: String, smali: String) {
        val patch = parse(smali)
        require(patch.descriptor == descriptor) {
            "Патч для $descriptor, а в тексте ${patch.descriptor}"
        }
        val existing = tree.findClass(descriptor)
            ?: throw IllegalArgumentException("Класс не найден: $descriptor")

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

    fun removeClass(descriptor: String) {
        if (!tree.removeClass(descriptor)) {
            throw IllegalArgumentException("Класс не найден: $descriptor")
        }
    }

    fun addMethod(descriptor: String, smali: String) {
        val existing = tree.findClass(descriptor)
            ?: throw IllegalArgumentException("Класс не найден: $descriptor")
        val tmp = parse(smali)
        for (m in tmp.methods) {
            if (existing.methods.any { sameMethod(it, m) }) {
                throw IllegalArgumentException(
                    "Метод ${m.name}(${m.paramTypes.joinToString("")})${m.returnType} уже есть в $descriptor"
                )
            }
            existing.methods.add(m)
        }
    }

    fun addField(descriptor: String, smali: String) {
        val existing = tree.findClass(descriptor)
            ?: throw IllegalArgumentException("Класс не найден: $descriptor")
        val tmp = parse(smali)
        for (f in tmp.fields) {
            if (existing.fields.any { sameField(it, f) }) {
                throw IllegalArgumentException("Поле ${f.name}:${f.type} уже есть в $descriptor")
            }
            existing.fields.add(f)
        }
    }

    fun replaceMethod(descriptor: String, smali: String) {
        val existing = tree.findClass(descriptor)
            ?: throw IllegalArgumentException("Класс не найден: $descriptor")
        val tmp = parse(smali)
        for (m in tmp.methods) {
            val i = existing.methods.indexOfFirst { sameMethod(it, m) }
            if (i < 0) throw IllegalArgumentException(
                "Метод ${m.name}(${m.paramTypes.joinToString("")})${m.returnType} не найден в $descriptor"
            )
            existing.methods[i] = m
        }
    }

    fun removeMethod(descriptor: String, name: String, paramTypes: List<String>, returnType: String) {
        val existing = tree.findClass(descriptor)
            ?: throw IllegalArgumentException("Класс не найден: $descriptor")
        val removed = existing.methods.removeAll {
            it.name == name && it.paramTypes == paramTypes && it.returnType == returnType
        }
        if (!removed) throw IllegalArgumentException("Метод $name(${paramTypes.joinToString("")})$returnType не найден")
    }

    private fun parse(smali: String): SmaliClass =
        SmaliParser.parseClass(smali)
            ?: throw IllegalArgumentException("В тексте нет класса smali (.class)")

    private fun sameMethod(a: SmaliMethod, b: SmaliMethod): Boolean =
        a.name == b.name && a.paramTypes == b.paramTypes && a.returnType == b.returnType

    private fun sameField(a: SmaliField, b: SmaliField): Boolean =
        a.name == b.name && a.type == b.type
}