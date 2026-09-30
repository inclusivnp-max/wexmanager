package org.wexclient.app.db

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Файл не похож на базу SQLite или повреждён так, что читать нечего. */
class SqliteFormatException(message: String) : IOException(message)

/** Значение ячейки. */
sealed class SqlValue {
    data object Null : SqlValue()
    class Integer(val value: Long) : SqlValue()
    class Real(val value: Double) : SqlValue()
    class Text(val value: String) : SqlValue()
    class Blob(val bytes: ByteArray) : SqlValue()
}

/** Строка таблицы; [rowid] есть только у обычных таблиц (не WITHOUT ROWID). */
class SqlRow(val rowid: Long?, val values: List<SqlValue>)

/**
 * Чтение файлов SQLite без посторонних библиотек: заголовок, страницы, b-деревья, записи,
 * страницы переполнения и журнал WAL. Только чтение — база пользователя никогда не меняется.
 *
 * Свой разбор вместо готового драйвера: так открываются и базы внутри архивов и APK
 * (драйверу нужен настоящий файл на диске), и не нужны нативные библиотеки.
 */
class SqliteDb private constructor(
    private val data: Bytes,
    private val wal: Wal?,
    val pageSize: Int,
    val reserved: Int,
    /** Страниц по заголовку (или по размеру файла, если счётчик изменений устарел). */
    val pageCount: Int,
    val encoding: Charset,
    val encodingName: String,
    val userVersion: Int,
    val applicationId: Int,
    val freelistPages: Int,
    val sqliteVersion: Int,
    val journalMode: String,
    val fileSize: Long
) : AutoCloseable {

    /** Место, откуда читаются байты: файл на диске или содержимое в памяти (запись из архива). */
    internal interface Bytes : AutoCloseable {
        val size: Long
        /** Копирует не больше [len] байт с [offset]; возвращает, сколько получилось. */
        fun read(offset: Long, dest: ByteArray, len: Int): Int
    }

    internal class FileBytes(private val channel: FileChannel) : Bytes {
        override val size: Long = channel.size()
        override fun read(offset: Long, dest: ByteArray, len: Int): Int {
            if (offset < 0) return 0
            val buffer = ByteBuffer.wrap(dest, 0, len)
            var position = offset
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, position)
                if (n <= 0) break
                position += n
            }
            return len - buffer.remaining()
        }

        override fun close() = channel.close()
    }

    internal class ArrayBytes(private val bytes: ByteArray) : Bytes {
        override val size: Long = bytes.size.toLong()
        override fun read(offset: Long, dest: ByteArray, len: Int): Int {
            if (offset < 0 || offset >= bytes.size) return 0
            val n = minOf(len.toLong(), bytes.size - offset).toInt()
            System.arraycopy(bytes, offset.toInt(), dest, 0, n)
            return n
        }

        override fun close() = Unit
    }

    /** Журнал WAL: страница → смещение её последнего зафиксированного кадра. */
    internal class Wal(val data: Bytes, val frames: Map<Int, Long>, val dbSize: Int) : AutoCloseable {
        override fun close() = data.close()
    }

    /** Полезный размер страницы: без зарезервированного хвоста. */
    val usable: Int = pageSize - reserved

    /** Кадров журнала WAL, которые видит просмотрщик (0 — журнала нет). */
    val walFrames: Int = wal?.frames?.size ?: 0

    private val cache = object : LinkedHashMap<Int, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean = size > PAGE_CACHE
    }

    /** Всё из sqlite_master: таблицы, представления, индексы, триггеры. */
    val objects: List<SqlObject> by lazy { readSchema() }

    val tables: List<SqlObject> get() = objects.filter { it.kind == SqlObjectKind.TABLE }

    /** Проблемы, замеченные при чтении схемы (файл повреждён, но что-то прочиталось). */
    var schemaProblem: String? = null
        private set

    override fun close() {
        data.close()
        wal?.close()
        synchronized(cache) { cache.clear() }
    }

    // ---- страницы ---------------------------------------------------------

    /** Страница [number] (нумерация с единицы), из журнала WAL, если она там есть. */
    fun readPage(number: Int): ByteArray {
        if (number < 1 || number > MAX_PAGE) throw SqliteFormatException("Номер страницы вне диапазона: $number")
        synchronized(cache) { cache[number] }?.let { return it }
        val page = ByteArray(pageSize)
        val walOffset = wal?.frames?.get(number)
        val read = if (walOffset != null) {
            wal.data.read(walOffset, page, pageSize)
        } else {
            data.read((number - 1).toLong() * pageSize, page, pageSize)
        }
        if (read < pageSize) throw SqliteFormatException("Страница $number обрывается: файл короче, чем заявлено")
        synchronized(cache) { cache[number] = page }
        return page
    }

    /** Разобранный заголовок страницы b-дерева. */
    internal class PageInfo(
        val number: Int,
        val type: Int,
        val cellCount: Int,
        val rightMost: Int,
        val headerOffset: Int,
        val bytes: ByteArray
    ) {
        val leaf: Boolean get() = type == LEAF_TABLE || type == LEAF_INDEX
        val index: Boolean get() = type == LEAF_INDEX || type == INTERIOR_INDEX
        val cellArray: Int get() = headerOffset + if (leaf) 8 else 12
    }

    internal fun readBtreePage(number: Int): PageInfo {
        val bytes = readPage(number)
        // На первой странице перед деревом лежит стобайтовый заголовок файла
        val headerOffset = if (number == 1) 100 else 0
        val type = u8(bytes, headerOffset)
        if (type != LEAF_TABLE && type != INTERIOR_TABLE && type != LEAF_INDEX && type != INTERIOR_INDEX) {
            throw SqliteFormatException("Страница $number: не страница дерева (тип $type)")
        }
        val cellCount = u16(bytes, headerOffset + 3)
        val leaf = type == LEAF_TABLE || type == LEAF_INDEX
        val rightMost = if (leaf) 0 else i32(bytes, headerOffset + 8)
        val arrayEnd = headerOffset + (if (leaf) 8 else 12) + cellCount * 2
        if (arrayEnd > usable) throw SqliteFormatException("Страница $number: ячеек больше, чем помещается ($cellCount)")
        return PageInfo(number, type, cellCount, rightMost, headerOffset, bytes)
    }

    internal fun cellOffset(page: PageInfo, i: Int): Int {
        val offset = u16(page.bytes, page.cellArray + i * 2)
        if (offset < page.headerOffset + 8 || offset >= usable) {
            throw SqliteFormatException("Страница ${page.number}: ячейка $i указывает за пределы страницы")
        }
        return offset
    }

    // ---- записи -----------------------------------------------------------

    /**
     * Полезная нагрузка ячейки: местная часть плюс, если надо, цепочка страниц переполнения.
     * Пороги — из спецификации формата (таблицы и индексы считают по-разному).
     */
    private fun payload(page: ByteArray, offset: Int, total: Long, tableLeaf: Boolean): ByteArray {
        if (total < 0 || total > MAX_PAYLOAD) throw SqliteFormatException("Неправдоподобный размер записи: $total")
        val size = total.toInt()
        val maxLocal = if (tableLeaf) usable - 35 else ((usable - 12) * 64 / 255) - 23
        if (size <= maxLocal) {
            if (offset + size > usable) throw SqliteFormatException("Запись выходит за край страницы")
            return page.copyOfRange(offset, offset + size)
        }
        val minLocal = ((usable - 12) * 32 / 255) - 23
        val k = minLocal + ((size - minLocal) % (usable - 4))
        val local = if (k <= maxLocal) k else minLocal
        if (offset + local + 4 > usable) throw SqliteFormatException("Запись выходит за край страницы")
        val out = ByteArray(size)
        System.arraycopy(page, offset, out, 0, local)
        var written = local
        var next = i32(page, offset + local)
        var hops = 0
        while (next != 0 && written < size) {
            if (++hops > MAX_OVERFLOW) throw SqliteFormatException("Слишком длинная цепочка страниц переполнения")
            val overflow = readPage(next)
            val chunk = minOf(usable - 4, size - written)
            System.arraycopy(overflow, 4, out, written, chunk)
            written += chunk
            next = i32(overflow, 0)
        }
        if (written < size) throw SqliteFormatException("Оборванная цепочка страниц переполнения")
        return out
    }

    /** Разбор записи: заголовок с кодами типов, затем сами значения. */
    internal fun parseRecord(record: ByteArray): List<SqlValue> {
        val headerSize = varint(record, 0, record.size)
        val header = headerSize.value
        if (header < 1 || header > record.size) throw SqliteFormatException("Битый заголовок записи")
        val types = ArrayList<Long>(8)
        var p = headerSize.size
        while (p < header) {
            val type = varint(record, p, header.toInt())
            types.add(type.value)
            p += type.size
        }
        var body = header.toInt()
        val values = ArrayList<SqlValue>(types.size)
        for (type in types) {
            val (value, used) = readValue(type, record, body)
            values.add(value)
            body += used
        }
        return values
    }

    private fun readValue(type: Long, record: ByteArray, at: Int): Pair<SqlValue, Int> {
        // Размер считается в Long: тип — varint до 2^64, и на испорченном файле toInt() давал отрицательный
        // размер, проверка края проходила, а copyOfRange/String падали непредусмотренным исключением
        fun need(n: Long): Int {
            if (n < 0 || at + n > record.size) throw SqliteFormatException("Значение обрывается на краю записи")
            return n.toInt()
        }
        return when {
            type == 0L -> SqlValue.Null to 0
            type in 1L..6L -> {
                val size = when (type.toInt()) { 5 -> 6; 6 -> 8; else -> type.toInt() }
                need(size.toLong())
                var v = record[at].toLong() // старший байт со знаком
                for (i in 1 until size) v = (v shl 8) or (record[at + i].toLong() and 0xFF)
                SqlValue.Integer(v) to size
            }
            type == 7L -> {
                need(8)
                var bits = 0L
                for (i in 0 until 8) bits = (bits shl 8) or (record[at + i].toLong() and 0xFF)
                SqlValue.Real(Double.fromBits(bits)) to 8
            }
            type == 8L -> SqlValue.Integer(0) to 0
            type == 9L -> SqlValue.Integer(1) to 0
            // 10 и 11 — служебные, в обычных записях не встречаются
            type < 12L -> SqlValue.Null to 0
            type % 2 == 0L -> {
                val size = need((type - 12) / 2)
                SqlValue.Blob(record.copyOfRange(at, at + size)) to size
            }
            else -> {
                val size = need((type - 13) / 2)
                SqlValue.Text(String(record, at, size, encoding)) to size
            }
        }
    }

    // ---- обход таблиц -----------------------------------------------------

    /**
     * Последовательный обход таблицы: строки отдаются порциями, состояние обхода сохраняется,
     * поэтому «показать ещё» не перечитывает уже прочитанное.
     */
    private class Frame(val page: PageInfo) {
        var index = 0
        var pendingKey = -1
    }

    inner class RowCursor internal constructor(private val obj: SqlObject) {
        private val stack = ArrayList<Frame>()
        private var visited = 0
        private val aliasColumn = obj.columns.indexOfFirst { it.rowidAlias }

        /** Все строки прочитаны. */
        var finished = false
            private set

        /** Что не так с файлом, если по дороге встретилось повреждение. */
        var problem: String? = null
            private set

        /** Строк пропущено из-за повреждений. */
        var skipped = 0
            private set

        init {
            if (obj.rootPage <= 0) finished = true else push(obj.rootPage)
        }

        private fun push(number: Int) {
            if (stack.size >= MAX_DEPTH) {
                fail("Дерево страниц слишком глубокое")
                return
            }
            if (++visited > maxOf(pageCount, 64) * 2 + 1024) {
                fail("Похоже на петлю в дереве страниц")
                return
            }
            try {
                stack.add(Frame(readBtreePage(number)))
            } catch (e: SqliteFormatException) {
                note(e)
            }
        }

        private fun fail(message: String) {
            problem = problem ?: message
            stack.clear()
            finished = true
        }

        private fun note(e: Exception) {
            skipped++
            if (problem == null) problem = e.message ?: "Файл повреждён"
        }

        /** Следующие не больше [max] строк. */
        fun next(max: Int): List<SqlRow> {
            val out = ArrayList<SqlRow>(minOf(max, 4096))
            while (out.size < max && stack.isNotEmpty()) {
                val frame = stack[stack.size - 1]
                val page = frame.page
                if (page.leaf) {
                    if (frame.index >= page.cellCount) {
                        stack.removeAt(stack.size - 1)
                        continue
                    }
                    val i = frame.index++
                    readRow(page, i)?.let { out.add(it) }
                    continue
                }
                if (frame.pendingKey >= 0) {
                    val i = frame.pendingKey
                    frame.pendingKey = -1
                    // У индексного дерева запись лежит и во внутренних страницах
                    readRow(page, i)?.let { out.add(it) }
                    continue
                }
                when {
                    frame.index < page.cellCount -> {
                        val i = frame.index++
                        val child = try {
                            i32(page.bytes, cellOffset(page, i))
                        } catch (e: SqliteFormatException) {
                            note(e)
                            0
                        }
                        if (page.index) frame.pendingKey = i
                        if (child > 0) push(child)
                    }
                    frame.index == page.cellCount -> {
                        frame.index++
                        if (page.rightMost > 0) push(page.rightMost)
                    }
                    else -> stack.removeAt(stack.size - 1)
                }
            }
            if (stack.isEmpty()) finished = true
            return out
        }

        private fun readRow(page: PageInfo, i: Int): SqlRow? = try {
            val start = cellOffset(page, i)
            var p = start
            var rowid: Long? = null
            val size: Long
            when (page.type) {
                LEAF_TABLE -> {
                    val length = varint(page.bytes, p, usable); p += length.size
                    val id = varint(page.bytes, p, usable); p += id.size
                    rowid = id.value
                    size = length.value
                }
                LEAF_INDEX -> {
                    val length = varint(page.bytes, p, usable); p += length.size
                    size = length.value
                }
                INTERIOR_INDEX -> {
                    p += 4
                    val length = varint(page.bytes, p, usable); p += length.size
                    size = length.value
                }
                else -> throw SqliteFormatException("Страница ${page.number}: строки здесь не лежат")
            }
            val values = parseRecord(payload(page.bytes, p, size, page.type == LEAF_TABLE))
            // INTEGER PRIMARY KEY хранится пустым: настоящее значение — это rowid
            if (aliasColumn in values.indices && rowid != null && values[aliasColumn] is SqlValue.Null) {
                values.toMutableList().also { it[aliasColumn] = SqlValue.Integer(rowid) }
                    .let { return SqlRow(rowid, it) }
            }
            SqlRow(rowid, values)
        } catch (e: SqliteFormatException) {
            note(e)
            null
        } catch (e: IndexOutOfBoundsException) {
            note(e)
            null
        }
    }

    fun cursor(obj: SqlObject): RowCursor = RowCursor(obj)

    /**
     * Сколько строк в таблице: обход только по заголовкам страниц, без разбора записей —
     * миллион строк считается за доли секунды. [cancelled] прерывает долгий подсчёт.
     */
    fun countRows(obj: SqlObject, cancelled: () -> Boolean = { false }): Long {
        if (obj.rootPage <= 0) return 0
        var total = 0L
        var visited = 0
        val stack = ArrayList<Int>()
        stack.add(obj.rootPage)
        while (stack.isNotEmpty()) {
            if (cancelled()) return -1
            if (++visited > maxOf(pageCount, 64) * 2 + 1024) return total
            val number = stack.removeAt(stack.size - 1)
            val page = try {
                readBtreePage(number)
            } catch (_: SqliteFormatException) {
                continue
            }
            if (page.leaf) {
                total += page.cellCount
                continue
            }
            // У индексного дерева записи лежат и во внутренних страницах
            if (page.index) total += page.cellCount
            if (page.rightMost > 0) stack.add(page.rightMost)
            for (i in 0 until page.cellCount) {
                val child = try {
                    i32(page.bytes, cellOffset(page, i))
                } catch (_: SqliteFormatException) {
                    continue
                }
                if (child > 0) stack.add(child)
            }
        }
        return total
    }

    // ---- схема ------------------------------------------------------------

    private fun readSchema(): List<SqlObject> {
        val master = SqlObject(SqlObjectKind.TABLE, "sqlite_master", "sqlite_master", 1, MASTER_SQL)
        val out = ArrayList<SqlObject>()
        val cursor = RowCursor(master)
        var guard = 0
        while (!cursor.finished && guard < MAX_SCHEMA_ROWS) {
            val rows = cursor.next(256)
            if (rows.isEmpty() && cursor.finished) break
            for (row in rows) {
                guard++
                val values = row.values
                if (values.size < 5) continue
                val kind = when ((values[0] as? SqlValue.Text)?.value?.lowercase()) {
                    "table" -> SqlObjectKind.TABLE
                    "view" -> SqlObjectKind.VIEW
                    "index" -> SqlObjectKind.INDEX
                    "trigger" -> SqlObjectKind.TRIGGER
                    else -> continue
                }
                val name = (values[1] as? SqlValue.Text)?.value ?: continue
                val table = (values[2] as? SqlValue.Text)?.value ?: name
                val root = ((values[3] as? SqlValue.Integer)?.value ?: 0L).toInt()
                val sql = (values[4] as? SqlValue.Text)?.value
                out.add(SqlObject(kind, name, table, root, sql))
            }
        }
        cursor.problem?.let { schemaProblem = it }
        out.sortWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }))
        return out
    }

    companion object {
        internal const val LEAF_TABLE = 13
        internal const val INTERIOR_TABLE = 5
        internal const val LEAF_INDEX = 10
        internal const val INTERIOR_INDEX = 2
        private const val PAGE_CACHE = 96
        private const val MAX_PAGE = 2_147_483_646
        private const val MAX_DEPTH = 64
        private const val MAX_OVERFLOW = 1 shl 20
        private const val MAX_PAYLOAD = 64L shl 20
        private const val MAX_SCHEMA_ROWS = 100_000
        private const val MASTER_SQL =
            "CREATE TABLE sqlite_master(type TEXT, name TEXT, tbl_name TEXT, rootpage INTEGER, sql TEXT)"

        /** Подпись файла: «SQLite format 3 ». */
        val MAGIC: ByteArray = "SQLite format 3 ".toByteArray(Charsets.ISO_8859_1)

        /** Похоже ли начало файла на базу SQLite (нужны первые 16 байт). */
        fun looksLikeSqlite(head: ByteArray): Boolean {
            if (head.size < MAGIC.size) return false
            for (i in MAGIC.indices) if (head[i] != MAGIC[i]) return false
            return true
        }

        /** База из файла на диске; журнал WAL рядом читается, если он есть. */
        fun open(path: Path): SqliteDb {
            val channel = FileChannel.open(path, StandardOpenOption.READ)
            val bytes = FileBytes(channel)
            return try {
                create(bytes) { pageSize -> openWal(path, pageSize) }
            } catch (e: Throwable) {
                bytes.close()
                throw e
            }
        }

        /** База из памяти: файл внутри архива или APK. */
        fun open(content: ByteArray): SqliteDb = create(ArrayBytes(content)) { null }

        private fun create(bytes: Bytes, walFor: (Int) -> Wal?): SqliteDb {
            val head = ByteArray(100)
            if (bytes.read(0, head, 100) < 100) throw SqliteFormatException("Файл слишком мал для базы SQLite")
            if (!looksLikeSqlite(head)) throw SqliteFormatException("Это не база SQLite")
            val declared = u16(head, 16)
            val pageSize = if (declared == 1) 65536 else declared
            if (pageSize < 512 || Integer.bitCount(pageSize) != 1) {
                throw SqliteFormatException("Неверный размер страницы: $pageSize")
            }
            val reserved = u8(head, 20)
            if (pageSize - reserved < 480) throw SqliteFormatException("Неверный размер страницы")
            val (encoding, encodingName) = when (i32(head, 56)) {
                2 -> Charsets.UTF_16LE to "UTF-16LE"
                3 -> Charsets.UTF_16BE to "UTF-16BE"
                else -> Charsets.UTF_8 to "UTF-8"
            }
            val wal = walFor(pageSize)
            val headerPages = i32(head, 28)
            val filePages = (bytes.size / pageSize).toInt()
            val pageCount = when {
                wal != null && wal.dbSize > 0 -> wal.dbSize
                headerPages > 0 && i32(head, 24) == i32(head, 92) -> headerPages
                else -> maxOf(1, filePages)
            }
            return SqliteDb(
                data = bytes,
                wal = wal,
                pageSize = pageSize,
                reserved = reserved,
                pageCount = pageCount,
                encoding = encoding,
                encodingName = encodingName,
                userVersion = i32(head, 60),
                applicationId = i32(head, 68),
                freelistPages = i32(head, 36),
                sqliteVersion = i32(head, 96),
                journalMode = if (u8(head, 18) >= 2) "WAL" else "journal",
                fileSize = bytes.size
            )
        }

        /** Журнал рядом с базой: свежие страницы лежат в нём, а не в самом файле. */
        private fun openWal(path: Path, pageSize: Int): Wal? {
            val walPath = path.resolveSibling(path.fileName.toString() + "-wal")
            if (!Files.isRegularFile(walPath)) return null
            val size = runCatching { Files.size(walPath) }.getOrDefault(0L)
            val frameSize = 24L + pageSize
            if (size < 32 + frameSize) return null
            val channel = runCatching { FileChannel.open(walPath, StandardOpenOption.READ) }.getOrNull() ?: return null
            val bytes = FileBytes(channel)
            try {
                val head = ByteArray(32)
                if (bytes.read(0, head, 32) < 32) return null.also { bytes.close() }
                val magic = i32(head, 0)
                if (magic != 0x377f0682.toInt() && magic != 0x377f0683.toInt()) return null.also { bytes.close() }
                if (i32(head, 8) != pageSize) return null.also { bytes.close() }
                val salt1 = i32(head, 16)
                val salt2 = i32(head, 20)
                val frames = LinkedHashMap<Int, Long>()
                var committed: Map<Int, Long> = emptyMap()
                var dbSize = 0
                val frameHeader = ByteArray(24)
                val count = ((size - 32) / frameSize).toInt()
                for (i in 0 until count) {
                    val offset = 32L + i * frameSize
                    if (bytes.read(offset, frameHeader, 24) < 24) break
                    // Кадры из прошлой жизни журнала отличаются солью — их не читаем
                    if (i32(frameHeader, 8) != salt1 || i32(frameHeader, 12) != salt2) break
                    val page = i32(frameHeader, 0)
                    if (page <= 0) break
                    frames[page] = offset + 24
                    val commit = i32(frameHeader, 4)
                    if (commit != 0) {
                        committed = LinkedHashMap(frames)
                        dbSize = commit
                    }
                }
                if (committed.isEmpty()) return null.also { bytes.close() }
                return Wal(bytes, committed, dbSize)
            } catch (_: Exception) {
                bytes.close()
                return null
            }
        }

        internal fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

        internal fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        internal fun i32(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

        internal class Varint(val value: Long, val size: Int)

        /** Число переменной длины: до восьми байт по семь бит, девятый — целиком. */
        internal fun varint(b: ByteArray, offset: Int, limit: Int): Varint {
            var result = 0L
            for (i in 0 until 8) {
                val at = offset + i
                if (at >= limit || at >= b.size) throw SqliteFormatException("Число обрывается на краю страницы")
                val byte = b[at].toInt() and 0xFF
                result = (result shl 7) or (byte and 0x7F).toLong()
                if (byte and 0x80 == 0) return Varint(result, i + 1)
            }
            val at = offset + 8
            if (at >= limit || at >= b.size) throw SqliteFormatException("Число обрывается на краю страницы")
            return Varint((result shl 8) or (b[at].toLong() and 0xFF), 9)
        }
    }
}
