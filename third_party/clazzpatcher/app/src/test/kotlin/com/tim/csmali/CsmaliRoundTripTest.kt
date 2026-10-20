package com.tim.csmali

import com.tim.casm.frames.Bytecode
import com.tim.casm.hierarchy.ClassHierarchy
import com.tim.casm.hierarchy.ClassPathHierarchy
import com.tim.casm.hierarchy.CompositeHierarchy
import com.tim.casm.hierarchy.MapHierarchy
import com.tim.clazz.model.ClassFile
import com.tim.clazz.model.attributes.Code
import com.tim.clazz.model.attributes.LineNumberTable
import com.tim.clazz.model.attributes.LocalVariableTable
import com.tim.clazz.parser.ClassReader
import com.tim.clazz.writer.ClassWriter
import com.tim.testutil.ClassCorpus
import com.tim.testutil.JvmVerifier
import com.tim.clazz.repoRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * .class → csmali → .class на реальных классах:
 * текст стабилен, байткод, лимиты, таблицы исключений/строк/локалов совпадают с исходными,
 * а собранные классы (с заново посчитанными фреймами) принимает верификатор HotSpot.
 */
class CsmaliRoundTripTest {

    @TempDir
    lateinit var tmp: File

    private class Outcome(val assembled: Map<String, ByteArray>, val methods: Int, val millis: Long)

    private fun hierarchyOf(corpus: Map<String, ByteArray>): ClassHierarchy {
        val map = MapHierarchy()
        for (bytes in corpus.values) {
            val cf = ClassReader().read(bytes, "?")
            map.add(cf.thisClassName, cf.superClassName, cf.accessFlags and 0x0200 != 0)
        }
        return CompositeHierarchy(listOf(map, Smali.defaultHierarchy))
    }

    private fun roundTrip(corpus: Map<String, ByteArray>): Outcome {
        val hierarchy = hierarchyOf(corpus)
        val out = LinkedHashMap<String, ByteArray>()
        var methods = 0
        val start = System.nanoTime()
        for ((name, bytes) in corpus) {
            val original = ClassReader().read(bytes, name)
            val text = Smali.classToSmali(original)
            val rebuilt = try {
                Smali.smaliToClass(text, hierarchy)
            } catch (e: Exception) {
                throw AssertionError("$name: сборка csmali: ${e.message}\n${context(text, e.message)}", e)
            }
            val rebuiltBytes = ClassWriter(rebuilt).toByteArray()
            val reread = ClassReader().read(rebuiltBytes, name)
            val text2 = Smali.classToSmali(reread)
            if (text != text2) throw AssertionError("$name: текст csmali нестабилен:\n${firstDiff(text, text2)}")
            methods += compareCode(name, original, reread)
            out[name] = rebuiltBytes
        }
        return Outcome(out, methods, (System.nanoTime() - start) / 1_000_000)
    }

    /** Байткод, лимиты и таблицы каждого метода совпадают с исходными. */
    private fun compareCode(name: String, a: ClassFile, b: ClassFile): Int {
        assertEquals(a.methods.size, b.methods.size, "$name: число методов")
        var n = 0
        for ((ma, mb) in a.methods.zip(b.methods)) {
            val where = "$name.${a.utf8(ma.nameIndex)}${a.utf8(ma.descriptorIndex)}"
            val ca = ma.attributes.filterIsInstance<Code>().firstOrNull()
            val cb = mb.attributes.filterIsInstance<Code>().firstOrNull()
            assertEquals(ca == null, cb == null, "$where: наличие Code")
            if (ca == null || cb == null) continue
            n++
            // Индексы пула у нового класса свои, поэтому сравниваются границы и опкоды инструкций,
            // а операнды — через совпадение текста csmali (см. roundTrip).
            if (ca.code.size != cb.code.size) throw AssertionError("$where: размер байткода ${ca.code.size} → ${cb.code.size}")
            val da = Bytecode.decode(ca.code)
            val db = Bytecode.decode(cb.code)
            for (i in 0 until maxOf(da.count, db.count)) {
                if (da.pc.getOrNull(i) != db.pc.getOrNull(i) || da.op.getOrNull(i) != db.op.getOrNull(i) || da.wide.getOrNull(i) != db.wide.getOrNull(i)) {
                    throw AssertionError("$where: инструкция #$i различается (pc ${da.pc.getOrNull(i)}/${db.pc.getOrNull(i)})")
                }
            }
            assertEquals(ca.maxStack, cb.maxStack, "$where: max_stack")
            assertEquals(ca.maxLocals, cb.maxLocals, "$where: max_locals")
            assertEquals(
                ca.exceptionTable.map { listOf(it.startPc, it.endPc, it.handlerPc, if (it.catchType == 0) "*" else a.className(it.catchType)) },
                cb.exceptionTable.map { listOf(it.startPc, it.endPc, it.handlerPc, if (it.catchType == 0) "*" else b.className(it.catchType)) },
                "$where: таблица исключений"
            )
            assertEquals(lines(ca), lines(cb), "$where: LineNumberTable")
            assertEquals(locals(a, ca), locals(b, cb), "$where: LocalVariableTable")
        }
        return n
    }

    /** Порядок записей LineNumberTable не значим (javac/kotlinc пишут их не по возрастанию pc). */
    private fun lines(c: Code) = c.attributes.filterIsInstance<LineNumberTable>()
        .flatMap { t -> t.entries.map { it.startPc to it.lineNumber } }
        .sortedWith(compareBy({ it.first }, { it.second }))

    private fun locals(cf: ClassFile, c: Code) = c.attributes.filterIsInstance<LocalVariableTable>()
        .flatMap { t -> t.entries.map { listOf(it.startPc, it.length, cf.utf8(it.nameIndex), cf.utf8(it.descriptorIndex), it.index) } }
        .sortedWith(compareBy({ it[0] as Int }, { it[4] as Int }, { it[2] as String }))

    /** Строки текста вокруг номера строки из сообщения парсера («строка N»). */
    private fun context(text: String, message: String?): String {
        val n = Regex("строка (\\d+)").find(message ?: "")?.groupValues?.get(1)?.toInt() ?: return text.take(2000)
        val lines = text.lines()
        return (maxOf(0, n - 25) until minOf(lines.size, n + 5)).joinToString("\n") { "${it + 1}: ${lines[it]}" }
    }

    private fun firstDiff(a: String, b: String): String {
        val la = a.lines()
        val lb = b.lines()
        for (i in 0 until maxOf(la.size, lb.size)) {
            if (la.getOrNull(i) != lb.getOrNull(i)) return "строка ${i + 1}:\n- ${la.getOrNull(i)}\n+ ${lb.getOrNull(i)}"
        }
        return "(различие не найдено)"
    }

    private fun verify(label: String, original: Map<String, ByteArray>, assembled: Map<String, ByteArray>, extraCp: List<File> = emptyList()) {
        val origDir = File(tmp, "$label-orig").also { JvmVerifier.writeClasses(it, original) }
        val newDir = File(tmp, "$label-new").also { JvmVerifier.writeClasses(it, assembled) }
        val before = JvmVerifier.verify(listOf(origDir) + extraCp, original.keys)
        val after = JvmVerifier.verify(listOf(newDir) + extraCp, assembled.keys)
        println("$label: исходные — $before; после csmali — $after")
        // Всё, что верифицировалось в исходном виде, обязано верифицироваться и после пересборки.
        val regressions = before.ok.filter { it !in after.ok }
        assertTrue(regressions.isEmpty()) {
            "$label: после csmali не проходят верификацию ${regressions.size} классов:\n" +
                regressions.take(20).joinToString("\n") { "$it: ${after.failed[it] ?: after.skipped[it]}" }
        }
        assertTrue(after.failed.isEmpty()) { "$label: $after" }
    }

    @Test
    fun fixtures() {
        val corpus = ClassCorpus.fixtures()
        val r = roundTrip(corpus)
        verify("fixtures", corpus, r.assembled)
    }

    @Test
    fun kotlinStdlib() {
        val corpus = ClassCorpus.kotlinStdlib()
        val r = roundTrip(corpus)
        println("kotlin-stdlib: ${corpus.size} классов, ${r.methods} методов с кодом, ${r.millis} мс")
        verify("stdlib", corpus, r.assembled)
    }

    @Test
    fun projectClasses() {
        val corpus = ClassCorpus.projectMain()
        val r = roundTrip(corpus)
        println("классы проекта: ${corpus.size} классов, ${r.methods} методов с кодом, ${r.millis} мс")
        verify("project", corpus, r.assembled, listOf(ClassCorpus.kotlinStdlibJar()))
    }

    /** Java 21: records, sealed, pattern switch, лямбды/indy, интерфейсы, аннотации, -parameters. */
    @Test
    fun javaFeaturesRunIdentically() {
        val src = File(repoRoot(), "app/src/test/fixtures-src/java/features")
        val origDir = File(tmp, "feat-orig")
        JvmVerifier.compileJava(src.walkTopDown().filter { it.name.endsWith(".java") }.toList(), origDir, "--release", "21", "-g", "-parameters")
        val corpus = origDir.walkTopDown().filter { it.name.endsWith(".class") }
            .associate { f -> f.readBytes().let { ClassCorpus.internalName(it) to it } }
        val r = roundTrip(corpus)
        val newDir = File(tmp, "feat-new").also { JvmVerifier.writeClasses(it, r.assembled) }
        val expected = JvmVerifier.runMain(listOf(origDir), "fx.feat.Features")
        val actual = JvmVerifier.runMain(listOf(newDir), "fx.feat.Features")
        assertEquals(0, expected.first, expected.second)
        assertEquals(expected, actual)
        verify("features", corpus, r.assembled)
    }

    /**
     * Находки прогона по кэшу Gradle: аннотация типа у компонента record, больше 127 строк под `ldc`,
     * переменные, открытые и закрытые на одном pc (как в inline-функциях Kotlin), — текст стабилен.
     */
    @Test
    fun recordTypeAnnotationsManyLdcStringsAndSamePcLocals() {
        val src = File(tmp, "g/src/g/G.java").apply { parentFile.mkdirs() }
        src.writeText(buildString {
            append("package g;\nimport java.lang.annotation.*;\n")
            append("@Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME) @interface N {}\n")
            append("record R(@N String name, int n) {}\n")
            append("public class G {\n  static String[] all() {\n    return new String[] {\n")
            for (k in 0 until 200) append("      \"s$k\",\n")
            append("    };\n  }\n}\n")
        })
        val out = File(tmp, "g/classes")
        JvmVerifier.compileJava(listOf(src), out, "--release", "21", "-g")
        val corpus = out.walkTopDown().filter { it.name.endsWith(".class") }
            .associate { f -> f.readBytes().let { ClassCorpus.internalName(it) to it } }.toMutableMap()
        assertTrue(".typeannotation" in Smali.classToSmali(ClassReader().read(corpus.getValue("g/R"))))
        // Две переменные открываются и закрываются на одном pc, в «неудобном» порядке слотов.
        val locals = Smali.smaliToClass(
            """
            .class public super Lg/L;
            .super Ljava/lang/Object;
            .version 52

            .method static f()I
                .limit stack 2
                .limit locals 3
                iconst_1
                istore_2
                iconst_2
                istore_1
                :s
                .local v2, "b":I
                .local v1, "a":I
                iload_1
                iload_2
                iadd
                istore_0
                .end local v1
                .end local v2
                .local v0, "c":I
                iload_0
                ireturn
            .end method
            """.trimIndent(), Smali.defaultHierarchy
        )
        corpus["g/L"] = ClassWriter(locals).toByteArray()
        roundTrip(corpus)
    }

    /** Record без компонент: атрибут Record пуст, но обязан сохраниться (директива `.record`). */
    @Test
    fun emptyRecordStaysRecord() {
        val src = File(repoRoot(), "app/src/test/fixtures-src/java/desugar/fx/ds/DesugarJdk.java")
        val origDir = File(tmp, "rec-orig")
        JvmVerifier.compileJava(listOf(src), origDir, "--release", "21", "-g")
        val corpus = origDir.walkTopDown().filter { it.name.endsWith(".class") }
            .associate { f -> f.readBytes().let { ClassCorpus.internalName(it) to it } }
        val text = Smali.classToSmali(ClassReader().read(corpus.getValue("fx/ds/DesugarJdk\$None")))
        assertTrue("\n.record\n" in text) { text }
        val r = roundTrip(corpus)
        val newDir = File(tmp, "rec-new").also { JvmVerifier.writeClasses(it, r.assembled) }
        val expected = JvmVerifier.runMain(listOf(origDir), "fx.ds.DesugarJdk")
        assertTrue("records true true 0" in expected.second) { expected.second }
        assertEquals(expected, JvmVerifier.runMain(listOf(newDir), "fx.ds.DesugarJdk"))
    }

    /** CONSTANT_Dynamic (Java 11+) и имена членов с пробелами (как у тестов Kotlin в обратных кавычках). */
    @Test
    fun condyAndSpacedNames() {
        val casm = com.tim.casm.Casm.create(61, Smali.defaultHierarchy)
        val pool = casm.state.pool
        val bsm = pool.methodHandle(6, pool.methodref(
            "java/lang/invoke/ConstantBootstraps", "primitiveClass",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Class;"
        ))
        val condy = pool.dynamic(casm.state.bootstrapPool.add(bsm, emptyList()), pool.nameAndType("J", "Ljava/lang/Class;"))
        val out = pool.fieldref("java/lang/System", "out", "Ljava/io/PrintStream;")
        val println = pool.methodref("java/io/PrintStream", "println", "(Ljava/lang/Object;)V")
        casm.useDsl {
            createClass("t/Spaced") {
                public()
                field("a b") { public(); static(); type("int"); constant(7) }
                method("my test") {
                    public(); static(); returnType("Ljava/lang/Object;")
                    code { ldc(condy); areturn() }
                }
                method("main") {
                    public(); static(); param("[Ljava/lang/String;")
                    code {
                        getstatic(out)
                        invokestatic(pool.methodref("t/Spaced", "my test", "()Ljava/lang/Object;"))
                        invokevirtual(println)
                        returnVoid()
                    }
                }
            }
        }
        val bytes = casm.toByteArray()
        val text = Smali.classToSmali(ClassReader().read(bytes, "Spaced"))
        assertTrue("`my test`" in text && "`a b`" in text) { text }
        val r = roundTrip(mapOf("t/Spaced" to bytes))
        val dir = File(tmp, "spaced").also { JvmVerifier.writeClasses(it, r.assembled) }
        assertEquals(0 to "long", JvmVerifier.runMain(listOf(dir), "t.Spaced").let { it.first to it.second.trim() })
    }

    /**
     * Необязательный корпус: `./gradlew :app:test -PclassCorpus=/путь/a.jar:/путь/каталог --tests '*CsmaliRoundTripTest.classCorpus'`.
     * Каждый jar/каталог: точный round-trip и верификация HotSpot (классы корпуса — в classpath проверки).
     */
    @Test
    fun classCorpus() {
        val setting = System.getProperty("clazzpatcher.classCorpus")?.takeIf { it.isNotBlank() }
        org.junit.jupiter.api.Assumptions.assumeTrue(setting != null, "корпус не задан (-PclassCorpus=...)")
        val inputs = setting!!.split(File.pathSeparator).map { File(it) }
        for ((i, input) in inputs.withIndex()) {
            val corpus = if (input.isDirectory) {
                input.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }
                    .associate { f -> f.readBytes().let { ClassCorpus.internalName(it) to it } }
            } else ClassCorpus.jarClasses(input).filterKeys { !it.endsWith("module-info") }
            val r = roundTrip(corpus)
            println("${input.name}: ${corpus.size} классов, ${r.methods} методов с кодом, ${r.millis} мс")
            verify("corpus$i", corpus, r.assembled, inputs.filterIndexed { j, _ -> j != i })
        }
    }

    /** Пустой и пересекающиеся диапазоны локалов одного слота (так пишут kotlinc и d2c). */
    @Test
    fun emptyAndOverlappingLocals() {
        val casm = com.tim.casm.Casm.create(52, Smali.defaultHierarchy)
        casm.useDsl {
            createClass("t/Locals") {
                public()
                method("m") {
                    public(); static(); returnType("I")
                    code {
                        label("L0"); iconst(1); istore(1)
                        label("L1"); iload(1); iconst(2); iadd(); istore(1)
                        label("L2"); iload(1); ireturn()
                        label("L3")
                    }
                    localVariable("L1", "L1", "empty", "I", 1)
                    localVariable("L1", "L3", "wide", "I", 1)
                    localVariable("L2", "L3", "inner", "I", 1)
                }
            }
        }
        val r = roundTrip(mapOf("t/Locals" to casm.toByteArray()))
        val dir = File(tmp, "locals").also { JvmVerifier.writeClasses(it, r.assembled) }
        val report = JvmVerifier.verify(listOf(dir), r.assembled.keys)
        assertTrue(report.ok.size == 1) { report.toString() }
    }

    @Test
    fun jdkBase() {
        val corpus = ClassCorpus.jdkModule("java.base")
        val r = roundTrip(corpus)
        println("java.base: ${corpus.size} классов, ${r.methods} методов с кодом, ${r.millis} мс")
    }
}
