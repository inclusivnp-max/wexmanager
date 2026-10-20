package org.wexclient.app.dex

import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.Manifest

/**
 * Java-движок на Vineflower (живой наследник Fernflower):
 * DEX -> .class (d2c, запасной — dex2jar, [DexJarCache]) -> Java через Vineflower.
 *
 * Vineflower обычно аккуратнее CFR/JADX восстанавливает generics, enum'ы,
 * try-with-resources и сложные условия — имеет смысл смотреть все три вкладки.
 *
 * Декомпиляция поклассовая по запросу: трансляция DEX -> jar делается один раз
 * (общий [DexJarCache]), а Vineflower гоняется только на выбранном классе — иначе
 * прогон тысяч классов целиком занимает минуты и выглядит как вечная загрузка.
 */
class VineflowerEngine(
    val sources: List<DexSource>,
    private val jarCache: DexJarCache? = null
) {
    private val cache = jarCache ?: DexJarCache(sources)
    private val ownsCache = jarCache == null

    internal val workDir = org.wexclient.app.util.ProcessScratch.work.newDir("vf-").toFile()

    // Имя класса с точками -> Java
    private val javaByClass = ConcurrentHashMap<String, String>()

    fun decompileToJava(fullName: String): String {
        val key = fullName.replace('/', '.')
        javaByClass[key]?.let { return it }
        return try {
            // Трансляция DEX -> jar одна на всех (дальше из кэша), Vineflower — только на этом классе
            val entryName = fullName.replace('.', '/') + ".class"
            val first = decompileFrom(cache.jarsFor(entryName), entryName, fullName, key)
                ?: return "// Vineflower: класс $fullName не найден в транслированном jar"
            val java = DexToJar.secondOpinion(first, FAILED) {
                cache.alternativeJarsFor(entryName)?.let { decompileFrom(it, entryName, fullName, key) }
            }
            javaByClass[key] = java
            java
        } catch (e: Exception) {
            "// Vineflower недоступен: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Java класса из первого jar, где он есть; null — ни в одном. */
    private fun decompileFrom(jars: List<String>, entryName: String, fullName: String, key: String): String? {
        val classBytes = DexToJar.readFirst(jars, entryName) ?: return null
        val saver = CollectingSaver()
        // Своя папка на каждый вызов: в общей копились все открытые раньше классы, и Vineflower
        // заново разбирал их все (N-й класс — N декомпиляций), а временные файлы только росли
        val dir = java.nio.file.Files.createTempDirectory(workDir.toPath(), "single-").toFile()
        try {
            dir.resolve(entryName).apply {
                parentFile.mkdirs()
                writeBytes(classBytes)
            }
            val decompiler = BaseDecompiler(saver, options(), IFernflowerLogger.NO_OP)
            decompiler.addSource(dir)
            decompiler.decompileContext()

            // Одна запись на класс; имя приводится к нотации с точками
            val out = saver.classes.mapKeys { it.key.replace('/', '.') }
            out[key]?.let { return it }
            // Запасной вариант для вложенных классов с другой нотацией имени
            val simple = fullName.substringAfterLast('.')
            out.entries.firstOrNull { (k, _) -> k.substringAfterLast('.') == simple }?.let { return it.value }
            return "// Vineflower: класс $fullName не декомпилирован"
        } finally {
            try {
                saver.close()
            } catch (_: Exception) {
            }
            runCatching { org.wexclient.app.util.FileTree.delete(dir) }
        }
    }

    fun close() {
        try {
            org.wexclient.app.util.FileTree.delete(workDir)
        } catch (_: Exception) {
        }
        javaByClass.clear()
        if (ownsCache) {
            cache.close()
        }
    }

    companion object {
        /** Пометка Vineflower у метода, который он не собрал. */
        private val FAILED = arrayOf("Couldn't be decompiled")

        internal fun options(): Map<String, Any> = mapOf(
            IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES to "1",
            IFernflowerPreferences.DECOMPILE_ENUM to "1",
            IFernflowerPreferences.DECOMPILE_INNER to "1",
            IFernflowerPreferences.DECOMPILE_ASSERTIONS to "1",
            IFernflowerPreferences.REMOVE_SYNTHETIC to "1",
            IFernflowerPreferences.REMOVE_BRIDGE to "1",
            IFernflowerPreferences.HIDE_EMPTY_SUPER to "0",
            IFernflowerPreferences.HIDE_DEFAULT_CONSTRUCTOR to "1",
            IFernflowerPreferences.INCORPORATE_RETURNS to "1",
            IFernflowerPreferences.FINALLY_DEINLINE to "1",
            IFernflowerPreferences.TERNARY_CONSTANT_SIMPLIFICATION to "1",
            IFernflowerPreferences.PATTERN_MATCHING to "1",
            IFernflowerPreferences.SWITCH_EXPRESSIONS to "1",
            IFernflowerPreferences.OVERRIDE_ANNOTATION to "1",
            IFernflowerPreferences.EXPLICIT_GENERIC_ARGUMENTS to "0",
            IFernflowerPreferences.INLINE_SIMPLE_LAMBDAS to "1",
            IFernflowerPreferences.LAMBDA_TO_ANONYMOUS_CLASS to "0",
            IFernflowerPreferences.DECOMPILE_PREVIEW to "1",
            IFernflowerPreferences.DECOMPILER_COMMENTS to "1",
            IFernflowerPreferences.THREADS to "4",
            IFernflowerPreferences.LOG_LEVEL to "WARN",
            IFernflowerPreferences.INDENT_STRING to "   ",
            IFernflowerPreferences.PREFERRED_LINE_LENGTH to "160"
        )
    }

    internal class CollectingSaver : IResultSaver {
        val classes = ConcurrentHashMap<String, String>()

        override fun saveFolder(path: String) {}
        override fun copyFile(source: String, path: String, entryName: String) {}
        override fun saveClassFile(
            path: String,
            qualifiedName: String,
            entryName: String,
            content: String?,
            mapping: IntArray?
        ) {
            if (content != null) classes[qualifiedName] = content
        }

        override fun createArchive(path: String, archiveName: String, manifest: Manifest?) {}
        override fun saveDirEntry(path: String, archiveName: String, entryName: String) {}
        override fun copyEntry(source: String, path: String, archiveName: String, entryName: String) {}
        override fun saveClassEntry(
            path: String,
            archiveName: String,
            qualifiedName: String,
            entryName: String,
            content: String?
        ) {
            if (content != null) classes[qualifiedName] = content
        }

        override fun closeArchive(path: String, archiveName: String) {}
    }
}
