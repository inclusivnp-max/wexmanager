package org.wexclient.app.apk

import org.wexclient.app.axml.AxmlDecoder
import org.wexclient.app.axml.ManifestInfo
import java.nio.file.Files
import java.nio.file.Path

/** Что сейчас делается: строка для человека и доля выполненного. */
internal data class CompareProgress(val stage: String, val done: Int = 0, val total: Int = 0) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/** Готовый разбор пары APK: считается один раз в фоне. */
internal data class ApkComparison(
    val changes: List<ApkFileChange>,
    val summary: ApkDiffSummary,
    val oldInfo: ManifestInfo?,
    val newInfo: ManifestInfo?,
    val manifest: ManifestDiff,
    val code: DexDiff,
    val resources: ResourceDiff
)

internal fun apkManifest(path: Path): ManifestInfo? = runCatching {
    val bytes = ApkDiff.read(path, "AndroidManifest.xml") ?: return null
    ManifestInfo.parse(AxmlDecoder().decode(bytes))
}.getOrNull()

/** [cancelled] — сравнение больше не нужно: проверяется между этапами, между dex и между классами. */
internal fun compareApks(
    old: Path,
    new: Path,
    options: DexCompareOptions,
    cancelled: () -> Boolean = { false },
    onProgress: (CompareProgress) -> Unit = {}
): ApkComparison {
    checkCancelled(cancelled)
    onProgress(CompareProgress("Читаю оглавление пакетов"))
    val oldEntries = ApkDiff.entries(old)
    val newEntries = ApkDiff.entries(new)

    onProgress(CompareProgress("Сравниваю файлы внутри"))
    val changes = ApkDiff.compare(oldEntries, newEntries)

    checkCancelled(cancelled)
    onProgress(CompareProgress("Читаю манифесты"))
    val oldInfo = apkManifest(old)
    val newInfo = apkManifest(new)

    // Код: все classes*.dex каждого пакета читаются целиком, но только если они вообще различаются
    val dexChanged = changes.any { it.name.endsWith(".dex") && it.kind != DiffKind.SAME }
    val code = if (dexChanged) {
        val oldNames = ApkDiff.dexNames(oldEntries)
        val newNames = ApkDiff.dexNames(newEntries)
        val total = oldNames.size + newNames.size
        var done = 0
        val oldCode = dexesOf(old, oldNames, options, cancelled) { name ->
            onProgress(CompareProgress("Разбираю код старого пакета: $name", ++done, total))
        }
        val newCode = dexesOf(new, newNames, options, cancelled) { name ->
            onProgress(CompareProgress("Разбираю код нового пакета: $name", ++done, total))
        }
        onProgress(CompareProgress("Сравниваю классы", total, total))
        ApkDexDiff.compare(oldCode, newCode)
    } else {
        DexDiff(emptyList(), 0, 0, 0, 0)
    }

    val arscChanged = changes.any { it.name == "resources.arsc" && it.kind != DiffKind.SAME }
    val resources = if (arscChanged) {
        checkCancelled(cancelled)
        onProgress(CompareProgress("Сравниваю ресурсы"))
        ApkResourceDiff.compare(
            resourcesOf(old, oldEntries),
            resourcesOf(new, newEntries)
        )
    } else {
        ResourceDiff(emptyList(), 0, 0)
    }

    return ApkComparison(
        changes = changes,
        summary = ApkDiff.summarize(
            changes,
            runCatching { Files.size(old) }.getOrDefault(0L),
            runCatching { Files.size(new) }.getOrDefault(0L)
        ),
        oldInfo = oldInfo,
        newInfo = newInfo,
        manifest = ApkDiff.compareManifests(oldInfo ?: ManifestInfo(), newInfo ?: ManifestInfo()),
        code = code,
        resources = resources
    )
}

/** Слепки классов пакета: каждый dex читается отдельно, чтобы было видно продвижение. */
private fun dexesOf(
    path: Path,
    dexNames: List<String>,
    options: DexCompareOptions,
    cancelled: () -> Boolean,
    onDex: (String) -> Unit
): Map<String, DexClassSignature> {
    val result = LinkedHashMap<String, DexClassSignature>()
    for (name in dexNames) {
        checkCancelled(cancelled)
        onDex(name)
        val bytes = ApkDiff.read(path, name, limit = 128 * 1024 * 1024)
            ?: throw java.io.IOException("Не удалось прочитать $name в «${path.fileName}» (лимит 128 МиБ)")
        val classes = try {
            ApkDexDiff.read(listOf(bytes), options, cancelled)
        } catch (e: java.io.IOException) {
            throw java.io.IOException("${path.fileName}: $name: ${e.message}", e)
        }
        for ((key, value) in classes) result.putIfAbsent(key, value)
    }
    return result
}

private fun checkCancelled(cancelled: () -> Boolean) {
    if (cancelled()) throw kotlinx.coroutines.CancellationException("Сравнение отменено")
}

private fun resourcesOf(path: Path, entries: Map<String, ApkEntry>): Map<String, String> {
    if ("resources.arsc" !in entries) return emptyMap()
    val bytes = ApkDiff.read(path, "resources.arsc")
        ?: throw java.io.IOException("Не удалось прочитать resources.arsc в «${path.fileName}» (лимит 16 МиБ)")
    return ApkResourceDiff.read(bytes)
}
