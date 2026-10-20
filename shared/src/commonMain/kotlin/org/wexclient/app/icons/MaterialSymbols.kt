package org.wexclient.app.icons

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter

/**
 * Иконки Material Symbols Rounded (M3) из шрифтов в resources/symbols.
 * Глиф рисуется как Painter, поэтому работает с обычным Icon(tint = ...).
 */
enum class SymbolStyle(val key: String, val resource: String, val title: String) {
    FILLED("filled", "MaterialSymbolsRounded-Filled.ttf", "С заливкой"),
    OUTLINED("outlined", "MaterialSymbolsRounded-Outlined.ttf", "Контурные")
}

/** Иконка в настройках: "filled:folder:e2c7" (кодпоинт — чтобы не читать индекс на старте). */
data class SymbolRef(val name: String, val style: SymbolStyle = SymbolStyle.FILLED, val codepoint: Int? = null) {
    fun encode(): String {
        val cp = codepoint ?: MaterialSymbols.codepointOf(name)
        return if (cp != null) "${style.key}:$name:${cp.toString(16)}" else "${style.key}:$name"
    }

    fun resolvedCodepoint(): Int? = codepoint ?: MaterialSymbols.codepointOf(name)

    companion object {
        val FOLDER = SymbolRef("folder", SymbolStyle.FILLED, 0xe2c7)

        // Значения iconType из старого bookmarks.tsv
        private val legacy = mapOf(
            "FOLDER" to FOLDER,
            "HOME" to SymbolRef("home", SymbolStyle.FILLED, 0xe88a),
            "ROOT" to SymbolRef("hard_drive", SymbolStyle.FILLED, 0xf80e),
            "DRIVE" to SymbolRef("hard_drive", SymbolStyle.FILLED, 0xf80e),
            "DOWNLOADS" to SymbolRef("download", SymbolStyle.FILLED, 0xe171),
            "DESKTOP" to SymbolRef("desktop_windows", SymbolStyle.FILLED, 0xe30c)
        )

        fun parse(raw: String?): SymbolRef {
            if (raw.isNullOrBlank()) return FOLDER
            legacy[raw]?.let { return it }
            val parts = raw.split(':')
            return when (parts.size) {
                1 -> SymbolRef(parts[0])
                else -> SymbolRef(
                    name = parts[1],
                    style = SymbolStyle.entries.firstOrNull { it.key == parts[0] } ?: SymbolStyle.FILLED,
                    codepoint = parts.getOrNull(2)?.toIntOrNull(16)
                )
            }
        }
    }
}

data class SymbolInfo(
    val name: String,
    val codepoint: Int,
    val category: Int,
    val popularity: Int,
    val tags: String
)

object MaterialSymbols {
    /** Индексы совпадают с колонкой category в index.tsv. */
    val categories = listOf(
        "Текст", "Интерфейс", "Люди и соцсети", "Изображения", "Android", "Действия",
        "Устройства", "Аудио и видео", "Дом и быт", "Бизнес", "Карты", "Связь",
        "Транспорт", "Занятия", "Умный дом", "Путешествия", "Безопасность"
    )

    /** Подборка для папок — показывается первой в выборе иконки. */
    val folderPresets = listOf(
        "folder", "folder_open", "folder_special", "folder_zip", "folder_managed", "folder_shared",
        "home", "download", "desktop_windows", "description", "article", "code", "terminal",
        "data_object", "android", "adb", "deployed_code", "package_2", "inventory_2", "archive",
        "sports_esports", "stadia_controller", "movie", "videocam", "music_note", "headphones",
        "image", "photo_library", "wallpaper", "palette", "brush", "cloud", "cloud_download",
        "hard_drive", "storage", "usb", "sd_card", "dns", "database", "memory", "computer",
        "smartphone", "work", "school", "science", "rocket_launch", "build",
        "construction", "bug_report", "extension", "settings", "apps", "star", "favorite",
        "bookmark", "label", "flag", "lock", "key", "shield", "public", "language", "translate",
        "book", "library_books", "menu_book", "schedule", "history", "backup", "sync",
        "delete", "recycling", "shopping_bag", "receipt_long", "account_circle", "group",
        "pets", "bolt", "local_fire_department", "token", "savings", "travel_explore"
    )

    // Русские подсказки для поиска: префикс слова -> английские ключи имён/тегов
    private val ruHints = mapOf(
        "пап" to listOf("folder"), "каталог" to listOf("folder"), "загруз" to listOf("download"),
        "скач" to listOf("download"), "дом" to listOf("home", "house"), "рабоч" to listOf("desktop", "work"),
        "стол" to listOf("desktop"), "документ" to listOf("description", "article", "document"),
        "текст" to listOf("text", "article", "notes"), "код" to listOf("code", "data_object"),
        "терминал" to listOf("terminal"), "консол" to listOf("terminal"), "андроид" to listOf("android"),
        "прилож" to listOf("apps", "android"), "игр" to listOf("game", "esports", "controller"),
        "музык" to listOf("music", "audio"), "звук" to listOf("volume", "audio", "music"),
        "видео" to listOf("movie", "video"), "фильм" to listOf("movie", "theaters"),
        "фото" to listOf("photo", "image", "camera"), "картин" to listOf("image", "wallpaper"),
        "изображ" to listOf("image", "photo"), "обла" to listOf("cloud"), "звезд" to listOf("star"),
        "избран" to listOf("favorite", "star", "bookmark"), "сердц" to listOf("favorite"),
        "работ" to listOf("work", "business"), "учеб" to listOf("school"), "школ" to listOf("school"),
        "наук" to listOf("science"), "диск" to listOf("hard_drive", "storage", "disc"),
        "флеш" to listOf("usb", "sd_card"), "настрой" to listOf("settings", "tune"),
        "архив" to listOf("archive", "folder_zip", "inventory"), "книг" to listOf("book", "library"),
        "замок" to listOf("lock"), "ключ" to listOf("key"), "защит" to listOf("shield", "security"),
        "телефон" to listOf("smartphone", "phone"), "ноут" to listOf("laptop"),
        "компьют" to listOf("computer", "desktop"), "проект" to listOf("rocket", "work", "deployed_code"),
        "поиск" to listOf("search"), "корзин" to listOf("delete", "shopping"), "удал" to listOf("delete"),
        "флаг" to listOf("flag"), "врем" to listOf("schedule", "timer"), "час" to listOf("schedule", "watch"),
        "истори" to listOf("history"), "сеть" to listOf("network", "wifi", "lan"), "интернет" to listOf("public", "language"),
        "почт" to listOf("mail"), "чат" to listOf("chat", "forum"), "пользоват" to listOf("person", "account"),
        "люд" to listOf("group", "person"), "деньг" to listOf("payments", "savings", "money"),
        "покуп" to listOf("shopping"), "молни" to listOf("bolt"), "огон" to listOf("fire"),
        "животн" to listOf("pets"), "кош" to listOf("pets"), "собак" to listOf("pets"),
        "инструмент" to listOf("build", "construction", "handyman"), "баг" to listOf("bug"),
        "ошибк" to listOf("bug", "error"), "пакет" to listOf("package", "inventory"),
        "шрифт" to listOf("font"), "перевод" to listOf("translate"), "карт" to listOf("map"),
        "машин" to listOf("car"), "самол" to listOf("flight"), "путеш" to listOf("travel", "luggage"),
        "бэкап" to listOf("backup"), "резерв" to listOf("backup"), "синхр" to listOf("sync"),
        "скрин" to listOf("screenshot"), "запис" to listOf("mic", "record"), "памят" to listOf("memory"),
        "база" to listOf("database"), "сервер" to listOf("dns", "storage"), "устрой" to listOf("devices")
    )

    val all: List<SymbolInfo> by lazy {
        val stream = MaterialSymbols::class.java.classLoader.getResourceAsStream("symbols/index.tsv")
            ?: return@lazy emptyList()
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { line ->
                val p = line.split('\t')
                val cp = p.getOrNull(1)?.toIntOrNull(16) ?: return@mapNotNull null
                SymbolInfo(
                    name = p[0],
                    codepoint = cp,
                    category = p.getOrNull(2)?.toIntOrNull() ?: -1,
                    popularity = p.getOrNull(3)?.toIntOrNull() ?: 0,
                    tags = p.getOrNull(4) ?: ""
                )
            }.toList()
        }
    }

    private val byName: Map<String, SymbolInfo> by lazy { all.associateBy { it.name } }

    fun codepointOf(name: String): Int? = byName[name]?.codepoint

    fun info(name: String): SymbolInfo? = byName[name]

    /** Поиск по имени/тегам (понимает часть русских слов); без повторов одного глифа. */
    fun search(query: String, category: Int? = null, presetsFirst: Boolean = false): List<SymbolInfo> {
        val q = query.trim().lowercase()
        val base = if (category != null) all.filter { it.category == category } else all
        val result: List<SymbolInfo> = if (q.isEmpty()) {
            if (presetsFirst && category == null) {
                val presets = folderPresets.mapNotNull { byName[it] }
                presets + base
            } else base
        } else {
            val words = q.split(' ', '_', '-').filter { it.isNotEmpty() }
            val keys = words.map { w ->
                val hints = ruHints.entries.filter { w.startsWith(it.key) || it.key.startsWith(w) && w.length >= 3 }
                    .flatMap { it.value }
                if (hints.isNotEmpty()) hints else listOf(w)
            }
            base.mapNotNull { s ->
                var score = 0
                for (alternatives in keys) {
                    val best = alternatives.maxOf { k ->
                        when {
                            s.name == k -> 100
                            s.name.startsWith(k) -> 60
                            s.name.contains(k) -> 40
                            s.tags.split(' ').any { it == k } -> 25
                            s.tags.contains(k) -> 10
                            else -> 0
                        }
                    }
                    if (best == 0) return@mapNotNull null
                    score += best
                }
                s to score
            }.sortedWith(compareByDescending<Pair<SymbolInfo, Int>> { it.second }.thenByDescending { it.first.popularity })
                .map { it.first }
        }
        val seen = HashSet<Int>()
        return result.filter { seen.add(it.codepoint) }
    }
}

/** Глиф шрифта Material Symbols нужного начертания; цвет задаётся tint'ом Icon. Рисует платформа (Skia / android.graphics). */
expect fun symbolPainter(codepoint: Int, style: SymbolStyle): Painter

@Composable
fun rememberSymbolPainter(ref: SymbolRef): Painter {
    return remember(ref) { symbolPainter(ref.resolvedCodepoint() ?: 0xe2c7, ref.style) }
}

@Composable
fun SymbolIcon(
    ref: SymbolRef,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(rememberSymbolPainter(ref), contentDescription, modifier, tint)
}
