package org.wexclient.app.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Properties

enum class AppTheme(val title: String, val subtitle: String) {
    DYNAMIC("Динамическая", "Цвета оболочки Noctalia"),
    NOCTALIA("Noctalia", "Тёплая тёмная"),
    MIDNIGHT("Midnight", "Синяя тёмная"),
    FOREST("Forest", "Зелёная тёмная"),
    DRACULA("Dracula", "Фиолетовая тёмная"),
    NORD("Nord", "Холодная полярная"),
    GRUVBOX("Gruvbox", "Тёплая ретро"),
    TOKYO_NIGHT("Tokyo Night", "Неоновая ночная"),
    CATPPUCCIN("Catppuccin", "Мягкая пастельная"),
    SOLARIZED("Solarized", "Классическая тёмная"),
    MONOKAI("Monokai", "Контрастная неоновая"),
    ONE_DARK("One Dark", "Спокойная синяя"),
    ROSE_PINE("Rosé Pine", "Розово-сиреневая"),
    EVERFOREST("Everforest", "Мягкая лесная"),
    AYU("Ayu Dark", "Глубокая чёрная"),
    KANAGAWA("Kanagawa", "Приглушённая чернильная")
}

enum class AppContrast(val title: String) {
    STANDARD("Обычный"),
    MEDIUM("Средний"),
    HIGH("Высокий")
}

object ThemePreferences {
    private val configDir = File(System.getProperty("user.home"), ".config/wexdesktop")
    private val configFile = File(configDir, "theme.properties")

    var appTheme by mutableStateOf(AppTheme.NOCTALIA)
        private set

    var appContrast by mutableStateOf(AppContrast.STANDARD)
        private set

    var colorScheme: ColorScheme by mutableStateOf(NoctaliaPalette.toScheme())
        private set

    init {
        load()
        apply()
    }

    fun paletteOf(theme: AppTheme): AppPalette = when (theme) {
        // Системные цвета читаются каждый раз: оболочка меняет их вместе с обоями
        AppTheme.DYNAMIC -> DynamicColors.palette() ?: NoctaliaPalette
        AppTheme.NOCTALIA -> NoctaliaPalette
        AppTheme.MIDNIGHT -> MidnightPalette
        AppTheme.FOREST -> ForestPalette
        AppTheme.DRACULA -> DraculaPalette
        AppTheme.NORD -> NordPalette
        AppTheme.GRUVBOX -> GruvboxPalette
        AppTheme.TOKYO_NIGHT -> TokyoNightPalette
        AppTheme.CATPPUCCIN -> CatppuccinPalette
        AppTheme.SOLARIZED -> SolarizedPalette
        AppTheme.MONOKAI -> MonokaiPalette
        AppTheme.ONE_DARK -> OneDarkPalette
        AppTheme.ROSE_PINE -> RosePinePalette
        AppTheme.EVERFOREST -> EverforestPalette
        AppTheme.AYU -> AyuPalette
        AppTheme.KANAGAWA -> KanagawaPalette
    }

    fun setTheme(theme: AppTheme) {
        if (appTheme != theme) {
            appTheme = theme
            save()
            apply()
        }
    }

    fun setContrast(contrast: AppContrast) {
        if (appContrast != contrast) {
            appContrast = contrast
            save()
            apply()
        }
    }

    /** Пересобрать цвета: сменились системные цвета или прозрачность панелей. */
    fun refresh() = apply()

    private fun apply() {
        val adjusted = paletteOf(appTheme).withContrast(appContrast)
        adjusted.applyToGlobals()
        // Цвета ANSI в консоли — из той же темы
        org.wexclient.app.terminal.WexPalette.apply(appTheme)
        // С фоновой картинкой или живым фоном панели могут быть полупрозрачными — фон виден сквозь них
        val opacity = if (BackgroundPreferences.hasBackdrop) BackgroundPreferences.panelOpacity / 100f else 1f
        colorScheme = adjusted.toScheme(opacity)
        dynamicStamp = DynamicColors.stamp()
    }

    /** Когда в последний раз читались системные цвета (для слежения за их сменой). */
    var dynamicStamp: Long = 0L
        private set

    /** Системные цвета поменялись — перекрасить окно. */
    fun refreshDynamicIfChanged() {
        if (appTheme != AppTheme.DYNAMIC) return
        if (DynamicColors.stamp() != dynamicStamp) apply()
    }

    private fun load() {
        try {
            if (configFile.exists()) {
                val props = Properties()
                configFile.inputStream().use { props.load(it) }
                props.getProperty("theme")?.let { name ->
                    runCatching { appTheme = AppTheme.valueOf(name) }
                }
                props.getProperty("contrast")?.let { name ->
                    runCatching { appContrast = AppContrast.valueOf(name) }
                }
            }
        } catch (_: Throwable) {
            // Fallback to defaults
        }
    }

    private fun save() {
        try {
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            val props = Properties()
            props.setProperty("theme", appTheme.name)
            props.setProperty("contrast", appContrast.name)
            org.wexclient.app.util.AtomicFiles.writeProperties(configFile, props, "WEX Desktop Theme Preferences")
        } catch (_: Throwable) {
            // Ignore write errors safely
        }
    }
}
