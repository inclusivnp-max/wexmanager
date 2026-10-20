package org.wexclient.app.dex

import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.wexclient.app.util.ProcessScratch
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Запросы, пришедшие, пока движок декомпиляции ещё создаётся, ждут его, а не создают свой:
 * раньше каждый клик по классу во время загрузки JADX запускал ещё одну полную загрузку,
 * и лишние экземпляры (и рабочие папки CFR/Vineflower) не закрывались никогда.
 */
class DecompilerSingleFlightTest {

    private val helloSmali = """
        .class public Ltest/Hello;
        .super Ljava/lang/Object;

        .method public static greet()Ljava/lang/String;
            .registers 1
            const-string v0, "HelloSingleFlight"
            return-object v0
        .end method
    """.trimIndent()

    private fun assembleDex(): ByteArray {
        val tmp = Files.createTempDirectory("single_flight_test_").toFile()
        try {
            val smali = File(tmp, "Hello.smali").apply { writeText(helloSmali) }
            val dex = File(tmp, "classes.dex")
            val options = SmaliOptions().apply { outputDexFile = dex.absolutePath }
            assertTrue(Smali.assemble(options, smali.absolutePath), "smali assembly failed")
            return dex.readBytes()
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Сколько в рабочей папке процесса файлов и папок с этим префиксом. */
    private fun scratch(prefix: String): Int =
        ProcessScratch.work.dir.toFile().listFiles { f -> f.name.startsWith(prefix) }?.size ?: 0

    /** Одновременно из [threads] потоков. */
    private fun <T> together(threads: Int, block: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val futures = (1..threads).map { pool.submit<T> { start.await(); block() } }
            start.countDown()
            return futures.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `JADX грузится один раз на одновременные запросы`() {
        val engine = DexEditorEngine(listOf(DexSource("classes.dex", assembleDex())))
        val node = engine.classes.first { it.fullName == "test.Hello" }
        val before = scratch("jadx-")
        try {
            val texts = together(4) { engine.decompileToJava(node) }
            texts.forEach { assertTrue(it.contains("HelloSingleFlight"), it) }
            // Одна загрузка — один входной файл JADX; раньше свой писал и свой JADX грузил каждый поток
            assertEquals(before + 1, scratch("jadx-"))
        } finally {
            engine.close()
        }
        assertEquals(before, scratch("jadx-"))
    }

    @Test
    fun `движок CFR создаётся один на одновременные запросы`() {
        val engine = DexEditorEngine(listOf(DexSource("classes.dex", assembleDex())))
        val node = engine.classes.first { it.fullName == "test.Hello" }
        val before = scratch("cfr-")
        try {
            val texts = together(4) { engine.decompileToJavaCfr(node) }
            texts.forEach { assertTrue(it.contains("HelloSingleFlight"), it) }
            assertEquals(before + 1, scratch("cfr-"))
        } finally {
            engine.close()
        }
        // Рабочая папка единственного движка удалена при закрытии
        assertEquals(before, scratch("cfr-"))
    }
}
