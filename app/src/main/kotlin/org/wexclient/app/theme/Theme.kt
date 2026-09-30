package org.wexclient.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import org.wexclient.app.ui.observeBackgroundTouches
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

@Composable
fun NoctaliaTheme(backdrop: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ThemePreferences.colorScheme,
        typography = CenteredTypography,
        shapes = NoctaliaShapes
    ) {
      // Поверхности со стеклом прозрачные, а Material для прозрачного фона берёт чёрный текст —
      // задаём цвет содержимого явно, иначе подписи в диалогах и меню чернеют
      androidx.compose.runtime.CompositionLocalProvider(
          androidx.compose.material3.LocalContentColor provides ThemePreferences.colorScheme.onSurface
      ) {
        if (backdrop) {
            val shader = BackgroundPreferences.shader
            val renderer = androidx.compose.runtime.remember { org.wexclient.app.ui.ShaderBackgroundRenderer() }
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.fillMaxSize()
                    // Живой фон отзывается на нажатия в любом месте окна, ничего у окна не забирая
                    .observeBackgroundTouches(renderer, shader.enabled && shader.touch)
            ) {
                org.wexclient.app.ui.AppBackground(renderer = renderer)
                // Фон окна поверх картинки — у каждого окна одинаковый. Панели и диалоги кладут
                // свой тон уже на него: иначе отдельные окна выходили светлее главного
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize()
                        .background(ThemePreferences.colorScheme.background)
                )
                // Содержимое окна — своим слоем. В Compose Desktop всё, что не в слое, рисуется заново
                // в каждом кадре окна: от живого фона, снега или мигания курсора заново рисовались
                // обе панели со всеми строками. Со слоем их нарисованная картинка переиспользуется
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer()
                ) {
                    content()
                }
                // Свечение за курсором — поверх содержимого: под полупрозрачными панелями его не было видно
                if (shader.enabled && shader.touch) org.wexclient.app.ui.ShaderTouchOverlay(renderer)
            }
        } else {
            content()
        }
      }
    }
}
