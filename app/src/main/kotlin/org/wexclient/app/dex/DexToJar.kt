package org.wexclient.app.dex

import com.googlecode.d2j.Method
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.dex.DexExceptionHandler
import com.googlecode.d2j.node.DexMethodNode
import org.objectweb.asm.MethodVisitor
import java.io.File
import java.util.zip.ZipFile

/**
 * Общий мост DEX -> jar с .class файлами (dex2jar).
 * Используется Java-движками, которые не умеют читать Dalvik напрямую (CFR, Vineflower).
 */
internal object DexToJar {

    fun translate(sources: List<DexSource>, workDir: File): List<String> {
        return sources.mapIndexed { idx, src ->
            val dexFile = workDir.resolve("input_$idx.dex").apply { writeBytes(src.bytes) }
            val jarFile = workDir.resolve("classes_$idx.jar")
            Dex2jar.from(dexFile.readBytes())
                .withExceptionHandler(lenientHandler())
                .to(jarFile.toPath())
            jarFile.absolutePath
        }
    }

    /**
     * Ломаные методы/файлы пропускаем, а не роняем всю трансляцию —
     * иначе один кривой класс убьет конвертацию всего DEX.
     */
    fun lenientHandler() = object : DexExceptionHandler {
        override fun handleFileException(e: Exception) {
            // skip broken file
        }

        override fun handleMethodTranslateException(
            method: Method,
            methodNode: DexMethodNode,
            mv: MethodVisitor,
            e: Exception
        ) {
            // skip broken method
        }
    }

    /** Достаёт байты одного .class из jar по имени entry (pkg/Name.class). */
    fun readEntry(jarPath: String, entryName: String): ByteArray? {
        return try {
            ZipFile(jarPath).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null
                zip.getInputStream(entry).readBytes()
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Один раз транслирует DEX'ы в jar'ы и переиспользует результат между движками
 * (CFR и Vineflower делят один кэш вместо двойной трансляции).
 */
class DexJarCache(val sources: List<DexSource>) {
    private val workDir = org.wexclient.app.util.ProcessScratch.work.newDir("dexjar-").toFile()

    @Volatile
    private var jars: List<String>? = null

    @Synchronized
    fun getJars(): List<String> {
        jars?.let { return it }
        return DexToJar.translate(sources, workDir).also { jars = it }
    }

    fun close() {
        jars = null
        try {
            org.wexclient.app.util.FileTree.delete(workDir)
        } catch (_: Exception) {
            // ignore
        }
    }
}
