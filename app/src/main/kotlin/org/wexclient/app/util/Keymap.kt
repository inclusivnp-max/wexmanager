package org.wexclient.app.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import java.io.File
import java.util.Properties
import java.awt.event.KeyEvent as Awt

/**
 * Сочетание клавиш: основная клавиша (код AWT) и модификаторы.
 *
 * Буквы хранятся латинской клавишей: при русской раскладке та же физическая клавиша даёт то же
 * сочетание, поэтому Ctrl+С (кириллическое) — это Ctrl+C.
 */
data class KeyCombo(
    val code: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false
) {
    /** Подпись для окна настроек и меню: «Ctrl+Shift+F». */
    fun label(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        if (meta) append("Meta+")
        append(keyName(code))
    }

    companion object {
        /** Клавиша нажата отдельно — это ещё не сочетание. */
        private val MODIFIERS = setOf(
            Awt.VK_CONTROL, Awt.VK_SHIFT, Awt.VK_ALT, Awt.VK_META, Awt.VK_ALT_GRAPH,
            Awt.VK_CAPS_LOCK, Awt.VK_NUM_LOCK, Awt.VK_SCROLL_LOCK, Awt.VK_UNDEFINED
        )

        /** Имена клавиш пишем сами: getKeyText переводится по языку системы и меняется от машины к машине. */
        private val NAMES: Map<Int, String> = buildMap {
            put(Awt.VK_ENTER, "Enter"); put(Awt.VK_ESCAPE, "Esc"); put(Awt.VK_TAB, "Tab")
            put(Awt.VK_SPACE, "Пробел"); put(Awt.VK_BACK_SPACE, "Backspace")
            put(Awt.VK_DELETE, "Delete"); put(Awt.VK_INSERT, "Insert")
            put(Awt.VK_HOME, "Home"); put(Awt.VK_END, "End")
            put(Awt.VK_PAGE_UP, "PgUp"); put(Awt.VK_PAGE_DOWN, "PgDn")
            put(Awt.VK_UP, "↑"); put(Awt.VK_DOWN, "↓"); put(Awt.VK_LEFT, "←"); put(Awt.VK_RIGHT, "→")
            put(Awt.VK_BACK_QUOTE, "`"); put(Awt.VK_MINUS, "-"); put(Awt.VK_EQUALS, "=")
            put(Awt.VK_OPEN_BRACKET, "["); put(Awt.VK_CLOSE_BRACKET, "]")
            put(Awt.VK_SEMICOLON, ";"); put(Awt.VK_QUOTE, "'"); put(Awt.VK_BACK_SLASH, "\\")
            put(Awt.VK_COMMA, ","); put(Awt.VK_PERIOD, "."); put(Awt.VK_SLASH, "/")
            put(Awt.VK_PLUS, "+"); put(Awt.VK_ADD, "Num +"); put(Awt.VK_SUBTRACT, "Num -")
            for (i in 1..12) put(Awt.VK_F1 + (i - 1), "F$i")
        }

        private val BY_NAME: Map<String, Int> =
            NAMES.entries.associate { (code, name) -> name.lowercase() to code } +
                mapOf("space" to Awt.VK_SPACE, "escape" to Awt.VK_ESCAPE, "пробел" to Awt.VK_SPACE)

        fun keyName(code: Int): String = when {
            code in Awt.VK_A..Awt.VK_Z -> ('A' + (code - Awt.VK_A)).toString()
            code in Awt.VK_0..Awt.VK_9 -> ('0' + (code - Awt.VK_0)).toString()
            else -> NAMES[code] ?: "#$code"
        }

        /** Сочетание из нажатия. null — нажали один модификатор или клавишу без кода. */
        fun of(e: KeyEvent): KeyCombo? {
            val awt = e.awtEvent() ?: return null
            if (awt.keyCode in MODIFIERS) return null
            val letter = e.physicalLatinLetter()
            val code = when {
                letter != null -> Awt.VK_A + (letter - 'a')
                // Русская «ё» — та же клавиша, что и `
                awt.keyChar == 'ё' || awt.keyChar == 'Ё' || (awt.extendedKeyCode and 0xFFFFFF) == 'ё'.code -> Awt.VK_BACK_QUOTE
                else -> awt.keyCode
            }
            if (code == Awt.VK_UNDEFINED) return null
            return KeyCombo(
                code = code,
                ctrl = awt.isControlDown,
                shift = awt.isShiftDown,
                alt = awt.isAltDown,
                meta = awt.isMetaDown
            )
        }

        /** Разбор записи вида «Ctrl+Shift+F» — так сочетания хранятся в файле настроек. */
        fun parse(text: String): KeyCombo? {
            val parts = text.split('+').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var ctrl = false
            var shift = false
            var alt = false
            var meta = false
            var code = -1
            for ((i, part) in parts.withIndex()) {
                val last = i == parts.size - 1
                when (part.lowercase()) {
                    "ctrl", "control" -> if (!last) ctrl = true else code = Awt.VK_CONTROL
                    "shift" -> if (!last) shift = true else code = Awt.VK_SHIFT
                    "alt" -> if (!last) alt = true else code = Awt.VK_ALT
                    "meta", "cmd", "super" -> if (!last) meta = true else code = Awt.VK_META
                    else -> code = codeOf(part) ?: return null
                }
            }
            // «Ctrl+» без клавиши или один модификатор — не сочетание
            if (code <= 0 || code in MODIFIERS) return null
            return KeyCombo(code, ctrl, shift, alt, meta)
        }

        private fun codeOf(name: String): Int? {
            if (name.length == 1) {
                val ch = name[0].uppercaseChar()
                if (ch in 'A'..'Z') return Awt.VK_A + (ch - 'A')
                if (ch in '0'..'9') return Awt.VK_0 + (ch - '0')
            }
            return BY_NAME[name.lowercase()]
        }
    }
}

/** Описание действия, которому можно назначить сочетание. */
class BindDef(
    val id: String,
    val group: String,
    val title: String,
    /** Сочетание по умолчанию — то, что было зашито в программе. */
    val default: String
) {
    val defaultCombo: KeyCombo? = KeyCombo.parse(default)
}

/**
 * Все сочетания клавиш в одном месте: обработчики спрашивают [action] или [matches], а окно
 * настроек показывает список и переназначает. Изменения сохраняются в ~/.config/wexdesktop.
 */
object Keymap {
    const val GROUP_MAIN = "Основное"
    const val GROUP_PANE = "В директориях"
    const val GROUP_TERMINAL = "Консоль"
    const val GROUP_EDITOR = "Редактор"

    val groups = listOf(GROUP_MAIN, GROUP_PANE, GROUP_TERMINAL, GROUP_EDITOR)

    val binds: List<BindDef> = listOf(
        // ---------- основное ----------
        BindDef("app.shortcuts", GROUP_MAIN, "Справка по клавишам", "F1"),
        BindDef("app.search.name", GROUP_MAIN, "Поиск по имени файла", "Ctrl+F"),
        BindDef("app.search.content", GROUP_MAIN, "Поиск текста внутри файлов", "Ctrl+Shift+F"),
        BindDef("app.history", GROUP_MAIN, "История переходов", "Ctrl+H"),
        BindDef("app.ssh", GROUP_MAIN, "Подключиться к серверу по SSH", "Ctrl+Shift+K"),
        BindDef("app.adb", GROUP_MAIN, "Телефон по adb: файлы и приложения", "Ctrl+Shift+M"),
        BindDef("app.diskusage", GROUP_MAIN, "Анализ занятого места", "Ctrl+Shift+U"),
        BindDef("app.foldercompare", GROUP_MAIN, "Сравнить папки панелей", "Ctrl+Shift+S"),
        BindDef("app.terminal", GROUP_MAIN, "Консоль панели", "Ctrl+T"),
        BindDef("app.terminal2", GROUP_MAIN, "Консоль панели (второе сочетание)", "Ctrl+`"),
        BindDef("app.refresh", GROUP_MAIN, "Обновить список", "Ctrl+R"),
        BindDef("app.pathedit", GROUP_MAIN, "Ввести путь", "Ctrl+L"),
        BindDef("app.quit", GROUP_MAIN, "Выйти из WEX", "Ctrl+Q"),

        // ---------- в директориях ----------
        BindDef("pane.other", GROUP_PANE, "Перейти в другую панель", "Tab"),
        BindDef("pane.up", GROUP_PANE, "На уровень вверх", "Backspace"),
        BindDef("pane.up2", GROUP_PANE, "На уровень вверх (второе сочетание)", "Alt+↑"),
        BindDef("pane.back", GROUP_PANE, "Назад по истории", "Alt+←"),
        BindDef("pane.forward", GROUP_PANE, "Вперёд по истории", "Alt+→"),
        BindDef("pane.properties", GROUP_PANE, "Свойства", "Alt+Enter"),
        BindDef("pane.selectAll", GROUP_PANE, "Выделить всё", "Ctrl+A"),
        BindDef("pane.rename", GROUP_PANE, "Переименовать", "F2"),
        BindDef("pane.view", GROUP_PANE, "Просмотр содержимого", "F3"),
        BindDef("pane.edit", GROUP_PANE, "Правка содержимого", "F4"),
        BindDef("pane.newFile", GROUP_PANE, "Новый файл", "Shift+F4"),
        BindDef("pane.newFile2", GROUP_PANE, "Новый файл (второе сочетание)", "Ctrl+N"),
        BindDef("pane.copyTo", GROUP_PANE, "Копировать в другую панель", "F5"),
        BindDef("pane.moveTo", GROUP_PANE, "Переместить в другую панель", "F6"),
        BindDef("pane.newFolder", GROUP_PANE, "Новая папка", "F7"),
        BindDef("pane.newFolder2", GROUP_PANE, "Новая папка (второе сочетание)", "Ctrl+Shift+N"),
        BindDef("pane.trash", GROUP_PANE, "В корзину", "Delete"),
        BindDef("pane.delete", GROUP_PANE, "Удалить навсегда", "Shift+Delete"),
        BindDef("pane.undo", GROUP_PANE, "Отменить действие с файлами", "Ctrl+Z"),
        BindDef("pane.clipCopy", GROUP_PANE, "Копировать файлы", "Ctrl+C"),
        BindDef("pane.clipCut", GROUP_PANE, "Вырезать файлы", "Ctrl+X"),
        BindDef("pane.clipPaste", GROUP_PANE, "Вставить файлы", "Ctrl+V"),
        BindDef("pane.duplicate", GROUP_PANE, "Копия рядом", "Ctrl+Shift+D"),
        BindDef("pane.diff", GROUP_PANE, "Сравнить с файлом соседней панели", "Ctrl+D"),
        BindDef("pane.hex", GROUP_PANE, "Hex-редактор", "Ctrl+Shift+H"),
        BindDef("pane.compress", GROUP_PANE, "Сжать в архив", "Alt+F5"),
        BindDef("pane.extract", GROUP_PANE, "Распаковать архив", "Alt+F9"),
        BindDef("tabs.new", GROUP_PANE, "Новая вкладка панели", "Ctrl+Shift+T"),
        BindDef("tabs.close", GROUP_PANE, "Закрыть вкладку", "Ctrl+W"),
        BindDef("tabs.next", GROUP_PANE, "Следующая вкладка", "Ctrl+Tab"),
        BindDef("tabs.prev", GROUP_PANE, "Предыдущая вкладка", "Ctrl+Shift+Tab"),

        // ---------- консоль ----------
        BindDef("term.copy", GROUP_TERMINAL, "Копировать выделенное", "Ctrl+Shift+C"),
        BindDef("term.paste", GROUP_TERMINAL, "Вставить", "Ctrl+Shift+V"),
        BindDef("term.paste2", GROUP_TERMINAL, "Вставить (второе сочетание)", "Shift+Insert"),
        BindDef("term.selectAll", GROUP_TERMINAL, "Выделить весь вывод", "Ctrl+Shift+A"),
        BindDef("term.find", GROUP_TERMINAL, "Поиск по выводу", "Ctrl+Shift+F"),
        BindDef("term.newTab", GROUP_TERMINAL, "Новая вкладка консоли", "Ctrl+Shift+T"),
        BindDef("term.closeTab", GROUP_TERMINAL, "Закрыть вкладку консоли", "Ctrl+Shift+W"),
        BindDef("term.nextTab", GROUP_TERMINAL, "Следующая вкладка консоли", "Ctrl+PgDn"),
        BindDef("term.prevTab", GROUP_TERMINAL, "Предыдущая вкладка консоли", "Ctrl+PgUp"),
        BindDef("term.clear", GROUP_TERMINAL, "Очистить вывод", "Ctrl+Shift+L"),
        BindDef("term.reset", GROUP_TERMINAL, "Сбросить консоль", "Ctrl+Shift+R"),
        BindDef("term.top", GROUP_TERMINAL, "В начало истории", "Ctrl+Shift+Home"),
        BindDef("term.bottom", GROUP_TERMINAL, "В конец вывода", "Ctrl+Shift+End"),
        BindDef("term.pageUp", GROUP_TERMINAL, "Страница вверх", "Shift+PgUp"),
        BindDef("term.pageDown", GROUP_TERMINAL, "Страница вниз", "Shift+PgDn"),
        BindDef("term.zoomIn", GROUP_TERMINAL, "Крупнее", "Ctrl+="),
        BindDef("term.zoomOut", GROUP_TERMINAL, "Мельче", "Ctrl+-"),
        BindDef("term.zoomReset", GROUP_TERMINAL, "Обычный размер", "Ctrl+0"),

        // ---------- редактор ----------
        BindDef("editor.save", GROUP_EDITOR, "Сохранить", "Ctrl+S"),
        BindDef("editor.find", GROUP_EDITOR, "Найти", "Ctrl+F"),
        BindDef("editor.replace", GROUP_EDITOR, "Заменить", "Ctrl+R"),
        BindDef("editor.format", GROUP_EDITOR, "Форматировать", "Ctrl+Alt+L"),
        BindDef("editor.structure", GROUP_EDITOR, "Структура файла", "Ctrl+F12"),
        BindDef("editor.goto", GROUP_EDITOR, "Перейти к строке", "Ctrl+G"),
        BindDef("editor.selectAll", GROUP_EDITOR, "Выделить всё", "Ctrl+A"),
        BindDef("editor.undo", GROUP_EDITOR, "Отменить", "Ctrl+Z"),
        BindDef("editor.redo", GROUP_EDITOR, "Повторить", "Ctrl+Shift+Z"),
        BindDef("editor.duplicate", GROUP_EDITOR, "Дублировать строку", "Ctrl+D"),
        BindDef("editor.deleteLine", GROUP_EDITOR, "Удалить строку", "Ctrl+Y"),
        BindDef("editor.comment", GROUP_EDITOR, "Комментарий", "Ctrl+/"),
        BindDef("editor.moveUp", GROUP_EDITOR, "Строку выше", "Alt+Shift+↑"),
        BindDef("editor.moveDown", GROUP_EDITOR, "Строку ниже", "Alt+Shift+↓"),
        BindDef("editor.complete", GROUP_EDITOR, "Автодополнение", "Ctrl+Пробел"),
        BindDef("editor.wrap", GROUP_EDITOR, "Перенос строк", "Alt+Z"),
        BindDef("editor.findInSelection", GROUP_EDITOR, "Поиск в выделении", "Alt+L"),
        BindDef("editor.nextOccurrence", GROUP_EDITOR, "Каретка на следующее вхождение", "Alt+J"),
        BindDef("editor.prevOccurrence", GROUP_EDITOR, "Убрать последнее вхождение", "Alt+Shift+J"),
        BindDef("editor.allOccurrences", GROUP_EDITOR, "Каретки на все вхождения", "Ctrl+Alt+Shift+J"),
        BindDef("editor.caretAbove", GROUP_EDITOR, "Добавить каретку выше", "Ctrl+Alt+↑"),
        BindDef("editor.caretBelow", GROUP_EDITOR, "Добавить каретку ниже", "Ctrl+Alt+↓"),
        BindDef("editor.caretsLineEnds", GROUP_EDITOR, "Каретки в концы выделенных строк", "Alt+Shift+I")
    )

    private val byId: Map<String, BindDef> = binds.associateBy { it.id }

    private val file = File(System.getProperty("user.home"), ".config/wexdesktop/keymap.properties")

    /** Переназначенные сочетания: id -> запись («» — действие отключено). */
    private val custom = mutableStateMapOf<String, String>()

    /** Меняется при любой правке — по нему пересобирается таблица поиска. */
    var revision by mutableStateOf(0)
        private set

    private var table: Map<KeyCombo, MutableList<String>> = emptyMap()
    private var tableRevision = -1

    init {
        runCatching {
            if (file.isFile) {
                val p = Properties()
                file.inputStream().use { p.load(it) }
                for (def in binds) {
                    val value = p.getProperty(def.id) ?: continue
                    custom[def.id] = value
                }
            }
        }
        rebuild()
    }

    private fun rebuild() {
        val map = HashMap<KeyCombo, MutableList<String>>()
        for (def in binds) {
            val combo = combo(def.id) ?: continue
            map.getOrPut(combo) { ArrayList(2) }.add(def.id)
        }
        table = map
        tableRevision = revision
    }

    private fun tableNow(): Map<KeyCombo, MutableList<String>> {
        if (tableRevision != revision) rebuild()
        return table
    }

    fun def(id: String): BindDef? = byId[id]

    /** Назначенное сочетание: своё, если его задали, иначе стандартное. null — действие отключено. */
    fun combo(id: String): KeyCombo? {
        val def = byId[id] ?: return null
        val own = custom[id] ?: return def.defaultCombo
        if (own.isBlank()) return null
        return KeyCombo.parse(own) ?: def.defaultCombo
    }

    /** Подпись для меню и подсказок: «Ctrl+Shift+F» или «—», если действие отключено. */
    fun label(id: String): String = combo(id)?.label() ?: "—"

    fun isDefault(id: String): Boolean = custom[id] == null

    /** Нажатие подходит под [id]? Реагируем только на нажатие клавиши, не на отпускание. */
    fun matches(id: String, e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val combo = combo(id) ?: return false
        return KeyCombo.of(e) == combo
    }

    /** Действия этой группы, назначенные на нажатие (обычно одно). */
    fun actions(e: KeyEvent, group: String): List<String> {
        if (e.type != KeyEventType.KeyDown) return emptyList()
        val combo = KeyCombo.of(e) ?: return emptyList()
        val ids = tableNow()[combo] ?: return emptyList()
        return ids.filter { byId[it]?.group == group }
    }

    /** Первое действие группы для нажатия — то, что обычно и нужно обработчику. */
    fun action(e: KeyEvent, group: String): String? = actions(e, group).firstOrNull()

    /** Другие действия той же группы с таким же сочетанием — их показываем как конфликт. */
    fun conflicts(id: String, with: KeyCombo?): List<BindDef> {
        if (with == null) return emptyList()
        val group = byId[id]?.group ?: return emptyList()
        return binds.filter { it.id != id && it.group == group && combo(it.id) == with }
    }

    fun assign(id: String, combo: KeyCombo?) {
        val def = byId[id] ?: return
        val text = combo?.label() ?: ""
        if (combo != null && combo == def.defaultCombo) custom.remove(id) else custom[id] = text
        revision++
        save()
    }

    fun reset(id: String) {
        custom.remove(id)
        revision++
        save()
    }

    fun resetAll() {
        custom.clear()
        revision++
        save()
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val p = Properties()
            for ((id, value) in custom) p.setProperty(id, value)
            file.outputStream().use { p.store(it, "Сочетания клавиш WEX Desktop") }
        }
    }
}
