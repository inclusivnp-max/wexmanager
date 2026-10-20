package com.tim.dex

import com.tim.dex.model.DexFile
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Вспомогательные функции тестов dexparser. */
object DexTestUtil {

    fun typeName(dex: DexFile, typeIdx: Int): String = dex.strings[dex.typeIds[typeIdx].descriptorIdx]

    fun fieldName(dex: DexFile, fieldIdx: Int): String = dex.strings[dex.fieldIds[fieldIdx].nameIdx]

    fun methodName(dex: DexFile, methodIdx: Int): String = dex.strings[dex.methodIds[methodIdx].nameIdx]

    fun classIndex(dex: DexFile, descriptor: String): Int =
        dex.classDefs.indexOfFirst { typeName(dex, it.classIdx) == descriptor }

    /** Юнит байткода. */
    fun unit(insns: ByteArray, pc: Int): Int =
        (insns[pc * 2].toInt() and 0xFF) or ((insns[pc * 2 + 1].toInt() and 0xFF) shl 8)

    /** Байткод из 16-битных юнитов. */
    fun code(vararg units: Int): ByteArray {
        val out = ByteArray(units.size * 2)
        for (i in units.indices) {
            out[i * 2] = units[i].toByte()
            out[i * 2 + 1] = (units[i] ushr 8).toByte()
        }
        return out
    }

    /**
     * Проверка DEX верификатором ART (dexdump -d). Если Android SDK не установлен,
     * тест пропускается (assumption), а не молча считается пройденным.
     */
    fun verifyWithDexdump(bytes: ByteArray, name: String) {
        // Для ручной проверки (baksmali и т.п.): DEXPARSER_TEST_DUMP=<каталог>
        System.getenv("DEXPARSER_TEST_DUMP")?.let { File(it, "$name.dex").writeBytes(bytes) }
        val dexdump = File(System.getProperty("user.home"), "Android/Sdk/build-tools/37.0.0/dexdump")
        assumeTrue(dexdump.canExecute(), "dexdump не найден: $dexdump")
        val file = File.createTempFile(name, ".dex")
        val dump = File.createTempFile(name, ".txt")
        try {
            file.writeBytes(bytes)
            val process = ProcessBuilder(dexdump.path, "-d", file.path)
                .redirectOutput(dump)
                .redirectErrorStream(false)
                .start()
            val err = process.errorStream.bufferedReader().readText()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("dexdump завис на $name")
            }
            val errors = err.lines().filter { it.contains(" E dexdump") }
            if (process.exitValue() != 0 || errors.isNotEmpty()) {
                throw AssertionError("dexdump отверг $name (exit=${process.exitValue()}):\n" + errors.take(10).joinToString("\n"))
            }
        } finally {
            file.delete()
            dump.delete()
        }
    }
}
