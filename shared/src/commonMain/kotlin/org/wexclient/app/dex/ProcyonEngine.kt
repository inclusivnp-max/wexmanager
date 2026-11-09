package org.wexclient.app.dex

import java.util.concurrent.ConcurrentHashMap

/**
 * Java-движок на Procyon (Apache-2.0):
 * DEX -> .class (d2c, запасной — dex2jar, [DexJarCache]) -> Java через Procyon.
 *
 * Procyon силён там, где остальные пасуют: enum'ы, string-switch,
 * локальные/анонимные классы, аннотации. Загрузка поклассовая по запросу
 * (трансляция DEX -> jar одна на всех через общий [DexJarCache]).
 */
class ProcyonEngine(
    val sources: List<DexSource>,
    private val jarCache: DexJarCache? = null
) {
    private val cache = jarCache ?: DexJarCache(sources)
    private val ownsCache = jarCache == null

    private val javaByClass = ConcurrentHashMap<String, String>()

    fun decompileToJava(fullName: String, familyNames: Set<String> = emptySet()): String {
        javaByClass[fullName]?.let { return it }
        return try {
            // Трансляция DEX -> jar одна на всех (дальше из кэша), Procyon — только на этом классе
            val internalName = fullName.replace('.', '/')
            val entryName = "$internalName.class"
            val first = decompileFrom(cache.jarsFor(entryName), internalName, familyNames)
                ?: return "// Procyon: класс $fullName не найден в транслированном jar"
            val java = DexToJar.secondOpinion(first, FAILED) {
                cache.alternativeJarsFor(entryName)?.let { decompileFrom(it, internalName, familyNames) }
            }
            javaByClass[fullName] = java
            java
        } catch (e: Exception) {
            "// Procyon недоступен: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Java класса из первого jar, где он есть (типы — из того же jar); null — ни в одном. */
    private fun decompileFrom(jars: List<String>, internalName: String, familyNames: Set<String>): String? {
        val set = ClassFiles.fromJars(jars, internalName.replace('/', '.'), familyNames) ?: return null
        return ClassFiles.render(set, ClassView.PROCYON)
    }

    fun close() {
        javaByClass.clear()
        if (ownsCache) {
            cache.close()
        }
    }

    private companion object {
        /** Пометка Procyon у метода, который он не собрал. */
        val FAILED = arrayOf("could not be decompiled")
    }
}
