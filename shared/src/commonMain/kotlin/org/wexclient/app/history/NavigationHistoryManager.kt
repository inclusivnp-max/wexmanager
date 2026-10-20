package org.wexclient.app.history

import androidx.compose.runtime.mutableStateListOf
import org.wexclient.app.util.AtomicFiles
import org.wexclient.app.util.ErrorLog
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NavHistoryItem(
    val pathStr: String,
    val fileName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isBeforeClose: Boolean = false,
    val paneSide: String = "Левая" // "Левая" or "Правая"
) {
    val displayName: String
        get() = fileName ?: try {
            Paths.get(pathStr).fileName?.toString() ?: pathStr
        } catch (_: Exception) {
            pathStr
        }
}

object NavigationHistoryManager {
    private val configDir = org.wexclient.app.util.AppDirs.config.toFile()
    private val historyFile = File(configDir, "history.tsv")

    val items = mutableStateListOf<NavHistoryItem>()

    @Volatile
    private var lastLeftPath: String? = null
    @Volatile
    private var lastLeftFile: String? = null
    @Volatile
    private var lastRightPath: String? = null
    @Volatile
    private var lastRightFile: String? = null

    init {
        load()
        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                flushCurrentStateBeforeClose()
                saveNow()
            })
        } catch (_: Exception) {}
    }

    fun updateCurrentPaneState(
        leftPath: Path?,
        leftFile: String?,
        rightPath: Path?,
        rightFile: String?
    ) {
        if (leftPath != null) lastLeftPath = leftPath.toString()
        if (leftFile != null) lastLeftFile = leftFile
        if (rightPath != null) lastRightPath = rightPath.toString()
        if (rightFile != null) lastRightFile = rightFile
    }

    fun record(
        path: Path,
        fileName: String? = null,
        isBeforeClose: Boolean = false,
        paneSide: String = "Левая"
    ) {
        // Путь на сервере и телефоне в историю не пишем: после перезапуска он открылся бы как локальный
        if (path.fileSystem is org.wexclient.app.vfs.remote.RemoteFileSystem) return
        val pathStr = path.toString()
        if (paneSide == "Левая") {
            lastLeftPath = pathStr
            lastLeftFile = fileName
        } else {
            lastRightPath = pathStr
            lastRightFile = fileName
        }

        // Удаляем дубликат обычной записи, но сохраняем метку "Перед закрытием" если была
        items.removeAll { it.pathStr == pathStr && it.fileName == fileName && !it.isBeforeClose }
        items.add(0, NavHistoryItem(pathStr, fileName, System.currentTimeMillis(), isBeforeClose, paneSide))
        trimAndSave()
    }

    fun recordBeforeClose(
        leftPath: Path?,
        leftFile: String?,
        rightPath: Path?,
        rightFile: String?
    ) {
        val lPath = leftPath?.toString() ?: lastLeftPath
        val lFile = leftFile ?: lastLeftFile
        val rPath = rightPath?.toString() ?: lastRightPath
        val rFile = rightFile ?: lastRightFile

        val now = System.currentTimeMillis()
        // Удаляем старые метки "перед закрытием", чтобы актуальными были только самые свежие
        items.removeAll { it.isBeforeClose }

        if (rPath != null) {
            items.removeAll { it.pathStr == rPath && it.fileName == rFile }
            items.add(0, NavHistoryItem(rPath, rFile, now, isBeforeClose = true, paneSide = "Правая"))
        }
        if (lPath != null) {
            items.removeAll { it.pathStr == lPath && it.fileName == lFile }
            items.add(0, NavHistoryItem(lPath, lFile, now, isBeforeClose = true, paneSide = "Левая"))
        }
        trimAndSave()
    }

    fun flushCurrentStateBeforeClose() {
        recordBeforeClose(
            leftPath = lastLeftPath?.let { try { Paths.get(it) } catch (_: Exception) { null } },
            leftFile = lastLeftFile,
            rightPath = lastRightPath?.let { try { Paths.get(it) } catch (_: Exception) { null } },
            rightFile = lastRightFile
        )
    }

    fun clear() {
        items.clear()
        save()
    }

    private fun trimAndSave() {
        while (items.size > 60) {
            items.removeAt(items.size - 1)
        }
        save()
    }

    private fun load() {
        try {
            if (!historyFile.exists()) return
            val lines = historyFile.readLines(Charsets.UTF_8)
            items.clear()
            for (line in lines) {
                if (line.isBlank()) continue
                val parts = line.split("\t")
                if (parts.size >= 4) {
                    val ts = parts[0].toLongOrNull() ?: System.currentTimeMillis()
                    val pathStr = unescapeField(parts[1])
                    val fileName = unescapeField(parts[2]).takeIf { it.isNotEmpty() && it != "null" }
                    val isClose = parts[3].toBoolean()
                    val side = parts.getOrNull(4)?.takeIf { it.isNotEmpty() } ?: "Левая"
                    items.add(NavHistoryItem(pathStr, fileName, ts, isClose, side))
                }
            }
        } catch (e: Exception) {
            ErrorLog.record("Не удалось прочитать историю переходов", e)
        }
    }

    // Табуляция и перевод строки в имени файла (в Linux так можно) не должны ломать строки истории
    internal fun escapeField(s: String): String {
        if (s.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '\t' -> sb.append("\\t")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    internal fun unescapeField(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    else -> { sb.append(c); i--; }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private val writeLock = Any()
    private var pending: List<NavHistoryItem>? = null
    private val writer by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "wex-history").apply { isDaemon = true }
        }
    }

    /**
     * Запись склеивается и уходит из потока окна: переходы по папкам идут часто,
     * а файл достаточно обновить раз в полсекунды.
     */
    fun save() {
        val snapshot = items.toList()
        synchronized(writeLock) {
            val schedule = pending == null
            pending = snapshot
            if (schedule) writer.schedule({ writePending() }, 400, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    /** Сразу, из текущего потока (выход из программы). */
    fun saveNow() {
        synchronized(writeLock) { pending = null }
        write(items.toList())
    }

    private fun writePending() {
        val list = synchronized(writeLock) { pending.also { pending = null } } ?: return
        write(list)
    }

    private fun write(list: List<NavHistoryItem>) {
        try {
            configDir.mkdirs()
            val text = list.joinToString("\n") {
                "${it.timestamp}\t${escapeField(it.pathStr)}\t${escapeField(it.fileName ?: "")}\t${it.isBeforeClose}\t${it.paneSide}"
            }
            AtomicFiles.write(historyFile) { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            ErrorLog.record("Не удалось сохранить историю переходов", e)
        }
    }

    fun formatRelativeTime(timestamp: Long): String {
        val diffSec = (System.currentTimeMillis() - timestamp) / 1000
        return when {
            diffSec < 60 -> "Только что"
            diffSec < 3600 -> "${diffSec / 60} мин назад"
            diffSec < 86400 -> "${diffSec / 3600} ч назад"
            else -> {
                val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
        }
    }
}
