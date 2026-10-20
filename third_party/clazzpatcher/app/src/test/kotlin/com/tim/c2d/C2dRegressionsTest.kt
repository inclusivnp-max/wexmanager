package com.tim.c2d

import com.tim.clazz.writer.ClassWriter
import com.tim.dex.parser.DexReader
import com.tim.testutil.JvmVerifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * Регрессии c2d из аудита против ART (эмулятор API 35): вывод программы после .class → DEX
 * в [DexInterpreter] совпадает с HotSpot, DEX проходит проверки ([C2dTestKit.assertValidDex]).
 */
class C2dRegressionsTest {
    @TempDir
    lateinit var tmp: File

    private fun resource(path: String) = File(javaClass.getResource("/c2d/regress/$path")!!.toURI())

    private fun check(case: String, main: String) {
        val classes = com.tim.csmali.Smali.assembleAll(com.tim.csmali.Smali.loadDir(resource(case)))
        val bytes = classes.associate { it.thisClassName to ClassWriter(it).toByteArray() }
        val dir = File(tmp, case).also { JvmVerifier.writeClasses(it, bytes) }
        val expected = JvmVerifier.runMain(listOf(dir), main, "-Xverify:all")
        assertEquals(0, expected.first, expected.second)
        val dex = C2dTestKit.convert(bytes.values.toList())
        C2dTestKit.assertValidDex(dex, case, expectedClasses = classes.size)
        assertEquals(expected.second, DexInterpreter(DexReader().read(dex)).runMain("L$main;"), "$case: вывод после .class → DEX")
    }

    /** dup_x1/dup_x2/dup2_x1/dup2_x2 во всех формах, в том числе «два cat1 поверх long». */
    @Test
    fun dupForms() = check("dup", "St")

    /** invokespecial собственного виртуального метода: без виртуального выбора (private-копия). */
    @Test
    fun invokespecialSelf() = check("spec", "A")

    /**
     * Вложенный monitorenter на том же локале при this выше v15: копии this во временных регистрах
     * затираются после захвата (иначе ART: «failed lock verification», проверка — в ArtRunTest).
     */
    @Test
    fun nestedMonitorCopies() = check("lock", "Lk")

    /** jsr/ret (версия 49): finally-подпрограммы, вложенные, с циклом и обработчиками — встраиваются. */
    @Test
    fun jsrSubroutinesInlined() = check("jsr", "Js")

    /** Маркеры InlineMarker из тел inline-функций Kotlin не переводятся (проверка блокировок — в ArtRunTest). */
    @Test
    fun kotlinInlineMarkersDropped() {
        check("inline-marker", "Km")
        val classes = com.tim.csmali.Smali.assembleAll(com.tim.csmali.Smali.loadDir(resource("inline-marker")))
        val dex = DexReader().read(C2dTestKit.convert(classes.map { ClassWriter(it).toByteArray() }))
        assertEquals(0, dex.methodIds.count { dex.strings[dex.typeIds[it.classIdx].descriptorIdx] == "Lkotlin/jvm/internal/InlineMarker;" && dex.strings[it.nameIdx] != "<init>" &&
            dex.classDefs.none { c -> c.classIdx == it.classIdx } }, "вызовы маркеров не остались")
    }

    /**
     * long/double пишется в слот, который никто не читает, но который — вторая половина живой пары:
     * запись отбрасывается (пара легла бы за конец кадра). Найдено дифференциальным фаззингом.
     */
    @Test
    fun wideStoreIntoUnreadSlot() = check("wide-dead", "Wd")

    /**
     * Метод начинается с головы цикла, слот параметра на обратной дуге — другого типа: копия параметра
     * в прологе берёт тип из сигнатуры (APK эмулятора: «значение типа top нельзя держать в регистре»).
     */
    @Test
    fun paramCopyWhenEntryIsLoopHeader() = check("loop-entry", "Le")

    /**
     * checkcast на месте (слот дальше не читается), приведённое значение на стеке через monitorenter по
     * копии объекта: передача блокировки в регистр слота сначала выносит ссылку стека (аудит; проверка ART —
     * в ArtRunTest).
     */
    @Test
    fun castInPlaceThenMonitorOnCopy() = check("cast-lock", "Cl")

    /** synchronized-метод с кадром больше 256 регистров: регистр блокировки — в 8-битных полях (аудит). */
    @Test
    fun synchronizedMethodWithHugeFrame() {
        val names = (0 until 260).map { "a$it" }
        val source = """
            public class Main {
              synchronized int f(int x) {
                int ${names.mapIndexed { k, n -> "$n = x + $k" }.joinToString(", ")};
                return ${names.joinToString(" + ")};
              }
              static synchronized long g(long y) {
                long ${names.take(140).mapIndexed { k, n -> "$n = y * $k" }.joinToString(", ")};
                return ${names.take(140).joinToString(" ^ ")};
              }
              public static void main(String[] args) { System.out.println(new Main().f(3) + " " + g(7L)); }
            }
        """.trimIndent()
        val (expected, classes) = C2dTestKit.compileAndRun(mapOf("Main.java" to source), "Main", 8)
        val dex = C2dTestKit.convert(classes)
        C2dTestKit.assertValidDex(dex, "sync-huge", expectedClasses = classes.size)
        assertEquals(expected, DexInterpreter(DexReader().read(dex)).runMain("LMain;"))
    }

    /** ConstantValue boolean: младший бит (2 → false). */
    @Test
    fun booleanConstantValue() = check("cv", "Cv")

    /**
     * try длиннее 65535 code units: несколько try_item с одним обработчиком. Тело — записи разных
     * long-констант: 4 байта JVM (`ldc2_w; lstore`) дают 5 кодовых единиц `const-wide`, так что при
     * пределе кода JVM в 64 КиБ try в DEX длиннее 65535 единиц.
     */
    @Test
    fun tryLongerThanU2() {
        val lines = (0 until 14_000).joinToString("\n") { "      l = ${0x0123456789000000L + it * 0x10001L}L;" }
        val source = """
            public class Main {
              static long big(int a, int b) {
                long l = 0;
                try {
            $lines
                  a = a / b;
                  return a + l;
                } catch (ArithmeticException e) {
                  return -a + l;
                }
              }
              public static void main(String[] args) { System.out.println(big(1, 3) + " " + big(7, 0)); }
            }
        """.trimIndent()
        val (expected, classes) = C2dTestKit.compileAndRun(mapOf("Main.java" to source), "Main", 8)
        val dex = C2dTestKit.convert(classes)
        C2dTestKit.assertValidDex(dex, "try", expectedClasses = classes.size)
        val parsed = DexReader().read(dex)
        assert(parsed.codeItems.any { it.tries.size > 1 && it.tries.all { t -> t.insnCount <= 0xFFFF } })
        assertEquals(expected, DexInterpreter(parsed).runMain("LMain;"))
    }
}
