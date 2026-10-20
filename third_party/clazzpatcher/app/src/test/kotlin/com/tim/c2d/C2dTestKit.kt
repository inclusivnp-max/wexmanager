package com.tim.c2d

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.tim.clazz.parser.ClassReader
import com.tim.dex.data.DexMagic
import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import java.io.File
import java.io.StringWriter
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Инструменты тестов c2d: компиляция Java-исходников javac, преобразование в DEX и проверка
 * результата: [DalvikVerifier] (типы регистров и доступ, как у ART), эталонный baksmali (разбор
 * каждой инструкции), dexdump (структурный верификатор libdexfile) и d8 (читает DEX и строит IR).
 * dexdump и d8 берутся из Android SDK (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, `~/Android/Sdk`,
 * для d8 ещё `D8_JAR`); если SDK нет, эти две проверки пропускаются.
 */
object C2dTestKit {

    val projectDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    val fixturesSrc: File = File(projectDir, "app/src/test/fixtures-src/java")

    // ---- javac ----

    /** Скомпилировать исходники (путь → текст); результат — байты всех .class. */
    fun javac(sources: Map<String, String>, release: Int = 8, debug: String = "-g"): List<ByteArray> {
        val dir = Files.createTempDirectory("c2d-src").toFile()
        try {
            val files = sources.map { (path, text) -> File(dir, path).also { it.parentFile.mkdirs(); it.writeText(text) } }
            return javacFiles(files, release, debug)
        } finally {
            dir.deleteRecursively()
        }
    }

    fun javacFiles(files: List<File>, release: Int = 8, debug: String = "-g", extra: List<String> = emptyList()): List<ByteArray> {
        val out = Files.createTempDirectory("c2d-classes").toFile()
        try {
            val cmd = listOf("javac", "--release", release.toString(), debug, "-nowarn", "-encoding", "UTF-8", "-d", out.path) +
                extra + files.map { it.path }
            run(cmd, "javac")
            return out.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.sortedBy { it.path }
                .map { it.readBytes() }.toList()
        } finally {
            out.deleteRecursively()
        }
    }

    /**
     * Скомпилировать и выполнить программу на JVM: вывод `java -cp <классы> [mainClass]`
     * и байты всех классов — эталон для сравнения с исполнением DEX.
     */
    fun compileAndRun(sources: Map<String, String>, mainClass: String, release: Int = 8): Pair<String, List<ByteArray>> {
        val src = Files.createTempDirectory("c2d-src").toFile()
        val out = Files.createTempDirectory("c2d-classes").toFile()
        try {
            val files = sources.map { (path, text) -> File(src, path).also { it.parentFile.mkdirs(); it.writeText(text) } }
            run(listOf("javac", "--release", release.toString(), "-g", "-nowarn", "-encoding", "UTF-8", "-d", out.path) + files.map { it.path }, "javac")
            val java = File(System.getProperty("java.home"), "bin/java").path
            val p = ProcessBuilder(java, "-Xverify:all", "-cp", out.path, mainClass).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) fail<Unit>("java $mainClass: код ${p.exitValue()}\n$output")
            val classes = out.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.sortedBy { it.path }.map { it.readBytes() }.toList()
            return output to classes
        } finally {
            src.deleteRecursively()
            out.deleteRecursively()
        }
    }

    // ---- преобразование ----

    fun convert(classes: List<ByteArray>, options: C2dOptions = C2dOptions()): ByteArray =
        DexWriter(ClassToDex.convert(classes.map { ClassReader().read(it) }, options)).buildDex()

    const val SYNTHETIC_LAMBDA = "\$\$ExternalSyntheticLambda"

    fun isSyntheticLambda(dex: com.tim.dex.model.DexFile, c: com.tim.dex.model.ClassDefItem): Boolean =
        SYNTHETIC_LAMBDA in dex.strings[dex.typeIds[c.classIdx].descriptorIdx]

    /** Все проверки валидности DEX; [what] — для сообщений. */
    fun assertValidDex(dex: ByteArray, what: String, expectedClasses: Int? = null) {
        val parsed = try {
            DexReader().read(dex)
        } catch (e: Exception) {
            fail<Nothing>("$what: DexReader не читает результат: $e")
        }
        // Рассахаривание лямбд добавляет синтетические классы Хозяин$$ExternalSyntheticLambdaN.
        if (expectedClasses != null) assertEquals(expectedClasses, parsed.classDefs.count { !isSyntheticLambda(parsed, it) }, "$what: число классов")
        val typeErrors = DalvikVerifier.verify(parsed)
        if (typeErrors.isNotEmpty()) {
            fail<Unit>("$what: ошибки типов регистров (${typeErrors.size}):\n" + typeErrors.take(15).joinToString("\n"))
        }
        baksmali(dex, what)
        dexdump(dex, what)
        d8(dex, DexMagic.getVersion(parsed.header.magic), what)
    }

    // ---- baksmali ----

    /** Текст smali всех классов эталонным baksmali 3.0.9 (падает на некорректном байткоде). */
    fun baksmali(dex: ByteArray, what: String): Map<String, String> {
        val file = try {
            DexBackedDexFile(null, dex)
        } catch (e: Exception) {
            fail<Nothing>("$what: эталонный dexlib2 не читает DEX: $e")
        }
        val options = BaksmaliOptions().apply {
            parameterRegisters = true
            localsDirective = false
            debugInfo = true
            codeOffsets = false
            accessorComments = false
            implicitReferences = false
            registerInfo = 0
        }
        val out = sortedMapOf<String, String>()
        for (cls in file.classes) {
            try {
                val sw = StringWriter()
                val writer = BaksmaliWriter(sw, null)
                ClassDefinition(options, cls).writeTo(writer)
                writer.flush()
                out[cls.type] = sw.toString()
            } catch (e: Exception) {
                fail<Nothing>("$what: baksmali падает на классе ${cls.type}: $e")
            }
        }
        return out
    }

    // ---- dexdump ----

    private val dexdumpExe: File? by lazy { newestBuildTool("dexdump") }

    fun dexdump(dex: ByteArray, what: String) {
        val exe = dexdumpExe ?: return
        val file = Files.createTempFile("c2d", ".dex").toFile()
        try {
            file.writeBytes(dex)
            val p = ProcessBuilder(exe.path, "-d", file.path).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            val errors = p.errorStream.bufferedReader().readLines()
            if (!p.waitFor(120, TimeUnit.SECONDS)) fail<Unit>("$what: dexdump не завершился")
            val bad = errors.filter { it.contains(" E dexdump") || it.contains("Failure") }
            if (p.exitValue() != 0 || bad.isNotEmpty()) {
                fail<Unit>("$what: dexdump отвергает DEX (код ${p.exitValue()}):\n" + (bad.ifEmpty { errors }).take(20).joinToString("\n"))
            }
        } finally {
            file.delete()
        }
    }

    // ---- d8 ----

    private val d8Jar: File? by lazy {
        System.getenv("D8_JAR")?.let { File(it) }?.takeIf { it.isFile }
            ?: newestBuildTool("lib/d8.jar")
    }

    /** d8 принимает DEX на вход: строит IR и выводит типы регистров, падает на противоречиях. */
    fun d8(dex: ByteArray, version: String, what: String) {
        val jar = d8Jar ?: return
        val minApi = when (version) {
            "035" -> 21
            "037" -> 24
            "038" -> 26
            else -> 28
        }
        val dir = Files.createTempDirectory("c2d-d8").toFile()
        try {
            val input = File(dir, "in.dex").apply { writeBytes(dex) }
            val outDir = File(dir, "out").apply { mkdirs() }
            val java = File(System.getProperty("java.home"), "bin/java").path
            val cmd = listOf(java, "-cp", jar.path, "com.android.tools.r8.D8", "--min-api", minApi.toString(),
                "--output", outDir.path, input.path)
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(300, TimeUnit.SECONDS)) fail<Unit>("$what: d8 не завершился")
            if (p.exitValue() != 0 || output.contains("Error")) {
                fail<Unit>("$what: d8 отвергает DEX (код ${p.exitValue()}):\n${output.take(4000)}")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- вспомогательное ----

    private fun newestBuildTool(relative: String): File? {
        val roots = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
            File(System.getProperty("user.home"), "Android/Sdk").path)
        for (root in roots) {
            val found = File(root, "build-tools").listFiles().orEmpty()
                .map { File(it, relative) }.filter { it.isFile }
                .maxWithOrNull(compareBy<File> { version(it.path.substringAfter("build-tools/").substringBefore('/')) })
            if (found != null) return found
        }
        return null
    }

    private fun version(s: String): Long {
        var v = 0L
        for (part in s.split('.', '-').take(3)) v = v * 1000 + (part.toIntOrNull() ?: 0)
        return v
    }

    private fun run(cmd: List<String>, what: String) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(300, TimeUnit.SECONDS)) fail<Unit>("$what не завершился")
        if (p.exitValue() != 0) fail<Unit>("$what: код ${p.exitValue()}\n${output.take(4000)}")
    }
}
