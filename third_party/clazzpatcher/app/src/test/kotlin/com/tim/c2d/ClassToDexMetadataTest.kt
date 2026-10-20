package com.tim.c2d

import com.tim.c2d.meta.SystemAnnotations
import com.tim.dex.data.EncodedValueType
import com.tim.dex.parser.DexReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Метаданные (аннотации, в том числе системные `dalvik.annotation.*`, static_values) совпадают
 * с тем, что делает из тех же .class эталонный d8.
 */
class ClassToDexMetadataTest {

    @Test
    fun annotationsMatchD8() {
        val d8 = d8Jar()
        assumeTrue(d8 != null, "d8.jar не найден в Android SDK")
        val sources = mapOf("c2d/Samples.java" to C2dSamples.SAMPLES, "c2d/Meta.java" to META)
        val src = Files.createTempDirectory("c2d-meta-src").toFile()
        val classesDir = Files.createTempDirectory("c2d-meta-classes").toFile()
        val d8Out = Files.createTempDirectory("c2d-meta-d8").toFile()
        try {
            val files = sources.map { (path, text) -> File(src, path).also { it.parentFile.mkdirs(); it.writeText(text) } }
            exec(listOf("javac", "--release", "8", "-g", "-parameters", "-nowarn", "-d", classesDir.path) + files.map { it.path })
            val classFiles = classesDir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.sortedBy { it.path }.toList()
            exec(listOf(File(System.getProperty("java.home"), "bin/java").path, "-cp", d8!!.path, "com.android.tools.r8.D8",
                "--min-api", "26", "--no-desugaring", "--output", d8Out.path) + classFiles.map { it.path })

            val theirs = C2dTestKit.baksmali(File(d8Out, "classes.dex").readBytes(), "d8")
            // d8 отбрасывает CLASS-аннотации — сравниваем в том же режиме.
            val ours = C2dTestKit.baksmali(
                C2dTestKit.convert(classFiles.map { it.readBytes() }, C2dOptions(invisibleAnnotations = false)), "c2d"
            )
            assertEquals(theirs.keys, ours.keys, "набор классов")
            val report = StringBuilder()
            for (cls in theirs.keys) {
                val expected = annotations(theirs[cls]!!)
                val actual = annotations(ours[cls]!!)
                if (expected != actual) {
                    report.append("$cls\n  только у d8: ${expected - actual.toSet()}\n  только у c2d: ${actual - expected.toSet()}\n")
                }
            }
            assertTrue(report.isEmpty(), "аннотации отличаются от d8:\n$report")
        } finally {
            src.deleteRecursively(); classesDir.deleteRecursively(); d8Out.deleteRecursively()
        }
    }

    @Test
    fun invisibleAnnotationsKeptAsBuild() {
        val classes = C2dTestKit.javac(mapOf("c2d/Meta.java" to META))
        val smali = C2dTestKit.baksmali(C2dTestKit.convert(classes), "Meta").getValue("Lc2d/Meta;")
        assertTrue(smali.contains(".annotation build Lc2d/Meta\$Invisible;"), smali)
    }

    @Test
    fun staticValues() {
        val classes = C2dTestKit.javac(mapOf("c2d/Consts.java" to CONSTS))
        val dex = DexReader().read(C2dTestKit.convert(classes))
        val cls = dex.classDefs.single()
        val values = dex.encodedArrayItems[cls.staticValuesIdx]
        val data = dex.classDataItems[cls.classDataIdx]
        val byName = data.staticFields.mapIndexed { i, f -> dex.strings[dex.fieldIds[f.fieldIdx].nameIdx] to values.getOrNull(i) }.toMap()
        assertEquals(EncodedValueType.VALUE_BOOLEAN, byName["Z"]!!.type)
        assertEquals(true, byName["Z"]!!.value)
        assertEquals((-3).toByte(), byName["B"]!!.value)
        assertEquals(300.toShort(), byName["S"]!!.value)
        assertEquals('q', byName["C"]!!.value)
        assertEquals(123456, byName["I"]!!.value)
        assertEquals(1L shl 40, byName["J"]!!.value)
        assertEquals(1.5f, byName["F"]!!.value)
        assertEquals(-2.25, byName["D"]!!.value)
        assertEquals("строка", dex.strings[(byName["STR"]!!.value as Number).toInt()])
    }

    @Test
    fun signatureSplitLikeD8() {
        assertEquals(listOf("Ljava/lang/Enum<", "Lc2d/Samples\$Color;", ">;"),
            SystemAnnotations.splitSignature("Ljava/lang/Enum<Lc2d/Samples\$Color;>;"))
        assertEquals(listOf("<T:", "Ljava/lang/Object;", ">([[TT;)", "Ljava/util/List<", "TT;>;"),
            SystemAnnotations.splitSignature("<T:Ljava/lang/Object;>([[TT;)Ljava/util/List<TT;>;"))
    }

    /** Аннотации каждого элемента класса: «элемент :: аннотация с содержимым», отсортированно. */
    private fun annotations(smali: String): List<String> {
        val out = ArrayList<String>()
        var current: StringBuilder? = null
        var member = ""
        for (raw in smali.lines()) {
            val l = raw.trim()
            if (l.startsWith(".method") || l.startsWith(".field") || l.startsWith(".class")) member = l
            if (l.startsWith(".param")) member = member.substringBefore(" | ") + " | " + l.substringBefore(',')
            when {
                l.startsWith(".annotation") -> current = StringBuilder(member).append(" :: ").append(l)
                current != null && l == ".end annotation" -> { out.add(current.toString()); current = null }
                current != null -> current.append(' ').append(l)
            }
        }
        return out.sorted()
    }

    private fun d8Jar(): File? {
        val root = File(System.getProperty("user.home"), "Android/Sdk/build-tools")
        return (System.getenv("D8_JAR")?.let { File(it) }?.takeIf { it.isFile })
            ?: root.listFiles().orEmpty().map { File(it, "lib/d8.jar") }.filter { it.isFile }.maxByOrNull { it.parentFile.parentFile.name }
    }

    private fun exec(cmd: List<String>) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        check(p.waitFor(300, TimeUnit.SECONDS) && p.exitValue() == 0) { "${cmd.first()}: ${output.take(3000)}" }
    }

    private companion object {
        val META = """
            package c2d;

            import java.lang.annotation.*;
            import java.util.*;

            public class Meta<T extends Comparable<T>> implements Iterable<T> {
                @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
                public @interface Info { String value() default "v"; long big() default 1L << 33; Class<?> type() default Object.class; ElementType kind() default ElementType.TYPE; Deprecated nested() default @Deprecated; double[] ds() default {1.5, -0.0}; }

                @Retention(RetentionPolicy.CLASS) public @interface Invisible { int value(); }

                @Info("field") @Invisible(1) protected List<? super T> items = new ArrayList<>();

                @Info(type = String.class, kind = ElementType.METHOD)
                public <E extends Exception> T first(@Info("p") @Invisible(2) int index, final String... rest) throws E, java.io.IOException {
                    return null;
                }

                public Iterator<T> iterator() {
                    return new Iterator<T>() {
                        public boolean hasNext() { return false; }
                        public T next() { throw new NoSuchElementException(); }
                    };
                }

                static class Nested<K> { class Deep {} }

                Runnable local() {
                    class Local implements Runnable { public void run() {} }
                    return new Local();
                }
            }
        """.trimIndent()

        val CONSTS = """
            package c2d;
            public class Consts {
                static final boolean Z = true;
                static final byte B = -3;
                static final short S = 300;
                static final char C = 'q';
                static final int I = 123456;
                static final long J = 1L << 40;
                static final float F = 1.5f;
                static final double D = -2.25;
                static final String STR = "строка";
            }
        """.trimIndent()
    }
}
