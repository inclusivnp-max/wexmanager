package org.wexclient.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

val NoctaliaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

/**
 * [backdrop] — нарисовать под содержимым фоновую картинку. Нужно каждому отдельному окну:
 * панели в теме полупрозрачные, и без подложки они выглядят выцветшими.
 */
/**
 * Текст в интерфейсе центрируется по самим буквам: у Material высота строки 24sp задана
 * и для десятипиксельной подписи, из-за чего цифры в плашках стояли не по центру, а в
 * контейнерах с жёсткой высотой подрезались снизу.
 */
private fun TextStyle.centered(): TextStyle = copy(
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    )
)

private val CenteredTypography: Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.centered(),
        displayMedium = t.displayMedium.centered(),
        displaySmall = t.displaySmall.centered(),
        headlineLarge = t.headlineLarge.centered(),
        headlineMedium = t.headlineMedium.centered(),
        headlineSmall = t.headlineSmall.centered(),
        titleLarge = t.titleLarge.centered(),
        titleMedium = t.titleMedium.centered(),
        titleSmall = t.titleSmall.centered(),
        bodyLarge = t.bodyLarge.centered(),
        bodyMedium = t.bodyMedium.centered(),
        bodySmall = t.bodySmall.centered(),
        labelLarge = t.labelLarge.centered(),
        labelMedium = t.labelMedium.centered(),
        labelSmall = t.labelSmall.centered()
    )
}

/** Подпись без своего стиля: высота строки — по шрифту, а не фиксированные 24sp. */
private val DefaultTextStyle: TextStyle =
    CenteredTypography.bodyLarge.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)

/**
 * Тема WEX: Material 3 Expressive (движение — пружинное, «выразительное») с палитрой из [ThemePreferences].
 * [backdrop] — нарисовать под содержимым фон окна (на десктопе — картинка или живой фон, см. [ThemeBackdrop]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NoctaliaTheme(backdrop: Boolean = true, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = ThemePreferences.colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = CenteredTypography,
        shapes = NoctaliaShapes
    ) {
      // Поверхности со стеклом прозрачные, а Material для прозрачного фона берёт чёрный текст —
      // задаём цвет содержимого явно, иначе подписи в диалогах и меню чернеют
      androidx.compose.runtime.CompositionLocalProvider(
          androidx.compose.material3.LocalContentColor provides ThemePreferences.colorScheme.onSurface
      ) {
        if (backdrop) ThemeBackdrop(content) else content()
      }
    }
}

/** Фон окна под содержимым темы: у каждой платформы свой (живой фон Skia на десктопе, однотонный на Android). */
@Composable
expect fun ThemeBackdrop(content: @Composable () -> Unit)
