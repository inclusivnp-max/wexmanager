package org.wexclient.app.vfs

import org.wexclient.app.util.readUpTo

import org.wexclient.app.util.withUserEnv
import java.io.ByteArrayOutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.wexclient.app.util.startHelper

/** Состояние файла в git; для папок побеждает то, что раньше в списке. */
enum class GitMark(val letter: String, val title: String) {
    CONFLICT("!", "конфликт слияния"),
    MODIFIED("M", "изменён"),
    ADDED("A", "добавлен в индекс"),
    RENAMED("R", "переименован"),
    DELETED("D", "удалён"),
    UNTRACKED("U", "не отслеживается"),
    IGNORED("I", "игнорируется");

    fun strongest(other: GitMark?): GitMark = if (other == null || ordinal <= other.ordinal) this else other
}

/** `git status` папки: ветка и пометки файлов (пути — от корня репозитория, через «/»). */
class GitRepoStatus(
    val root: Path,
    /** null — HEAD отсоединён */
    val branch: String?,
    val commit: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    private val files: Map<String, GitMark>,
    /** Папка → самая важная пометка внутри неё */
    private val dirs: Map<String, GitMark>,
    private val untrackedDirs: Set<String>,
    private val ignoredDirs: Set<String>
) {
    /** Изменённых, новых и удалённых файлов (без игнорируемых). */
    val changedCount: Int = files.count { it.value != GitMark.IGNORED } + untrackedDirs.size

    private fun relative(path: Path): String? {
        val abs = path.toAbsolutePath().normalize()
        if (!abs.startsWith(root) || abs == root) return null
        return root.relativize(abs).joinToString("/")
    }

    // Внутри неотслеживаемой или игнорируемой папки git отдельные файлы не перечисляет
    private fun inside(rel: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var i = rel.lastIndexOf('/')
        while (i > 0) {
            if (rel.substring(0, i) in set) return true
            i = rel.lastIndexOf('/', i - 1)
        }
        return false
    }

    fun markFor(path: Path, isDirectory: Boolean): GitMark? {
        val rel = relative(path) ?: return null
        if (rel == ".git" || rel.startsWith(".git/")) return null
        if (isDirectory) {
            return when {
                rel in ignoredDirs || inside(rel, ignoredDirs) -> GitMark.IGNORED
                rel in untrackedDirs || inside(rel, untrackedDirs) -> GitMark.UNTRACKED
                else -> dirs[rel]
            }
        }
        return files[rel] ?: when {
            inside(rel, ignoredDirs) -> GitMark.IGNORED
            inside(rel, untrackedDirs) -> GitMark.UNTRACKED
            else -> null
        }
    }

    /** «main ↑2 ↓1» */
    val branchLabel: String
        get() = buildString {
            append(branch ?: commit?.let { "HEAD $it" } ?: "HEAD")
            if (ahead > 0) append(" ↑").append(ahead)
            if (behind > 0) append(" ↓").append(behind)
        }
}

object GitStatus {
    private const val MAX_OUTPUT = 32 shl 20

    // Не lazy: git, поставленный, пока процесс живёт в фоне, находится без перезапуска (отказ ProcessRunner помнит 30 с)
    private val gitAvailable: Boolean get() = org.wexclient.app.vfs.archive.ArchiveFormats.toolAvailable("git")

    /** Корень рабочей копии: ближайшая папка с `.git` (папкой или файлом — у подмодулей и worktree). */
    fun findRoot(dir: Path): Path? {
        if (dir.fileSystem !== FileSystems.getDefault()) return null
        var cur: Path? = dir.toAbsolutePath().normalize()
        while (cur != null) {
            // Внутри самой .git рабочей копии нет
            if (cur.fileName?.toString() == ".git") return null
            if (Files.exists(cur.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return cur
            cur = cur.parent
        }
        return null
    }

    private fun gitProcess(dir: Path, args: List<String>): ProcessBuilder {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).withUserEnv()
        pb.environment().apply {
            // Состояние папки, а не репозитория из окружения, в котором запущен WEX
            remove("GIT_DIR")
            remove("GIT_WORK_TREE")
            remove("GIT_INDEX_FILE")
            remove("GIT_CONFIG_PARAMETERS")
            remove("GIT_CONFIG_COUNT")
            put("GIT_TERMINAL_PROMPT", "0")
            put("GIT_OPTIONAL_LOCKS", "0")
        }
        pb.redirectError(org.wexclient.app.util.DiscardOutput)
        return pb
    }

    /**
     * Фильтры, объявленные конфигурацией самого репозитория (.git/config и то, что она подключает).
     * Их команды git status выполняет при пересчёте файлов — в чужом репозитории (распакованном
     * из архива) это был бы запуск чужого кода. Чтение конфигурации ничего не выполняет.
     */
    internal fun repositoryFilterDrivers(dir: Path): Set<String>? {
        val names = LinkedHashSet<String>()
        for (scope in listOf("--local", "--worktree")) {
            val pb = gitProcess(dir, listOf("config", scope, "--includes", "--name-only", "-z", "--get-regexp", "^filter\\."))
            val process = runCatching { pb.startHelper() }.getOrNull() ?: return null
            process.outputStream.close()
            // include.path может указывать на FIFO — git ждал бы его вечно, а с ним и чтение ниже
            val killed = java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog = Thread {
                try {
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        killed.set(true)
                        process.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                }
            }.apply {
                isDaemon = true
                start()
            }
            val out = try {
                process.inputStream.use { it.readUpTo(1 shl 20) }
            } catch (_: Exception) {
                process.destroyForcibly()
                return null
            } finally {
                watchdog.interrupt()
            }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (killed.get()) return null
            // Код 1 — таких ключей нет, у --worktree без расширения — ошибка: не страшно
            for (key in out.toString(Charsets.UTF_8).split('\u0000', '\n')) {
                if (!key.startsWith("filter.")) continue
                val name = key.removePrefix("filter.").substringBeforeLast('.', "")
                if (name.isEmpty()) continue
                // Такое имя не передать через -c однозначно — лучше не показывать статус вовсе
                if ('=' in name || name.any { it < ' ' }) return null
                names.add(name)
            }
        }
        return names
    }

    /** Состояние для папки [dir] и всего, что в ней; null — не репозиторий или git недоступен. */
    fun read(dir: Path, timeoutMs: Long = 5000): GitRepoStatus? {
        val root = findRoot(dir) ?: return null
        if (!gitAvailable || !Files.isDirectory(dir)) return null
        val filters = repositoryFilterDrivers(dir) ?: return null
        val args = mutableListOf(
            "--no-optional-locks", "-c", "core.quotepath=off",
            // Хук fsmonitor из .git/config — это команда; статус без него просто чуть медленнее
            "-c", "core.fsmonitor=false"
        )
        for (name in filters) {
            args += listOf("-c", "filter.$name.clean=", "-c", "filter.$name.smudge=", "-c", "filter.$name.process=")
        }
        // Изменения внутри подмодулей не проверяем: git запускал бы status в них, с их конфигурацией
        args += listOf(
            "status", "--porcelain=v2", "--branch", "-z", "--ignored=matching", "--untracked-files=normal",
            "--ignore-submodules=dirty", "--", "."
        )
        val pb = gitProcess(dir, args)
        val process = runCatching { pb.startHelper() }.getOrNull() ?: return null
        process.outputStream.close()
        // Огромный репозиторий не должен держать панель: по таймауту процесс убиваем
        val watchdog = Thread {
            try {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            } catch (_: InterruptedException) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        val out = ByteArrayOutputStream()
        var tooBig = false
        try {
            process.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_OUTPUT) {
                        tooBig = true
                        process.destroyForcibly()
                        break
                    }
                }
            }
        } catch (_: Exception) {
            process.destroyForcibly()
            return null
        } finally {
            watchdog.interrupt()
        }
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        if (tooBig || code != 0) return null
        return parse(root, out.toString("UTF-8"))
    }

    /** Разбор `git status --porcelain=v2 --branch -z`. */
    fun parse(root: Path, output: String): GitRepoStatus {
        var branch: String? = null
        var commit: String? = null
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        val files = HashMap<String, GitMark>()
        val dirs = HashMap<String, GitMark>()
        val untrackedDirs = HashSet<String>()
        val ignoredDirs = HashSet<String>()

        fun addToParents(rel: String, mark: GitMark) {
            var i = rel.lastIndexOf('/')
            while (i > 0) {
                val parent = rel.substring(0, i)
                val prev = dirs[parent]
                val next = mark.strongest(prev)
                if (prev == next) break // выше уже не слабее
                dirs[parent] = next
                i = rel.lastIndexOf('/', i - 1)
            }
        }

        fun put(rel: String, mark: GitMark) {
            if (rel.isEmpty()) return
            if (rel.endsWith("/")) {
                val dir = rel.trimEnd('/')
                if (mark == GitMark.IGNORED) ignoredDirs.add(dir) else untrackedDirs.add(dir)
                if (mark != GitMark.IGNORED) addToParents(dir, mark)
                return
            }
            files[rel] = mark.strongest(files[rel])
            if (mark != GitMark.IGNORED) addToParents(rel, mark)
        }

        fun changeMark(xy: String): GitMark {
            val x = xy.getOrElse(0) { '.' }
            val y = xy.getOrElse(1) { '.' }
            return when {
                x == 'D' || y == 'D' -> GitMark.DELETED
                x == 'A' -> GitMark.ADDED
                x == 'R' || x == 'C' || y == 'R' || y == 'C' -> GitMark.RENAMED
                else -> GitMark.MODIFIED
            }
        }

        val records = output.split(Char(0))
        var i = 0
        while (i < records.size) {
            val rec = records[i]
            i++
            if (rec.isEmpty()) continue
            when (rec[0]) {
                '#' -> {
                    val parts = rec.split(' ', limit = 3)
                    val value = parts.getOrNull(2).orEmpty()
                    when (parts.getOrNull(1)) {
                        "branch.oid" -> commit = value.takeIf { it != "(initial)" }?.take(8)
                        "branch.head" -> branch = value.takeIf { it != "(detached)" }
                        "branch.upstream" -> upstream = value
                        "branch.ab" -> value.split(' ').forEach { v ->
                            when {
                                v.startsWith("+") -> ahead = v.drop(1).toIntOrNull() ?: 0
                                v.startsWith("-") -> behind = v.drop(1).toIntOrNull() ?: 0
                            }
                        }
                    }
                }
                '1' -> rec.split(' ', limit = 9).let { f -> if (f.size == 9) put(f[8], changeMark(f[1])) }
                '2' -> {
                    rec.split(' ', limit = 10).let { f -> if (f.size == 10) put(f[9], GitMark.RENAMED.strongest(changeMark(f[1]))) }
                    // Следом — прежнее имя
                    i++
                }
                'u' -> rec.split(' ', limit = 11).let { f -> if (f.size == 11) put(f[10], GitMark.CONFLICT) }
                '?' -> put(rec.substring(2), GitMark.UNTRACKED)
                '!' -> put(rec.substring(2), GitMark.IGNORED)
            }
        }
        return GitRepoStatus(
            root = root.toAbsolutePath().normalize(),
            branch = branch,
            commit = commit,
            upstream = upstream,
            ahead = ahead,
            behind = behind,
            files = files,
            dirs = dirs,
            untrackedDirs = untrackedDirs,
            ignoredDirs = ignoredDirs
        )
    }
}
