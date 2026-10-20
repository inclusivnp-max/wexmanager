package org.wexclient.app.ui

import org.wexclient.app.vfs.VfsManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Подсказки для строки пути: пока набирают, показываются папки, которые подходят под начало имени.
 * Разбор отделён от списка файлов, чтобы его можно было проверить тестами.
 */
object PathSuggestions {

    /** На что делится введённый текст: папка, в которой ищем, и начало имени. */
    data class Split(val parent: String, val prefix: String)

    private const val MAX = 14

    /** Домашняя папка подставляется вместо «~». */
    fun expand(text: String, home: String = org.wexclient.app.util.AppDirs.userHome.toString()): String = when {
        text == "~" -> home
        text.startsWith("~/") -> home + text.substring(1)
        else -> text
    }

    /**
     * Делит ввод: «/home/za» → папка «/home», начало «za»; «/home/» → папка «/home», начало пусто.
     */
    fun split(text: String): Split {
        val at = text.lastIndexOf('/')
        return when {
            at < 0 -> Split("", text)
            at == 0 -> Split("/", text.substring(1))
            else -> Split(text.substring(0, at), text.substring(at + 1))
        }
    }

    /** Подходит ли имя под начало (без учёта регистра); скрытые показываем, только если их просят. */
    fun matches(name: String, prefix: String): Boolean {
        if (name.startsWith(".") && !prefix.startsWith(".")) return false
        return prefix.isEmpty() || name.startsWith(prefix, ignoreCase = true)
    }

    /**
     * Папки, подходящие под набранное. [base] — текущая папка панели: от неё считаются
     * относительные пути. Обращение к диску — вызывать из фонового потока.
     */
    fun forInput(text: String, base: Path, vfsManager: VfsManager?): List<Path> {
        val expanded = expand(text.trim())
        if (expanded.isEmpty()) return emptyList()
        val (parentText, prefix) = split(expanded)
        val parent = runCatching {
            when {
                parentText.isEmpty() -> base
                parentText == "/" -> base.fileSystem.getPath("/")
                parentText.startsWith("/") -> base.fileSystem.getPath(parentText)
                else -> base.resolve(parentText)
            }.normalize()
        }.getOrNull() ?: return emptyList()

        val entries = runCatching {
            if (vfsManager != null) {
                vfsManager.listEntries(parent).filter { it.isDirectory || it.isArchive }.map { it.path }
            } else {
                Files.list(parent).use { stream ->
                    stream.filter { Files.isDirectory(it) }.limit(4000).toList()
                }
            }
        }.getOrNull() ?: return emptyList()

        return entries
            .filter { matches(it.fileName?.toString().orEmpty(), prefix) }
            .sortedWith { a, b ->
                org.wexclient.app.util.NaturalOrder.compare(
                    a.fileName?.toString().orEmpty(),
                    b.fileName?.toString().orEmpty()
                )
            }
            .take(MAX)
    }

    /** Текст, который получится после выбора подсказки: путь с косой чертой на конце. */
    fun completion(path: Path): String {
        val text = path.toAbsolutePath().toString()
        return if (text.endsWith("/")) text else "$text/"
    }

    /** Для тестов: путь из строки без обращения к диску. */
    internal fun pathOf(text: String): Path = Paths.get(text)
}
