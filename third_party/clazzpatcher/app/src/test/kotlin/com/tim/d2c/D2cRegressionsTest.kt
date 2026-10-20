package com.tim.d2c

import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.ConstantIntegerInfo
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.attributes.ConstantValue
import com.tim.clazz.model.attributes.InnerClasses
import com.tim.clazz.model.attributes.LocalVariableTable
import com.tim.clazz.parser.ClassReader
import com.tim.dex.model.DexFile
import com.tim.dsmali.Smali
import com.tim.dsmali.io.SmaliParser
import com.tim.dsmali.model.SmaliTree
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Регрессии d2c на рукописном smali (`/d2c/smali/regress/<случай>`): код, который ART принимает,
 * после перевода обязан загружаться и работать в HotSpot с `-Xverify:all`.
 */
class D2cRegressionsTest {

    private fun dex(case: String): DexFile {
        val dir = File(javaClass.getResource("/d2c/smali/regress/$case")!!.toURI())
        val classes = dir.listFiles()!!.filter { it.name.endsWith(".smali") }.sortedBy { it.name }.map { f ->
            SmaliParser.parseClass(f.readText()) ?: error("нет класса в ${f.name}")
        }
        return Smali.assemble(SmaliTree(classes.toMutableList()))
    }

    private fun dexOf(text: String): DexFile =
        Smali.assemble(SmaliTree(mutableListOf(SmaliParser.parseClass(text) ?: error("нет класса"))))

    /** Запуск [main] и верификация всех классов; строки вывода. */
    private fun run(classes: Map<String, ByteArray>, main: String): List<String> {
        val jar = File(D2cTestSupport.tempDir("regress"), "r.jar")
        D2cTestSupport.jar(classes, jar)
        val r = D2cTestSupport.runMain(jar, main)
        assertEquals(0, r.exit, r.out)
        val v = D2cTestSupport.verify(jar)
        assertTrue(v.verifyErrors.isEmpty() && v.linkErrors.isEmpty(), (v.verifyErrors + v.linkErrors).joinToString("\n"))
        return r.out.trim().lines()
    }

    private fun classes(dex: DexFile, options: D2cOptions = D2cOptions()): Map<String, ClassFile> =
        DexToClass.convert(dex, options).associateBy { it.thisClassName }

    private fun methods(cf: ClassFile) = cf.methods.map { cf.utf8(it.nameIndex) + cf.utf8(it.descriptorIndex) }

    private fun messages(e: Throwable) = generateSequence(e) { it.cause }.joinToString(" <- ") { it.message ?: "" }

    // ---- Код методов ----

    @Test
    fun `обработчик на первой инструкции метода`() {
        assertEquals(listOf("3"), run(DexToClass.convertToBytes(dex("handler0")), "Main"))
    }

    @Test
    fun `aget на null и слияние констант в if — тип по использованию`() {
        assertEquals(
            listOf("npe-float", "npe-double", "1.0", "-1.0", "-2.0", "10.0"),
            run(DexToClass.convertToBytes(dex("types")), "t.T")
        )
    }

    @Test
    fun `fill-array-data проверяет массив до записи`() {
        assertEquals(listOf("empty-ok", "empty-npe", "0"), run(DexToClass.convertToBytes(dex("fill")), "f.Main"))
    }

    @Test
    fun `невыразимые отладочные локалы не попадают в LocalVariableTable`() {
        val d = dex("locals")
        assertEquals(listOf("2"), run(DexToClass.convertToBytes(d), "l.Main"))
        val cf = classes(d).getValue("l/Main")
        val f = cf.methods.first { cf.utf8(it.nameIndex) == "f" }
        val lvt = f.attributes.filterIsInstance<Code>().single().attributes.filterIsInstance<LocalVariableTable>().single()
        assertEquals(listOf("ok"), lvt.entries.map { cf.utf8(it.nameIndex) })
    }

    @Test
    fun `код длиннее 64 КиБ — ошибка, а в нестрогом режиме заглушка`() {
        // Вызовы с аргументами из регистров выше v255: в JVM каждый — пять wide-загрузок (23 байта против 6 в DEX).
        val text = buildString {
            appendLine(".class public LBig;")
            appendLine(".super Ljava/lang/Object;")
            appendLine(".method public static f(IIIII)V")
            appendLine("    .registers 5")
            appendLine("    return-void")
            appendLine(".end method")
            appendLine(".method public static calls()V")
            appendLine("    .registers 310")
            for (r in 300..304) { appendLine("    const/16 v0, $r"); appendLine("    move/16 v$r, v0") }
            repeat(4000) { appendLine("    invoke-static/range {v300 .. v304}, LBig;->f(IIIII)V") }
            appendLine("    return-void")
            appendLine(".end method")
        }
        val d = dexOf(text)
        val e = assertThrows<IllegalStateException> { DexToClass.convert(d, D2cOptions(threads = 1)) }
        assertTrue("65535" in messages(e), messages(e))

        val warnings = Collections.synchronizedList(ArrayList<String>())
        val bytes = DexToClass.convertToBytes(d, D2cOptions(strict = false, warnings = { warnings.add(it) }))
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue("65535" in warnings[0], warnings[0])
        ClassReader().read(bytes.getValue("Big"), "Big")
    }

    @Test
    fun `таблица на 40 000 элементов переводится строкой в пуле`() {
        // Раньше — ошибка (40 000 поэлементных записей больше 64 КиБ); индексы и вне sipush.
        val text = buildString {
            appendLine(".class public LBig;")
            appendLine(".super Ljava/lang/Object;")
            appendLine(".method public static table()[B")
            appendLine("    .registers 1")
            appendLine("    const v0, 40000")
            appendLine("    new-array v0, v0, [B")
            appendLine("    fill-array-data v0, :data")
            appendLine("    return-object v0")
            appendLine("    :data")
            appendLine("    .array-data 1")
            repeat(40000) { appendLine("        0x${(it % 100).toString(16)}t") }
            appendLine("    .end array-data")
            appendLine(".end method")
            appendLine(".method public static main([Ljava/lang/String;)V")
            appendLine("    .registers 3")
            appendLine("    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;")
            appendLine("    invoke-static {}, LBig;->table()[B")
            appendLine("    move-result-object v1")
            appendLine("    invoke-static {v1}, Ljava/util/Arrays;->hashCode([B)I")
            appendLine("    move-result v1")
            appendLine("    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(I)V")
            appendLine("    return-void")
            appendLine(".end method")
        }
        val expected = ByteArray(40000) { (it % 100).toByte() }.contentHashCode()
        assertEquals(listOf(expected.toString()), run(DexToClass.convertToBytes(dexOf(text)), "Big"))
    }

    // ---- Семантика ART (из тестов art/test) ----

    @Test
    fun `invoke-super своего класса и непрямого суперинтерфейса — как в ART`() {
        // 425/553/556: поиск с суперкласса; 810: default-метод интерфейса, который реализует суперкласс.
        assertEquals(listOf("Base.foo", "Itf.bar"), run(DexToClass.convertToBytes(dex("art-invokesuper")), "Main"))
    }

    @Test
    fun `invoke-super на default-метод интерфейса JDK через суперкласс — интерфейс становится прямым`() {
        // SystemUI, Settings (Compose: Map.getOrDefault), NexusLauncher: вызывается default Map, не HashMap.
        val bytes = DexToClass.convertToBytes(dex("art-super-jdk-iface"))
        assertEquals(listOf("from-get", "dflt"), run(bytes, "Main"))
        val m = ClassReader().read(bytes.getValue("M"))
        assertTrue(m.interfaces.any { m.className(it) == "java/util/Map" }, "Map — в прямых суперинтерфейсах M")
    }

    @Test
    fun `регистр this переиспользуется до super — слот 0 держит this`() {
        // Bugle, Dialer: слияния и обработчики до super(), где this уже нигде не лежит, — VerifyError без копии.
        assertEquals(listOf("5", "1", "0", "-1", "5"), run(DexToClass.convertToBytes(dex("art-ctor-this-reused")), "Main"))
    }

    @Test
    fun `большие таблицы fill-array-data — строкой в пуле, чтобы метод поместился в 64 КиБ`() {
        // DevicePersonalization, Velvet: <clinit> с сотнями таблиц. Все типы элементов и крайние значения.
        val n = 3000
        val rnd = java.util.Random(7)
        val ints = IntArray(n) { if (it % 7 == 0) listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE, 0x80, 0xFFFF, 0x10000)[it / 7 % 7] else rnd.nextInt() }
        val longs = LongArray(n) { if (it % 5 == 0) listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x8000L)[it / 5 % 5] else rnd.nextLong() }
        val bytes = ByteArray(n) { (ints[it] xor (it * 31)).toByte() }
        val shorts = ShortArray(n) { (ints[it] ushr 7).toShort() }
        val floats = FloatArray(n) { java.lang.Float.intBitsToFloat(ints[(it * 13) % n]) }
        val doubles = DoubleArray(n) { java.lang.Double.longBitsToDouble(longs[(it * 17) % n]) }
        val sb = StringBuilder(".class public LBig;\n.super Ljava/lang/Object;\n")
        val arrays = listOf("I" to 4, "J" to 8, "B" to 1, "S" to 2, "C" to 2, "Z" to 1, "F" to 4, "D" to 8)
        for ((t, _) in arrays) sb.append(".field public static a$t:[$t\n")
        sb.append(".method static constructor <clinit>()V\n    .registers 2\n")
        for ((t, _) in arrays) {
            sb.append("    const/16 v0, $n\n    new-array v0, v0, [$t\n    fill-array-data v0, :d$t\n    sput-object v0, LBig;->a$t:[$t\n")
        }
        sb.append("    return-void\n")
        fun hex(v: Long) = (if (v < 0) "-0x" + (-v).toULong().toString(16) else "0x" + v.toString(16))
        for ((t, w) in arrays) {
            sb.append("    :d$t\n    .array-data $w\n")
            for (k in 0 until n) {
                val v: Long = when (t) {
                    "I" -> ints[k].toLong(); "J" -> longs[k]; "B" -> bytes[k].toLong(); "S" -> shorts[k].toLong()
                    "C" -> (shorts[k].toInt() and 0xFFFF).toLong(); "Z" -> (k % 3 % 2).toLong()
                    "F" -> floats[k].toRawBits().toLong(); else -> doubles[k].toRawBits()
                }
                val suffix = when (w) { 1 -> "t"; 2 -> "s"; 8 -> "L"; else -> "" }
                sb.append("        ").append(if (v == Long.MIN_VALUE) "0x8000000000000000" else hex(v)).append(suffix).append('\n')
            }
            sb.append("    .end array-data\n")
        }
        sb.append(".end method\n")
        sb.append(".method public static main([Ljava/lang/String;)V\n    .registers 3\n    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;\n")
        for ((t, _) in arrays) {
            sb.append("    sget-object v1, LBig;->a$t:[$t\n    invoke-static {v1}, Ljava/util/Arrays;->hashCode([$t)I\n    move-result v1\n")
            sb.append("    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(I)V\n")
        }
        sb.append("    return-void\n.end method\n")
        val bytesOut = DexToClass.convertToBytes(dexOf(sb.toString()))
        val expected = listOf(
            ints.contentHashCode(), longs.contentHashCode(), bytes.contentHashCode(), shorts.contentHashCode(),
            CharArray(n) { shorts[it].toInt().toChar() }.contentHashCode(), BooleanArray(n) { it % 3 % 2 == 1 }.contentHashCode(),
            floats.contentHashCode(), doubles.contentHashCode()
        ).map { it.toString() }
        assertEquals(expected, run(bytesOut, "Big"))
        val big = ClassReader().read(bytesOut.getValue("Big"))
        assertTrue(methods(big).any { it.startsWith("d2c\$fillD(") }, "методы раскладки данных добавлены")
    }

    @Test
    fun `invoke-direct C init без конструктора в C — конструктор предка`() {
        assertEquals(listOf("ok"), run(DexToClass.convertToBytes(dex("art-inherited-ctor")), "Main"))
    }

    @Test
    fun `φ констант как float и как int — хранятся биты`() {
        assertEquals(listOf("0"), run(DexToClass.convertToBytes(dex("art-constphi")), "TestCase"))
    }

    @Test
    fun `return не того вида в недостижимом коде и несуществующий тип catch`() {
        assertEquals(listOf("0"), run(DexToClass.convertToBytes(dex("art-return-after-throw")), "Main"))
        val warnings = Collections.synchronizedList(ArrayList<String>())
        val bytes = DexToClass.convertToBytes(dex("art-unresolved-catch"), D2cOptions(warnings = { warnings.add(it) }))
        assertEquals(listOf("ok"), run(bytes, "Main"))
        assertTrue(warnings.any { "ArithmException" in it }, warnings.toString())
    }

    @Test
    fun `invoke-super по ссылке на не-предка — NoSuchMethodError, суперкласс-интерфейс — без переходников`() {
        assertEquals(listOf("NoSuchMethodError"), run(DexToClass.convertToBytes(dex("art-super-unrelated")), "Z"))
        // Интерфейс в цепочке суперклассов: переходник в него не добавляется (иначе ClassFormatError).
        val classes = classes(dex("art-mismatched-super"))
        val defs = classes.getValue("Defs")
        assertTrue(defs.methods.none { defs.utf8(it.nameIndex) == "<init>" })
    }

    private fun fixtureDex(name: String): DexFile =
        com.tim.dex.parser.DexReader().read(javaClass.getResource("/d2c/dex/$name.dex")!!.readBytes())

    @Test
    fun `clone массива из default-метода после d8, null поверх ссылки, повтор метода`() {
        // 856: d8 выносит clone() массива в I$0.m(Object) с invoke-virtual Object.clone().
        assertEquals(listOf("x"), run(DexToClass.convertToBytes(fixtureDex("clone-default")), "Main"))
        // 036: `o = null` — ссылка в слоте JVM очищается, объект собирается.
        assertEquals(listOf("collected"), run(DexToClass.convertToBytes(fixtureDex("nullstore")), "Main"))
        // 097: повтор method_idx в class_data — берётся первое определение.
        val warnings = Collections.synchronizedList(ArrayList<String>())
        val bytes = DexToClass.convertToBytes(fixtureDex("dupmethod"), D2cOptions(warnings = { warnings.add(it) }))
        assertEquals(listOf("Success!"), run(bytes, "Main"))
        assertTrue(warnings.any { "повторное определение" in it }, warnings.toString())
    }

    @Test
    fun `поля — сначала экземпляра, затем статические (как getDeclaredFields в ART)`() {
        val d = dexOf(".class public LF;\n.super Ljava/lang/Object;\n.field public static a:I\n.field public b:J\n.field public static c:Ljava/lang/String;\n.field public d:I\n")
        val cf = classes(d).getValue("F")
        assertEquals(listOf("b", "d", "a", "c"), cf.fields.map { cf.utf8(it.nameIndex) })
    }

    // ---- Синтетические методы второй фазы ----

    @Test
    fun `protected через соседний класс — метод доступа`() {
        assertEquals(listOf("47"), run(DexToClass.convertToBytes(dex("sibling")), "c.P"))
        val a = classes(dex("sibling")).getValue("b/A")
        assertTrue(methods(a).any { it.startsWith("access\$d2c\$call\$m(") }, methods(a).toString())
        assertTrue(methods(a).any { it.startsWith("access\$d2c\$get\$pf(") }, methods(a).toString())
    }

    @Test
    fun `переходники к целям с параметром Void не совпадают`() {
        assertEquals(listOf("Z", "Z"), run(DexToClass.convertToBytes(dex("markers")), "v.Z"))
        val z = classes(dex("markers")).getValue("v/Z")
        val ctors = z.methods.filter { z.utf8(it.nameIndex) == "<init>" }.map { z.utf8(it.descriptorIndex) }
        assertEquals(3, ctors.toSet().size, ctors.toString())
    }

    @Test
    fun `методы доступа к методу get$x и полю x различаются`() {
        assertEquals(listOf("1"), run(DexToClass.convertToBytes(dex("names")), "c.P"))
    }

    // ---- Метаданные ----

    @Test
    fun `InnerClasses через границу DEX и для анонимных классов`() {
        val bytes = DexToClass.convertAllToBytes(listOf(dex("nested1"), dex("nested2")))
        assertEquals(listOf("null", "class p.O", "class p.O"), run(bytes, "p.Main"))
        val o = ClassReader().read(bytes.getValue("p/O"), "p/O")
        val entries = o.attributes.filterIsInstance<InnerClasses>().single().classes.associateBy { o.className(it.innerClassInfoIndex) }
        // Член из второго DEX — с его флагами (public static), а не 0.
        assertEquals(0x9, entries.getValue("p/O\$M").innerClassAccessFlags)
        // Анонимный — без внешнего класса и имени (как у javac).
        val anon = entries.getValue("p/O\$1")
        assertEquals(0, anon.outerClassInfoIndex)
        assertEquals(0, anon.innerNameIndex)
    }

    @Test
    fun `нули static_values — ConstantValue только у настоящих констант`() {
        val k = classes(dex("statics")).getValue("k/K")
        fun constant(name: String): Int? {
            val f = k.fields.first { k.utf8(it.nameIndex) == name }
            val cv = f.attributes.filterIsInstance<ConstantValue>().singleOrNull() ?: return null
            return (k.constantPool[cv.constantValueIndex] as ConstantIntegerInfo).value
        }
        assertNull(constant("a"), "a присваивается в <clinit>")
        assertEquals(0, constant("z"))
        assertNull(constant("c"), "c не final")
        assertEquals(5, constant("b"))
    }

    @Test
    fun `record и sealed — атрибуты действуют, компоненты с сигнатурой и аннотациями`() {
        val bytes = DexToClass.convertToBytes(dex("record"))
        assertEquals(listOf("true", "Ljava/util/List<Ljava/lang/String;>;", "annotated", "true"), run(bytes, "r.Main"))
        assertTrue(ClassReader().read(bytes.getValue("r/P"), "r/P").majorVersion >= 60)
        assertTrue(ClassReader().read(bytes.getValue("r/S"), "r/S").majorVersion >= 61)
    }

    @Test
    fun `версия класса повышается только там, где нужна`() {
        val bytes = DexToClass.convertToBytes(dex("target"), D2cOptions(majorVersion = 50))
        assertEquals(listOf("7", "7-x"), run(bytes, "t.Main"))
        fun major(name: String) = ClassReader().read(bytes.getValue(name), name).majorVersion
        assertEquals(52, major("t/Main"), "invokestatic метода интерфейса")
        assertEquals(52, major("t/I"))
        assertEquals(50, major("t/Plain"))
    }
}
