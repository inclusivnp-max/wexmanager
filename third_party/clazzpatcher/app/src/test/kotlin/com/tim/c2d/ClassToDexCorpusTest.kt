package com.tim.c2d

import com.tim.clazz.parser.ClassReader
import com.tim.dex.writer.DexWriter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Реальный байткод: все классы kotlin-stdlib из classpath тестов (и jar/каталоги из переменной
 * `C2D_CORPUS`, через `File.pathSeparator`) → DEX → проверки валидности.
 */
class ClassToDexCorpusTest {

    @Test
    fun kotlinStdlib() {
        val jars = System.getProperty("java.class.path").split(File.pathSeparator)
            .filter { File(it).name.startsWith("kotlin-stdlib") && it.endsWith(".jar") }
        assertTrue(jars.isNotEmpty(), "kotlin-stdlib не найден в classpath тестов")
        for (jar in jars) convertAndCheck(File(jar))
    }

    /** Модуль java.base текущего JDK (≈7500 классов современного байткода: indy, VarHandle, nest, record). */
    @Test
    fun jdkJavaBase() {
        val fs = try {
            java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"))
        } catch (e: Exception) {
            null
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(fs != null, "jrt:/ недоступна (JDK 8)")
        val root = fs!!.getPath("/modules/java.base")
        val bytes = java.nio.file.Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".class") && !it.toString().endsWith("module-info.class") }
                .sorted().map { java.nio.file.Files.readAllBytes(it) }.collect(java.util.stream.Collectors.toList())
        }
        check(bytes, "java.base")
    }

    /** Разбиение на несколько DEX: с маленьким пределом id kotlin-stdlib ложится в несколько файлов. */
    @Test
    fun multiDexSplit() {
        val jar = System.getProperty("java.class.path").split(File.pathSeparator)
            .first { File(it).name.startsWith("kotlin-stdlib") && it.endsWith(".jar") }
        val classes = readClasses(File(jar)).map { ClassReader().read(it) }
        val dexes = ClassToDex.convertMultiDex(classes, C2dOptions(maxIdsPerDex = 6000))
        assertTrue(dexes.size > 2, "ожидалось несколько DEX, получено ${dexes.size}")
        var total = 0
        for ((i, dexFile) in dexes.withIndex()) {
            assertTrue(dexFile.methodIds.size <= 6000 && dexFile.fieldIds.size <= 6000 && dexFile.typeIds.size <= 6000 &&
                dexFile.strings.size <= 6000,
                "classes${i + 1}.dex: method_ids ${dexFile.methodIds.size}, field_ids ${dexFile.fieldIds.size}, " +
                    "type_ids ${dexFile.typeIds.size}, strings ${dexFile.strings.size}")
            val dex = DexWriter(dexFile).buildDex()
            C2dTestKit.assertValidDex(dex, "classes${i + 1}.dex")
            total += dexFile.classDefs.count { !C2dTestKit.isSyntheticLambda(dexFile, it) }
        }
        assertTrue(total == classes.size, "классов во всех DEX $total, а на входе ${classes.size}")
    }

    /** Перевод параллельный, но результат от числа потоков не зависит — байт в байт. */
    @Test
    fun parallelIsDeterministic() {
        val jar = System.getProperty("java.class.path").split(File.pathSeparator)
            .first { File(it).name.startsWith("kotlin-stdlib") && it.endsWith(".jar") }
        val classes = readClasses(File(jar)).map { ClassReader().read(it) }
        fun multi(threads: Int) = ClassToDex.convertMultiDex(classes, C2dOptions(maxIdsPerDex = 6000, threads = threads))
            .map { DexWriter(it).buildDex() }
        val one = multi(1)
        for (threads in listOf(3, 8)) {
            val many = multi(threads)
            assertTrue(one.size == many.size, "DEX: ${one.size} в одном потоке, ${many.size} в $threads")
            for (i in one.indices) assertTrue(one[i].contentEquals(many[i]), "classes${i + 1}.dex различается ($threads потоков)")
        }
        val part = classes.take(1500)
        val single = DexWriter(ClassToDex.convert(part, C2dOptions(threads = 1))).buildDex()
        assertTrue(single.contentEquals(DexWriter(ClassToDex.convert(part, C2dOptions(threads = 5))).buildDex()), "один DEX различается")
    }

    @Test
    fun externalCorpus() {
        val corpus = System.getenv("C2D_CORPUS") ?: return
        for (path in corpus.split(File.pathSeparator).filter { it.isNotBlank() }) convertAndCheck(File(path))
    }

    private fun convertAndCheck(source: File) = check(readClasses(source), source.name)

    private fun check(bytes: List<ByteArray>, name: String) {
        val started = System.nanoTime()
        val classes = bytes.map { ClassReader().read(it) }
        val parsed = System.nanoTime()
        val dexFiles = ClassToDex.convertMultiDex(classes)
        val converted = System.nanoTime()
        val dexes = dexFiles.map { DexWriter(it).buildDex() }
        val written = System.nanoTime()
        println("c2d $name: ${classes.size} классов, чтение ${ms(parsed - started)} мс, " +
            "перевод ${ms(converted - parsed)} мс, запись ${ms(written - converted)} мс, " +
            "DEX: ${dexes.size} файл(ов), ${dexes.sumOf { it.size } / 1024} КБ")
        for ((i, dex) in dexes.withIndex()) C2dTestKit.assertValidDex(dex, "$name → classes${i + 1}.dex")
    }

    private fun readClasses(source: File): List<ByteArray> =
        if (source.isDirectory) {
            source.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }
                .sortedBy { it.path }.map { it.readBytes() }.toList()
        } else ZipFile(source).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") && !it.name.endsWith("module-info.class") }
                .sortedBy { it.name }
                .map { e -> zip.getInputStream(e).use { it.readBytes() } }
                .toList()
        }

    private fun ms(nanos: Long) = nanos / 1_000_000
}
