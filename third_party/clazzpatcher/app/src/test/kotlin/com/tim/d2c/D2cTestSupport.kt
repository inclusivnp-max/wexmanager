package com.tim.d2c

import com.tim.clazz.parser.ClassReader
import com.tim.dex.model.DexFile
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Внешние инструменты и проверки для тестов DEX → class. */
object D2cTestSupport {

    val java: String = File(System.getProperty("java.home"), "bin/java").path
    val javac: String = File(System.getProperty("java.home"), "bin/javac").let { if (it.isFile) it.path else "javac" }

    /** d8.jar из Android SDK (самая новая версия build-tools), null — нет. */
    val d8Jar: File? by lazy {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: (System.getProperty("user.home") + "/Android/Sdk")
        File(sdk, "build-tools").listFiles().orEmpty()
            .sortedByDescending { it.name }
            .map { File(it, "lib/d8.jar") }
            .firstOrNull { it.isFile }
    }

    class Result(val exit: Int, val out: String)

    fun run(vararg cmd: String, dir: File? = null, timeoutSec: Long = 300): Result {
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        if (dir != null) pb.directory(dir)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw IllegalStateException("Процесс не завершился за $timeoutSec с: ${cmd.joinToString(" ")}")
        }
        return Result(p.exitValue(), out)
    }

    private val created = Collections.synchronizedList(ArrayList<File>())

    init {
        Runtime.getRuntime().addShutdownHook(Thread { synchronized(created) { for (d in created) d.deleteRecursively() } })
    }

    /** Временный каталог (удаляется при завершении JVM тестов). */
    fun tempDir(prefix: String): File = Files.createTempDirectory("d2c-$prefix").toFile().also { created.add(it) }

    /** Компилирует исходники Java (карта путь → текст) в каталог классов. */
    fun javac(sources: Map<String, String>, release: Int = 8): File {
        val src = tempDir("src")
        for ((path, text) in sources) File(src, path).also { it.parentFile.mkdirs() }.writeText(text)
        val out = tempDir("classes")
        val files = src.walkTopDown().filter { it.name.endsWith(".java") }.map { it.path }.toList()
        val r = run(javac, "--release", release.toString(), "-g", "-d", out.path, *files.toTypedArray())
        check(r.exit == 0) { "javac:\n${r.out}" }
        return out
    }

    /** Классы → DEX эталонным d8 (min-api 26); [noDesugaring] — лямбды остаются invoke-custom. */
    fun d8(classes: File, minApi: Int = 26, noDesugaring: Boolean = false): File {
        val jar = d8Jar ?: throw IllegalStateException("нет d8.jar в Android SDK")
        val out = tempDir("dex")
        val files = classes.walkTopDown().filter { it.name.endsWith(".class") }.map { it.path }.toList()
        val cmd = arrayListOf(java, "-cp", jar.path, "com.android.tools.r8.D8", "--min-api", minApi.toString(), "--output", out.path)
        if (noDesugaring) cmd.add("--no-desugaring")
        cmd.addAll(files)
        val r = run(*cmd.toTypedArray())
        check(r.exit == 0) { "d8:\n${r.out}" }
        return File(out, "classes.dex")
    }

    /** Jar → каталог с DEX (d8 может разбить на classes*.dex). */
    fun d8Jar(input: File, minApi: Int = 26): File {
        val jar = d8Jar ?: throw IllegalStateException("нет d8.jar в Android SDK")
        val out = tempDir("dexdir")
        val r = run(java, "-cp", jar.path, "com.android.tools.r8.D8", "--min-api", minApi.toString(),
            "--output", out.path, input.path, timeoutSec = 900)
        check(r.exit == 0) { "d8:\n${r.out}" }
        return out
    }

    /** Классы DEX → class в jar (каждый проверяется повторным чтением ClassReader). */
    fun writeJar(dex: DexFile, jar: File, options: D2cOptions = D2cOptions()): Map<String, ByteArray> {
        val classes = DexToClass.convertToBytes(dex, options)
        jar(classes, jar)
        return classes
    }

    fun jar(classes: Map<String, ByteArray>, jar: File) {
        JarOutputStream(jar.outputStream().buffered()).use { out ->
            for ((name, bytes) in classes) {
                ClassReader().read(bytes, name)
                out.putNextEntry(JarEntry("$name.class"))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    /** Итог верификации HotSpot по классам каталога. */
    class Verification(val lines: List<String>) {
        val ok = lines.count { it.startsWith("OK ") }
        val verifyErrors = lines.filter { it.startsWith("VERIFY ") || it.startsWith("FORMAT ") }
        val linkErrors = lines.filter { it.startsWith("LINK ") || it.startsWith("OTHER ") }
        override fun toString() = "OK=$ok, VerifyError/ClassFormatError=${verifyErrors.size}, нет зависимостей=${linkErrors.size}"
    }

    private val harness: File by lazy {
        val dir = tempDir("harness")
        val src = File(dir, "VerifyAll.java")
        src.writeText(D2cTestSupport::class.java.getResource("/d2c/VerifyAll.java")!!.readText())
        val r = run(javac, "-d", dir.path, src.path)
        check(r.exit == 0) { "javac VerifyAll:\n${r.out}" }
        dir
    }

    /** Отдельный процесс JVM с полной верификацией: линкует каждый класс из [jar]. */
    fun verify(jar: File, extraClasspath: List<File> = emptyList()): Verification {
        val cp = (listOf(harness, jar) + extraClasspath).joinToString(File.pathSeparator) { it.path }
        val r = run(java, "-Xverify:all", "-cp", cp, "VerifyAll", jar.path)
        val lines = r.out.lines().filter { it.startsWith("OK ") || it.startsWith("VERIFY ") || it.startsWith("FORMAT ") ||
            it.startsWith("LINK ") || it.startsWith("OTHER ") }
        return Verification(lines)
    }

    /** Запуск `main` класса [mainClass] (каталог или jar) с полной верификацией. */
    fun runMain(classpath: File, mainClass: String): Result =
        run(java, "-Xverify:all", "-cp", classpath.path, mainClass)
}
