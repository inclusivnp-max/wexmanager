package org.wexclient.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.BackgroundEffect
import org.wexclient.app.theme.BackgroundPreferences
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.ThemePreferences
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Подложка окна: фоновая картинка (своя или обои рабочего стола) с затемнением и размытием,
 * а поверх — необязательный эффект (снег, пузыри, звёзды и прочее). Рисуется под всем
 * содержимым; панели показываются поверх с той прозрачностью, которую задали в настройках.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    val prefs = BackgroundPreferences
    val file = prefs.image()
    val blur = prefs.blur
    val key = file?.let { "${it.absolutePath}:${it.lastModified()}:$blur" }

    // Картинка читается и размывается один раз на всю программу: окон много, память одна
    val bitmap by produceState<ImageBitmap?>(initialValue = BackgroundImage.ready(key), key) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            BackgroundImage.get(key ?: return@withContext null, file, blur)
        }
    }

    // Системные цвета и обои меняются вместе с рабочим столом — подхватываем без перезапуска.
    // Следит одно окно: остальные просто видят обновлённое состояние.
    LaunchedEffect(Unit) {
        if (!BackgroundImage.startWatching()) return@LaunchedEffect
        try {
            while (true) {
                delay(3000)
                // Проверка файлов — на диске, поэтому не в потоке окна
                withContext(Dispatchers.IO) {
                    ThemePreferences.refreshDynamicIfChanged()
                    BackgroundPreferences.pollSystemWallpaper()
                }
            }
        } finally {
            BackgroundImage.stopWatching()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (prefs.dim > 0) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = prefs.dim / 100f)))
            }
        }
    }
}

/**
 * Эффект поверх окна: снежинки и прочее видно и над панелями. Слой не ловит мышь —
 * щелчки проходят сквозь него к содержимому.
 */
@Composable
fun AppEffects(modifier: Modifier = Modifier) {
    val prefs = BackgroundPreferences
    if (!prefs.effectEnabled) return
    Box(modifier = modifier.fillMaxSize()) {
        ParticleLayer(prefs.effect, prefs.density.count)
    }
}

/** Одна разобранная картинка на все окна: каждое окно держало бы свою копию в десятки мегабайт. */
internal object BackgroundImage {
    private val lock = Any()
    private var key: String? = null
    private var image: ImageBitmap? = null
    private val watching = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Уже готовая картинка для этого ключа (чтобы новое окно не мигало пустым фоном). */
    fun ready(key: String?): ImageBitmap? = synchronized(lock) { if (key != null && key == this.key) image else null }

    /** Текущая подложка окна — её же показывают «стеклянные» меню и диалоги. */
    fun current(): ImageBitmap? = synchronized(lock) { image }

    fun get(key: String, file: File, blur: Int): ImageBitmap? {
        ready(key)?.let { return it }
        val loaded = runCatching { loadBackground(file, blur) }.getOrNull()
        synchronized(lock) {
            this.key = key
            image = loaded
        }
        return loaded
    }

    fun startWatching(): Boolean = watching.compareAndSet(false, true)

    fun stopWatching() {
        watching.set(false)
    }
}

/** Картинка уменьшается под экран и размывается заранее — на отрисовку это не влияет. */
private fun loadBackground(file: File, blurDp: Int): ImageBitmap? {
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    val scaled = org.wexclient.app.util.ImageScale.decodeScaled(
        bytes,
        maxPx = 2560,
        maxSourcePx = 20000,
        maxSourcePixels = 120_000_000
    ) ?: return null
    if (blurDp <= 0) return scaled
    return runCatching { blurred(scaled, blurDp.toFloat()) }.getOrDefault(scaled)
}

private fun blurred(source: ImageBitmap, radius: Float): ImageBitmap {
    val image = org.jetbrains.skia.Image.makeFromBitmap(source.asSkiaBitmap())
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(image.width, image.height)
    val paint = org.jetbrains.skia.Paint().apply {
        imageFilter = org.jetbrains.skia.ImageFilter.makeBlur(radius, radius, org.jetbrains.skia.FilterTileMode.CLAMP)
    }
    surface.canvas.drawImage(image, 0f, 0f, paint)
    val snapshot = surface.makeImageSnapshot()
    image.close()
    return snapshot.toComposeImageBitmap()
}

/** Одна частица эффекта. */
private class Particle(
    var x: Float,
    var y: Float,
    val size: Float,
    val speed: Float,
    val drift: Float,
    val phase: Float,
    val alpha: Float
)

@Composable
private fun ParticleLayer(effect: BackgroundEffect, count: Int) {
    val accent = NoctaliaPrimary
    val particles = remember(effect, count) {
        val random = Random(effect.ordinal * 1000 + count)
        List(count) {
            Particle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                size = when (effect) {
                    BackgroundEffect.SNOW -> 1.5f + random.nextFloat() * 3f
                    BackgroundEffect.BUBBLES -> 4f + random.nextFloat() * 14f
                    BackgroundEffect.STARS -> 0.8f + random.nextFloat() * 1.8f
                    BackgroundEffect.RAIN -> 0.8f + random.nextFloat() * 1.2f
                    BackgroundEffect.LEAVES -> 3f + random.nextFloat() * 5f
                    else -> 2f + random.nextFloat() * 3f
                },
                speed = 0.2f + random.nextFloat(),
                drift = random.nextFloat() * 2f - 1f,
                phase = random.nextFloat() * (2 * PI).toFloat(),
                alpha = 0.25f + random.nextFloat() * 0.5f
            )
        }
    }
    var time by remember { mutableStateOf(0f) }
    LaunchedEffect(effect, count) {
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                if (previous != 0L) time += ((now - previous) / 1_000_000_000.0).toFloat()
                previous = now
            }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        for (p in particles) {
            when (effect) {
                BackgroundEffect.SNOW -> {
                    val y = ((p.y + time * p.speed * 0.04f) % 1f) * h
                    val x = (p.x * w + sin(time * 0.6f + p.phase) * 18f * p.drift + w) % w
                    drawCircle(Color.White.copy(alpha = p.alpha), p.size, Offset(x, y))
                }
                BackgroundEffect.BUBBLES -> {
                    val y = h - ((p.y + time * p.speed * 0.03f) % 1f) * h
                    val x = (p.x * w + sin(time * 0.5f + p.phase) * 26f + w) % w
                    drawCircle(accent.copy(alpha = p.alpha * 0.45f), p.size, Offset(x, y))
                    drawCircle(accent.copy(alpha = p.alpha * 0.25f), p.size * 0.55f, Offset(x - p.size * 0.3f, y - p.size * 0.3f))
                }
                BackgroundEffect.STARS -> {
                    val twinkle = (sin(time * 1.6f + p.phase) + 1f) / 2f
                    drawCircle(
                        Color.White.copy(alpha = p.alpha * (0.25f + twinkle * 0.75f)),
                        p.size,
                        Offset(p.x * w, p.y * h)
                    )
                }
                BackgroundEffect.RAIN -> {
                    val y = ((p.y + time * (0.25f + p.speed) * 0.25f) % 1f) * h
                    val x = (p.x * w + y * 0.12f) % w
                    drawLine(
                        color = accent.copy(alpha = p.alpha * 0.5f),
                        start = Offset(x, y),
                        end = Offset(x - 3f, y + 14f + p.speed * 10f),
                        strokeWidth = p.size
                    )
                }
                BackgroundEffect.LEAVES -> {
                    val y = ((p.y + time * p.speed * 0.035f) % 1f) * h
                    val angle = time * p.speed + p.phase
                    val x = (p.x * w + sin(angle) * 40f + w) % w
                    rotate(degrees = (angle * 30f) % 360f, pivot = Offset(x, y)) {
                        drawOval(
                            color = accent.copy(alpha = p.alpha * 0.6f),
                            topLeft = Offset(x - p.size, y - p.size / 2f),
                            size = androidx.compose.ui.geometry.Size(p.size * 2f, p.size)
                        )
                    }
                }
                BackgroundEffect.FIREFLIES -> {
                    val angle = time * p.speed * 0.6f + p.phase
                    val x = p.x * w + cos(angle) * 40f
                    val y = p.y * h + sin(angle * 0.8f) * 30f
                    val glow = (sin(time * 2f + p.phase) + 1f) / 2f
                    drawCircle(accent.copy(alpha = p.alpha * glow * 0.35f), p.size * 3f, Offset(x, y))
                    drawCircle(accent.copy(alpha = p.alpha * (0.4f + glow * 0.6f)), p.size, Offset(x, y))
                }
                BackgroundEffect.NONE -> Unit
            }
        }
    }
}
