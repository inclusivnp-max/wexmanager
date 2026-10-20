package org.wexclient.app.vfs

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Что делать, если в папке назначения уже есть файл с таким именем. */
enum class ConflictPolicy { REPLACE, SKIP, KEEP_BOTH }

/** Итог копирования или переноса: откуда → куда, и что было заменено (лежит в корзине). */
class TransferResult(
    val done: List<Pair<Path, Path>>,
    val replaced: List<TrashItem>,
    val skipped: Int,
    /** Остановились на ошибке — сделанное до неё всё равно в [done] */
    val error: Throwable? = null,
    /** Заменённое внутри архивов — копии на диске для отмены */
    val archiveReplaced: List<ArchiveBackup> = emptyList()
)

/** Копирование и перенос в папку: дубли в той же папке, конфликты имён, замена через корзину. */
object FileTransfers {
    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    private fun nameOf(p: Path) = p.fileName?.toString()?.trimEnd('/') ?: "файл"

    private fun sameDir(src: Path, targetDir: Path): Boolean =
        src.fileSystem === targetDir.fileSystem &&
            runCatching { src.toAbsolutePath().normalize().parent == targetDir.toAbsolutePath().normalize() }.getOrDefault(false)

    /** «отчёт.pdf» → «отчёт (копия).pdf», «отчёт (копия 2).pdf», … */
    fun copyName(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return if (n <= 1) "$stem (копия)$ext" else "$stem (копия $n)$ext"
    }

    /** Свободное имя в папке: «имя», «имя (2)», … */
    fun freeName(dir: Path, name: String): Path {
        var candidate = dir.resolve(name)
        var n = 2
        while (Files.exists(candidate, NOFOLLOW)) {
            candidate = dir.resolve(Trash.numbered(name, n))
            n++
        }
        return candidate
    }

    fun freeCopyTarget(dir: Path, name: String): Path {
        var n = 1
        while (true) {
            val candidate = dir.resolve(copyName(name, n))
            if (!Files.exists(candidate, NOFOLLOW)) return candidate
            n++
        }
    }

    /** Источники, чьё имя уже занято в [targetDir] (дубли в той же папке — не конфликт). */
    fun conflicts(sources: List<Path>, targetDir: Path): List<Path> {
        // На сервере и телефоне — один список папки вместо запроса на каждое имя
        val remoteNames: Set<String>? = if (targetDir.fileSystem is org.wexclient.app.vfs.remote.RemoteFileSystem) {
            runCatching { Files.list(targetDir).use { s -> s.map { it.fileName.toString() }.toList().toHashSet() } }.getOrNull()
        } else null
        return sources.filter { src ->
            !sameDir(src, targetDir) &&
                (remoteNames?.contains(nameOf(src)) ?: Files.exists(targetDir.resolve(nameOf(src)), NOFOLLOW))
        }
    }

    fun transfer(
        vfs: VfsManager,
        sourcesIn: List<Path>,
        targetDirIn: Path,
        move: Boolean,
        policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH
    ): TransferResult {
        // Вся операция — под блокировкой записи в архивы; пути — уже после ожидания
        return vfs.exclusive(targetDirIn, *sourcesIn.toTypedArray()) {
            // Пути из буфера обмена могли остаться от закрытой (сохранённой) копии архива
            val sources = sourcesIn.map { vfs.live(it) }
            val targetDir = vfs.live(targetDirIn)
            // Проверка до первого действия: иначе часть файлов успела бы уехать
            for (src in sources) {
                vfs.transferProblem(src, targetDir, move)?.let {
                    return TransferResult(emptyList(), emptyList(), 0, IllegalArgumentException(it))
                }
            }
            val done = ArrayList<Pair<Path, Path>>()
            val replaced = ArrayList<TrashItem>()
            val archiveReplaced = ArrayList<ArchiveBackup>()
            var skipped = 0
            var error: Throwable? = null
            // Перенос между диском и архивом: сначала копии и запись архива, источники — только после неё.
            // Иначе при ошибке записи архива (нет места, архив переименовали) файлы пропадали совсем.
            val deferred = ArrayList<Path>()
            var copied = false
            var blockDone = false
            try {
                vfs.batch {
                    transferAll(vfs, sources, targetDir, move, policy, done, replaced, archiveReplaced, deferred) { skipped++ }
                    blockDone = true
                }
                copied = true
            } catch (e: Exception) {
                error = e
                // Упало на одном из файлов, а сама запись архива прошла — скопированное можно довести до конца
                copied = !blockDone && e.suppressed.isEmpty()
            }
            if (deferred.isNotEmpty()) {
                if (copied) {
                    try {
                        vfs.batch { deferred.forEach { vfs.deleteEntry(it) } }
                    } catch (e: Exception) {
                        if (error == null) error = java.io.IOException("Скопировано, но источники не удалены: ${e.message ?: e.javaClass.simpleName}", e)
                    }
                } else {
                    // Ничего не удалено: эти файлы не перемещены
                    val kept = deferred.toHashSet()
                    done.removeAll { it.first in kept }
                }
            }
            return TransferResult(done, replaced, skipped, error, archiveReplaced)
    
        }
    }

    private fun transferAll(
        vfs: VfsManager,
        sources: List<Path>,
        targetDir: Path,
        move: Boolean,
        policy: ConflictPolicy,
        done: MutableList<Pair<Path, Path>>,
        replaced: MutableList<TrashItem>,
        archiveReplaced: MutableList<ArchiveBackup>,
        deferredDeletes: MutableList<Path>,
        skip: () -> Unit
    ) {
        for (src in sources) {
            val name = nameOf(src)
            var target = targetDir.resolve(name)
            if (sameDir(src, targetDir)) {
                // Вырезали и вставили туда же — ничего не делаем; копия — рядом с новым именем
                if (move) {
                    skip()
                    continue
                }
                target = freeCopyTarget(targetDir, name)
            } else if (Files.exists(target, NOFOLLOW)) {
                when (policy) {
                    ConflictPolicy.SKIP -> {
                        skip()
                        continue
                    }
                    ConflictPolicy.KEEP_BOTH -> target = freeCopyTarget(targetDir, name)
                    ConflictPolicy.REPLACE -> {
                        // Источника уже нет (вырезанное вставили раньше) — цель не трогаем
                        if (!Files.exists(src, NOFOLLOW)) throw java.nio.file.NoSuchFileException(src.toString(), null, "источника уже нет")
                        // Тот же самый файл — заменять нечем
                        if (src.fileSystem === target.fileSystem && runCatching { Files.isSameFile(src, target) }.getOrDefault(false)) {
                            skip()
                            continue
                        }
                        // Папку нельзя заменить тем, что лежит внутри неё: источник уехал бы в корзину вместе с ней
                        if (src.fileSystem === target.fileSystem &&
                            src.toAbsolutePath().normalize().startsWith(target.toAbsolutePath().normalize())
                        ) throw IllegalArgumentException("«$name» нельзя заменить: копируемое лежит внутри неё")
                        // И наоборот — цель внутри источника (папка в собственную подпапку): отказ до того,
                        // как заменяемое уйдёт в корзину, а не после
                        if (src.fileSystem === target.fileSystem &&
                            target.toAbsolutePath().normalize().startsWith(src.toAbsolutePath().normalize())
                        ) throw IllegalArgumentException("Нельзя ${if (move) "переместить" else "скопировать"} папку «$name» внутрь неё самой")
                        if (target.fileSystem === FileSystems.getDefault()) {
                            // Заменяемое — в корзину, чтобы замену можно было отменить
                            replaced.add(Trash.trash(target))
                        } else {
                            // В архиве корзины нет: сначала новое под временным именем, старое удаляем после
                            val acrossArchive = move && src.fileSystem !== target.fileSystem
                            val temp = targetDir.resolve(".wex-new-${System.nanoTime()}-$name")
                            val placed = if (move && !acrossArchive) vfs.moveTo(src, temp) else vfs.copyTo(src, temp)
                            try {
                                // Заменяемое — копией на диск: иначе Ctrl+Z не вернул бы его в архив
                                archiveReplaced.add(UndoBackups.save(vfs, target))
                                vfs.deleteEntry(target)
                            } catch (e: Exception) {
                                // Старое осталось на месте — новое убираем: перенесённое возвращаем, копию удаляем
                                runCatching { if (move && !acrossArchive) vfs.moveTo(placed, src) else vfs.deleteEntry(placed) }
                                throw e
                            }
                            done.add(src to vfs.moveTo(placed, target))
                            // Источник — в очередь на удаление, только когда новое уже стоит на месте
                            if (acrossArchive) deferredDeletes.add(src)
                            continue
                        }
                    }
                }
            }
            val acrossArchive = move && src.fileSystem !== target.fileSystem &&
                (src.fileSystem !== FileSystems.getDefault() || target.fileSystem !== FileSystems.getDefault())
            val result = if (move && !acrossArchive) vfs.moveTo(src, target) else vfs.copyTo(src, target)
            if (acrossArchive) deferredDeletes.add(src)
            done.add(src to result)
        }
    }
}
