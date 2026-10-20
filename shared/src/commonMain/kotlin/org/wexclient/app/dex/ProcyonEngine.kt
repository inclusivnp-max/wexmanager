package org.wexclient.app.dex

import com.strobel.decompiler.Decompiler
import com.strobel.decompiler.DecompilerSettings
import com.strobel.decompiler.PlainTextOutput
import com.strobel.assembler.metadata.JarTypeLoader
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

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

    fun decompileToJava(fullName: String): String {
        javaByClass[fullName]?.let { return it }
        return try {
            // Трансляция DEX -> jar одна на всех (дальше из кэша), Procyon — только на этом классе
            val internalName = fullName.replace('.', '/')
            val entryName = "$internalName.class"
            val first = decompileFrom(cache.jarsFor(entryName), internalName)
                ?: return "// Procyon: класс $fullName не найден в транслированном jar"
            val java = DexToJar.secondOpinion(first, FAILED) {
                cache.alternativeJarsFor(entryName)?.let { decompileFrom(it, internalName) }
            }
            javaByClass[fullName] = java
            java
        } catch (e: Exception) {
            "// Procyon недоступен: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Java класса из первого jar, где он есть (типы — из того же jar); null — ни в одном. */
    private fun decompileFrom(jars: List<String>, internalName: String): String? {
        for (jar in jars) {
            val text: String? = try {
                JarFile(jar).use { jf ->
                    if (jf.getJarEntry("$internalName.class") == null) {
                        null
                    } else {
                        val settings = DecompilerSettings.javaDefaults()
                        settings.typeLoader = JarTypeLoader(jf)
                        val writer = StringWriter()
                        Decompiler.decompile(internalName, PlainTextOutput(writer), settings)
                        writer.toString().ifBlank { null }
                    }
                }
            } catch (_: Exception) {
                null
            }
            if (text != null) return text
        }
        return null
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
