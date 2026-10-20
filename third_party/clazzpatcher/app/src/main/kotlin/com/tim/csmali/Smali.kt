package com.tim.csmali

import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.clazz.model.ClassFile
import com.tim.clazz.parser.ClassReader
import com.tim.clazz.writer.ClassWriter
import com.tim.csmali.asm.TreeToClass
import com.tim.csmali.disasm.ClassToTree
import com.tim.csmali.io.SmaliFiles
import com.tim.csmali.io.SmaliParser
import com.tim.csmali.io.SmaliWriter
import com.tim.csmali.model.SmaliClass
import com.tim.csmali.model.SmaliTree
import com.tim.csmali.patch.SmaliPatch
import com.tim.csmali.util.Parallel
import java.io.File

/**
 * Фасад csmali: JVM `.class` ⇄ текст в синтаксисе smali.
 *
 * [ClassHierarchy] при сборке нужна для точных фреймов StackMapTable в местах слияния разных
 * классов; по умолчанию — классы JDK из `jrt:/` (если доступны), неизвестные — `java/lang/Object`.
 */
object Smali {

    /** Иерархия по умолчанию: JDK текущей JVM (на Android/Java 8 — пустая). */
    val defaultHierarchy: ClassHierarchy by lazy { com.tim.casm.hierarchy.ClassPathHierarchy.jdk() }

    fun disassemble(clazz: File): SmaliTree = disassemble(ClassReader().read(clazz))

    fun disassemble(clazz: ClassFile): SmaliTree = ClassToTree().build(clazz)

    fun assemble(tree: SmaliTree, hierarchy: ClassHierarchy = defaultHierarchy): ClassFile =
        TreeToClass(hierarchy).build(tree)

    fun assemble(clazz: SmaliClass, hierarchy: ClassHierarchy = defaultHierarchy): ClassFile =
        TreeToClass(hierarchy).build(clazz)

    fun assemble(tree: SmaliTree, out: File, hierarchy: ClassHierarchy = defaultHierarchy) {
        ClassWriter(assemble(tree, hierarchy)).write(out)
    }

    fun assembleToBytes(clazz: SmaliClass, hierarchy: ClassHierarchy = defaultHierarchy): ByteArray =
        ClassWriter(assemble(clazz, hierarchy)).toByteArray()

    /** Все классы параллельно: [ClassFile] → дерево (порядок сохраняется). */
    fun disassembleAll(classes: List<ClassFile>): SmaliTree =
        SmaliTree(Parallel.map(classes) { ClassToTree().build(it).classes[0] }.toMutableList())

    /** Все классы дерева параллельно → [ClassFile] (порядок сохраняется). */
    fun assembleAll(tree: SmaliTree, hierarchy: ClassHierarchy = defaultHierarchy): List<ClassFile> {
        val builder = TreeToClass(hierarchy)
        return Parallel.map(tree.classes) { builder.build(it) }
    }

    /** Каталог `<пакет>/<Имя>.smali`; возвращает число файлов. */
    fun saveDir(tree: SmaliTree, dir: File): Int = SmaliFiles.writeDir(tree, dir)

    fun loadDir(dir: File): SmaliTree = SmaliFiles.readDir(dir)

    fun save(tree: SmaliTree, outputFile: File) = SmaliFiles.write(tree, outputFile)

    fun save(clazz: SmaliClass, outputFile: File) = SmaliFiles.writeClass(clazz, outputFile)

    fun load(inputFile: File): SmaliTree = SmaliFiles.read(inputFile)

    fun loadClass(inputFile: File): SmaliClass = SmaliFiles.readClass(inputFile)

    fun parse(smali: String): SmaliClass =
        SmaliParser.parseClass(smali) ?: throw IllegalArgumentException("В тексте нет класса smali (.class)")

    fun write(clazz: SmaliClass): String = SmaliWriter.write(clazz)

    fun patch(tree: SmaliTree, block: SmaliPatch.() -> Unit): SmaliTree {
        SmaliPatch(tree).block()
        return tree
    }

    fun patch(tree: SmaliTree, smali: String): SmaliTree = patch(tree) { applyText(smali) }

    /** `.class` → текст csmali. */
    fun classToSmali(clazz: ClassFile): String = SmaliWriter.write(disassemble(clazz).classes[0])

    fun classToSmali(clazz: File): String = classToSmali(ClassReader().read(clazz))

    /** Текст csmali → `.class`. */
    fun smaliToClass(smali: String, hierarchy: ClassHierarchy = defaultHierarchy): ClassFile =
        assemble(parse(smali), hierarchy)
}
