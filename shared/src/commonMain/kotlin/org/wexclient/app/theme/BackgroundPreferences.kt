package org.wexclient.app.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Properties

/** Падающая мелочь поверх фона. */
enum class BackgroundEffect(val title: String, val description: String) {
    NONE("Нет", "Только фон"),
    SNOW("Снег", "Медленные снежинки"),
    BUBBLES("Пузыри", "Всплывают снизу"),
    STARS("Звёзды", "Мерцают на месте"),
    RAIN("Дождь", "Косые капли"),
    LEAVES("Листья", "Кружатся и падают"),
    FIREFLIES("Светлячки", "Плавают по кругу")
}

/** Сколько частиц рисовать. */
enum class EffectDensity(val title: String, val count: Int) {
    LOW("Мало", 40),
    MEDIUM("Средне", 90),
    HIGH("Много", 180)
}

/** Живой фон: переливающийся градиент с элементами поверх (как в WexRE). Рисуется вместо картинки. */
data class ShaderPrefs(
    val enabled: Boolean = false,
    /** 0 — цвета темы, 1..5 — готовые палитры [SHADER_STYLES]. */
    val style: Int = 0,
    /** Насыщенность, 0..80. */
    val alpha: Int = 34,
    /** Сдвиг оттенка, -180..180. */
    val hue: Int = 0,
    /** Яркость, 0..100. */
    val intensity: Int = 70,
    val colorAnimation: Boolean = false,
    val speed: Int = 45,
    val blur: Int = 0,
    /** 0 — нет, 1 — боке, 2 — частицы, 3 — пузыри, 4 — фигуры ([SHADER_EFFECTS]). */
    val effect: Int = 0,
    val effectDensity: Int = 60,
    val effectSize: Int = 50,
    val effectGlow: Int = 50,
    /** Свечение и след за курсором (нажатие разгорается ярче). */
    val touch: Boolean = true,
    val touchIntensity: Int = 60,
    /** След за курсором и без нажатия; выключено — свечение и след только пока нажата кнопка. */
    val hoverTrail: Boolean = true,
    /** Цвет следа и свечения — индекс в [TRAIL_COLORS]: 0 — от темы, последний — радуга. */
    val trailColor: Int = 0,
    /** Сколько живёт хвост, мс. */
    val trailLength: Int = 650,
    /** Толщина хвоста, % от обычной. */
    val trailWidth: Int = 100,
    /** Яркость хвоста, 5..100. */
    val trailBrightness: Int = 60,
    /** Размер свечения под курсором, % от обычного. */
    val glowSize: Int = 100
)

val SHADER_STYLES = listOf("Тема", "Лайм", "Океан", "Неон", "Закат", "Бирюза")
val SHADER_EFFECTS = listOf("Нет", "Боке", "Частицы", "Пузыри", "Фигуры")
val TRAIL_COLORS = listOf("Тема", "Белый", "Голубой", "Фиолетовый", "Розовый", "Оранжевый", "Зелёный", "Радуга")

/**
 * Фон окна: картинка (своя или системные обои), затемнение, размытие, прозрачность панелей
 * и эффект поверх. Всё хранится рядом с остальными настройками и применяется сразу.
 */
object BackgroundPreferences {
    private val configDir = org.wexclient.app.util.AppDirs.config.toFile()
    private val configFile = File(configDir, "background.properties")

    /** Путь к своей картинке; пусто — картинки нет. */
    var imagePath by mutableStateOf("")
        private set

    /** Брать текущие обои рабочего стола (Noctalia). */
    var useSystemWallpaper by mutableStateOf(false)
        private set

    /** Затемнение картинки, проценты. */
    var dim by mutableStateOf(45)
        private set

    /** Размытие картинки в точках. */
    var blur by mutableStateOf(12)
        private set

    /** Насколько панели закрывают картинку, проценты (100 — непрозрачные). */
    var panelOpacity by mutableStateOf(88)
        private set

    var effect by mutableStateOf(BackgroundEffect.NONE)
        private set

    var density by mutableStateOf(EffectDensity.MEDIUM)
        private set

    /** Эффект рисуется и без картинки. */
    val effectEnabled: Boolean get() = effect != BackgroundEffect.NONE

    /** Живой фон вместо картинки. */
    var shader by mutableStateOf(ShaderPrefs())
        private set

    /** Какую картинку показывать с учётом «как в системе»; null — никакой. */
    fun image(): File? {
        if (useSystemWallpaper) {
            systemWallpaper?.let { return it }
        }
        val own = imagePath.trim()
        if (own.isEmpty()) return null
        val file = File(own)
        return if (file.isFile) file else null
    }

    private val wallpaperState: File
        get() = org.wexclient.app.util.AppDirs.cacheRoot.resolve("noctalia/wallpapers.json").toFile()

    private var wallpaperStamp = -1L

    /** Текущие обои рабочего стола; обновляются, когда оболочка их меняет. */
    var systemWallpaper by mutableStateOf<File?>(null)
        private set

    /** Системные обои вообще есть на этой машине. */
    val systemWallpaperAvailable: Boolean get() = wallpaperState.isFile

    /**
     * Перечитывает обои, если оболочка их сменила. Зовётся из фонового цикла, а не из отрисовки:
     * запись состояния во время композиции ломает перерисовку.
     */
    fun pollSystemWallpaper() {
        val state = wallpaperState
        if (!state.isFile) {
            if (systemWallpaper != null) systemWallpaper = null
            wallpaperStamp = -1L
            return
        }
        val stamp = state.lastModified()
        if (stamp == wallpaperStamp) return
        wallpaperStamp = stamp
        val found = runCatching { parseWallpaper(state.readText()) }.getOrNull()
        if (found?.absolutePath != systemWallpaper?.absolutePath) systemWallpaper = found
    }

    /**
     * Порядок важен: сначала обои монитора, потом последние случайные и только затем те,
     * что лежат в поставке оболочки — иначе вместо своих обоев показывалась бы заставка Noctalia.
     */
    internal fun parseWallpaper(text: String): File? {
        val candidates = ArrayList<String>()
        runCatching {
            val root = com.google.gson.JsonParser.parseString(text).asJsonObject
            root.getAsJsonObject("wallpapers")?.entrySet()
                // Обои с именем монитора надёжнее записи с пустым ключом
                ?.sortedByDescending { it.key.isNotEmpty() }
                ?.forEach { (_, value) ->
                    val monitor = value.asJsonObject
                    monitor.get("dark")?.asString?.let(candidates::add)
                    monitor.get("light")?.asString?.let(candidates::add)
                }
            root.getAsJsonObject("usedRandomWallpapers")?.getAsJsonArray("all")
                ?.forEach { candidates.add(it.asString) }
            root.get("defaultWallpaper")?.asString?.let(candidates::add)
        }
        if (candidates.isEmpty()) {
            // Запасной разбор, если формат файла поменяется
            Regex("\"(?:dark|light)\"\\s*:\\s*\"([^\"]+)\"").findAll(text)
                .forEach { candidates.add(it.groupValues[1]) }
        }
        return candidates.map(::File).firstOrNull { it.isFile }
    }

    val hasImage: Boolean get() = image() != null

    /** Под панелями есть что показать — картинка или живой фон: панели тогда полупрозрачные. */
    val hasBackdrop: Boolean get() = shader.enabled || hasImage

    fun updateImagePath(value: String) = update { imagePath = value.trim() }

    fun updateUseSystemWallpaper(value: Boolean) = update { useSystemWallpaper = value }

    fun updateDim(value: Int) = update { dim = value.coerceIn(0, 95) }

    fun updateBlur(value: Int) = update { blur = value.coerceIn(0, 60) }

    fun updatePanelOpacity(value: Int) = update { panelOpacity = value.coerceIn(40, 100) }

    fun updateEffect(value: BackgroundEffect) = update { effect = value }

    fun updateDensity(value: EffectDensity) = update { density = value }

    /** Цвета темы пересобираем, только когда живой фон включили или выключили: от этого зависит прозрачность панелей. */
    fun updateShader(change: (ShaderPrefs) -> ShaderPrefs) {
        val old = shader
        val next = change(old)
        if (next == old) return
        shader = next
        save()
        if (next.enabled != old.enabled) ThemePreferences.refresh()
    }

    private inline fun update(change: () -> Unit) {
        change()
        save()
        // Прозрачность панелей входит в цветовую схему — её надо пересобрать
        ThemePreferences.refresh()
    }

    private fun load() {
        if (!configFile.isFile) return
        runCatching {
            val props = Properties()
            configFile.inputStream().use { props.load(it) }
            imagePath = props.getProperty("image", "")
            useSystemWallpaper = props.getProperty("systemWallpaper", "false").toBoolean()
            dim = props.getProperty("dim", "45").toIntOrNull()?.coerceIn(0, 95) ?: 45
            blur = props.getProperty("blur", "12").toIntOrNull()?.coerceIn(0, 60) ?: 12
            panelOpacity = props.getProperty("panelOpacity", "88").toIntOrNull()?.coerceIn(40, 100) ?: 88
            effect = runCatching { BackgroundEffect.valueOf(props.getProperty("effect", "NONE")) }
                .getOrDefault(BackgroundEffect.NONE)
            density = runCatching { EffectDensity.valueOf(props.getProperty("density", "MEDIUM")) }
                .getOrDefault(EffectDensity.MEDIUM)
            val d = ShaderPrefs()
            fun int(key: String, def: Int, range: IntRange) =
                props.getProperty("shader.$key")?.toIntOrNull()?.coerceIn(range) ?: def
            fun bool(key: String, def: Boolean) = props.getProperty("shader.$key")?.toBooleanStrictOrNull() ?: def
            shader = ShaderPrefs(
                enabled = bool("enabled", d.enabled),
                style = int("style", d.style, 0..SHADER_STYLES.lastIndex),
                alpha = int("alpha", d.alpha, 0..80),
                hue = int("hue", d.hue, -180..180),
                intensity = int("intensity", d.intensity, 0..100),
                colorAnimation = bool("colorAnimation", d.colorAnimation),
                speed = int("speed", d.speed, 10..100),
                blur = int("blur", d.blur, 0..40),
                effect = int("effect", d.effect, 0..SHADER_EFFECTS.lastIndex),
                effectDensity = int("effectDensity", d.effectDensity, 0..100),
                effectSize = int("effectSize", d.effectSize, 0..100),
                effectGlow = int("effectGlow", d.effectGlow, 0..100),
                touch = bool("touch", d.touch),
                touchIntensity = int("touchIntensity", d.touchIntensity, 0..100),
                hoverTrail = bool("hoverTrail", d.hoverTrail),
                trailColor = int("trailColor", d.trailColor, 0..TRAIL_COLORS.lastIndex),
                trailLength = int("trailLength", d.trailLength, 150..2000),
                trailWidth = int("trailWidth", d.trailWidth, 20..250),
                trailBrightness = int("trailBrightness", d.trailBrightness, 5..100),
                glowSize = int("glowSize", d.glowSize, 20..250)
            )
        }
    }

    private fun save() {
        runCatching {
            configDir.mkdirs()
            val props = Properties()
            props.setProperty("image", imagePath)
            props.setProperty("systemWallpaper", useSystemWallpaper.toString())
            props.setProperty("dim", dim.toString())
            props.setProperty("blur", blur.toString())
            props.setProperty("panelOpacity", panelOpacity.toString())
            props.setProperty("effect", effect.name)
            props.setProperty("density", density.name)
            with(shader) {
                props.setProperty("shader.enabled", enabled.toString())
                props.setProperty("shader.style", style.toString())
                props.setProperty("shader.alpha", alpha.toString())
                props.setProperty("shader.hue", hue.toString())
                props.setProperty("shader.intensity", intensity.toString())
                props.setProperty("shader.colorAnimation", colorAnimation.toString())
                props.setProperty("shader.speed", speed.toString())
                props.setProperty("shader.blur", blur.toString())
                props.setProperty("shader.effect", effect.toString())
                props.setProperty("shader.effectDensity", effectDensity.toString())
                props.setProperty("shader.effectSize", effectSize.toString())
                props.setProperty("shader.effectGlow", effectGlow.toString())
                props.setProperty("shader.touch", touch.toString())
                props.setProperty("shader.touchIntensity", touchIntensity.toString())
                props.setProperty("shader.hoverTrail", hoverTrail.toString())
                props.setProperty("shader.trailColor", trailColor.toString())
                props.setProperty("shader.trailLength", trailLength.toString())
                props.setProperty("shader.trailWidth", trailWidth.toString())
                props.setProperty("shader.trailBrightness", trailBrightness.toString())
                props.setProperty("shader.glowSize", glowSize.toString())
            }
            configFile.outputStream().use { props.store(it, "Фон окна WEXdesktop") }
        }
    }

    // Порядок важен: инициализация идёт сверху вниз, а load() и опрос обоев читают всё объявленное выше
    init {
        load()
        pollSystemWallpaper()
    }
}
