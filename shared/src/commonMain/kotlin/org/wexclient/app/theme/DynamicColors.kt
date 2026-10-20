package org.wexclient.app.theme

import androidx.compose.ui.graphics.Color
import java.io.File

/**
 * Цвета оболочки Noctalia: она пишет их в ~/.config/noctalia/colors.json и обновляет при
 * смене обоев или схемы. Отсюда берётся «Динамическая» тема — окно красится в тон рабочему столу.
 */
object DynamicColors {

    private val configFile: File
        get() = org.wexclient.app.util.AppDirs.configRoot.resolve("noctalia/colors.json").toFile()

    /** Когда файл цветов менялся в последний раз (0 — файла нет). */
    fun stamp(): Long = configFile.let { if (it.isFile) it.lastModified() else 0L }

    /** Есть ли на этой машине цвета системной оболочки. */
    val available: Boolean get() = configFile.isFile

    /** Палитра по системным цветам или null, если их нет или файл испорчен. */
    fun palette(): AppPalette? {
        val file = configFile
        if (!file.isFile) return null
        val colors = runCatching { parse(file.readText()) }.getOrNull() ?: return null
        val primary = colors["mPrimary"] ?: return null
        val surface = colors["mSurface"] ?: return null
        val onSurface = colors["mOnSurface"] ?: Color.White
        val secondary = colors["mSecondary"] ?: primary
        val tertiary = colors["mTertiary"] ?: colors["mHover"] ?: secondary
        val surfaceVariant = colors["mSurfaceVariant"] ?: surface.mix(onSurface, 0.06f)
        val outline = colors["mOutline"] ?: onSurface.mix(surface, 0.55f)
        return AppPalette(
            primary = primary,
            onPrimary = colors["mOnPrimary"] ?: surface,
            // Контейнеров в системной схеме нет — выводим их из тона акцента и фона
            primaryContainer = primary.mix(surface, 0.72f),
            onPrimaryContainer = primary.mix(Color.White, 0.55f),
            secondary = secondary,
            onSecondary = colors["mOnSecondary"] ?: surface,
            secondaryContainer = secondary.mix(surface, 0.75f),
            onSecondaryContainer = secondary.mix(Color.White, 0.55f),
            tertiary = tertiary,
            onTertiary = colors["mOnTertiary"] ?: surface,
            tertiaryContainer = tertiary.mix(surface, 0.75f),
            onTertiaryContainer = tertiary.mix(Color.White, 0.55f),
            background = surface,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = colors["mOnSurfaceVariant"] ?: onSurface.mix(surface, 0.25f),
            outline = outline,
            outlineVariant = outline.mix(surface, 0.55f),
            error = colors["mError"] ?: Color(0xFFFFB4AB),
            onError = colors["mOnError"] ?: Color(0xFF690005)
        )
    }

    /** Простой разбор: имя в кавычках, двоеточие, цвет #rrggbb в кавычках. */
    internal fun parse(json: String): Map<String, Color> {
        val out = LinkedHashMap<String, Color>()
        val pattern = Regex("\"(\\w+)\"\\s*:\\s*\"(#[0-9a-fA-F]{3,8})\"")
        for (match in pattern.findAll(json)) {
            color(match.groupValues[2])?.let { out[match.groupValues[1]] = it }
        }
        return out
    }

    internal fun color(value: String): Color? {
        val hex = value.removePrefix("#")
        return runCatching {
            when (hex.length) {
                3 -> Color(
                    red = hex[0].digitToInt(16) * 17,
                    green = hex[1].digitToInt(16) * 17,
                    blue = hex[2].digitToInt(16) * 17
                )
                6 -> Color(hex.toLong(16) or 0xFF000000L)
                8 -> {
                    // Noctalia пишет #rrggbbaa, а Compose ждёт альфу первой
                    val rgba = hex.toLong(16)
                    Color(((rgba ushr 8) or ((rgba and 0xFF) shl 24)))
                }
                else -> null
            }
        }.getOrNull()
    }
}
