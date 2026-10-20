package org.wexclient.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import org.wexclient.app.ui.AppBackground
import org.wexclient.app.ui.ShaderBackgroundRenderer
import org.wexclient.app.ui.ShaderTouchOverlay
import org.wexclient.app.ui.observeBackgroundTouches

/**
 * Нужно каждому отдельному окну: панели в теме полупрозрачные, и без подложки они выглядят выцветшими.
 */
@Composable
actual fun ThemeBackdrop(content: @Composable () -> Unit) {
    val shader = BackgroundPreferences.shader
    val renderer = remember { ShaderBackgroundRenderer() }
    Box(
        modifier = Modifier.fillMaxSize()
            // Живой фон отзывается на нажатия в любом месте окна, ничего у окна не забирая
            .observeBackgroundTouches(renderer, shader.enabled && shader.touch)
    ) {
        AppBackground(renderer = renderer)
        // Фон окна поверх картинки — у каждого окна одинаковый. Панели и диалоги кладут
        // свой тон уже на него: иначе отдельные окна выходили светлее главного
        Box(modifier = Modifier.fillMaxSize().background(ThemePreferences.colorScheme.background))
        // Содержимое окна — своим слоем. В Compose Desktop всё, что не в слое, рисуется заново
        // в каждом кадре окна: от живого фона, снега или мигания курсора заново рисовались
        // обе панели со всеми строками. Со слоем их нарисованная картинка переиспользуется
        Box(modifier = Modifier.fillMaxSize().graphicsLayer()) {
            content()
        }
        // Свечение за курсором — поверх содержимого: под полупрозрачными панелями его не было видно
        if (shader.enabled && shader.touch) ShaderTouchOverlay(renderer)
    }
}
