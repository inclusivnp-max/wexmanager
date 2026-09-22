package org.wexclient.app.adb

import org.wexclient.app.util.ProcessRunner
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

enum class AdbDeviceState(val title: String) {
    READY("Готово"),
    UNAUTHORIZED("Нет доступа"),
    OFFLINE("Не в сети"),
    NO_PERMISSIONS("Нет прав USB"),
    OTHER("Недоступно")
}

data class AdbDevice(
    val serial: String,
    val rawState: String,
    val model: String? = null,
    val product: String? = null,
    val deviceCode: String? = null,
    val displayName: String = serial,
    val androidVersion: String? = null,
    val sdk: Int? = null,
    val abi: String? = null
) {
    val state: AdbDeviceState
        get() = when (rawState) {
            "device" -> AdbDeviceState.READY
            "unauthorized" -> AdbDeviceState.UNAUTHORIZED
            "offline" -> AdbDeviceState.OFFLINE
            "no permissions" -> AdbDeviceState.NO_PERMISSIONS
            else -> AdbDeviceState.OTHER
        }

    val isEmulator: Boolean get() = serial.startsWith("emulator-")
    val isWireless: Boolean get() = serial.contains(':') || serial.contains("._adb-tls-")
}

data class InstallOptions(
    val reinstall: Boolean = true,
    val allowDowngrade: Boolean = false,
    val grantPermissions: Boolean = false,
    val allowTestOnly: Boolean = true
)

data class InstallResult(
    val success: Boolean,
    val code: String?,
    val explanation: String?,
    val output: String
) {
    /** Что можно предложить пользователю по коду ошибки. */
    val suggestion: InstallSuggestion?
        get() = when (code) {
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" -> InstallSuggestion.UNINSTALL_AND_RETRY
            "INSTALL_FAILED_VERSION_DOWNGRADE" -> InstallSuggestion.ALLOW_DOWNGRADE
            "INSTALL_FAILED_TEST_ONLY" -> InstallSuggestion.ALLOW_TEST
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES",
            "INSTALL_PARSE_FAILED_BAD_SIGNATURE",
            "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES",
            "INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING",
            "INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED" -> InstallSuggestion.SIGN
            else -> null
        }
}

enum class InstallSuggestion { UNINSTALL_AND_RETRY, ALLOW_DOWNGRADE, ALLOW_TEST, SIGN }

/** Обёртка над adb: список устройств с именами, установка (в т.ч. split/XAPK), сервер, Wi‑Fi. */
object AdbManager {

    private val propsCache = ConcurrentHashMap<String, Map<String, String>>()

    fun adbPath(): String? {
        ProcessRunner.which("adb")?.let { return it }
        val home = System.getProperty("user.home")
        val candidates = listOfNotNull(
            System.getenv("ANDROID_HOME")?.let { "$it/platform-tools/adb" },
            System.getenv("ANDROID_SDK_ROOT")?.let { "$it/platform-tools/adb" },
            "$home/Android/Sdk/platform-tools/adb",
            "/opt/android-sdk/platform-tools/adb"
        )
        return candidates.firstOrNull { File(it).canExecute() }
    }

    private fun adb(): String = adbPath() ?: throw IOException(
        "adb не найден. Установите android-tools (sudo pacman -S android-tools) или Android SDK Platform-Tools"
    )

    fun version(): String? = try {
        val out = ProcessRunner.run(listOf(adb(), "version"), timeoutMs = 5_000).output
        out.lineSequence().firstOrNull { it.startsWith("Version") }?.removePrefix("Version ")?.trim()
            ?: out.lineSequence().firstOrNull()?.trim()
    } catch (_: Exception) {
        null
    }

    fun listDevices(): List<AdbDevice> {
        val r = ProcessRunner.run(listOf(adb(), "devices", "-l"), timeoutMs = 10_000)
        if (r.timedOut) throw IOException("adb не отвечает (таймаут). Попробуйте перезапустить сервер ADB")
        return parseDevices(r.output).map { d ->
            if (d.state != AdbDeviceState.READY) return@map d
            val props = propsCache[d.serial] ?: readProps(d.serial)?.also { propsCache[d.serial] = it }
            if (props == null) d else d.copy(
                displayName = displayName(d, props),
                androidVersion = props["ro.build.version.release"]?.takeIf { it.isNotBlank() },
                sdk = props["ro.build.version.sdk"]?.toIntOrNull(),
                abi = props["ro.product.cpu.abi"]?.takeIf { it.isNotBlank() }
            )
        }
    }

    private val propKeys = listOf(
        "ro.product.manufacturer", "ro.product.model", "ro.product.marketname",
        "ro.product.vendor.marketname", "ro.product.odm.marketname", "ro.config.marketing_name",
        "ro.build.version.release", "ro.build.version.sdk", "ro.product.cpu.abi", "ro.product.brand"
    )

    private fun readProps(serial: String): Map<String, String>? {
        // Один shell-вызов: getprop по каждому ключу, значения построчно в том же порядке
        val script = propKeys.joinToString("; ") { "echo \"$it=\$(getprop $it)\"" }
        val r = try {
            ProcessRunner.run(listOf(adb(), "-s", serial, "shell", script), timeoutMs = 8_000)
        } catch (_: Exception) {
            return null
        }
        if (!r.ok) return null
        return r.output.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=').trim() }
    }

    internal fun parseDevices(output: String): List<AdbDevice> {
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") && !it.startsWith("adb ") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val kv = parts.drop(2).filter { ':' in it }.associate { it.substringBefore(':') to it.substringAfter(':') }
                // «no permissions (...)» — состояние из нескольких слов
                val state = if (parts[1] == "no") "no permissions" else parts[1]
                val model = kv["model"]?.replace('_', ' ')
                AdbDevice(
                    serial = parts[0],
                    rawState = state,
                    model = model,
                    product = kv["product"],
                    deviceCode = kv["device"],
                    displayName = model ?: parts[0]
                )
            }
            .toList()
    }

    /** «Google Pixel 9 Pro»: маркетинговое имя, иначе производитель + модель. */
    internal fun displayName(d: AdbDevice, props: Map<String, String>): String {
        val market = listOf("ro.product.marketname", "ro.product.vendor.marketname", "ro.product.odm.marketname", "ro.config.marketing_name")
            .firstNotNullOfOrNull { props[it]?.takeIf { v -> v.isNotBlank() } }
        val manufacturer = (props["ro.product.manufacturer"]?.takeIf { it.isNotBlank() } ?: props["ro.product.brand"] ?: "")
            .replaceFirstChar { it.uppercase() }
        val model = props["ro.product.model"]?.takeIf { it.isNotBlank() } ?: d.model ?: d.serial
        val name = when {
            market != null && manufacturer.isNotEmpty() && !market.startsWith(manufacturer, ignoreCase = true) &&
                props["ro.product.brand"]?.let { market.startsWith(it, ignoreCase = true) } != true -> "$manufacturer $market"
            market != null -> market
            manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
        return if (d.isEmulator) "Эмулятор · $name" else name
    }

    fun restartServer(): String {
        propsCache.clear()
        val kill = ProcessRunner.run(listOf(adb(), "kill-server"), timeoutMs = 10_000)
        val start = ProcessRunner.run(listOf(adb(), "start-server"), timeoutMs = 20_000)
        if (!start.ok) throw IOException("Не удалось запустить сервер ADB:\n${(kill.output + start.output).trim()}")
        return "Сервер ADB перезапущен"
    }

    fun connect(hostPort: String): String {
        val r = ProcessRunner.run(listOf(adb(), "connect", hostPort.trim()), timeoutMs = 15_000)
        val out = r.output.trim()
        if (!r.ok || out.contains("failed", true) || out.contains("cannot", true)) throw IOException(out.ifEmpty { "Не удалось подключиться" })
        return out
    }

    fun pair(hostPort: String, code: String): String {
        val r = ProcessRunner.run(listOf(adb(), "pair", hostPort.trim(), code.trim()), timeoutMs = 30_000)
        val out = r.output.trim()
        if (!r.ok || !out.contains("Successfully", true)) throw IOException(out.ifEmpty { "Сопряжение не удалось" })
        return out
    }

    fun disconnect(serial: String): String {
        propsCache.remove(serial)
        return ProcessRunner.run(listOf(adb(), "disconnect", serial), timeoutMs = 10_000).output.trim()
    }

    private val packageNamePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")

    /**
     * Имя пакета берётся из манифеста APK — то есть из файла, которому нельзя доверять,
     * а adb shell склеивает аргументы в строку для оболочки телефона. Пропускаем только
     * допустимые в Android имена: «x; rm -rf /sdcard» выполнился бы на устройстве.
     */
    fun isValidPackageName(pkg: String): Boolean = pkg.length <= 255 && packageNamePattern.matches(pkg)

    private fun requirePackage(pkg: String) {
        if (!isValidPackageName(pkg)) throw IOException("Недопустимое имя пакета: «${pkg.take(80)}»")
    }

    /** Установленная версия пакета («1.2 (45)») или null. */
    fun installedVersion(serial: String, pkg: String): String? {
        if (!isValidPackageName(pkg)) return null
        val r = try {
            ProcessRunner.run(listOf(adb(), "-s", serial, "shell", "dumpsys package '$pkg' | grep -E 'versionName=|versionCode=' | head -n 2"), timeoutMs = 10_000)
        } catch (_: Exception) {
            return null
        }
        val name = Regex("versionName=(\\S+)").find(r.output)?.groupValues?.get(1)
        val code = Regex("versionCode=(\\d+)").find(r.output)?.groupValues?.get(1)
        return when {
            name != null && code != null -> "$name ($code)"
            name != null -> name
            else -> null
        }
    }

    fun uninstall(serial: String, pkg: String): String {
        requirePackage(pkg)
        val r = ProcessRunner.run(listOf(adb(), "-s", serial, "uninstall", pkg), timeoutMs = 60_000)
        val out = r.output.trim()
        if (!out.contains("Success")) throw IOException(out.ifEmpty { "Не удалось удалить $pkg" })
        return "Приложение $pkg удалено с устройства"
    }

    fun launch(serial: String, pkg: String): String {
        requirePackage(pkg)
        val r = ProcessRunner.run(
            listOf(adb(), "-s", serial, "shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"),
            timeoutMs = 20_000
        )
        if (r.output.contains("No activities found", true) || r.output.contains("aborted", true)) {
            throw IOException("У $pkg нет запускаемой активности")
        }
        return "Приложение $pkg запущено"
    }

    /**
     * Установка APK или split-архива (.xapk/.apks/.apkm): APK из архива распаковываются
     * во временную папку и ставятся через install-multiple; OBB из XAPK копируются на устройство.
     */
    fun install(
        serial: String,
        source: Path,
        options: InstallOptions,
        isCancelled: () -> Boolean = { false },
        onLine: (String) -> Unit = {}
    ): InstallResult {
        val work = org.wexclient.app.util.ProcessScratch.work.newDir("adb-install-")
        try {
            val lower = source.fileName?.toString()?.lowercase() ?: ""
            val isBundle = lower.endsWith(".xapk") || lower.endsWith(".apks") || lower.endsWith(".apkm")
            val local = if (source.fileSystem === FileSystems.getDefault()) source.toFile() else {
                // APK внутри архива — adb нужен файл на диске
                work.resolve(source.fileName.toString()).also {
                    Files.copy(source, it, StandardCopyOption.REPLACE_EXISTING)
                }.toFile()
            }

            val flags = buildList {
                if (options.reinstall) add("-r")
                if (options.allowDowngrade) add("-d")
                if (options.grantPermissions) add("-g")
                if (options.allowTestOnly) add("-t")
            }

            var obbFiles: List<Pair<File, String>> = emptyList()
            val command = if (isBundle) {
                // Распаковка XAPK с OBB — гигабайты: «Прервать» останавливает и её
                val (apks, obbs) = try {
                    extractBundle(local, work.toFile(), isCancelled)
                } catch (_: ExtractCancelled) {
                    return InstallResult(false, "CANCELLED", "Установка отменена", "")
                }
                if (apks.isEmpty()) throw IOException("В архиве ${local.name} нет APK")
                obbFiles = obbs
                onLine("Split-установка: ${apks.size} APK (${apks.joinToString { it.name }})")
                listOf(adb(), "-s", serial, "install-multiple") + flags + apks.map { it.absolutePath }
            } else {
                listOf(adb(), "-s", serial, "install") + flags + local.absolutePath
            }

            if (isCancelled()) return InstallResult(false, "CANCELLED", "Установка отменена", "")
            onLine("$ " + command.drop(1).joinToString(" ") { if (it.contains(' ')) "'$it'" else it })
            val r = ProcessRunner.run(command, timeoutMs = 10 * 60_000, isCancelled = isCancelled, onLine = onLine)
            if (r.cancelled) return InstallResult(false, "CANCELLED", "Установка отменена", r.output)
            if (r.timedOut) return InstallResult(false, "TIMEOUT", "adb не ответил за 10 минут", r.output)

            val result = parseInstallOutput(r.output, r.exitCode)
            if (result.success && obbFiles.isNotEmpty()) {
                for ((file, remote) in obbFiles) {
                    onLine("$ push ${file.name} → $remote")
                    val push = ProcessRunner.run(listOf(adb(), "-s", serial, "push", file.absolutePath, remote), timeoutMs = 30 * 60_000, isCancelled = isCancelled, onLine = onLine)
                    if (!push.ok) {
                        return InstallResult(false, "OBB_PUSH_FAILED", "Приложение установлено, но OBB не скопирован: ${push.output.takeLast(300)}", r.output + push.output)
                    }
                }
            }
            return result
        } finally {
            org.wexclient.app.util.FileTree.delete(work)
        }
    }

    /**
     * Куда на устройстве положить OBB из XAPK: только внутрь /sdcard/Android/obb/.
     * Имя записи задаёт автор архива — «Android/obb/../../DCIM/x» перезаписал бы чужие файлы.
     */
    internal fun obbRemotePath(entryName: String): String? {
        val rel = entryName.replace('\\', '/').substringAfter("Android/obb/", "")
        val parts = rel.split('/')
        if (parts.size < 2 || parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (!isValidPackageName(parts[0])) return null
        if (parts.any { part -> part.any { it.code < 0x20 || it == '\u007f' } }) return null
        return "/sdcard/Android/obb/" + parts.joinToString("/")
    }

    private class ExtractCancelled : Exception()

    internal fun extractBundle(
        bundle: File,
        dir: File,
        isCancelled: () -> Boolean = { false }
    ): Pair<List<File>, List<Pair<File, String>>> {
        val apks = mutableListOf<Pair<File, String>>()
        val obbs = mutableListOf<Pair<File, String>>()
        ZipFile(bundle).use { zip ->
            // bundletool кладёт рядом со «splits/» ещё «standalones/» — целые APK для старых Android.
            // Вместе с частями их не ставят: install-multiple на этом падает
            val splitsDir = zip.entries().asSequence().any {
                it.name.replace('\\', '/').startsWith("splits/") && it.name.lowercase().endsWith(".apk")
            }
            for (e in zip.entries()) {
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                val base = name.substringAfterLast('/')
                val isApk = base.lowercase().endsWith(".apk")
                if (isApk) {
                    val wanted = if (splitsDir) name.startsWith("splits/") || name.startsWith("asset-slices/")
                    else !name.startsWith("standalones/") && !name.startsWith("instant/")
                    if (!wanted) continue
                }
                val target = when {
                    isApk -> File(dir, "${apks.size}_$base").also { apks.add(it to base) }
                    name.lowercase().endsWith(".obb") && name.contains("Android/obb/") -> {
                        val remote = obbRemotePath(name) ?: continue
                        File(dir, "obb_${obbs.size}_$base").also { obbs.add(it to remote) }
                    }
                    else -> continue
                }
                if (isCancelled()) throw ExtractCancelled()
                zip.getInputStream(e).use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (isCancelled()) throw ExtractCancelled()
                        }
                    }
                }
            }
        }
        // Основной APK первым — install-multiple так надёжнее
        val ordered = apks.sortedBy { (_, base) ->
            val b = base.lowercase()
            if (b == "base.apk" || !(b.startsWith("split") || b.startsWith("config."))) 0 else 1
        }.map { it.first }
        return ordered to obbs
    }

    internal fun parseInstallOutput(output: String, exitCode: Int): InstallResult {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { it == "Success" } && exitCode == 0) {
            return InstallResult(true, null, "Приложение установлено", output)
        }
        val code = Regex("\\b(INSTALL_[A-Z_]+)").find(output)?.groupValues?.get(1)
            ?: when {
                output.contains("resources.arsc", true) && output.contains("-124") -> "INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED"
                output.contains("device unauthorized", true) -> "DEVICE_UNAUTHORIZED"
                output.contains("device offline", true) -> "DEVICE_OFFLINE"
                output.contains("not found", true) && output.contains("device", true) -> "DEVICE_NOT_FOUND"
                else -> null
            }
        val normalized = if (code == "INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION" && output.contains("resources.arsc", true)) {
            "INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED"
        } else code
        return InstallResult(false, normalized, explain(normalized, output), output)
    }

    private fun explain(code: String?, output: String): String = when (code) {
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" ->
            "Подпись APK не совпадает с установленной версией. Удалите приложение с устройства (данные будут потеряны) и установите заново."
        "INSTALL_FAILED_VERSION_DOWNGRADE" ->
            "На устройстве стоит более новая версия. Включите «Разрешить понижение версии»."
        "INSTALL_PARSE_FAILED_NO_CERTIFICATES", "INSTALL_PARSE_FAILED_BAD_SIGNATURE",
        "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES", "INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING" ->
            "APK не подписан или подпись повреждена (файлы внутри менялись). Подпишите APK и повторите."
        "INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED" ->
            "resources.arsc сжат или не выровнен — для Android 11+ так нельзя. Подпишите APK заново (WEX пересоберёт его правильно)."
        "INSTALL_FAILED_TEST_ONLY" -> "APK помечен как тестовый (testOnly). Включите «Разрешить тестовые APK»."
        "INSTALL_FAILED_OLDER_SDK" -> "Версия Android на устройстве ниже, чем требует приложение (minSdk)."
        "INSTALL_FAILED_NO_MATCHING_ABIS" -> "В APK нет нативных библиотек под архитектуру процессора устройства."
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> "На устройстве недостаточно свободного места."
        "INSTALL_FAILED_ALREADY_EXISTS" -> "Приложение уже установлено — включите «Переустановить с сохранением данных»."
        "INSTALL_FAILED_USER_RESTRICTED", "INSTALL_FAILED_ABORTED" ->
            "Установка отклонена на устройстве. Подтвердите её на экране; на Xiaomi/MIUI включите «Установка через USB» в параметрах разработчика."
        "INSTALL_FAILED_VERIFICATION_FAILURE" -> "Установку заблокировала проверка (Play Protect). Отключите проверку приложений через USB."
        "INSTALL_FAILED_DUPLICATE_PERMISSION" -> "Другое установленное приложение уже объявляет такое же разрешение."
        "INSTALL_FAILED_CONFLICTING_PROVIDER" -> "Конфликт content provider с другим установленным приложением."
        "INSTALL_FAILED_INVALID_APK", "INSTALL_PARSE_FAILED_NOT_APK" -> "Файл повреждён или это не APK (для split-APK нужны все части)."
        "INSTALL_FAILED_MISSING_SPLIT" -> "Не хватает split-частей: установите полный набор (XAPK/APKS)."
        "INSTALL_FAILED_DEPRECATED_SDK_VERSION" -> "Слишком старый targetSdk для этой версии Android (нужен adb install --bypass-low-target-sdk-block)."
        "DEVICE_UNAUTHORIZED" -> "Устройство не авторизовано: подтвердите отладку по USB на экране телефона."
        "DEVICE_OFFLINE" -> "Устройство не в сети. Переподключите кабель или перезапустите сервер ADB."
        "DEVICE_NOT_FOUND" -> "Устройство не найдено — обновите список."
        null -> output.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "Неизвестная ошибка adb"
        else -> code
    }
}
