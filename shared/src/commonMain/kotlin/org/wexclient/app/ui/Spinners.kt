package org.wexclient.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Неопределённые индикаторы. У M3 такой индикатор просит кадр на каждое обновление экрана — до 144
 * в секунду, — а кадр в Compose Desktop это отрисовка всего окна; копирование или jadx держали так
 * процессор минутами. Эти шагают ~20 раз в секунду, меняют только свою картинку (без перекомпоновки),
 * а в окне не в фокусе замирают и кадров не просят вовсе.
 */

private const val STEP_MS = 50L

/** Номер шага анимации; пока окно не в фокусе — стоит. Читать только при рисовании. */
@Composable
private fun spinnerTicks(): State<Int> {
    val tick = remember { mutableIntStateOf(0) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        while (true) {
            delay(STEP_MS)
            tick.intValue++
        }
    }
    return tick
}

/** Замена `CircularProgressIndicator()` без progress: дуга, которая вращается ~1 оборот в секунду. */
@Composable
fun WexSpinner(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
    trackColor: Color = Color.Transparent,
    strokeCap: StrokeCap = StrokeCap.Round,
) {
    val tick = spinnerTicks()
    Canvas(modifier.progressSemantics().size(40.dp)) {
        val w = strokeWidth.toPx()
        val d = size.minDimension - w
        val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        val arcSize = Size(d, d)
        if (trackColor.alpha > 0f) drawArc(trackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
        // 18° за шаг — оборот в секунду; дуга дышит, как у M3, но шагами
        val t = tick.value
        val sweep = 90f + 180f * (0.5f + 0.5f * kotlin.math.sin(t * 0.21f))
        drawArc(color, t * 18f - 90f, sweep, false, topLeft, arcSize, style = Stroke(w, cap = strokeCap))
    }
}

/** Замена `LinearProgressIndicator()` без progress: отрезок бежит по дорожке. */
@Composable
fun WexLinearSpinner(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    strokeCap: StrokeCap = StrokeCap.Butt,
) {
    val tick = spinnerTicks()
    Canvas(modifier.progressSemantics().size(240.dp, 4.dp)) {
        val h = size.height
        val y = h / 2f
        val cap = if (strokeCap == StrokeCap.Butt) 0f else h / 2f
        drawLine(trackColor, Offset(cap, y), Offset(size.width - cap, y), h, strokeCap)
        // Полный пробег за 1,6 с; отрезок — 30 % ширины
        val phase = (tick.value % 32) / 32f
        val len = size.width * 0.3f
        val start = -len + (size.width + len) * phase
        val a = start.coerceIn(0f, size.width)
        val b = (start + len).coerceIn(0f, size.width)
        if (b - a > 1f) drawLine(color, Offset(a + cap, y), Offset((b - cap).coerceAtLeast(a + cap), y), h, strokeCap)
    }
}
