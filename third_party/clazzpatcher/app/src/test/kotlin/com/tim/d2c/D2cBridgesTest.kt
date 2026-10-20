package com.tim.d2c

import com.tim.clazz.model.ClassFile
import com.tim.dex.model.DexFile
import com.tim.dsmali.Smali
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.model.SmaliTree
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Синтетические методы второй фазы на рукописном smali (как после R8):
 * - `new D` + `A.<init>` в обход D, C и B — переходники по всей цепочке;
 * - в `C.<init>` вызов `A.<init>` на this в обход прямого суперкласса B;
 * - у B уже есть конструктор `(I, Void)` — переходник получает другой дескриптор;
 * - protected-метод и поле A из другого пакета на объекте не своего типа — методы доступа;
 * - перевод из двух DEX: B лежит во втором;
 * - цепочка через класс вне DEX: строгий режим — ошибка, нестрогий — предупреждение.
 * Проверка — запуск на JVM с `-Xverify:all` и верификатор HotSpot для всех классов.
 */
class D2cBridgesTest {

    private val main = listOf("A", "C", "D", "P", "Main")

    private fun dex(names: List<String>): DexFile {
        val classes = names.map { n ->
            val text = javaClass.getResource("/d2c/smali/bridges/$n.smali")!!.readText()
            SmaliParser.parseClass(text) ?: error("нет класса в $n.smali")
        }
        return Smali.assemble(SmaliTree(classes.toMutableList()))
    }

    private fun run(classes: Map<String, ByteArray>): List<String> {
        val jar = File(D2cTestSupport.tempDir("bridges"), "b.jar")
        D2cTestSupport.jar(classes, jar)
        val r = D2cTestSupport.runMain(jar, "c.Main")
        assertEquals(0, r.exit, r.out)
        val v = D2cTestSupport.verify(jar)
        assertTrue(v.verifyErrors.isEmpty() && v.linkErrors.isEmpty(), (v.verifyErrors + v.linkErrors).joinToString("\n"))
        return r.out.trim().lines()
    }

    /** 5 — A.<init> на new D; 7 — C.<init> через B; 0 — конструкторы B не выполнялись; 73 — protected; 1 — обычный B. */
    private val expected = listOf("5", "7", "0", "73", "1")

    @Test
    fun oneDex() {
        assertEquals(expected, run(DexToClass.convertToBytes(dex(main + "B"))))
    }

    @Test
    fun chainAcrossTwoDexes() {
        val classes = DexToClass.convertAllToBytes(listOf(dex(main), dex(listOf("B"))))
        assertEquals(expected, run(classes))
    }

    @Test
    fun bridgesAreDeterministicAndOnlyWhereNeeded() {
        val a = DexToClass.convertToBytes(dex(main + "B"), D2cOptions(threads = 1))
        val b = DexToClass.convertToBytes(dex(main + "B"), D2cOptions(threads = 8))
        assertEquals(a.keys, b.keys)
        for (k in a.keys) assertTrue(a[k]!!.contentEquals(b[k]!!), "класс $k отличается при разном числе потоков")

        val classes = DexToClass.convert(dex(main + "B")).associateBy { it.thisClassName }
        fun ctors(name: String) = classes.getValue(name).let { cf -> cf.methods.filter { cf.utf8(it.nameIndex) == "<init>" }.map { cf.utf8(it.descriptorIndex) } }
        val void = "Ljava/lang/Void;"
        // Переходники: D — 3 шага до A, C — 2, B — 1, но (I, Void) занят, поэтому 1 + 64.
        assertTrue("(I$void$void$void)V" in ctors("b/D"), ctors("b/D").toString())
        assertTrue("(I$void$void)V" in ctors("b/C"), ctors("b/C").toString())
        assertTrue("(I" + void.repeat(65) + ")V" in ctors("b/B"), ctors("b/B").toString())
        assertEquals(listOf("(I)V"), ctors("b/A"))
        assertTrue(methods(classes.getValue("b/A")).any { it.startsWith("access\$d2c\$call\$m(") }, methods(classes.getValue("b/A")).toString())
        assertTrue(methods(classes.getValue("b/A")).any { it.startsWith("access\$d2c\$get\$pf(") })
        assertTrue(methods(classes.getValue("b/A")).any { it.startsWith("access\$d2c\$put\$pf(") })
        assertTrue(methods(classes.getValue("c/Main")).none { "access" in it })
    }

    private fun methods(cf: ClassFile) = cf.methods.map { cf.utf8(it.nameIndex) + cf.utf8(it.descriptorIndex) }

    @Test
    fun externalClassInChain() {
        val e = assertThrows<IllegalStateException> { DexToClass.convert(dex(listOf("External")), D2cOptions(threads = 1)) }
        val message = generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { it.message ?: "" }
        assertTrue("java/util/ArrayList" in message && "Lb/E;" in message, message)

        val warnings = Collections.synchronizedList(ArrayList<String>())
        DexToClass.convert(dex(listOf("External")), D2cOptions(strict = false, warnings = { warnings.add(it) }))
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue("вызов оставлен как есть" in warnings[0], warnings[0])
    }
}
