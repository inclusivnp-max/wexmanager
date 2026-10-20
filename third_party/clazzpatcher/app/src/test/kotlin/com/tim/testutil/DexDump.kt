package com.tim.testutil

import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files

/**
 * Проверка DEX верификатором libdexfile через `dexdump -d` из Android SDK.
 *
 * Поиск: переменная DEXDUMP, иначе самая новая build-tools/<версия>/dexdump в $ANDROID_HOME,
 * $ANDROID_SDK_ROOT или ~/Android/Sdk. Тесты, которым он нужен, пропускаются, если dexdump не найден.
 */
object DexDump {
    val executable: File? by lazy { locate() }

    private fun locate(): File? {
        System.getenv("DEXDUMP")?.takeIf { it.isNotBlank() }?.let { path ->
            val f = File(path)
            if (!f.canExecute()) throw IllegalStateException("DEXDUMP=$path: файл не найден или не исполняемый")
            return f
        }
        val roots = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            File(System.getProperty("user.home"), "Android/Sdk").path
        )
        for (root in roots) {
            val candidates = File(root, "build-tools").listFiles().orEmpty()
                .map { File(it, "dexdump") }
                .filter { it.canExecute() }
            if (candidates.isNotEmpty()) return candidates.maxWithOrNull(Comparator { a, b -> compareVersions(a.parentFile.name, b.parentFile.name) })
        }
        return null
    }

    /** Сравнение версий вида 36.1.0 / 37.0.0-rc1 по числовым компонентам. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: -1 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: -1 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun available(): Boolean = executable != null

    /** null — DEX принят; иначе текст ошибок верификатора. */
    fun verify(bytes: ByteArray): String? {
        val exe = executable ?: throw IllegalStateException("dexdump не найден")
        val file = Files.createTempFile("verify", ".dex").toFile()
        try {
            file.writeBytes(bytes)
            val process = ProcessBuilder(exe.path, "-d", file.path)
                .redirectOutput(nullFile())
                .start()
            val errors = process.errorStream.bufferedReader().readLines()
            val code = process.waitFor()
            val bad = errors.filter { it.contains(" E dexdump") }
            if (code == 0 && bad.isEmpty()) return null
            val shown = (bad.ifEmpty { errors }).take(15).map { it.substringAfter(" E dexdump").trimStart(' ', ':').replace(file.path, "<dex>") }
            return "dexdump -d: код $code, ошибок ${bad.size}\n" + shown.joinToString("\n")
        } finally {
            file.delete()
        }
    }

    /** Пропускает тест, если dexdump недоступен; падает, если DEX отвергнут. */
    fun assertValid(bytes: ByteArray, what: String) {
        assumeTrue(available(), "dexdump не найден (DEXDUMP / ANDROID_HOME / ~/Android/Sdk)")
        verify(bytes)?.let { fail<Unit>("$what: libdexfile отвергает DEX\n$it") }
    }

    private fun nullFile(): File = File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")
}
