package org.wexclient.app.editor

import java.io.File
import java.nio.file.Path

/**
 * Где стояла каретка в файле, когда его закрыли: при следующем открытии редактор вернётся туда.
 * Строка и колонка, а не смещение, — правка файла снаружи сдвигает место меньше. Помним последние
 * [MAX] файлов, по одному на строку: «строка:колонка<TAB>путь».
 */
object EditorPositions {
    private const val MAX = 300

    private val file = org.wexclient.app.util.AppDirs.config.resolve("editor-positions.tsv").toFile()

    /** Путь -> (строка, колонка) с 0; порядок — от давнего к недавнему. */
    private val positions: LinkedHashMap<String, Pair<Int, Int>> by lazy { load() }

    private fun load(): LinkedHashMap<String, Pair<Int, Int>> {
        val out = LinkedHashMap<String, Pair<Int, Int>>()
        runCatching {
            if (!file.isFile) return@runCatching
            file.forEachLine(Charsets.UTF_8) { row ->
                val tab = row.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val (line, column) = row.substring(0, tab).split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return@forEachLine
                out[row.substring(tab + 1)] = line to column
            }
        }
        return out
    }

    @Synchronized
    fun get(path: Path): TextTarget? = positions[key(path)]?.let { (line, column) -> TextTarget(line + 1, column + 1) }

    /** Запомнить место каретки; начало файла не храним — туда редактор и так открывается. */
    @Synchronized
    fun put(path: Path, line: Int, column: Int) {
        val k = key(path)
        val old = positions.remove(k)
        if (line == 0 && column == 0) {
            if (old != null) save()
            return
        }
        positions[k] = line to column
        while (positions.size > MAX) positions.remove(positions.keys.first())
        save()
    }

    private fun key(path: Path): String = path.toAbsolutePath().normalize().toString()

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val body = buildString {
                for ((path, pos) in positions) {
                    if (path.contains('\n')) continue
                    append(pos.first).append(':').append(pos.second).append('\t').append(path).append('\n')
                }
            }
            org.wexclient.app.util.AtomicFiles.write(file) { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
    }
}
