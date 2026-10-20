package com.tim.dex

import com.tim.dex.parser.DexReader
import com.tim.dex.writer.DexWriter
import java.io.File
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Замер скорости чтения/записи (прогретая JVM). Запускается только при заданной
 * переменной окружения `DEXPARSER_BENCH` (пути к DEX через `:`), например:
 * `DEXPARSER_BENCH=classes3.dex ./gradlew :app:test --tests '*DexBenchmarkTest*' -i`.
 */
class DexBenchmarkTest {

    @Test
    fun readWriteTimings() {
        val files = System.getenv("DEXPARSER_BENCH")?.split(':')?.filter { it.isNotBlank() }
        assumeTrue(!files.isNullOrEmpty(), "DEXPARSER_BENCH не задана")
        val warmup = 3
        val iterations = 7
        for (path in files!!) {
            val bytes = File(path).readBytes()
            val read = DoubleArray(iterations)
            val write = DoubleArray(iterations)
            for (i in 0 until warmup + iterations) {
                val t0 = System.nanoTime()
                val dex = DexReader().read(bytes)
                val t1 = System.nanoTime()
                DexWriter(dex).buildDex()
                val t2 = System.nanoTime()
                if (i >= warmup) {
                    read[i - warmup] = (t1 - t0) / 1e6
                    write[i - warmup] = (t2 - t1) / 1e6
                }
            }
            read.sort()
            write.sort()
            println(
                "%s (%d байт): чтение медиана %.1f мс (мин %.1f), запись медиана %.1f мс (мин %.1f)".format(
                    path, bytes.size, read[iterations / 2], read[0], write[iterations / 2], write[0]
                )
            )
        }
    }
}
