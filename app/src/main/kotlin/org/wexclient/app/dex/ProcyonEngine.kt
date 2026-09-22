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
 * DEX -> .class через dex2jar -> Java через Procyon.
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
            // 1. Транслированные jar'ы (один раз на всех, дальше из кэша)
            val jars = cache.getJars()
            val internalName = fullName.replace('.', '/')

            // 2. Ищем класс по jar'ам, декомпилируем первый совпавший
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
                if (text != null) {
                    javaByClass[fullName] = text
                    return text
                }
            }
            "// Procyon: класс $fullName не найден в транслированном jar"
        } catch (e: Exception) {
            "// Procyon недоступен: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun close() {
        javaByClass.clear()
        if (ownsCache) {
            cache.close()
        }
    }
}
