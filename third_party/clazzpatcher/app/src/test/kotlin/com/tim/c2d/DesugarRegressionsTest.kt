package com.tim.c2d

import com.tim.c2d.desugar.IndyDesugar
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.attributes.BootstrapMethods
import com.tim.clazz.parser.ClassReader
import com.tim.clazz.writer.ClassWriter
import com.tim.testutil.JvmVerifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Регрессии рассахаривания invokedynamic ([IndyDesugar]): результат — корректные классы JVM, их вывод
 * в HotSpot (`-Xverify:all`) совпадает с выводом исходных классов.
 */
class DesugarRegressionsTest {
    @TempDir
    lateinit var tmp: File

    private fun resource(path: String) = File(javaClass.getResource("/c2d/desugar/$path")!!.toURI())

    private fun write(dir: File, classes: List<ClassFile>): File {
        JvmVerifier.writeClasses(dir, classes.associate { it.thisClassName to ClassWriter(it).toByteArray() })
        return dir
    }

    private fun smaliClasses(name: String): List<ClassFile> {
        val tree = com.tim.csmali.Smali.loadDir(resource(name))
        return com.tim.csmali.Smali.assembleAll(tree)
    }

    private fun compare(original: List<ClassFile>, main: String): String {
        val orig = JvmVerifier.runMain(listOf(write(File(tmp, "orig"), original)), main)
        val desugared = IndyDesugar.run(original)
        val after = JvmVerifier.runMain(listOf(write(File(tmp, "ds"), desugared)), main, "-Xverify:all")
        assertEquals(orig, after)
        return orig.second.trim()
    }

    @Test
    fun `метки switch вне sipush (символы от U+8000, числа больше 32767)`() {
        val out = File(tmp, "sw3")
        JvmVerifier.compileJava(listOf(resource("sw3/Main.java")), out, "--release", "21")
        val classes = out.walkTopDown().filter { it.name.endsWith(".class") }.map { ClassReader().read(it.readBytes()) }.toList()
        assertEquals("hangul low lakh neg", compare(classes, "t.Main"))
    }

    @Test
    fun `ссылка на открытый метод Object у чужого объекта — без метода доступа`() {
        val out = File(tmp, "objref")
        JvmVerifier.compileJava(listOf(resource("objref/Main.java")), out, "--release", "21")
        val classes = out.walkTopDown().filter { it.name.endsWith(".class") }.map { ClassReader().read(it.readBytes()) }.toList()
        assertEquals("true false true Other true", compare(classes, "m.Main"))
    }

    @Test
    fun `имя метода доступа не совпадает с уже существующим методом`() {
        compare(smaliClasses("coll"), "q.Main")
    }

    @Test
    fun `некорректные аргументы bootstrap-метода — invokedynamic остаётся`() {
        val classes = smaliClasses("crafted")
        val out = compare(classes, "z.Main")
        assertTrue("BootstrapMethodError" in out, out)
        val main = IndyDesugar.run(classes).first { it.thisClassName == "z/Main" }
        assertTrue(main.attributes.any { it is BootstrapMethods })
    }
}
