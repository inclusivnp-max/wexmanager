package org.wexclient.app.dex

import com.googlecode.d2j.Method
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.dex.DexExceptionHandler
import com.googlecode.d2j.node.DexMethodNode
import com.tim.d2c.D2cOptions
import com.tim.d2c.DexToClass
import com.tim.dex.parser.DexReader
import org.objectweb.asm.MethodVisitor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipFile

/**
 * Мост DEX -> jar с .class для Java-движков, которые не читают Dalvik (CFR, Vineflower, Procyon).
 *
 * Основной переводчик — d2c из clazzpatcher: втрое быстрее dex2jar при меньшей памяти и следует семантике ART.
 * Классы, которые он перевёл не полностью (метод-заглушка `throw VerifyError`) или не перевёл вовсе, берутся
 * у dex2jar — тот запускается, только когда такой класс действительно открыли.
 */
internal object DexToJar {

    /** jar от d2c и внутренние имена классов в нём (переведённых полностью). */
    class Translated(val jar: File, val classes: Set<String>)

    /** d2c: null — DEX целиком не читается (тогда все классы — у dex2jar). */
    fun translateD2c(sources: List<DexSource>, workDir: File): Translated? {
        val degraded = ConcurrentHashMap.newKeySet<String>()
        val classes = try {
            val dexes = sources.map { DexReader().read(it.bytes) }
            val options = D2cOptions(
                strict = false,
                // Одно ядро — окну: перевод идёт, пока пользователь листает классы
                threads = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1),
                degraded = { degraded.add(it.removePrefix("L").removeSuffix(";")) }
            )
            DexToClass.convertAllToBytes(dexes, options)
        } catch (_: Exception) {
            return null
        } catch (_: StackOverflowError) {
            return null
        }
        val jar = workDir.resolve("classes_d2c.jar")
        val written = HashSet<String>(classes.size * 2)
        JarOutputStream(jar.outputStream().buffered(1 shl 16)).use { out ->
            // Jar временный и читается по одному классу: быстрое сжатие вместо плотного
            out.setLevel(Deflater.BEST_SPEED)
            for ((name, bytes) in classes) {
                if (name in degraded || !written.add(name)) continue
                out.putNextEntry(JarEntry("$name.class"))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return Translated(jar, written)
    }

    /** dex2jar: jar на каждый DEX. */
    fun translateDex2jar(sources: List<DexSource>, workDir: File): List<String> {
        return sources.mapIndexed { idx, src ->
            val jarFile = workDir.resolve("classes_$idx.jar")
            Dex2jar.from(src.bytes)
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

    /** Байты класса из первого jar, где он есть. */
    fun readFirst(jars: List<String>, entryName: String): ByteArray? {
        for (jar in jars) readEntry(jar, entryName)?.let { return it }
        return null
    }

    /**
     * Декомпилятор не собрал часть методов класса, переведённого d2c: тот же класс от dex2jar ([alternative])
     * иногда собирается лучше (другая раскладка байткода) — берётся вариант, где несобранных методов меньше,
     * при равенстве — первый. dex2jar запускается только здесь, при сбое.
     */
    inline fun secondOpinion(first: String, markers: Array<String>, alternative: () -> String?): String {
        val bad = failures(first, markers)
        if (bad == 0) return first
        val other = alternative() ?: return first
        return if (failures(other, markers) < bad) other else first
    }

    /** Сколько раз декомпилятор пометил в тексте несобранный метод. */
    fun failures(java: String, markers: Array<String>): Int {
        var n = 0
        for (m in markers) {
            var i = java.indexOf(m)
            while (i >= 0) {
                n++
                i = java.indexOf(m, i + m.length)
            }
        }
        return n
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
 * (CFR, Vineflower и Procyon делят один кэш вместо тройной трансляции).
 */
class DexJarCache(val sources: List<DexSource>) {
    private val workDir = org.wexclient.app.util.ProcessScratch.work.newDir("dexjar-").toFile()

    @Volatile
    private var translated: DexToJar.Translated? = null

    @Volatile
    private var translatedDone = false

    @Volatile
    private var dex2jar: List<String>? = null

    @Synchronized
    private fun primary(): DexToJar.Translated? {
        if (!translatedDone) {
            translated = DexToJar.translateD2c(sources, workDir)
            translatedDone = true
        }
        return translated
    }

    @Synchronized
    private fun fallback(): List<String> =
        dex2jar ?: DexToJar.translateDex2jar(sources, workDir).also { dex2jar = it }

    /** Прогрев: основной перевод (d2c, а если он не справился с DEX — dex2jar). */
    fun getJars(): List<String> = primary()?.let { listOf(it.jar.absolutePath) } ?: fallback()

    /**
     * jar'ы dex2jar — второе мнение, когда декомпилятор не собрал часть методов класса от d2c.
     * null — класс и так из dex2jar.
     */
    fun alternativeJarsFor(entryName: String): List<String>? {
        val p = primary() ?: return null
        if (entryName.removeSuffix(".class") !in p.classes) return null
        return fallback()
    }

    /** jar'ы, в которых искать класс [entryName] (`pkg/Name.class`), по порядку. */
    fun jarsFor(entryName: String): List<String> {
        val p = primary()
        if (p != null && entryName.removeSuffix(".class") in p.classes) return listOf(p.jar.absolutePath)
        return fallback()
    }

    fun close() {
        synchronized(this) {
            translated = null
            translatedDone = false
            dex2jar = null
        }
        try {
            org.wexclient.app.util.FileTree.delete(workDir)
        } catch (_: Exception) {
            // ignore
        }
    }
}
