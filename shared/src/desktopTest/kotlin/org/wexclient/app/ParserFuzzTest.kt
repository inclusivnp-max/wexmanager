package org.wexclient.app

import org.wexclient.app.arsc.ArscDocument
import org.wexclient.app.axml.AxmlDecoder
import org.wexclient.app.elf.ElfDocument
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Файлы из APK приходят от кого угодно: испорченные AXML, resources.arsc и ELF не должны
 * вешать разбор или съедать память — только обычная ошибка или частичный результат.
 */
class ParserFuzzTest {
    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/fuzz/$name")!!.use { it.readBytes() }

    private val iterations = System.getenv("WEX_FUZZ_ITERATIONS")?.toIntOrNull() ?: 400

    private fun mutate(seed: ByteArray, rnd: Random): ByteArray {
        var b = seed.copyOf()
        when (rnd.nextInt(6)) {
            // Случайные байты
            0 -> repeat(1 + rnd.nextInt(16)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            // «Опасные» числа в случайные поля
            1 -> repeat(1 + rnd.nextInt(6)) {
                val pos = rnd.nextInt(maxOf(1, b.size - 4)) and 3.inv()
                val v = listOf(0, -1, 0x7FFFFFFF, 0x80000000.toInt(), 0xFFFF, 0x10000, 8, 1)[rnd.nextInt(8)]
                for (k in 0 until 4) if (pos + k < b.size) b[pos + k] = (v ushr (8 * k)).toByte()
            }
            // Обрезка
            2 -> b = b.copyOf(rnd.nextInt(b.size))
            // Хвост мусора
            3 -> b = b + ByteArray(rnd.nextInt(4096)).also { rnd.nextBytes(it) }
            // Повтор куска (размножение чанков)
            4 -> {
                val from = rnd.nextInt(b.size)
                val len = minOf(b.size - from, 1 + rnd.nextInt(512))
                val piece = b.copyOfRange(from, from + len)
                val times = 1 + rnd.nextInt(200)
                b = b.copyOfRange(0, from) + ByteArray(len * times) { piece[it % len] } + b.copyOfRange(from, b.size)
            }
            // Байты подряд
            else -> {
                val from = rnd.nextInt(b.size)
                val v = rnd.nextInt(256).toByte()
                for (k in from until minOf(b.size, from + 1 + rnd.nextInt(64))) b[k] = v
            }
        }
        return b
    }

    private fun fuzz(name: String, seeds: List<ByteArray>, seed: Long, body: (ByteArray) -> Unit) {
        val rnd = Random(seed)
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "fuzz-$name").apply { isDaemon = true } }
        try {
            // Исходные файлы должны разбираться без ошибок
            for (s in seeds) body(s)
            repeat(iterations) { i ->
                val input = mutate(seeds[rnd.nextInt(seeds.size)], rnd)
                val future = pool.submit(Callable {
                    try {
                        body(input)
                        null
                    } catch (e: Exception) {
                        null
                    } catch (e: StackOverflowError) {
                        e
                    } catch (e: OutOfMemoryError) {
                        e
                    }
                })
                val problem = try {
                    future.get(5, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    future.cancel(true)
                    val dump = java.io.File(System.getProperty("java.io.tmpdir"), "wex-fuzz-$name-$i.bin")
                    dump.writeBytes(input)
                    fail("$name: разбор завис на варианте $i (сохранён в $dump)")
                }
                if (problem != null) {
                    val dump = java.io.File(System.getProperty("java.io.tmpdir"), "wex-fuzz-$name-$i.bin")
                    dump.writeBytes(input)
                    fail("$name: ${problem.javaClass.simpleName} на варианте $i (сохранён в $dump)")
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `binary xml decoder survives garbage`() {
        val seeds = listOf(fixture("small-manifest.xml"), fixture("small-layout.xml"))
        fuzz("axml", seeds, 11) { bytes ->
            val text = AxmlDecoder().decode(bytes)
            assertTrue(text.length < 64 shl 20)
            org.wexclient.app.axml.ManifestInfo.parse(text)
        }
    }

    @Test
    fun `apk icon lookup survives garbage`() {
        val manifest = fixture("small-manifest.xml")
        val arsc = fixture("small.arsc")
        val loader = org.wexclient.app.vfs.ApkIconLoader
        val iconId = loader.getIconResIdFromManifest(manifest)
        kotlin.test.assertNotNull(iconId)
        kotlin.test.assertEquals(listOf("res/drawable/ic.png"), loader.resolvePathsFromArsc(arsc, iconId))
        fuzz("icon-manifest", listOf(manifest), 14) { bytes -> loader.getIconResIdFromManifest(bytes) }
        fuzz("icon-arsc", listOf(arsc), 15) { bytes -> loader.resolvePathsFromArsc(bytes, iconId) }
    }

    @Test
    fun `resources table parser survives garbage`() {
        fuzz("arsc", listOf(fixture("small.arsc")), 12) { bytes ->
            val doc = ArscDocument(bytes)
            val pool = doc.currentGlobalStrings()
            for (p in doc.packages) {
                p.toPackageInfoXml()
                for (t in p.types) {
                    t.toTypeInfoXml()
                    for (c in t.configs) c.toXml(pool)
                    for (e in t.entries) e.displayValue(pool)
                }
            }
            // Правка строки пула на испорченном файле — ошибка, а не порча памяти
            if (pool.isNotEmpty()) {
                try {
                    doc.setGlobalString(pool.size / 2, "правка")
                    doc.currentGlobalStrings()
                } catch (_: Exception) {
                }
            }
        }
    }

    @Test
    fun `elf parser survives garbage`() {
        val elf = java.io.File("/usr/lib/libz.so.1").takeIf { it.canRead() }?.readBytes() ?: return
        fuzz("elf", listOf(elf), 13) { bytes ->
            val doc = ElfDocument(bytes)
            doc.sections()
            doc.programs()
            doc.symbols()
            doc.dynamics()
            doc.neededLibraries()
            doc.soname()
        }
    }
}
