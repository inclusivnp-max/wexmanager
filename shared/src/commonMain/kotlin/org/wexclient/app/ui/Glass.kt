package org.wexclient.app.ui

import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import org.wexclient.app.theme.BackgroundPreferences
import kotlin.math.roundToInt

/**
 * «Стекло»: под меню и диалогами рисуется тот самый размытый фон окна, а не просвечивает
 * список файлов. Настоящего размытия «за окном» в Compose нет, поэтому берётся заранее
 * размытая картинка и её кусок ровно под этим элементом — выглядит как матовое стекло.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.glass(
    shape: Shape,
    tint: Color,
    /**
     * Сколько тона поверх картинки: 1 — совсем непрозрачно. По умолчанию берётся из
     * настройки «Плотность панелей», чтобы меню и панели не расходились по плотности.
     */
    opacity: Float = -1f,
    /**
     * Слой под тоном — как фон окна под панелями. У главного окна поверх картинки лежат два слоя
     * (фон и панель), и без этого слоя обои просвечивали сквозь диалог сильнее: он выходил светлее окна.
     */
    base: Color? = null
): Modifier = composed {
    val window = LocalWindowInfo.current.containerSize
    val image = currentBackdropImage()
    // Меню и диалоги чуть плотнее панелей: сквозь них читают текст, а не смотрят картинку
    val panel = BackgroundPreferences.panelOpacity / 100f
    val solidity = if (opacity >= 0f) opacity else (panel + (1f - panel) * 0.45f).coerceIn(0.5f, 0.98f)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val dim = BackgroundPreferences.dim / 100f

    this
        .clip(shape)
        .onGloballyPositioned { origin = it.positionInWindow() }
        .drawBehind {
            if (image != null && window.width > 0 && window.height > 0) {
                // Картинка в окне растянута «по большей стороне» — повторяем ту же раскладку,
                // иначе кусок под меню не совпал бы с фоном окна
                val scale = maxOf(
                    window.width.toFloat() / image.width,
                    window.height.toFloat() / image.height
                )
                val drawnWidth = image.width * scale
                val drawnHeight = image.height * scale
                val left = (window.width - drawnWidth) / 2f
                val top = (window.height - drawnHeight) / 2f
                val srcX = ((origin.x - left) / scale).roundToInt().coerceIn(0, image.width - 1)
                val srcY = ((origin.y - top) / scale).roundToInt().coerceIn(0, image.height - 1)
                val srcW = (size.width / scale).roundToInt().coerceIn(1, image.width - srcX)
                val srcH = (size.height / scale).roundToInt().coerceIn(1, image.height - srcY)
                drawImage(
                    image = image,
                    srcOffset = IntOffset(srcX, srcY),
                    srcSize = IntSize(srcW, srcH),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                )
                if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
                if (base != null) drawRect(base)
                drawRect(tint.copy(alpha = solidity))
            } else {
                // Без картинки стекло не нужно — обычный плотный фон
                drawRect(tint.copy(alpha = 1f))
            }
        }
}

/**
 * Фон диалога внутри окна: сквозь него видна размытая подложка, а не список файлов.
 * Отдельным окнам подложку рисует сама тема, здесь — тот же приём, что у меню.
 */
@Composable
fun Modifier.dialogGlass(shape: Shape): Modifier =
    this.glass(shape, MaterialTheme.colorScheme.surface, base = MaterialTheme.colorScheme.background)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)

/** Крестик в шапке диалога: закрыть можно не только клавишей Esc. */
@Composable
fun DialogClose(onDismiss: () -> Unit) {
    androidx.compose.material3.IconButton(
        onClick = onDismiss,
        modifier = Modifier.size(28.dp)
    ) {
        androidx.compose.material3.Icon(
            Icons.Default.Close,
            contentDescription = "Закрыть",
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp)
        )
    }
}
