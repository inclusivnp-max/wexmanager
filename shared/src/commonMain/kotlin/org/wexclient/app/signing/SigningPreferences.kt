package org.wexclient.app.signing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Properties

/** Выбранные схемы подписи APK. V4 — отдельный файл .idsig рядом с APK (нужна V2 или V3). */
data class SignSchemes(
    val v1: Boolean = true,
    val v2: Boolean = true,
    val v3: Boolean = true,
    val v4: Boolean = false
) {
    val label: String
        get() = listOfNotNull("V1".takeIf { v1 }, "V2".takeIf { v2 }, "V3".takeIf { v3 }, "V4".takeIf { v4 })
            .joinToString("+").ifEmpty { "—" }

    val isValid: Boolean get() = (v1 || v2 || v3) && (!v4 || v2 || v3)
}

/** Настройки подписи (~/.config/wexdesktop/signing.properties). */
object SigningPreferences {
    private val configFile get() = org.wexclient.app.util.AppDirs.config.resolve("signing.properties").toFile()

    /** Подписывать изменённый APK сразу при выходе из него, без вопроса. */
    var autoSignModified by mutableStateOf(false)
        private set

    /** Ключ для быстрой подписи («Подписать», автоподпись). */
    var defaultKeyId by mutableStateOf(SigningKeys.TESTKEY_ID)
        private set

    var schemes by mutableStateOf(SignSchemes())
        private set

    /** В окне подписи: заменить исходный APK (как MT Manager) или сохранить копию. */
    var replaceOriginal by mutableStateOf(true)
        private set

    init {
        try {
            val f = configFile
            if (f.exists()) {
                val p = Properties()
                f.inputStream().use { p.load(it) }
                fun bool(k: String, def: Boolean) = p.getProperty(k)?.toBooleanStrictOrNull() ?: def
                autoSignModified = bool("autoSignModified", false)
                defaultKeyId = p.getProperty("defaultKey") ?: defaultKeyId
                schemes = SignSchemes(bool("v1", true), bool("v2", true), bool("v3", true), bool("v4", false))
                    .takeIf { it.isValid } ?: SignSchemes()
                replaceOriginal = bool("replaceOriginal", true)
            }
        } catch (_: Throwable) {
            // по умолчанию
        }
    }

    /** Ключ для быстрой подписи (если выбранный удалён — AOSP testkey). */
    val defaultKey: SigningKey
        get() = SigningKeys.byId(defaultKeyId) ?: SigningKeys.testKey

    fun updateAutoSignModified(value: Boolean) {
        autoSignModified = value
        save()
    }

    fun updateDefaults(keyId: String, schemes: SignSchemes, replaceOriginal: Boolean) {
        defaultKeyId = keyId
        if (schemes.isValid) this.schemes = schemes
        this.replaceOriginal = replaceOriginal
        save()
    }

    fun updateDefaultKey(keyId: String) {
        defaultKeyId = keyId
        save()
    }

    /** Схемы для быстрой подписи; недопустимое сочетание не сохраняется. */
    fun updateSchemes(value: SignSchemes): Boolean {
        if (!value.isValid) return false
        schemes = value
        save()
        return true
    }

    private fun save() {
        try {
            val f = configFile
            f.parentFile?.mkdirs()
            val p = Properties()
            p.setProperty("autoSignModified", autoSignModified.toString())
            p.setProperty("defaultKey", defaultKeyId)
            p.setProperty("v1", schemes.v1.toString())
            p.setProperty("v2", schemes.v2.toString())
            p.setProperty("v3", schemes.v3.toString())
            p.setProperty("v4", schemes.v4.toString())
            p.setProperty("replaceOriginal", replaceOriginal.toString())
            org.wexclient.app.util.AtomicFiles.writeProperties(f, p, "WEX Desktop Signing Preferences")
        } catch (_: Throwable) {
            // не критично
        }
    }
}
