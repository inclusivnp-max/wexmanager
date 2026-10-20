package org.wexclient.app.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Properties

object EditorPreferences {
    private const val DEFAULT_FONT_SIZE = 13f
    private const val MIN_FONT_SIZE = 8f
    private const val MAX_FONT_SIZE = 36f

    private val configDir = org.wexclient.app.util.AppDirs.config.toFile()
    private val configFile = File(configDir, "editor.properties")

    var fontSizeSp by mutableStateOf(DEFAULT_FONT_SIZE)
        private set

    /** Перенос длинных строк по ширине окна. */
    var softWrap by mutableStateOf(false)
        private set
    var showWhitespace by mutableStateOf(false)
        private set
    var showMinimap by mutableStateOf(true)
        private set
    var showIndentGuides by mutableStateOf(true)
        private set
    var highlightCurrentLine by mutableStateOf(true)
        private set
    /** Подсказки всплывают при вводе (иначе — только Ctrl+Space). */
    var autoPopup by mutableStateOf(true)
        private set
    var autoClose by mutableStateOf(true)
        private set
    var tabSize by mutableStateOf(4)
        private set
    var showOutline by mutableStateOf(false)
        private set
    /** Проверка ошибок на лету. */
    var diagnostics by mutableStateOf(true)
        private set
    /** Внешние проверщики: bash -n, python, node, luac, gofmt, qmllint, niri validate. */
    var externalChecks by mutableStateOf(true)
        private set
    var ligatures by mutableStateOf(true)
        private set
    /** Закреплённые заголовки блоков вверху (как sticky lines в IntelliJ). */
    var stickyHeaders by mutableStateOf(true)
        private set
    var showLineNumbers by mutableStateOf(true)
        private set
    /** Подсветка вхождений слова под курсором. */
    var highlightOccurrences by mutableStateOf(true)
        private set

    init {
        load()
    }

    private fun load() {
        try {
            if (!configFile.exists()) return
            val props = Properties()
            configFile.inputStream().use { props.load(it) }
            props.getProperty("fontSizeSp")?.toFloatOrNull()?.let {
                fontSizeSp = it.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
            }
            fun bool(key: String, def: Boolean) = props.getProperty(key)?.toBooleanStrictOrNull() ?: def
            softWrap = bool("softWrap", softWrap)
            showWhitespace = bool("showWhitespace", showWhitespace)
            showMinimap = bool("showMinimap", showMinimap)
            showIndentGuides = bool("showIndentGuides", showIndentGuides)
            stickyHeaders = bool("stickyHeaders", stickyHeaders)
            highlightCurrentLine = bool("highlightCurrentLine", highlightCurrentLine)
            autoPopup = bool("autoPopup", autoPopup)
            autoClose = bool("autoClose", autoClose)
            showOutline = bool("showOutline", showOutline)
            diagnostics = bool("diagnostics", diagnostics)
            externalChecks = bool("externalChecks", externalChecks)
            ligatures = bool("ligatures", ligatures)
            showLineNumbers = bool("showLineNumbers", showLineNumbers)
            highlightOccurrences = bool("highlightOccurrences", highlightOccurrences)
            props.getProperty("tabSize")?.toIntOrNull()?.let { tabSize = it.coerceIn(1, 16) }
        } catch (_: Throwable) {
            // Остаются значения по умолчанию
        }
    }

    fun updateFontSize(newSize: Float) {
        val clamped = newSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        if (fontSizeSp != clamped) {
            fontSizeSp = clamped
            save()
        }
    }

    fun resetFontSize() {
        updateFontSize(DEFAULT_FONT_SIZE)
    }

    fun updateSoftWrap(v: Boolean) { softWrap = v; save() }
    fun updateShowWhitespace(v: Boolean) { showWhitespace = v; save() }
    fun updateShowMinimap(v: Boolean) { showMinimap = v; save() }
    fun updateShowIndentGuides(v: Boolean) { showIndentGuides = v; save() }
    fun updateStickyHeaders(v: Boolean) { stickyHeaders = v; save() }
    fun updateHighlightCurrentLine(v: Boolean) { highlightCurrentLine = v; save() }
    fun updateAutoPopup(v: Boolean) { autoPopup = v; save() }
    fun updateAutoClose(v: Boolean) { autoClose = v; save() }
    fun updateTabSize(v: Int) { tabSize = v.coerceIn(1, 16); save() }
    fun updateShowOutline(v: Boolean) { showOutline = v; save() }
    fun updateDiagnostics(v: Boolean) { diagnostics = v; save() }
    fun updateExternalChecks(v: Boolean) { externalChecks = v; save() }
    fun updateLigatures(v: Boolean) { ligatures = v; save() }
    fun updateShowLineNumbers(v: Boolean) { showLineNumbers = v; save() }
    fun updateHighlightOccurrences(v: Boolean) { highlightOccurrences = v; save() }

    private fun save() {
        try {
            if (!configDir.exists()) configDir.mkdirs()
            val props = Properties()
            props.setProperty("fontSizeSp", fontSizeSp.toString())
            props.setProperty("softWrap", softWrap.toString())
            props.setProperty("showWhitespace", showWhitespace.toString())
            props.setProperty("showMinimap", showMinimap.toString())
            props.setProperty("showIndentGuides", showIndentGuides.toString())
            props.setProperty("stickyHeaders", stickyHeaders.toString())
            props.setProperty("highlightCurrentLine", highlightCurrentLine.toString())
            props.setProperty("autoPopup", autoPopup.toString())
            props.setProperty("autoClose", autoClose.toString())
            props.setProperty("tabSize", tabSize.toString())
            props.setProperty("showOutline", showOutline.toString())
            props.setProperty("diagnostics", diagnostics.toString())
            props.setProperty("externalChecks", externalChecks.toString())
            props.setProperty("ligatures", ligatures.toString())
            props.setProperty("showLineNumbers", showLineNumbers.toString())
            props.setProperty("highlightOccurrences", highlightOccurrences.toString())
            org.wexclient.app.util.AtomicFiles.writeProperties(configFile, props, "WEX Desktop Editor Preferences")
        } catch (_: Throwable) {
            // Ошибки записи не мешают работе
        }
    }
}
