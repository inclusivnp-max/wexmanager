package org.wexclient.app.vfs

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * Недавно прочитанные папки на локальном диске, общие для обеих панелей. «Назад», «Вверх» и повторный
 * заход показывают список сразу, без stat каждого файла (в папке на 50 000 файлов это полсекунды
 * с индикатором); пересортировка и фильтр берут его отсюда и не читают диск вовсе.
 *
 * Запись годна, пока у папки тот же [Stamp]: добавить, удалить или переименовать файл, не сдвинув время
 * изменения, нельзя; chmod сдвигает ctime; удалить папку и создать заново — сменить inode. Размер и дату
 * файла, переписанного на месте, это не видит — панель после показа из кэша тихо сверяет список с диском.
 */
object ListingCache {
    /** Отпечаток папки: время изменения (полная точность), ctime (где есть) и сама папка (устройство и inode). */
    data class Stamp(val modified: FileTime, val changed: Any?, val key: Any?)

    /** Сырой список (порядок — как отдал диск) и отпечаток папки перед чтением. */
    class Raw(val entries: List<VfsEntry>, val stamp: Stamp, val storedAt: Long)

    private const val MAX_DIRS = 16

    /** VfsEntry — около 400 байт: 100 000 записей — порядка 40 МБ в худшем случае. */
    private const val MAX_ENTRIES = 100_000

    /** Давно не нужные папки память не держат. */
    private const val TTL_MS = 10 * 60_000L

    private val map = LinkedHashMap<Path, Raw>(MAX_DIRS * 2, 0.75f, true)
    private var total = 0

    fun supports(dir: Path): Boolean = dir.fileSystem === FileSystems.getDefault()

    /** Отпечаток папки одним обращением к диску; null — не вышло (такую запись не сохраняем и не берём). */
    fun stampOf(dir: Path): Stamp? = runCatching {
        val a = Files.readAttributes(dir, BasicFileAttributes::class.java)
        // ctime — только у unix: chmod и смена владельца двигают его, а время изменения — нет
        val ctime = runCatching { Files.getAttribute(dir, "unix:ctime") }.getOrNull()
        Stamp(a.lastModifiedTime(), ctime, a.fileKey())
    }.getOrNull()

    fun put(dir: Path, entries: List<VfsEntry>, stamp: Stamp?) {
        if (!supports(dir)) return
        // Сохранить нельзя — прежняя запись тоже больше не правда (папка выросла за предел и т. п.)
        if (stamp == null || entries.size > MAX_ENTRIES) {
            invalidate(dir)
            return
        }
        synchronized(map) {
            val old = map[dir]
            // Два чтения одной папки закончились не по порядку — более старое новое не затирает
            if (old != null && old.stamp.modified > stamp.modified) return
            if (old != null) total -= old.entries.size
            map[dir] = Raw(entries, stamp, System.currentTimeMillis())
            total += entries.size
            trimLocked()
        }
    }

    /** Последний прочитанный список папки — без проверки диска (для пересортировки того, что на экране). */
    fun last(dir: Path): Raw? = synchronized(map) { liveLocked(dir) }

    /** Список, если папка с тех пор не менялась (один stat самой папки). Звать не из потока окна. */
    fun fresh(dir: Path): Raw? {
        val raw = last(dir) ?: return null
        if (stampOf(dir) == raw.stamp) return raw
        // Устарела — убираем сразу: иначе её подобрала бы пересортировка
        invalidate(dir)
        return null
    }

    fun invalidate(dir: Path) {
        synchronized(map) { map.remove(dir)?.let { total -= it.entries.size } }
    }

    fun clear() {
        synchronized(map) {
            map.clear()
            total = 0
        }
    }

    internal fun size(): Int = synchronized(map) { map.size }

    private fun liveLocked(dir: Path): Raw? {
        val raw = map[dir] ?: return null
        if (System.currentTimeMillis() - raw.storedAt > TTL_MS) {
            map.remove(dir)
            total -= raw.entries.size
            return null
        }
        return raw
    }

    private fun trimLocked() {
        val it = map.entries.iterator()
        val now = System.currentTimeMillis()
        while (it.hasNext() && (map.size > MAX_DIRS || total > MAX_ENTRIES)) {
            val e = it.next()
            total -= e.value.entries.size
            it.remove()
        }
        // Заодно — просроченные
        val stale = map.entries.iterator()
        while (stale.hasNext()) {
            val e = stale.next()
            if (now - e.value.storedAt > TTL_MS) {
                total -= e.value.entries.size
                stale.remove()
            }
        }
    }
}
