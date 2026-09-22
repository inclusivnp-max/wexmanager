package org.wexclient.app.dex

import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.api.SinkReturns
import java.util.concurrent.ConcurrentHashMap

/**
 * Альтернативный Java-движок: DEX -> .class через dex2jar -> Java через CFR.
 *
 * CFR (https://www.benf.org/other/cfr) часто чище восстанавливает сложные конструкции
 * (строковые конкатенации, switch по строкам, try/catch), где JADX сыпет ошибками.
 *
 * Декомпиляция поклассовая по запросу: трансляция DEX -> jar делается один раз
 * (общий [DexJarCache]), а CFR гоняется только на выбранном классе — иначе прогон
 * тысяч классов целиком занимает минуты и выглядит как вечная загрузка.
 */
class CfrEngine(
    val sources: List<DexSource>,
    private val jarCache: DexJarCache? = null
) {
    private val cache = jarCache ?: DexJarCache(sources)
    private val ownsCache = jarCache == null

    private val workDir = org.wexclient.app.util.ProcessScratch.work.newDir("cfr-").toFile()

    private val javaByClass = ConcurrentHashMap<String, String>()

    fun decompileToJava(fullName: String): String {
        javaByClass[fullName]?.let { return it }
        return try {
            // 1. Транслированные jar'ы (один раз на всех, дальше из кэша)
            val jars = cache.getJars()

            // 2. Байты нужного класса
            val entryName = fullName.replace('.', '/') + ".class"
            var classBytes: ByteArray? = null
            for (jar in jars) {
                classBytes = DexToJar.readEntry(jar, entryName)
                if (classBytes != null) break
            }
            if (classBytes == null) {
                return "// CFR: класс $fullName не найден в транслированном jar"
            }

            // 3. CFR только на этом классе
            val singleDir = workDir.resolve("single").apply { mkdirs() }
            val classFile = singleDir.resolve(entryName).apply {
                parentFile.mkdirs()
                writeBytes(classBytes)
            }

            val collected = ConcurrentHashMap<String, String>()
            val driver = CfrDriver.Builder()
                .withOptions(mapOf("showversion" to "false"))
                .withOutputSink(collectingSinkInto(collected))
                .build()
            driver.analyse(listOf(classFile.absolutePath))

            collected.forEach { (k, v) -> javaByClass[k] = v }
            javaByClass[fullName] ?: "// CFR: класс $fullName не декомпилирован"
        } catch (e: Exception) {
            "// CFR недоступен: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun close() {
        try {
            org.wexclient.app.util.FileTree.delete(workDir)
        } catch (_: Exception) {
            // ignore
        }
        javaByClass.clear()
        if (ownsCache) {
            cache.close()
        }
    }

    companion object {
        internal fun collectingSinkInto(collected: MutableMap<String, String>): OutputSinkFactory {
            return object : OutputSinkFactory {
                override fun getSupportedSinks(
                    sinkType: OutputSinkFactory.SinkType,
                    collection: Collection<OutputSinkFactory.SinkClass>
                ): List<OutputSinkFactory.SinkClass> {
                    // Поддерживаем только Java-вывод и сообщения об ошибках.
                    // Остальное (PROGRESS/SUMMARY/...) не заявляем, иначе CFR будет
                    // писать туда String-прогресс, а не Decompiled.
                    return when (sinkType) {
                        OutputSinkFactory.SinkType.JAVA ->
                            listOf(OutputSinkFactory.SinkClass.DECOMPILED)
                        OutputSinkFactory.SinkType.EXCEPTION ->
                            listOf(OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE)
                        else -> emptyList()
                    }
                }

                @Suppress("UNCHECKED_CAST")
                override fun <T> getSink(
                    sinkType: OutputSinkFactory.SinkType,
                    sinkClass: OutputSinkFactory.SinkClass
                ): OutputSinkFactory.Sink<T> {
                    if (sinkType == OutputSinkFactory.SinkType.JAVA &&
                        sinkClass == OutputSinkFactory.SinkClass.DECOMPILED
                    ) {
                        return OutputSinkFactory.Sink { d: Any? ->
                            val dec = d as SinkReturns.Decompiled
                            val fqcn = if (dec.packageName.isEmpty()) {
                                dec.className
                            } else {
                                "${dec.packageName}.${dec.className}"
                            }
                            collected[fqcn] = dec.java
                        } as OutputSinkFactory.Sink<T>
                    }
                    // Все остальные пары (ошибки, прогресс) — тихо глотаем.
                    return OutputSinkFactory.Sink<Any?> { } as OutputSinkFactory.Sink<T>
                }
            }
        }
    }
}
