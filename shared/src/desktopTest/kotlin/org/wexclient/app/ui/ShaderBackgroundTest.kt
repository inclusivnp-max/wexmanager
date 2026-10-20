package org.wexclient.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.wexclient.app.theme.BackgroundPreferences
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.theme.SHADER_EFFECTS
import org.wexclient.app.theme.SHADER_STYLES
import org.wexclient.app.theme.ShaderPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Живой фон: все стили и эффекты рисуются, а содержимое окна над ним заново не рисуется. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ShaderBackgroundTest {
    private val focused = object : WindowInfo {
        override val isWindowFocused = true
    }

    @Test
    fun `every style and effect draws`() {
        for (style in SHADER_STYLES.indices) for (effect in SHADER_EFFECTS.indices) {
            val prefs = ShaderPrefs(enabled = true, style = style, effect = effect, colorAnimation = true, blur = 10)
            val renderer = ShaderBackgroundRenderer()
            val scene = ImageComposeScene(240, 160, Density(1f)) {
                NoctaliaTheme(backdrop = false) { ShaderBackground(prefs, renderer, animate = true) }
            }
            try {
                val t0 = ShaderBackgroundRenderer.nowMs()
                renderer.onPointer(1, 120f, 80f, pressed = true, justPressed = true, nowMs = t0)
                scene.render(0).close()
                Thread.sleep(40)
                val png = scene.render(16_000_000).use { it.encodeToData(EncodedImageFormat.PNG)!!.bytes }
                assertFalse(png.isEmpty(), "style=$style effect=$effect")
            } finally {
                scene.close()
            }
        }
    }

    private fun brightness(c: Int) = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)

    @Test
    fun `glow follows the cursor, settles and fades after it leaves`() {
        val prefs = ShaderPrefs(enabled = true, style = 2)
        fun renderer() = ShaderBackgroundRenderer().apply {
            configure(prefs, 0xFF101010.toInt(), 0xFF3A84FF.toInt(), 0xFF23E0FF.toInt(), 0xFF8F62FF.toInt(), 1f)
        }
        val w = 400
        val h = 300
        val t0 = ShaderBackgroundRenderer.nowMs()
        val plain = renderer()
        val hover = renderer()
        hover.onMouse(300f, 200f, pressed = false, justPressed = false, inside = true, nowMs = t0)
        assertTrue(hover.touchActive())
        val out = System.getenv("SHADER_OUT")
        org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).use { a ->
            org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).use { b ->
                // Секунда кадров: свечение разгорелось, след погас, курсор стоит
                for (i in 0..60) {
                    plain.draw(a.canvas, w, h, t0 + i * 16L, animate = false)
                    plain.drawOverlay(a.canvas, w, h, t0 + i * 16L)
                    hover.draw(b.canvas, w, h, t0 + i * 16L, animate = false)
                    hover.drawOverlay(b.canvas, w, h, t0 + i * 16L)
                }
                val pa = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(w, h) }
                val pb = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(w, h) }
                a.readPixels(pa, 0, 0)
                b.readPixels(pb, 0, 0)
                assertTrue(brightness(pb.getColor(300, 200)) > brightness(pa.getColor(300, 200)) + 30, "под курсором нет свечения")
                if (out != null) b.makeImageSnapshot().use { java.io.File(out, "hover.png").writeBytes(it.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
                pa.close()
                pb.close()
            }
        }
        // Курсор стоит, свечение устоялось — частые кадры не нужны
        assertFalse(hover.touchActive())
        hover.onMouse(100f, 80f, pressed = false, justPressed = false, inside = true, nowMs = t0 + 1000)
        assertTrue(hover.touchActive())
        // Ушёл из окна — свечение гаснет и исчезает
        hover.onMouse(100f, 80f, pressed = false, justPressed = false, inside = false, nowMs = t0 + 1000)
        org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).use { s ->
            for (i in 1..250) hover.drawOverlay(s.canvas, w, h, t0 + 1000 + i * 16L)
        }
        assertFalse(hover.touchActive())
    }

    @Test
    fun `hover trail stays long and continuous with a fast mouse, and can be switched off`() {
        val colors = intArrayOf(0xFF101010.toInt(), 0xFF3A84FF.toInt(), 0xFF23E0FF.toInt(), 0xFF8F62FF.toInt())
        fun renderer(hoverTrail: Boolean) = ShaderBackgroundRenderer().apply {
            configure(ShaderPrefs(enabled = true, hoverTrail = hoverTrail), colors[0], colors[1], colors[2], colors[3], 1f)
        }
        val w = 1000
        val h = 400
        val t0 = ShaderBackgroundRenderer.nowMs()
        // Мышь с опросом 1000 Гц: полэкрана за 400 мс, событие каждую миллисекунду
        fun sweep(r: ShaderBackgroundRenderer, s: org.jetbrains.skia.Surface) {
            for (ms in 0..400) {
                r.onMouse(100f + ms * 2f, 200f, pressed = false, justPressed = false, inside = true, nowMs = t0 + ms)
                if (ms % 16 == 0) r.drawOverlay(s.canvas, w, h, t0 + ms)
            }
        }
        fun lit(r: ShaderBackgroundRenderer): List<Int> = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).use { s ->
            sweep(r, s)
            s.canvas.clear(0xFF000000.toInt())
            r.drawOverlay(s.canvas, w, h, t0 + 400)
            val bmp = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(w, h) }
            s.readPixels(bmp, 0, 0)
            listOf(300, 500, 700, 850).map { brightness(bmp.getColor(it, 200)) }.also { bmp.close() }
        }
        val on = lit(renderer(hoverTrail = true))
        // Хвост тянется на сотни точек назад, без провалов
        assertTrue(on.all { it > 20 }, "след прерывается: $on")
        val off = lit(renderer(hoverTrail = false))
        assertTrue(off.all { it < 5 }, "след без нажатия, хотя выключен: $off")
    }

    @Test
    fun `trail settings change length and color`() {
        val colors = intArrayOf(0xFF101010.toInt(), 0xFF3A84FF.toInt(), 0xFF23E0FF.toInt(), 0xFF8F62FF.toInt())
        val w = 1000
        val h = 400
        // Полэкрана за 800 мс, потом кадр на чёрном — только след
        fun trailPixels(prefs: ShaderPrefs, xs: List<Int>, save: String? = null): List<Int> {
            val r = ShaderBackgroundRenderer().apply { configure(prefs, colors[0], colors[1], colors[2], colors[3], 1f) }
            val t0 = ShaderBackgroundRenderer.nowMs()
            return org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).use { s ->
                for (ms in 0..800) {
                    r.onMouse(100f + ms, 200f, pressed = false, justPressed = false, inside = true, nowMs = t0 + ms)
                    if (ms % 16 == 0) r.drawOverlay(s.canvas, w, h, t0 + ms)
                }
                s.canvas.clear(0xFF000000.toInt())
                r.drawOverlay(s.canvas, w, h, t0 + 800)
                val out = System.getenv("SHADER_OUT")
                if (save != null && out != null) s.makeImageSnapshot().use { java.io.File(out, save).writeBytes(it.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
                val bmp = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(w, h) }
                s.readPixels(bmp, 0, 0)
                xs.map { bmp.getColor(it, 200) }.also { bmp.close() }
            }
        }
        // Точка в 500 мс позади курсора: при длине 300 мс её уже нет, при 2000 мс — светится
        val short = trailPixels(ShaderPrefs(enabled = true, trailLength = 300), listOf(400))
        val long = trailPixels(ShaderPrefs(enabled = true, trailLength = 2000), listOf(400))
        assertTrue(brightness(short[0]) < 5, "короткий след дотянулся слишком далеко")
        assertTrue(brightness(long[0]) > 40, "длинный след не дотянулся")
        // Белый — нейтральный свет у курсора
        val white = trailPixels(ShaderPrefs(enabled = true, trailColor = 1), listOf(880))[0]
        val (wr, wg, wb) = Triple((white shr 16) and 0xFF, (white shr 8) and 0xFF, white and 0xFF)
        assertTrue(maxOf(wr, wg, wb) - minOf(wr, wg, wb) < 25 && wr > 60, "белый след окрашен: $wr,$wg,$wb")
        // Радуга — разные оттенки вдоль хвоста
        val rainbow = trailPixels(ShaderPrefs(enabled = true, trailColor = 7, trailLength = 1200), listOf(500, 600, 700, 800), "trail-rainbow.png")
        val hues = rainbow.map { c ->
            java.awt.Color.RGBtoHSB((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, null)[0]
        }
        assertTrue(hues.maxOrNull()!! - hues.minOrNull()!! > 0.1f, "радуга одного цвета: $hues")
    }

    @Test
    fun `window content is not redrawn while the live background animates`() {
        val saved = BackgroundPreferences.shader
        BackgroundPreferences.updateShader { ShaderPrefs(enabled = true, effect = 2) }
        var contentDraws = 0
        val scene = ImageComposeScene(800, 500, Density(1f)) {
            CompositionLocalProvider(LocalWindowInfo provides focused) {
                NoctaliaTheme {
                    Box(Modifier.fillMaxSize().drawBehind { contentDraws++ }) {
                        Column(Modifier.width(360.dp).padding(12.dp).background(MaterialTheme.colorScheme.surface)) {
                            repeat(15) { Text("файл-$it.txt", color = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                }
            }
        }
        try {
            var t = 0L
            fun frame(): ByteArray {
                t += 16_000_000
                return scene.render(t).use { it.encodeToData(EncodedImageFormat.PNG)!!.bytes }
            }
            repeat(5) { frame(); Thread.sleep(40) }
            contentDraws = 0
            val before = frame()
            // Кадр фона — раз в ~33 мс по настоящему времени
            repeat(30) { frame(); Thread.sleep(40) }
            val after = frame()
            assertFalse(before.contentEquals(after), "живой фон не двигался")
            // Содержимое лежит в своём слое: от кадров фона его картинка переиспользуется
            assertEquals(0, contentDraws)
        } finally {
            scene.close()
            BackgroundPreferences.updateShader { saved }
        }
    }

    /** Фон стоит (окно не в фокусе) — цикл кадров спит до движения мыши, а не просыпается 12 раз в секунду. */
    @Test
    fun `idle background wakes on mouse move`() {
        val renderer = ShaderBackgroundRenderer()
        val woke = java.util.concurrent.CountDownLatch(1)
        val waiter = Thread {
            kotlinx.coroutines.runBlocking { renderer.awaitTouch() }
            woke.countDown()
        }.apply { isDaemon = true; start() }
        assertFalse(woke.await(150, java.util.concurrent.TimeUnit.MILLISECONDS), "проснулся без мыши")
        renderer.onMouse(10f, 10f, pressed = false, justPressed = false, inside = true, nowMs = ShaderBackgroundRenderer.nowMs())
        assertTrue(woke.await(2, java.util.concurrent.TimeUnit.SECONDS), "движение мыши не разбудило цикл")
        waiter.join(1000)
    }
}
