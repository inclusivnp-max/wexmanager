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

/**
 * Фон окна: картинка (своя или системные обои), затемнение, размытие, прозрачность панелей
 * и эффект поверх. Всё хранится рядом с остальными настройками и применяется сразу.
 */
object BackgroundPreferences {
    private val configDir = File(System.getProperty("user.home"), ".config/wexdesktop")
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
        get() = File(System.getProperty("user.home"), ".cache/noctalia/wallpapers.json")

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

    fun updateImagePath(value: String) = update { imagePath = value.trim() }

    fun updateUseSystemWallpaper(value: Boolean) = update { useSystemWallpaper = value }

    fun updateDim(value: Int) = update { dim = value.coerceIn(0, 95) }

    fun updateBlur(value: Int) = update { blur = value.coerceIn(0, 60) }

    fun updatePanelOpacity(value: Int) = update { panelOpacity = value.coerceIn(40, 100) }

    fun updateEffect(value: BackgroundEffect) = update { effect = value }

    fun updateDensity(value: EffectDensity) = update { density = value }

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
            configFile.outputStream().use { props.store(it, "Фон окна WEXdesktop") }
        }
    }

    // Порядок важен: инициализация идёт сверху вниз, а load() и опрос обоев читают всё объявленное выше
    init {
        load()
        pollSystemWallpaper()
    }
}
