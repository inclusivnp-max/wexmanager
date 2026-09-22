package org.wexclient.app.apk

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.core.export.ExportGradleType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Как выгружать проект из APK. */
data class JadxExportOptions(
    /** Проект Gradle: открывается в Android Studio и IDEA. Иначе — просто папки sources и resources. */
    val gradle: Boolean = true,
    /** Ресурсы и манифест — читаемым XML. */
    val resources: Boolean = true,
    /** Бессмысленные короткие имена (a, b, c) заменить на осмысленные и уникальные. */
    val deobfuscate: Boolean = false,
    /** Методы, которые не разобрались до конца, всё равно показать — с пометкой. */
    val showBadCode: Boolean = true,
    val threads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)
)

/** Ход выгрузки: [total] = 0 — сколько всего, ещё неизвестно. */
data class JadxExportProgress(val stage: String, val done: Int, val total: Int)

data class JadxExportResult(
    val outDir: Path,
    val classes: Int,
    val errors: Int,
    val warnings: Int,
    val millis: Long
)

/**
 * APK или DEX → проект с исходниками на Java (jadx): классы, ресурсы и манифест,
 * по желанию — готовый проект Gradle. Работает в фоне, останавливается по [export] cancelled.
 */
object JadxExport {

    /** Что jadx открывает без дополнительных модулей: APK и DEX (и zip с DEX внутри). */
    fun supports(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".apk") || lower.endsWith(".dex")
    }

    /** Свободная папка рядом с файлом: «app_jadx», занято — «app_jadx (2)». */
    fun defaultTarget(input: Path): Path {
        val name = input.fileName.toString()
        val base = name.substringBeforeLast('.').ifEmpty { name }
        return org.wexclient.app.vfs.SafeExtract.freeFolder(input.toAbsolutePath().parent, base + "_jadx")
    }

    fun export(
        input: Path,
        outDir: Path,
        options: JadxExportOptions = JadxExportOptions(),
        cancelled: () -> Boolean = { false },
        onProgress: (JadxExportProgress) -> Unit = {}
    ): JadxExportResult {
        val started = System.nanoTime()
        Files.createDirectories(outDir)
        val args = JadxArgs().apply {
            setInputFile(input.toFile())
            setOutDir(outDir.toFile())
            threadsCount = options.threads
            isSkipResources = !options.resources
            isShowInconsistentCode = options.showBadCode
            isDeobfuscationOn = options.deobfuscate
            isUseImports = true
            exportGradleType = if (options.gradle) ExportGradleType.AUTO else null
        }
        JadxDecompiler(args).use { jadx ->
            onProgress(JadxExportProgress("Чтение классов и ресурсов", 0, 0))
            jadx.load()
            if (cancelled()) throw CancellationException("Остановлено")
            val classes = jadx.classes.size
            val tasks = jadx.saveTaskExecutor
            tasks.execute()
            try {
                while (tasks.isRunning) {
                    if (cancelled()) {
                        tasks.terminate()
                        break
                    }
                    onProgress(JadxExportProgress("Декомпиляция", tasks.progress, tasks.tasksCount))
                    Thread.sleep(150)
                }
            } finally {
                tasks.awaitTermination()
            }
            if (cancelled()) throw CancellationException("Остановлено")
            onProgress(JadxExportProgress("Готово", tasks.tasksCount, tasks.tasksCount))
            return JadxExportResult(
                outDir = outDir,
                classes = classes,
                errors = jadx.errorsCount,
                warnings = jadx.warnsCount,
                millis = (System.nanoTime() - started) / 1_000_000
            )
        }
    }
}
