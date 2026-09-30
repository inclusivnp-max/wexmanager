package org.wexclient.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas as SkCanvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Path
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.wexclient.app.theme.ShaderPrefs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Живой фон: переливающийся градиентный туман, плавающие элементы и отклик на мышь (перенесено из WexRE).
 * Фаза — непрерывное время, а не цикл 0→1: на границе цикла ничего не прыгает.
 */
class ShaderBackgroundRenderer {
    private val paint = Paint().apply { isAntiAlias = true }
    private var animEpochMs = 0L
    private var cycleMs = 1L
    private var progress = 0f

    private var prefs = ShaderPrefs()
    private var themeBase = 0
    private var themePrimary = 0
    private var themeSecondary = 0
    private var themeTertiary = 0

    private var baseColor = 0
    private var washShader: Shader? = null
    private val blobShaders = arrayOfNulls<Shader>(3)
    private val blobRadius = FloatArray(3)
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var lastHueBucket = Int.MIN_VALUE

    private val effectPaint = Paint().apply { isAntiAlias = true }
    private val effectPath = Path()
    private val particles = ArrayList<Particle>()
    private var particleSignature = ""
    private val effectColors = IntArray(3)

    /**
     * Одна запись на нажатие (и одна на курсор мыши); после отпускания или ухода курсора живёт, пока гаснет
     * свечение. [target] — к какой силе тянется, пока [down]: нажатие — 1, курсор без нажатия — [HOVER_STRENGTH].
     */
    private class Touch(var x: Float, var y: Float, var down: Boolean, var strength: Float = 0f, var target: Float = 1f)

    /** Курсор сдвинулся с прошлого кадра — след и свечение надо перерисовать чаще обычного. */
    private var moved = false

    /** Время кадра. Его читают оба холста — фон и свечение поверх окна, — и оба перерисовываются вместе. */
    var frame by mutableLongStateOf(nowMs())
        internal set

    private val touches = LinkedHashMap<Long, Touch>()
    private var touchLastMs = 0L
    private var pushDX = 0f
    private var pushDY = 0f
    private val touchPaint = Paint().apply { isAntiAlias = true }
    private val trail = ArrayDeque<TrailPoint>()
    private var touchGlowColor = WHITE
    /** Шейдеры свечения по цвету: один на цвет, у радуги — по одному на [RAINBOW_STEPS] оттенков. */
    private val glowShaders = HashMap<Int, Shader>()
    private var density = 1f

    fun configure(prefs: ShaderPrefs, base: Int, primary: Int, secondary: Int, tertiary: Int, density: Float) {
        val colorsChanged = base != themeBase || primary != themePrimary || secondary != themeSecondary || tertiary != themeTertiary
        val old = this.prefs
        val next = prefs.copy(
            alpha = prefs.alpha.coerceIn(0, 80),
            intensity = prefs.intensity.coerceIn(0, 100),
            speed = prefs.speed.coerceIn(10, 100),
            blur = prefs.blur.coerceIn(0, 40),
            effect = prefs.effect.coerceIn(0, EFFECT_COUNT),
            effectDensity = prefs.effectDensity.coerceIn(0, 100),
            effectSize = prefs.effectSize.coerceIn(0, 100),
            effectGlow = prefs.effectGlow.coerceIn(0, 100),
            touchIntensity = prefs.touchIntensity.coerceIn(0, 100),
            trailColor = prefs.trailColor.coerceIn(0, TRAIL_TINTS.size + 1),
            trailLength = prefs.trailLength.coerceIn(150, 2000),
            trailWidth = prefs.trailWidth.coerceIn(20, 250),
            trailBrightness = prefs.trailBrightness.coerceIn(5, 100),
            glowSize = prefs.glowSize.coerceIn(20, 250),
        )
        // Композиция зовёт это на каждой перестройке — без изменений ничего не пересоздаём
        if (!colorsChanged && next == old && density == this.density && animEpochMs != 0L) return
        this.prefs = next
        this.density = density
        themeBase = base
        themePrimary = primary
        themeSecondary = secondary
        themeTertiary = tertiary
        if (colorsChanged || old.style != next.style || old.hue != next.hue || old.alpha != next.alpha ||
            old.intensity != next.intensity || old.colorAnimation != next.colorAnimation
        ) {
            washShader = null
        }
        if (old.effect != next.effect || old.effectDensity != next.effectDensity) particleSignature = ""
        val blend = rgb(
            (red(primary) + red(tertiary)) / 2,
            (green(primary) + green(tertiary)) / 2,
            (blue(primary) + blue(tertiary)) / 2,
        )
        touchGlowColor = lighten(blend, 0.35f)
        if (glowShaders.size > RAINBOW_STEPS + 2) clearGlowShaders()
        retime()
    }

    /** Смена скорости (длины цикла) не должна дёргать картинку: фазу сохраняем. */
    private fun retime() {
        val duration = max(6_000L, 22_000L - prefs.speed * 150L)
        val now = nowMs()
        if (animEpochMs == 0L) {
            animEpochMs = now
        } else if (cycleMs > 1L && cycleMs != duration) {
            val phase = (now - animEpochMs).toFloat() / cycleMs
            animEpochMs = now - (phase * duration).toLong()
        }
        cycleMs = duration
    }

    fun draw(canvas: SkCanvas, width: Int, height: Int, nowMs: Long, animate: Boolean) {
        if (width <= 0 || height <= 0) return
        advanceTouch(nowMs)
        if (animEpochMs == 0L) animEpochMs = nowMs
        if (animate) progress = (nowMs - animEpochMs).toFloat() / cycleMs.coerceAtLeast(1L)

        val effHue = prefs.hue + if (prefs.colorAnimation && animate) (progress % 1f) * 360f else 0f
        val hueBucket = (effHue / 3f).roundToInt()
        if (washShader == null || cachedWidth != width || cachedHeight != height || (prefs.colorAnimation && hueBucket != lastHueBucket)) {
            buildShaders(width, height, effHue)
            lastHueBucket = hueBucket
        }

        paint.shader = null
        paint.color = baseColor
        canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), paint)
        washShader?.let {
            paint.shader = it
            canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), paint)
            paint.shader = null
        }
        for (i in blobShaders.indices) drawBlob(canvas, i, width, height)

        if (prefs.effect != 0) {
            val sig = "${prefs.effect}|${prefs.effectDensity}|$width|$height"
            if (sig != particleSignature) {
                rebuildParticles()
                particleSignature = sig
            }
            if (particles.isNotEmpty()) drawEffects(canvas, width, height)
        }
        moved = false
    }

    /**
     * Свечение и след от курсора — отдельно от фона и поверх окна: под полупрозрачными панелями
     * (при плотности 63% сквозь тон окна и панель проходит ~14%) их почти не было видно, а размытие фона
     * растирало след в пятно. Рисуется «экраном» — только высветляет, текст под курсором не закрывает.
     */
    fun drawOverlay(canvas: SkCanvas, width: Int, height: Int, nowMs: Long) {
        if (width <= 0 || height <= 0) return
        // Второй вызов за кадр с тем же временем ничего не сдвигает
        advanceTouch(nowMs)
        drawTouchLayer(canvas, width, height, nowMs)
        moved = false
    }

    /**
     * Что-то меняется без нового ввода: курсор двигается, свечение разгорается или гаснет, тянется след.
     * Курсор стоит и свечение устоялось — частые кадры не нужны, картинка та же.
     */
    /** Сигнал «мышь шевельнулась» для цикла кадров, пока фон стоит: ждать его, а не опрашивать. */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Дождаться движения мыши или касания. */
    internal suspend fun awaitTouch() {
        wake.receive()
    }

    fun touchActive(): Boolean = moved || trail.isNotEmpty() ||
        touches.values.any { abs((if (it.down) it.target else 0f) - it.strength) > 0.01f }

    private fun buildShaders(width: Int, height: Int, effHue: Float) {
        val colors = if (prefs.style <= 0) {
            intArrayOf(themeBase, themePrimary, themeSecondary, themeTertiary)
        } else {
            PALETTES[(prefs.style - 1).coerceIn(0, PALETTES.size - 1)]
        }
        val base = shiftHue(colors[0], effHue)
        val colorA = shiftHue(colors[1], effHue)
        val colorB = shiftHue(colors[2], effHue)
        val colorC = shiftHue(colors[3], effHue)
        baseColor = base
        effectColors[0] = colorA
        effectColors[1] = colorB
        effectColors[2] = colorC

        val vivid = 0.5f + prefs.intensity / 100f
        val baseAlpha = (255f * prefs.alpha / 100f).roundToInt()
        val blobColors = intArrayOf(colorA, colorB, colorC)
        val maxSide = max(width, height).toFloat()

        // Прошлые шейдеры закрываем сами: сборщик мусора не видит нативную память. Уже записанные кадры
        // держат свою ссылку на шейдер, так что закрытие их не ломает
        washShader?.close()
        washShader = Shader.makeLinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(withAlpha(colorC, 0), withAlpha(colorC, alpha(baseAlpha * 0.35f * vivid))),
        )
        for (i in blobShaders.indices) {
            val color = blobColors[i]
            blobRadius[i] = maxSide * BLOB_RADIUS_SCALE[i]
            blobShaders[i]?.close()
            blobShaders[i] = Shader.makeRadialGradient(
                0f, 0f, max(1f, blobRadius[i]),
                intArrayOf(
                    withAlpha(color, alpha(baseAlpha * 0.78f * vivid)),
                    withAlpha(color, alpha(baseAlpha * 0.22f * vivid)),
                    TRANSPARENT,
                ),
                floatArrayOf(0f, 0.45f, 1f),
            )
        }
        cachedWidth = width
        cachedHeight = height
    }

    private fun drawBlob(canvas: SkCanvas, index: Int, width: Int, height: Int) {
        val shader = blobShaders[index] ?: return
        val angle = (progress + BLOB_PHASE[index]) * Math.PI * 2.0
        var cx = width * BLOB_ORIGIN_X[index] + cos(angle).toFloat() * width * 0.18f
        var cy = height * BLOB_ORIGIN_Y[index] + sin(angle * 0.8).toFloat() * height * 0.12f
        computePush(cx, cy, min(width, height) * 0.85f, min(width, height) * 0.18f)
        cx += pushDX
        cy += pushDY
        // Шейдер построен вокруг нуля — переносим холст, а не шейдер
        paint.shader = shader
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawCircle(0f, 0f, blobRadius[index], paint)
        canvas.restore()
        paint.shader = null
    }

    private fun rebuildParticles() {
        particles.clear()
        val mode = prefs.effect
        if (mode == 0) return
        val rnd = java.util.Random(mode * 977L + 1234L)
        val d = prefs.effectDensity / 100f
        fun count(lo: Int, hi: Int) = (lo + (hi - lo) * d).roundToInt().coerceAtLeast(if (d <= 0f) 0 else 1)
        fun rf(lo: Float, hi: Float) = lo + rnd.nextFloat() * (hi - lo)
        when (mode) {
            EFFECT_BOKEH -> repeat(count(4, 22)) {
                particles += Particle(rnd.nextFloat(), rnd.nextFloat(), rf(0.06f, 0.20f), rf(0.03f, 0.10f), rf(0.03f, 0.09f), rnd.nextFloat(), rf(0.4f, 1.0f), 0f, 0, rnd.nextInt(3), rf(0.35f, 0.75f), false)
            }
            EFFECT_PARTICLES -> repeat(count(12, 46)) {
                particles += Particle(rnd.nextFloat(), rnd.nextFloat(), rf(0.012f, 0.030f), rf(0.02f, 0.08f), rf(0.02f, 0.08f), rnd.nextFloat(), rf(0.5f, 1.4f), 0f, 0, rnd.nextInt(3), rf(0.75f, 1.0f), true)
            }
            EFFECT_BUBBLES -> repeat(count(10, 38)) {
                particles += Particle(rnd.nextFloat(), rnd.nextFloat(), rf(0.022f, 0.070f), rf(0.01f, 0.05f), 0f, rnd.nextFloat(), rf(0.5f, 1.2f), 0f, 0, rnd.nextInt(3), rf(0.6f, 0.95f), false)
            }
            EFFECT_SHAPES -> repeat(count(5, 20)) {
                particles += Particle(rnd.nextFloat(), rnd.nextFloat(), rf(0.045f, 0.110f), rf(0.03f, 0.10f), rf(0.03f, 0.09f), rnd.nextFloat(), rf(0.4f, 1.0f), rf(-1.2f, 1.2f), 3 + rnd.nextInt(4), rnd.nextInt(3), rf(0.6f, 1.0f), false)
            }
        }
    }

    private fun drawEffects(canvas: SkCanvas, width: Int, height: Int) {
        val minSide = min(width, height).toFloat()
        val baseAlpha = 255f * prefs.alpha / 100f
        val fxAlpha = 255f * (0.55f + 0.45f * (prefs.alpha / 80f).coerceIn(0f, 1f))
        val vivid = 0.6f + prefs.intensity / 100f * 0.6f
        val sizeScale = 0.4f + (prefs.effectSize / 100f) * 1.2f
        val glowFactor = prefs.effectGlow / 50f
        val t = progress
        val push = min(width, height)

        for (p in particles) {
            val rawColor = effectColors[p.colorIndex]
            when (prefs.effect) {
                EFFECT_BUBBLES -> {
                    val color = lighten(rawColor, 0.30f)
                    val travel = ((p.phase + t * p.speedMul) % 1f + 1f) % 1f
                    var cy = (1f - travel) * height
                    var cx = p.baseX * width + sin((t * p.speedMul * 2f + p.phase) * TWO_PI) * p.ampX * width
                    computePush(cx, cy, push * 0.5f, push * 0.10f)
                    cx += pushDX; cy += pushDY
                    val edgeFade = min(travel / 0.12f, min((1f - travel) / 0.12f, 1f)).coerceIn(0f, 1f)
                    val r = p.radius * minSide * sizeScale
                    val a = (fxAlpha * p.alphaMul * vivid * edgeFade).roundToInt().coerceIn(0, 255)
                    if (a <= 2) continue
                    effectPaint.mode = PaintMode.FILL
                    effectPaint.color = withAlpha(color, (a * 0.14f * glowFactor).roundToInt())
                    canvas.drawCircle(cx, cy, r, effectPaint)
                    effectPaint.mode = PaintMode.STROKE
                    effectPaint.strokeWidth = max(2f, r * 0.12f)
                    effectPaint.color = withAlpha(color, a)
                    canvas.drawCircle(cx, cy, r, effectPaint)
                    effectPaint.mode = PaintMode.FILL
                    effectPaint.color = withAlpha(WHITE, (a * 0.6f).roundToInt())
                    canvas.drawCircle(cx - r * 0.32f, cy - r * 0.32f, max(1.5f, r * 0.16f), effectPaint)
                }
                EFFECT_SHAPES -> {
                    val color = lighten(rawColor, 0.28f)
                    var cx = p.baseX * width + sin((t * p.speedMul + p.phase) * TWO_PI) * p.ampX * width
                    var cy = p.baseY * height + cos((t * p.speedMul * 0.85f + p.phase) * TWO_PI) * p.ampY * height
                    computePush(cx, cy, push * 0.5f, push * 0.10f)
                    cx += pushDX; cy += pushDY
                    val r = p.radius * minSide * sizeScale
                    val a = (fxAlpha * p.alphaMul * vivid).roundToInt().coerceIn(0, 255)
                    if (a <= 2) continue
                    buildPolygon(cx, cy, r, p.sides, (t * p.spin + p.phase) * 360f)
                    effectPaint.mode = PaintMode.FILL
                    effectPaint.color = withAlpha(color, (a * 0.14f * glowFactor).roundToInt())
                    canvas.drawPath(effectPath, effectPaint)
                    effectPaint.mode = PaintMode.STROKE
                    effectPaint.strokeWidth = max(2.5f, r * 0.10f)
                    effectPaint.color = withAlpha(color, a)
                    canvas.drawPath(effectPath, effectPaint)
                }
                EFFECT_PARTICLES -> {
                    val color = lighten(rawColor, 0.45f)
                    var cx = p.baseX * width + sin((t * p.speedMul + p.phase) * TWO_PI) * p.ampX * width
                    var cy = p.baseY * height + cos((t * p.speedMul * 1.1f + p.phase) * TWO_PI) * p.ampY * height
                    computePush(cx, cy, push * 0.5f, push * 0.10f)
                    cx += pushDX; cy += pushDY
                    val r = max(1.5f, p.radius * minSide * sizeScale)
                    val tw = if (p.twinkle) 0.7f + 0.3f * sin((t * (2f + p.speedMul) + p.phase) * TWO_PI) else 1f
                    val a = (fxAlpha * p.alphaMul * vivid * tw).roundToInt().coerceIn(0, 255)
                    if (a <= 2) continue
                    effectPaint.mode = PaintMode.FILL
                    effectPaint.color = withAlpha(color, (a * 0.28f * glowFactor).roundToInt())
                    canvas.drawCircle(cx, cy, r * (1f + 1.4f * glowFactor), effectPaint)
                    effectPaint.color = withAlpha(color, a)
                    canvas.drawCircle(cx, cy, r, effectPaint)
                }
                else -> {
                    var cx = p.baseX * width + sin((t * p.speedMul + p.phase) * TWO_PI) * p.ampX * width
                    var cy = p.baseY * height + cos((t * p.speedMul * 0.9f + p.phase) * TWO_PI) * p.ampY * height
                    computePush(cx, cy, push * 0.5f, push * 0.10f)
                    cx += pushDX; cy += pushDY
                    val r = p.radius * minSide * sizeScale
                    val a = (baseAlpha * p.alphaMul * vivid).roundToInt().coerceIn(0, 255)
                    if (a <= 2) continue
                    effectPaint.mode = PaintMode.FILL
                    effectPaint.color = withAlpha(rawColor, (a * 0.30f * glowFactor).roundToInt())
                    canvas.drawCircle(cx, cy, r, effectPaint)
                    effectPaint.color = withAlpha(rawColor, (a * 0.45f * glowFactor).roundToInt())
                    canvas.drawCircle(cx, cy, r * 0.66f, effectPaint)
                    effectPaint.color = withAlpha(rawColor, (a * 0.75f).roundToInt())
                    canvas.drawCircle(cx, cy, r * 0.34f, effectPaint)
                }
            }
        }
        effectPaint.mode = PaintMode.FILL
        effectPaint.strokeWidth = 0f
    }

    private fun buildPolygon(cx: Float, cy: Float, r: Float, sides: Int, rotDeg: Float) {
        effectPath.reset()
        val start = Math.toRadians(rotDeg.toDouble() - 90.0)
        val step = Math.PI * 2.0 / sides
        for (i in 0 until sides) {
            val ang = start + step * i
            val x = cx + (cos(ang) * r).toFloat()
            val y = cy + (sin(ang) * r).toFloat()
            if (i == 0) effectPath.moveTo(x, y) else effectPath.lineTo(x, y)
        }
        effectPath.closePath()
    }

    /** Одно касание-нажатие: [justPressed] — кнопку только что нажали, [pressed] — её держат. */
    fun onPointer(id: Long, x: Float, y: Float, pressed: Boolean, justPressed: Boolean, nowMs: Long) {
        if (!prefs.touch) return
        wake.trySend(Unit)
        when {
            justPressed -> {
                touches[id] = (touches[id] ?: Touch(x, y, true)).also { it.x = x; it.y = y; it.down = true }
                pushTrail(id, x, y, force = true, now = nowMs)
            }
            pressed -> touches[id]?.takeIf { it.down }?.let { t ->
                t.x = x
                t.y = y
                pushTrail(id, x, y, force = false, now = nowMs)
            }
            else -> touches[id]?.down = false
        }
    }

    /**
     * Мышь: свечение следует за курсором и без нажатия, мягче; нажатие разгорается сильнее.
     * [inside] = false — курсор ушёл из окна, свечение плавно гаснет.
     */
    fun onMouse(x: Float, y: Float, pressed: Boolean, justPressed: Boolean, inside: Boolean, nowMs: Long) {
        if (!prefs.touch) return
        wake.trySend(Unit)
        // Курсор ушёл из окна или след без нажатия выключен — свечение гаснет
        if (!inside || (!pressed && !prefs.hoverTrail)) {
            touches[MOUSE_ID]?.let {
                if (it.down) moved = true
                it.down = false
            }
            return
        }
        val t = touches.getOrPut(MOUSE_ID) { Touch(x, y, true, target = HOVER_STRENGTH) }
        val shifted = t.x != x || t.y != y
        t.x = x
        t.y = y
        t.down = true
        t.target = if (pressed) 1f else HOVER_STRENGTH
        if (justPressed) {
            pushTrail(MOUSE_ID, x, y, force = true, now = nowMs)
        } else if (shifted) {
            pushTrail(MOUSE_ID, x, y, force = false, now = nowMs)
        }
        if (shifted || justPressed) moved = true
    }

    private fun advanceTouch(now: Long) {
        val dt = if (touchLastMs == 0L) 16f else (now - touchLastMs).coerceIn(0L, 50L).toFloat()
        touchLastMs = now
        val it = touches.values.iterator()
        while (it.hasNext()) {
            val t = it.next()
            val target = if (t.down) t.target else 0f
            // Разгорается быстро, гаснет медленно
            val rate = if (target > t.strength) 0.016f else 0.006f
            t.strength += (target - t.strength) * (1f - exp(-rate * dt))
            if (!t.down && t.strength < 0.01f) it.remove()
        }
    }

    /** Сумма отталкиваний от всех нажатий — в [pushDX]/[pushDY] (по радиусу, квадратичное затухание). */
    private fun computePush(x: Float, y: Float, reach: Float, strengthPx: Float) {
        pushDX = 0f
        pushDY = 0f
        if (touches.isEmpty() || reach <= 1f) return
        val factor = touchFactor()
        for (t in touches.values) {
            if (t.strength <= 0.02f) continue
            val dx = x - t.x
            val dy = y - t.y
            val dist = hypot(dx, dy)
            if (dist >= reach || dist < 1f) continue
            val falloff = 1f - dist / reach
            val push = strengthPx * falloff * falloff * t.strength * factor
            pushDX += dx / dist * push
            pushDY += dy / dist * push
        }
    }

    private fun touchFactor(): Float = prefs.touchIntensity / 60f

    /**
     * Точка следа — не чаще раза в [TRAIL_STEP_MS]: мышь с опросом 500–1000 Гц иначе выбирала весь запас
     * точек за десятую долю секунды, и хвост выходил куцым. Быстрый рывок достраивается промежуточными
     * точками — хвост остаётся сплошным, а не рассыпается на бусины.
     */
    private fun pushTrail(id: Long, x: Float, y: Float, force: Boolean, now: Long) {
        val last = trail.lastOrNull { it.pointer == id }
        if (!force && last != null && now - last.bornMs < TRAIL_STEP_MS) return
        if (last != null && now - last.bornMs < prefs.trailLength) {
            val gap = hypot(x - last.x, y - last.y)
            val spacing = TRAIL_SPACING * density
            val extra = ((gap / spacing).toInt() - 1).coerceIn(0, TRAIL_FILL_MAX)
            for (k in 1..extra) {
                val f = k / (extra + 1f)
                trail.addLast(
                    TrailPoint(id, last.x + (x - last.x) * f, last.y + (y - last.y) * f, last.bornMs + ((now - last.bornMs) * f).toLong())
                )
            }
        }
        trail.addLast(TrailPoint(id, x, y, now))
        while (trail.size > TRAIL_MAX) trail.removeFirst()
    }

    /** Цвет следа и свечения. У радуги оттенок бежит со временем — вдоль хвоста он переливается. */
    private fun trailColorAt(timeMs: Long): Int {
        val choice = prefs.trailColor
        return when {
            choice <= 0 -> touchGlowColor
            choice <= TRAIL_TINTS.size -> TRAIL_TINTS[choice - 1]
            else -> {
                val step = ((timeMs / RAINBOW_MS_PER_STEP) % RAINBOW_STEPS).toInt()
                java.awt.Color.HSBtoRGB(step / RAINBOW_STEPS.toFloat(), 0.55f, 1f)
            }
        }
    }

    private fun clearGlowShaders() {
        glowShaders.values.forEach { it.close() }
        glowShaders.clear()
    }

    private fun drawGlowDot(canvas: SkCanvas, cx: Float, cy: Float, r: Float, a: Int, color: Int) {
        if (a <= 2 || r <= 1f) return
        val shader = glowShaders.getOrPut(color) {
            // Прежние цвета темы больше не нужны — не копим шейдеры (нативная память)
            if (glowShaders.size > RAINBOW_STEPS + 2) clearGlowShaders()
            Shader.makeRadialGradient(
                0f, 0f, 1f,
                intArrayOf(withAlpha(color, 235), withAlpha(color, 90), TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
            )
        }
        touchPaint.mode = PaintMode.FILL
        touchPaint.shader = shader
        touchPaint.blendMode = BlendMode.SCREEN
        touchPaint.alpha = a.coerceIn(0, 255)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(r, r)
        canvas.drawCircle(0f, 0f, 1f, touchPaint)
        canvas.restore()
        touchPaint.shader = null
        touchPaint.alpha = 255
    }

    private fun drawTouchLayer(canvas: SkCanvas, width: Int, height: Int, now: Long) {
        if (touches.isEmpty() && trail.isEmpty()) return
        val minSide = min(width, height).toFloat()
        val fac = touchFactor()
        val life = prefs.trailLength.toFloat()
        val width = prefs.trailWidth / 100f
        val glow = prefs.glowSize / 100f
        // 60 — прежняя яркость (160); выше — ярче, до насыщения
        val trailAlpha = prefs.trailBrightness * (160f / 60f)
        val ti = trail.iterator()
        while (ti.hasNext()) {
            val p = ti.next()
            val age = (now - p.bornMs).toFloat()
            if (age >= life) { ti.remove(); continue }
            val f = 1f - age / life
            val strength = touches[p.pointer]?.strength ?: 0f
            // Хвост гаснет медленнее, чем в WexRE (f^1.5, а не f²): поверх окна за курсором тянется заметный след
            drawGlowDot(
                canvas, p.x, p.y,
                minSide * (0.04f + 0.05f * f) * width,
                (trailAlpha * f * sqrt(f) * (0.35f + 0.65f * strength) * fac).roundToInt(),
                trailColorAt(p.bornMs)
            )
        }
        for (t in touches.values) {
            if (t.strength <= 0.02f) continue
            drawGlowDot(canvas, t.x, t.y, minSide * (0.15f + 0.06f * t.strength) * glow, (165f * t.strength * fac).roundToInt(), trailColorAt(now))
        }
    }

    private class Particle(
        val baseX: Float, val baseY: Float, val radius: Float, val ampX: Float, val ampY: Float, val phase: Float,
        val speedMul: Float, val spin: Float, val sides: Int, val colorIndex: Int, val alphaMul: Float, val twinkle: Boolean,
    )

    private class TrailPoint(val pointer: Long, val x: Float, val y: Float, val bornMs: Long)

    companion object {
        val PALETTES = arrayOf(
            intArrayOf(0xFF0A1210.toInt(), 0xFF92DD3D.toInt(), 0xFF23FFF1.toInt(), 0xFFA05CFF.toInt()),
            intArrayOf(0xFF0A1424.toInt(), 0xFF3A84FF.toInt(), 0xFF23E0FF.toInt(), 0xFF8F62FF.toInt()),
            intArrayOf(0xFF140A1E.toInt(), 0xFFFF5FCE.toInt(), 0xFFFF3D6E.toInt(), 0xFF23FFF1.toInt()),
            intArrayOf(0xFF1E0F0A.toInt(), 0xFFFF8A3D.toInt(), 0xFFFF5FA2.toInt(), 0xFFC05CFF.toInt()),
            intArrayOf(0xFF041826.toInt(), 0xFF1FA2A6.toInt(), 0xFF35F0D0.toInt(), 0xFF4DA8FF.toInt()),
        )
        const val EFFECT_COUNT = 4
        private const val EFFECT_BOKEH = 1
        private const val EFFECT_PARTICLES = 2
        private const val EFFECT_BUBBLES = 3
        private const val EFFECT_SHAPES = 4
        private const val TWO_PI = 6.2831855f
        /** Хвост в 2 с при точке каждые 10 мс и догоняющих точках при рывках. */
        private const val TRAIL_MAX = 400
        private const val TRAIL_STEP_MS = 10L
        /** Промежуточные точки при рывке — не реже, чем через столько точек экрана. */
        private const val TRAIL_SPACING = 14f
        private const val TRAIL_FILL_MAX = 8
        /** Готовые цвета следа по порядку [org.wexclient.app.theme.TRAIL_COLORS] после «Темы» (радуга — последней). */
        private val TRAIL_TINTS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF7FD8FF.toInt(), 0xFFB89CFF.toInt(), 0xFFFF9AD5.toInt(), 0xFFFFB27A.toInt(), 0xFF9CF5A0.toInt()
        )
        private const val RAINBOW_STEPS = 24
        /** Радуга проходит круг за 24 × 60 мс ≈ 1,4 с — на хвосте видно несколько цветов. */
        private const val RAINBOW_MS_PER_STEP = 60L
        /** Курсор без нажатия светит мягче нажатия. */
        private const val HOVER_STRENGTH = 0.75f
        /** Мышь — одна запись на всё время, какие бы номера указателя ни приходили при нажатиях. */
        private const val MOUSE_ID = Long.MIN_VALUE
        private val BLOB_ORIGIN_X = floatArrayOf(0.20f, 0.82f, 0.45f)
        private val BLOB_ORIGIN_Y = floatArrayOf(0.18f, 0.35f, 0.88f)
        private val BLOB_RADIUS_SCALE = floatArrayOf(0.72f, 0.62f, 0.82f)
        private val BLOB_PHASE = floatArrayOf(0f, 0.33f, 0.66f)
        private const val WHITE = -0x1
        private const val TRANSPARENT = 0

        fun nowMs(): Long = System.nanoTime() / 1_000_000

        private fun red(c: Int) = (c shr 16) and 0xFF
        private fun green(c: Int) = (c shr 8) and 0xFF
        private fun blue(c: Int) = c and 0xFF
        private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        private fun shiftHue(color: Int, deg: Float): Int {
            if (deg == 0f) return color
            val hsb = java.awt.Color.RGBtoHSB(red(color), green(color), blue(color), null)
            val h = ((hsb[0] * 360f + deg) % 360f).let { if (it < 0f) it + 360f else it } / 360f
            return (color and 0xFF000000.toInt()) or (java.awt.Color.HSBtoRGB(h, hsb[1], hsb[2]) and 0xFFFFFF)
        }

        private fun withAlpha(color: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

        private fun lighten(color: Int, f: Float): Int {
            val k = f.coerceIn(0f, 1f)
            return rgb(
                (red(color) + (255 - red(color)) * k).roundToInt().coerceIn(0, 255),
                (green(color) + (255 - green(color)) * k).roundToInt().coerceIn(0, 255),
                (blue(color) + (255 - blue(color)) * k).roundToInt().coerceIn(0, 255),
            )
        }

        private fun alpha(value: Float): Int = value.roundToInt().coerceIn(0, 255)
    }
}

/**
 * Живой фон под содержимым окна. Кадры идут только пока [animate] (окно в фокусе и анимации включены)
 * и не чаще [FRAME_MS]: медленный туман ничего не выигрывает от 144 Гц, а каждый кадр фона
 * перерисовывает и всё, что лежит поверх. Холст — своим слоем: его кадр не трогает остальное окно.
 */
@Composable
fun ShaderBackground(prefs: ShaderPrefs, renderer: ShaderBackgroundRenderer, animate: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    renderer.configure(
        prefs,
        scheme.surface.copy(alpha = 1f).toArgb(),
        scheme.primary.toArgb(),
        scheme.secondary.toArgb(),
        scheme.tertiary.toArgb(),
        density
    )
    LaunchedEffect(renderer, animate, prefs.touch) {
        while (true) {
            val touching = renderer.touchActive()
            if (!animate && !touching) {
                // Стоим до движения мыши. Раньше здесь был опрос раз в 80 мс — 12 пробуждений в секунду
                // и в свёрнутом окне, и в окне на другом рабочем столе
                renderer.awaitTouch()
                continue
            }
            // Под курсором свечение живее — там кадр чаще (60 в секунду: больше глаз у следа не различит,
            // а каждый кадр — всё окно); в окне не в фокусе свечение идёт в темпе фона
            delay(if (touching && animate) TOUCH_FRAME_MS else FRAME_MS)
            // Время — внутри кадра, как у анимаций Compose: запись после withFrameNanos приходила
            // уже после отрисовки и заказывала второй кадр — каждый шаг фона рисовался дважды
            withFrameNanos { renderer.frame = ShaderBackgroundRenderer.nowMs() }
        }
    }
    val blur = prefs.blur.coerceIn(0, 40).toFloat()
    Canvas(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                // Пятна шире холста, а размытие растекается за край — без обрезки лезли на соседей (в предпросмотре)
                clip = true
                renderEffect = if (blur > 0f) BlurEffect(blur, blur, TileMode.Clamp) else null
            }
    ) {
        val now = renderer.frame
        drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width.toInt(), size.height.toInt(), now, animate) }
    }
}

/** Свечение со следом за курсором — поверх всего окна, своим слоем. Клики проходят сквозь него. */
@Composable
fun ShaderTouchOverlay(renderer: ShaderBackgroundRenderer, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val now = renderer.frame
        drawIntoCanvas { renderer.drawOverlay(it.nativeCanvas, size.width.toInt(), size.height.toInt(), now) }
    }
}

/** Следит за курсором и нажатиями во всём окне на проходе Initial и ничего не забирает — интерфейс работает как обычно. */
fun Modifier.observeBackgroundTouches(renderer: ShaderBackgroundRenderer, enabled: Boolean): Modifier =
    if (!enabled) this else pointerInput(renderer) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val now = ShaderBackgroundRenderer.nowMs()
                for (change in event.changes) {
                    if (change.type == PointerType.Mouse) {
                        renderer.onMouse(
                            x = change.position.x,
                            y = change.position.y,
                            pressed = change.pressed,
                            justPressed = change.pressed && !change.previousPressed,
                            inside = event.type != PointerEventType.Exit,
                            nowMs = now,
                        )
                        continue
                    }
                    renderer.onPointer(
                        id = change.id.value,
                        x = change.position.x,
                        y = change.position.y,
                        pressed = change.pressed,
                        justPressed = change.pressed && !change.previousPressed,
                        nowMs = now,
                    )
                }
            }
        }
    }

/** Кадр фона — примерно 30 в секунду. */
private const val FRAME_MS = 33L

/** Кадр свечения под курсором — 60 в секунду. */
private const val TOUCH_FRAME_MS = 16L
