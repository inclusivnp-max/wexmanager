package org.wexclient.app.commands

import androidx.compose.runtime.mutableStateListOf
import org.wexclient.app.history.NavigationHistoryManager
import org.wexclient.app.util.AtomicFiles
import org.wexclient.app.util.ErrorLog
import java.io.File
import java.nio.file.Path

/**
 * Своя команда в меню файла. Шаблон — обычная строка оболочки с подстановками:
 * `%f` файл, `%F` все выделенные, `%n` имя, `%b` имя без расширения,
 * `%d` папка файла, `%D` папка соседней панели, `%%` — сам знак процента.
 */
data class UserCommand(
    val title: String,
    val template: String,
    /** Для каких файлов: расширения через пробел («apk xapk»), «/» — только папки, пусто — для всего. */
    val filter: String = "",
    /** В консоли панели (видно вывод) или в фоне с сообщением по завершении. */
    val inTerminal: Boolean = true
) {
    /** Подходит ли команда для выделенного. */
    fun accepts(paths: List<Path>, isDirectory: (Path) -> Boolean): Boolean {
        if (paths.isEmpty()) return false
        val rule = filter.trim().lowercase()
        if (rule.isEmpty()) return true
        val extensions = rule.split(' ', ',').map { it.trim().removePrefix(".") }.filter { it.isNotEmpty() }
        return paths.all { path ->
            val dir = isDirectory(path)
            when {
                "/" in extensions && dir -> true
                dir -> false
                else -> path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() in extensions
            }
        }
    }
}

/** Список своих команд (~/.config/wexdesktop/commands.tsv). */
object UserCommands {
    private val file: File get() = org.wexclient.app.util.AppDirs.config.resolve("commands.tsv").toFile()

    val commands = mutableStateListOf<UserCommand>()

    init {
        load()
    }

    fun add(command: UserCommand) {
        commands += command
        save()
    }

    fun update(index: Int, command: UserCommand) {
        if (index !in commands.indices) return
        commands[index] = command
        save()
    }

    fun remove(index: Int) {
        if (index !in commands.indices) return
        commands.removeAt(index)
        save()
    }

    fun move(from: Int, to: Int) {
        if (from !in commands.indices || to !in commands.indices || from == to) return
        val item = commands.removeAt(from)
        commands.add(to, item)
        save()
    }

    /**
     * Шаблон с подставленными путями. Каждый путь берётся в одинарные кавычки —
     * пробелы и спецсимволы в именах не разорвут команду и не выполнятся сами.
     */
    fun expand(template: String, targets: List<Path>, otherDir: Path?): String {
        val first = targets.firstOrNull()
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch != '%' || i + 1 >= template.length) {
                sb.append(ch)
                i++
                continue
            }
            when (template[i + 1]) {
                'f' -> sb.append(quote(first?.toAbsolutePath()?.toString().orEmpty()))
                'F' -> sb.append(targets.joinToString(" ") { quote(it.toAbsolutePath().toString()) })
                'n' -> sb.append(quote(first?.fileName?.toString().orEmpty()))
                'b' -> sb.append(quote(first?.fileName?.toString()?.substringBeforeLast('.').orEmpty()))
                'd' -> sb.append(quote(first?.toAbsolutePath()?.parent?.toString().orEmpty()))
                'D' -> sb.append(quote(otherDir?.toAbsolutePath()?.toString().orEmpty()))
                '%' -> sb.append('%')
                else -> sb.append(ch).append(template[i + 1])
            }
            i += 2
        }
        return sb.toString()
    }

    /**
     * Слово для оболочки: команда идёт и в sh -c, и в консоль, где оболочкой бывает fish. Внутри
     * одинарных кавычек fish понимает \\ и \' — имя с «\» вышло бы из кавычек, поэтому экранирование
     * общее с консолью: одинаково работает в sh/bash/zsh и fish.
     */
    fun quote(text: String): String = org.wexclient.app.terminal.ScriptRunner.quote(text)

    private fun load() {
        commands.clear()
        val source = file
        if (!source.isFile) {
            commands += defaults()
            return
        }
        runCatching {
            source.readLines().forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val parts = line.split('\t')
                if (parts.size < 2) return@forEach
                commands += UserCommand(
                    title = NavigationHistoryManager.unescapeField(parts[0]),
                    template = NavigationHistoryManager.unescapeField(parts[1]),
                    filter = parts.getOrNull(2)?.let { NavigationHistoryManager.unescapeField(it) }.orEmpty(),
                    inTerminal = parts.getOrNull(3) != "background"
                )
            }
        }.onFailure { ErrorLog.record("Не удалось прочитать пользовательские команды", it) }
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val esc = NavigationHistoryManager::escapeField
            val text = buildString {
                appendLine("# Свои команды WEXdesktop: название, шаблон, фильтр, где запускать")
                for (c in commands) {
                    append(esc(c.title)).append('\t')
                    append(esc(c.template)).append('\t')
                    append(esc(c.filter)).append('\t')
                    appendLine(if (c.inTerminal) "terminal" else "background")
                }
            }
            AtomicFiles.write(file) { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.onFailure { ErrorLog.record("Не удалось сохранить пользовательские команды", it) }
    }

    /** Несколько полезных примеров на первый запуск — их легко поправить или убрать. */
    private fun defaults(): List<UserCommand> = listOf(
        UserCommand("Размер на диске", "du -sh %F", inTerminal = true),
        UserCommand("Контрольная сумма SHA-256", "sha256sum %F", filter = "", inTerminal = true),
        UserCommand("Сделать исполняемым", "chmod +x %F", filter = "sh run appimage py", inTerminal = false)
    )
}
