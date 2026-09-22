package org.wexclient.app.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// Акцентные цвета — reactive vars: переключаются выбором темы в ThemePreferences,
// все экраны, читающие их в composition, обновляются живьём без перезапуска.
var NoctaliaPrimary by mutableStateOf(Color(0xFFFFB4AA))
var NoctaliaOnPrimary by mutableStateOf(Color(0xFF5F150F))
var NoctaliaPrimaryContainer by mutableStateOf(Color(0xFF442926))
var NoctaliaOnPrimaryContainer by mutableStateOf(Color(0xFFFFDAD5))

var NoctaliaSecondary by mutableStateOf(Color(0xFFE7BDB7))
var NoctaliaOnSecondary by mutableStateOf(Color(0xFF442926))
var NoctaliaSecondaryContainer by mutableStateOf(Color(0xFF352422))
var NoctaliaOnSecondaryContainer by mutableStateOf(Color(0xFFFFDAD5))

var NoctaliaTertiary by mutableStateOf(Color(0xFFDFC38C))
var NoctaliaOnTertiary by mutableStateOf(Color(0xFF3E2E04))
var NoctaliaTertiaryContainer by mutableStateOf(Color(0xFF352B18))
var NoctaliaOnTertiaryContainer by mutableStateOf(Color(0xFFFFE0A7))

var NoctaliaBackground by mutableStateOf(Color(0xFF181211))
var NoctaliaOnBackground by mutableStateOf(Color(0xFFEDE0DE))

var NoctaliaSurface by mutableStateOf(Color(0xFF181211))
var NoctaliaOnSurface by mutableStateOf(Color(0xFFEDE0DE))
var NoctaliaSurfaceVariant by mutableStateOf(Color(0xFF251E1D))
var NoctaliaOnSurfaceVariant by mutableStateOf(Color(0xFFD8C2BE))

var NoctaliaOutline by mutableStateOf(Color(0xFF534341))
var NoctaliaOutlineVariant by mutableStateOf(Color(0xFF352C2B))

var NoctaliaError by mutableStateOf(Color(0xFFFFB4AB))

// Смысловые цвета: «получилось», «осторожно», «плохо». Раньше эти же числа были
// скопированы по трём десяткам файлов, и правка темы их не касалась.
var NoctaliaOk by mutableStateOf(Color(0xFF4ADE80))
var NoctaliaWarn by mutableStateOf(Color(0xFFFBBF24))
var NoctaliaDanger by mutableStateOf(Color(0xFFF87171))
var NoctaliaOnError by mutableStateOf(Color(0xFF690005))

data class AppPalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val error: Color,
    val onError: Color
) {
    // Роли, которых нет в палитре, выводим из неё же: иначе M3 берёт свои базовые
    // фиолетово-серые цвета (меню — surfaceContainer, подсказки — inverseSurface и т.д.)
    /** [opacity] < 1 — поверхности полупрозрачные, сквозь них видна фоновая картинка. */
    fun toScheme(opacity: Float = 1f): ColorScheme = darkColorScheme(
        surfaceTint = primary,
        inversePrimary = primaryContainer,
        inverseSurface = onSurface,
        inverseOnSurface = surface,
        scrim = Color.Black,
        surfaceBright = outlineVariant,
        surfaceDim = (surface).fade(opacity),
        surfaceContainerLowest = (background.darken(0.25f)).fade(opacity),
        surfaceContainerLow = (surface.mix(surfaceVariant, 0.5f)).fade(opacity),
        surfaceContainer = (surfaceVariant).fade(opacity),
        surfaceContainerHigh = (surfaceVariant.mix(outlineVariant, 0.35f)).fade(opacity),
        surfaceContainerHighest = (surfaceVariant.mix(outlineVariant, 0.7f)).fade(opacity),
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = (background).fade(opacity),
        onBackground = onBackground,
        surface = (surface).fade(opacity),
        onSurface = onSurface,
        surfaceVariant = (surfaceVariant).fade(opacity),
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
        onError = onError
    )

    fun withContrast(contrast: AppContrast): AppPalette = when (contrast) {
        AppContrast.STANDARD -> this
        AppContrast.MEDIUM -> copy(
            background = background.darken(0.35f),
            surface = surface.darken(0.35f),
            surfaceVariant = surfaceVariant.darken(0.25f),
            onBackground = onBackground.lighten(0.35f),
            onSurface = onSurface.lighten(0.35f),
            onSurfaceVariant = onSurfaceVariant.lighten(0.3f),
            outline = outline.lighten(0.35f),
            outlineVariant = outlineVariant.lighten(0.25f)
        )
        AppContrast.HIGH -> copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color(0xFF1A1A1A),
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFE6E6E6),
            outline = Color(0xFFB0B0B0),
            outlineVariant = Color(0xFF5A5A5A)
        )
    }

    /** Раскидывает палитру по reactive-цветам, от которых читает весь UI. */
    fun applyToGlobals() {
        NoctaliaPrimary = primary
        NoctaliaOnPrimary = onPrimary
        NoctaliaPrimaryContainer = primaryContainer
        NoctaliaOnPrimaryContainer = onPrimaryContainer
        NoctaliaSecondary = secondary
        NoctaliaOnSecondary = onSecondary
        NoctaliaSecondaryContainer = secondaryContainer
        NoctaliaOnSecondaryContainer = onSecondaryContainer
        NoctaliaTertiary = tertiary
        NoctaliaOnTertiary = onTertiary
        NoctaliaTertiaryContainer = tertiaryContainer
        NoctaliaOnTertiaryContainer = onTertiaryContainer
        NoctaliaBackground = background
        NoctaliaOnBackground = onBackground
        NoctaliaSurface = surface
        NoctaliaOnSurface = onSurface
        NoctaliaSurfaceVariant = surfaceVariant
        NoctaliaOnSurfaceVariant = onSurfaceVariant
        NoctaliaOutline = outline
        NoctaliaOutlineVariant = outlineVariant
        NoctaliaError = error
        NoctaliaOnError = onError
    }
}

/** Полупрозрачная версия цвета (1 — без изменений). */
internal fun Color.fade(opacity: Float): Color = if (opacity >= 1f) this else copy(alpha = alpha * opacity)

internal fun Color.mix(other: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * f,
        green = green + (other.green - green) * f,
        blue = blue + (other.blue - blue) * f,
        alpha = alpha + (other.alpha - alpha) * f
    )
}

private fun Color.darken(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return copy(
        red = (red * (1f - f)).coerceIn(0f, 1f),
        green = (green * (1f - f)).coerceIn(0f, 1f),
        blue = (blue * (1f - f)).coerceIn(0f, 1f)
    )
}

private fun Color.lighten(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return copy(
        red = (red + (1f - red) * f).coerceIn(0f, 1f),
        green = (green + (1f - green) * f).coerceIn(0f, 1f),
        blue = (blue + (1f - blue) * f).coerceIn(0f, 1f)
    )
}

// Noctalia Warm Material You Dark Palette (дефолтная)
val NoctaliaPalette = AppPalette(
    primary = Color(0xFFFFB4AA),
    onPrimary = Color(0xFF5F150F),
    primaryContainer = Color(0xFF442926),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFE7BDB7),
    onSecondary = Color(0xFF442926),
    secondaryContainer = Color(0xFF352422),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFDFC38C),
    onTertiary = Color(0xFF3E2E04),
    tertiaryContainer = Color(0xFF352B18),
    onTertiaryContainer = Color(0xFFFFE0A7),
    background = Color(0xFF181211),
    onBackground = Color(0xFFEDE0DE),
    surface = Color(0xFF181211),
    onSurface = Color(0xFFEDE0DE),
    surfaceVariant = Color(0xFF251E1D),
    onSurfaceVariant = Color(0xFFD8C2BE),
    outline = Color(0xFF534341),
    outlineVariant = Color(0xFF352C2B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

// Midnight — холодная синяя тёмная
val MidnightPalette = AppPalette(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF0A305F),
    primaryContainer = Color(0xFF284677),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3B475D),
    onSecondaryContainer = Color(0xFFD8E2FF),
    tertiary = Color(0xFF86D3C3),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005048),
    onTertiaryContainer = Color(0xFF9FF2E4),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE2E6EC),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE2E6EC),
    surfaceVariant = Color(0xFF1C222B),
    onSurfaceVariant = Color(0xFFC2C8D0),
    outline = Color(0xFF5A6372),
    outlineVariant = Color(0xFF2A323D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

// Forest — зелёная тёмная
val ForestPalette = AppPalette(
    primary = Color(0xFFA9D6A5),
    onPrimary = Color(0xFF12370F),
    primaryContainer = Color(0xFF1F4D1C),
    onPrimaryContainer = Color(0xFFC4EFC0),
    secondary = Color(0xFFB9CCB2),
    onSecondary = Color(0xFF243425),
    secondaryContainer = Color(0xFF394B3A),
    onSecondaryContainer = Color(0xFFD5E8D0),
    tertiary = Color(0xFFE3C46B),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFFFE1A0),
    background = Color(0xFF101510),
    onBackground = Color(0xFFE0E7DE),
    surface = Color(0xFF101510),
    onSurface = Color(0xFFE0E7DE),
    surfaceVariant = Color(0xFF1D251D),
    onSurfaceVariant = Color(0xFFBFC9BC),
    outline = Color(0xFF55624F),
    outlineVariant = Color(0xFF272F26),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

// Dracula — фиолетовая тёмная
val DraculaPalette = AppPalette(
    primary = Color(0xFFBD93F9),
    onPrimary = Color(0xFF241B3D),
    primaryContainer = Color(0xFF443166),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFF8BE9FD),
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF004E59),
    onSecondaryContainer = Color(0xFFA6EEFF),
    tertiary = Color(0xFFFF79C6),
    onTertiary = Color(0xFF5B1140),
    tertiaryContainer = Color(0xFF6B1A4E),
    onTertiaryContainer = Color(0xFFFFD1E8),
    background = Color(0xFF282A36),
    onBackground = Color(0xFFF8F8F2),
    surface = Color(0xFF282A36),
    onSurface = Color(0xFFF8F8F2),
    surfaceVariant = Color(0xFF343746),
    onSurfaceVariant = Color(0xFFC4C8DD),
    outline = Color(0xFF6272A4),
    outlineVariant = Color(0xFF3A4058),
    error = Color(0xFFFF5555),
    onError = Color(0xFF690005)
)

// Nord — холодная полярная тёмная
val NordPalette = AppPalette(
    primary = Color(0xFF88C0D0),
    onPrimary = Color(0xFF0F2A33),
    primaryContainer = Color(0xFF2A4A56),
    onPrimaryContainer = Color(0xFFBDE3EE),
    secondary = Color(0xFF81A1C1),
    onSecondary = Color(0xFF0E2A3F),
    secondaryContainer = Color(0xFF2C4A61),
    onSecondaryContainer = Color(0xFFD0E4F5),
    tertiary = Color(0xFFA3BE8C),
    onTertiary = Color(0xFF1A2E12),
    tertiaryContainer = Color(0xFF354F29),
    onTertiaryContainer = Color(0xFFCDE8B8),
    background = Color(0xFF2E3440),
    onBackground = Color(0xFFD8DEE9),
    surface = Color(0xFF2E3440),
    onSurface = Color(0xFFE5E9F0),
    surfaceVariant = Color(0xFF3B4252),
    onSurfaceVariant = Color(0xFFB9C2D4),
    outline = Color(0xFF4C566A),
    outlineVariant = Color(0xFF363D4D),
    error = Color(0xFFBF616A),
    onError = Color(0xFF3F0A0E)
)

// Gruvbox — тёплая ретро
val GruvboxPalette = AppPalette(
    primary = Color(0xFFFABD2F),
    onPrimary = Color(0xFF3C2F00),
    primaryContainer = Color(0xFF4E3B10),
    onPrimaryContainer = Color(0xFFFFE7A8),
    secondary = Color(0xFF8EC07C),
    onSecondary = Color(0xFF16300F),
    secondaryContainer = Color(0xFF2F4526),
    onSecondaryContainer = Color(0xFFD3EFC4),
    tertiary = Color(0xFFD3869B),
    onTertiary = Color(0xFF3E1424),
    tertiaryContainer = Color(0xFF522736),
    onTertiaryContainer = Color(0xFFFFD6E2),
    background = Color(0xFF282828),
    onBackground = Color(0xFFEBDBB2),
    surface = Color(0xFF282828),
    onSurface = Color(0xFFEBDBB2),
    surfaceVariant = Color(0xFF32302F),
    onSurfaceVariant = Color(0xFFD5C4A1),
    outline = Color(0xFF928374),
    outlineVariant = Color(0xFF3C3836),
    error = Color(0xFFFB4934),
    onError = Color(0xFF4A0805)
)

// Tokyo Night — неоновая ночная
val TokyoNightPalette = AppPalette(
    primary = Color(0xFF7AA2F7),
    onPrimary = Color(0xFF0F1B3D),
    primaryContainer = Color(0xFF283457),
    onPrimaryContainer = Color(0xFFD5E0FF),
    secondary = Color(0xFFBB9AF7),
    onSecondary = Color(0xFF2A1B4D),
    secondaryContainer = Color(0xFF372A5E),
    onSecondaryContainer = Color(0xFFE7DBFF),
    tertiary = Color(0xFF7DCFFF),
    onTertiary = Color(0xFF003348),
    tertiaryContainer = Color(0xFF17415A),
    onTertiaryContainer = Color(0xFFC3ECFF),
    background = Color(0xFF1A1B26),
    onBackground = Color(0xFFC0CAF5),
    surface = Color(0xFF1A1B26),
    onSurface = Color(0xFFC0CAF5),
    surfaceVariant = Color(0xFF24283B),
    onSurfaceVariant = Color(0xFFA9B1D6),
    outline = Color(0xFF565F89),
    outlineVariant = Color(0xFF2F3550),
    error = Color(0xFFF7768E),
    onError = Color(0xFF4A0D18)
)

// Catppuccin Mocha — мягкая пастельная
val CatppuccinPalette = AppPalette(
    primary = Color(0xFFCBA6F7),
    onPrimary = Color(0xFF2B1B4A),
    primaryContainer = Color(0xFF3B2F63),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF89B4FA),
    onSecondary = Color(0xFF10294F),
    secondaryContainer = Color(0xFF27395F),
    onSecondaryContainer = Color(0xFFD7E4FF),
    tertiary = Color(0xFF94E2D5),
    onTertiary = Color(0xFF003B34),
    tertiaryContainer = Color(0xFF1C4A44),
    onTertiaryContainer = Color(0xFFBFF3EA),
    background = Color(0xFF1E1E2E),
    onBackground = Color(0xFFCDD6F4),
    surface = Color(0xFF1E1E2E),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF2A2B3C),
    onSurfaceVariant = Color(0xFFA6ADC8),
    outline = Color(0xFF6C7086),
    outlineVariant = Color(0xFF313244),
    error = Color(0xFFF38BA8),
    onError = Color(0xFF4A0A1C)
)

// Solarized — классическая тёмная
val SolarizedPalette = AppPalette(
    primary = Color(0xFF4FA3DB),
    onPrimary = Color(0xFF00243A),
    primaryContainer = Color(0xFF0E3F5A),
    onPrimaryContainer = Color(0xFFCDE7F7),
    secondary = Color(0xFF4BBFB0),
    onSecondary = Color(0xFF003A34),
    secondaryContainer = Color(0xFF0D4A44),
    onSecondaryContainer = Color(0xFFBFEFE8),
    tertiary = Color(0xFFC9A227),
    onTertiary = Color(0xFF332700),
    tertiaryContainer = Color(0xFF4A3A05),
    onTertiaryContainer = Color(0xFFFFE9A8),
    background = Color(0xFF002B36),
    onBackground = Color(0xFF93A1A1),
    surface = Color(0xFF002B36),
    onSurface = Color(0xFF9FB0B0),
    surfaceVariant = Color(0xFF073642),
    onSurfaceVariant = Color(0xFF839496),
    outline = Color(0xFF586E75),
    outlineVariant = Color(0xFF0A404E),
    error = Color(0xFFFF6B63),
    onError = Color(0xFF4A0603)
)

// Monokai — контрастная неоновая
val MonokaiPalette = AppPalette(
    primary = Color(0xFFA6E22E),
    onPrimary = Color(0xFF1F3000),
    primaryContainer = Color(0xFF33451A),
    onPrimaryContainer = Color(0xFFD7F5A8),
    secondary = Color(0xFF66D9EF),
    onSecondary = Color(0xFF00313C),
    secondaryContainer = Color(0xFF14444F),
    onSecondaryContainer = Color(0xFFC0EEF8),
    tertiary = Color(0xFFF92672),
    onTertiary = Color(0xFF450018),
    tertiaryContainer = Color(0xFF5E1030),
    onTertiaryContainer = Color(0xFFFFD3E0),
    background = Color(0xFF272822),
    onBackground = Color(0xFFF8F8F2),
    surface = Color(0xFF272822),
    onSurface = Color(0xFFF8F8F2),
    surfaceVariant = Color(0xFF33342B),
    onSurfaceVariant = Color(0xFFCFCFC2),
    outline = Color(0xFF75715E),
    outlineVariant = Color(0xFF3B3C33),
    error = Color(0xFFF92672),
    onError = Color(0xFF450018)
)

// One Dark — спокойная синяя
val OneDarkPalette = AppPalette(
    primary = Color(0xFF61AFEF),
    onPrimary = Color(0xFF06253F),
    primaryContainer = Color(0xFF1C3A57),
    onPrimaryContainer = Color(0xFFCFE6FF),
    secondary = Color(0xFFC678DD),
    onSecondary = Color(0xFF33123D),
    secondaryContainer = Color(0xFF452654),
    onSecondaryContainer = Color(0xFFF3DBFF),
    tertiary = Color(0xFF98C379),
    onTertiary = Color(0xFF17300B),
    tertiaryContainer = Color(0xFF2C4520),
    onTertiaryContainer = Color(0xFFD7EEC4),
    background = Color(0xFF282C34),
    onBackground = Color(0xFFABB2BF),
    surface = Color(0xFF282C34),
    onSurface = Color(0xFFBCC3CF),
    surfaceVariant = Color(0xFF31363F),
    onSurfaceVariant = Color(0xFF9DA5B4),
    outline = Color(0xFF5C6370),
    outlineVariant = Color(0xFF3A3F4B),
    error = Color(0xFFE06C75),
    onError = Color(0xFF40060A)
)

// Rosé Pine — розово-сиреневая
val RosePinePalette = AppPalette(
    primary = Color(0xFFEBBCBA),
    onPrimary = Color(0xFF3E1F27),
    primaryContainer = Color(0xFF4E3239),
    onPrimaryContainer = Color(0xFFFFDCDA),
    secondary = Color(0xFFC4A7E7),
    onSecondary = Color(0xFF2B1C3F),
    secondaryContainer = Color(0xFF3B2C54),
    onSecondaryContainer = Color(0xFFE7DAFF),
    tertiary = Color(0xFF9CCFD8),
    onTertiary = Color(0xFF05323A),
    tertiaryContainer = Color(0xFF1E4750),
    onTertiaryContainer = Color(0xFFC6EEF4),
    background = Color(0xFF191724),
    onBackground = Color(0xFFE0DEF4),
    surface = Color(0xFF191724),
    onSurface = Color(0xFFE0DEF4),
    surfaceVariant = Color(0xFF26233A),
    onSurfaceVariant = Color(0xFF908CAA),
    outline = Color(0xFF6E6A86),
    outlineVariant = Color(0xFF2A2740),
    error = Color(0xFFEB6F92),
    onError = Color(0xFF43081C)
)

// Everforest — мягкая лесная
val EverforestPalette = AppPalette(
    primary = Color(0xFFA7C080),
    onPrimary = Color(0xFF1E2C12),
    primaryContainer = Color(0xFF37452A),
    onPrimaryContainer = Color(0xFFDCEBC6),
    secondary = Color(0xFF83C092),
    onSecondary = Color(0xFF0B2E1B),
    secondaryContainer = Color(0xFF234534),
    onSecondaryContainer = Color(0xFFC4EBD1),
    tertiary = Color(0xFFDBBC7F),
    onTertiary = Color(0xFF352A0A),
    tertiaryContainer = Color(0xFF4B3F1E),
    onTertiaryContainer = Color(0xFFF7E6BC),
    background = Color(0xFF2D353B),
    onBackground = Color(0xFFD3C6AA),
    surface = Color(0xFF2D353B),
    onSurface = Color(0xFFD3C6AA),
    surfaceVariant = Color(0xFF343F44),
    onSurfaceVariant = Color(0xFFBFB295),
    outline = Color(0xFF859289),
    outlineVariant = Color(0xFF3D484D),
    error = Color(0xFFE67E80),
    onError = Color(0xFF43090A)
)

// Ayu Dark — глубокая чёрная
val AyuPalette = AppPalette(
    primary = Color(0xFFFFB454),
    onPrimary = Color(0xFF3A2200),
    primaryContainer = Color(0xFF4F3310),
    onPrimaryContainer = Color(0xFFFFE0B0),
    secondary = Color(0xFF59C2FF),
    onSecondary = Color(0xFF00273C),
    secondaryContainer = Color(0xFF14405A),
    onSecondaryContainer = Color(0xFFC6E9FF),
    tertiary = Color(0xFFAAD94C),
    onTertiary = Color(0xFF203000),
    tertiaryContainer = Color(0xFF35470F),
    onTertiaryContainer = Color(0xFFE0F3B4),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFBFBDB6),
    surface = Color(0xFF0B0E14),
    onSurface = Color(0xFFC8C6BF),
    surfaceVariant = Color(0xFF141A22),
    onSurfaceVariant = Color(0xFFA6A49C),
    outline = Color(0xFF565B66),
    outlineVariant = Color(0xFF1F252E),
    error = Color(0xFFF26D78),
    onError = Color(0xFF440509)
)

// Kanagawa — приглушённая чернильная
val KanagawaPalette = AppPalette(
    primary = Color(0xFF7E9CD8),
    onPrimary = Color(0xFF13203F),
    primaryContainer = Color(0xFF2B3A5E),
    onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = Color(0xFF98BB6C),
    onSecondary = Color(0xFF1A2A0E),
    secondaryContainer = Color(0xFF334425),
    onSecondaryContainer = Color(0xFFD9EEBC),
    tertiary = Color(0xFFDCA561),
    onTertiary = Color(0xFF3A2506),
    tertiaryContainer = Color(0xFF4E3818),
    onTertiaryContainer = Color(0xFFFFDFB0),
    background = Color(0xFF1F1F28),
    onBackground = Color(0xFFDCD7BA),
    surface = Color(0xFF1F1F28),
    onSurface = Color(0xFFDCD7BA),
    surfaceVariant = Color(0xFF2A2A37),
    onSurfaceVariant = Color(0xFFC8C3A6),
    outline = Color(0xFF727169),
    outlineVariant = Color(0xFF363646),
    error = Color(0xFFE46876),
    onError = Color(0xFF430810)
)
