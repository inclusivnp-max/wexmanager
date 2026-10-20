package org.wexclient.app.adb

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.wexclient.app.util.ProcessRunner

/** Приложение на телефоне. */
data class DevicePackage(
    val name: String,
    /** Основной APK на телефоне: /data/app/…/base.apk или /system/app/…/X.apk. */
    val apkPath: String,
    val versionCode: Long?,
    val versionName: String?,
    /** Встроено в прошивку — в том числе обновлённое из магазина. */
    val system: Boolean
) {
    /** «1.8.0 (24)», «24» или пусто. */
    val versionLabel: String
        get() = when {
            versionName != null && versionCode != null -> "$versionName ($versionCode)"
            versionName != null -> versionName
            versionCode != null -> versionCode.toString()
            else -> ""
        }
}

/**
 * Приложения на телефоне: список с версиями, выгрузка APK на компьютер, остановка, очистка данных.
 * Установка, удаление и запуск — в [AdbManager]. Имя пакета попадает в команду оболочки телефона,
 * поэтому каждое проверяется: пропускаются только допустимые в Android имена.
 */
object AdbPackages {

    /** Разделитель ответов нескольких команд в одном вызове adb shell. */
    private const val MARK = "--wex-section--"

    private fun adb(): String = AdbManager.adbPath() ?: throw IOException(
        "adb не найден. Установите android-tools (sudo pacman -S android-tools) или Android SDK Platform-Tools"
    )

    private fun requirePackage(pkg: String) {
        if (!AdbManager.isValidPackageName(pkg)) throw IOException("Недопустимое имя пакета: «${pkg.take(80)}»")
    }

    private fun lastLine(output: String): String? = output.lines().lastOrNull { it.isNotBlank() }?.trim()

    /** Все приложения с версиями — за один вызов adb: список pm, сторонние отдельно, имена версий из dumpsys. */
    fun list(serial: String): List<DevicePackage> {
        val script = "pm list packages -f --show-versioncode; echo $MARK; pm list packages -3; echo $MARK; " +
            "dumpsys package packages | grep -E '^  Package \\[|^    versionName='"
        val r = ProcessRunner.run(listOf(adb(), "-s", serial, "shell", script), timeoutMs = 60_000)
        if (r.timedOut) throw IOException("Телефон не ответил за минуту")
        val packages = parseList(r.output)
        if (packages.isEmpty()) throw IOException(lastLine(r.output) ?: "Телефон не отдал список приложений")
        return packages
    }

    internal fun parseList(output: String): List<DevicePackage> {
        val sections = output.split(MARK)
        val thirdParty = sections.getOrNull(1).orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .toHashSet()
        // «  Package [com.foo] (1a2b3c):» и ниже «    versionName=1.2»; скрытые системные копии идут позже — берём первое
        val versions = HashMap<String, String>()
        var current: String? = null
        for (line in sections.getOrNull(2).orEmpty().lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("Package [") -> current = t.substringAfter('[').substringBefore(']')
                t.startsWith("versionName=") -> current?.let { versions.putIfAbsent(it, t.removePrefix("versionName=")) }
            }
        }
        return sections.firstOrNull().orEmpty().lineSequence()
            .mapNotNull { parsePackageLine(it.trim()) }
            .map { (path, name, code) ->
                DevicePackage(
                    name = name,
                    apkPath = path,
                    versionCode = code,
                    versionName = versions[name]?.takeIf { it.isNotBlank() && it != "null" },
                    system = name !in thirdParty
                )
            }
            .sortedBy { it.name }
            .toList()
    }

    /** «package:/data/app/~~x==/com.foo-y==/base.apk=com.foo versionCode:24» → путь, имя, код версии. */
    internal fun parsePackageLine(line: String): Triple<String, String, Long?>? {
        if (!line.startsWith("package:")) return null
        val head = line.removePrefix("package:").substringBefore(' ')
        // В пути бывает «=» (папки в /data/app названы в base64), в имени пакета — нет: делим по последнему
        val eq = head.lastIndexOf('=')
        if (eq <= 0) return null
        val name = head.substring(eq + 1)
        if (!AdbManager.isValidPackageName(name)) return null
        val code = Regex("versionCode:(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
        return Triple(head.substring(0, eq), name, code)
    }

    /** Все APK приложения на телефоне: основной и split-части. */
    fun apkPaths(serial: String, pkg: String): List<String> {
        requirePackage(pkg)
        val r = ProcessRunner.run(listOf(adb(), "-s", serial, "shell", "pm path $pkg"), timeoutMs = 30_000)
        val paths = r.output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:/") }
            .map { it.removePrefix("package:") }
            .toList()
        if (paths.isEmpty()) throw IOException(lastLine(r.output) ?: "Android не отдал путь к APK $pkg")
        return paths
    }

    /** Имя файла для сохранённого APK: «пакет-версия». */
    internal fun fileBase(pkg: DevicePackage): String {
        val version = (pkg.versionName ?: pkg.versionCode?.toString())?.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        return if (version.isNullOrEmpty()) pkg.name else "${pkg.name}-$version"
    }

    /**
     * APK приложения — в папку [dir]: одиночный — «пакет-версия.apk», с частями — набор «пакет-версия.apks»
     * (base.apk и части в корне архива, как у SAI): WEX открывает его как обычный набор и ставит обратно.
     * Всё собирается во временной папке — прерванное копирование не оставляет полфайла.
     * Возвращает сохранённый файл; null — прервано.
     */
    fun saveApk(
        serial: String,
        pkg: DevicePackage,
        dir: Path,
        isCancelled: () -> Boolean = { false },
        onStep: (String) -> Unit = {}
    ): Path? {
        val remote = apkPaths(serial, pkg.name)
        val work = org.wexclient.app.util.ProcessScratch.work.newDir("adb-pull-")
        try {
            val parts = remote.mapIndexed { i, path ->
                // Имена частей внутри приложения не повторяются: base.apk, split_config.arm64_v8a.apk…
                val local = work.resolve(path.substringAfterLast('/'))
                onStep(if (remote.size == 1) "Копирование ${local.fileName}…" else "Копирование ${i + 1} из ${remote.size}: ${local.fileName}…")
                val r = ProcessRunner.run(
                    listOf(adb(), "-s", serial, "pull", path, local.toString()),
                    timeoutMs = 30 * 60_000,
                    isCancelled = isCancelled
                )
                if (r.cancelled) return null
                if (!r.ok || !Files.isRegularFile(local)) {
                    throw IOException("Не скопировался ${local.fileName}: ${lastLine(r.output).orEmpty()}")
                }
                local
            }
            val base = fileBase(pkg)
            val ready = if (parts.size == 1) parts[0] else work.resolve("$base.apks").also { zip(parts, it) }
            val target = org.wexclient.app.vfs.FileTransfers.freeName(dir, if (parts.size == 1) "$base.apk" else "$base.apks")
            Files.move(ready, target)
            return target
        } finally {
            org.wexclient.app.util.FileTree.delete(work)
        }
    }

    /** Набор без сжатия: APK и так сжаты, а повторное сжатие только замедлит и выгрузку, и установку. */
    private fun zip(parts: List<Path>, target: Path) {
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            for (part in parts) {
                zip.putNextEntry(ZipEntry(part.fileName.toString()))
                Files.copy(part, zip)
                zip.closeEntry()
            }
        }
    }

    /** Остановить приложение — как «Остановить» в настройках телефона. */
    fun forceStop(serial: String, pkg: String): String {
        requirePackage(pkg)
        val r = ProcessRunner.run(listOf(adb(), "-s", serial, "shell", "am force-stop $pkg"), timeoutMs = 20_000)
        if (!r.ok) throw IOException(lastLine(r.output) ?: "Не остановилось $pkg")
        return "Приложение $pkg остановлено"
    }

    /** Стереть данные приложения: настройки, вход, кэш — как сразу после установки. */
    fun clearData(serial: String, pkg: String): String {
        requirePackage(pkg)
        val r = ProcessRunner.run(listOf(adb(), "-s", serial, "shell", "pm clear $pkg"), timeoutMs = 60_000)
        if (r.output.lines().none { it.trim() == "Success" }) throw IOException(lastLine(r.output) ?: "Данные $pkg не очистились")
        return "Данные $pkg очищены"
    }
}
