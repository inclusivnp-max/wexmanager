package org.wexclient.app.dex

import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.api.SinkReturns
import java.util.concurrent.ConcurrentHashMap

/**
 * Альтернативный Java-движок: DEX -> .class (d2c, запасной — dex2jar, [DexJarCache]) -> Java через CFR.
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
            // Трансляция DEX -> jar одна на всех (дальше из кэша), CFR — только на этом классе
            val entryName = fullName.replace('.', '/') + ".class"
            val first = decompileFrom(cache.jarsFor(entryName), entryName, fullName)
                ?: return "// CFR: класс $fullName не найден в транслированном jar"
            val java = DexToJar.secondOpinion(first, FAILED) {
                cache.alternativeJarsFor(entryName)?.let { decompileFrom(it, entryName, fullName) }
            }
            javaByClass[fullName] = java
            java
        } catch (e: Exception) {
            "// CFR недоступен: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Java класса из первого jar, где он есть; null — ни в одном. */
    private fun decompileFrom(jars: List<String>, entryName: String, fullName: String): String? {
        val classBytes = DexToJar.readFirst(jars, entryName) ?: return null
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
        return collected[fullName] ?: "// CFR: класс $fullName не декомпилирован"
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
        /** Пометки CFR у метода, который он не собрал. */
        private val FAILED = arrayOf("has failed to decompile")

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
