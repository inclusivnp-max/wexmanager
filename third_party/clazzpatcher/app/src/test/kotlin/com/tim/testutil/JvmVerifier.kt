package com.tim.testutil

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Проверка классов настоящим верификатором HotSpot в отдельном процессе JVM
 * (харнесс `harness/VerifyAll.java` линкует каждый класс). Классы процесса тестов не загружаются.
 */
object JvmVerifier {

    class Report(val ok: List<String>, val failed: Map<String, String>, val skipped: Map<String, String>) {
        override fun toString(): String =
            "верифицировано ${ok.size}, ошибок ${failed.size}, пропущено ${skipped.size}" +
                failed.entries.take(10).joinToString("") { "\n  FAIL ${it.key}: ${it.value}" }
    }

    private val javaHome = File(System.getProperty("java.home"))
    val java: String = File(javaHome, "bin/java").path
    val javac: String = File(javaHome, "bin/javac").let { if (it.isFile) it.path else "javac" }

    private val harnessDir: File by lazy {
        val dir = Files.createTempDirectory("verify-harness").toFile()
        val src = File(dir, "VerifyAll.java")
        src.writeBytes(JvmVerifier::class.java.getResourceAsStream("/harness/VerifyAll.java")!!.use { it.readBytes() })
        run(listOf(javac, "-d", dir.path, src.path), dir).also { require(it.first == 0) { "javac харнесса: ${it.second}" } }
        dir
    }

    /** Записать классы (внутреннее имя → байты) в каталог [dir]. */
    fun writeClasses(dir: File, classes: Map<String, ByteArray>) {
        for ((name, bytes) in classes) {
            val f = File(dir, "$name.class")
            f.parentFile.mkdirs()
            f.writeBytes(bytes)
        }
    }

    /** Верифицировать классы [names] (внутренние имена) из [classpath]. */
    fun verify(classpath: List<File>, names: Collection<String>): Report {
        val list = Files.createTempFile("verify", ".txt").toFile()
        list.writeText(names.joinToString("\n") { it.replace('/', '.') })
        val cp = (listOf(harnessDir) + classpath).joinToString(File.pathSeparator) { it.path }
        val (code, out) = run(listOf(java, "-Xshare:off", "-cp", cp, "VerifyAll", list.path), list.parentFile)
        list.delete()
        require(code == 0) { "Процесс проверки завершился с кодом $code:\n${out.take(4000)}" }
        val ok = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        val skipped = LinkedHashMap<String, String>()
        for (line in out.lineSequence()) {
            val parts = line.split(' ', limit = 3)
            if (parts.size < 2) continue
            val name = parts[1].replace('.', '/')
            when (parts[0]) {
                "OK" -> ok.add(name)
                "FAIL" -> failed[name] = parts.getOrElse(2) { "" }
                "SKIP" -> skipped[name] = parts.getOrElse(2) { "" }
            }
        }
        return Report(ok, failed, skipped)
    }

    /** Запуск `java -cp … main` — вывод stdout+stderr и код возврата. */
    fun runMain(classpath: List<File>, mainClass: String, vararg jvmArgs: String): Pair<Int, String> {
        val cp = classpath.joinToString(File.pathSeparator) { it.path }
        return run(listOf(java, "-Xshare:off") + jvmArgs + listOf("-cp", cp, mainClass), classpath.first())
    }

    fun run(cmd: List<String>, dir: File): Pair<Int, String> {
        val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            throw IllegalStateException("Процесс не завершился: $cmd")
        }
        return p.exitValue() to out
    }

    /** Компиляция Java-исходников javac в каталог. */
    fun compileJava(sources: List<File>, out: File, vararg options: String) {
        out.mkdirs()
        val (code, text) = run(listOf(javac, "-d", out.path) + options + sources.map { it.path }, out)
        require(code == 0) { "javac: $text" }
    }
}
