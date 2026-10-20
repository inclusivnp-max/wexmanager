package org.wexclient.app.db

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Чтение настоящих баз: файлы создаёт сам sqlite3, а разбирает наш читатель.
 * Если sqlite3 в системе нет, проверки пропускаются — в сборке они не обязательны.
 */
class SqliteDbTest {

    private val haveSqlite: Boolean by lazy {
        runCatching {
            val p = ProcessBuilder("sqlite3", "-version").redirectErrorStream(true).start()
            p.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun tempDir(): Path = Files.createTempDirectory("wex-sqlite-test")

    /** Создаёт базу и выполняет в ней [sql]. */
    private fun makeDb(dir: Path, name: String, sql: String): Path {
        val db = dir.resolve(name)
        val process = ProcessBuilder("sqlite3", db.toString()).redirectErrorStream(true).start()
        process.outputStream.use { it.write(sql.toByteArray()) }
        val output = process.inputStream.use { String(it.readBytes()) }
        check(process.waitFor() == 0) { "sqlite3: $output" }
        return db
    }

    private fun rows(db: SqliteDb, table: String): List<SqlRow> {
        val obj = db.objects.first { it.name == table }
        val cursor = db.cursor(obj)
        val out = ArrayList<SqlRow>()
        while (!cursor.finished) out.addAll(cursor.next(256))
        return out
    }

    private fun text(v: SqlValue?): String? = (v as? SqlValue.Text)?.value
    private fun int(v: SqlValue?): Long? = (v as? SqlValue.Integer)?.value

    @Test
    fun `читает значения всех типов`() {
        assumeTrue(haveSqlite, "нет sqlite3")
        val dir = tempDir()
        val db = makeDb(
            dir, "types.db",
            """
            CREATE TABLE t(i INTEGER, r REAL, s TEXT, b BLOB, n INTEGER);
            INSERT INTO t VALUES(42, 3.5, 'привет', x'00ff10', NULL);
            INSERT INTO t VALUES(-9223372036854775808, -0.125, '', x'', 0);
            INSERT INTO t VALUES(1, 1.0, 'tail', x'0102030405', 1);
            """.trimIndent()
        )
        SqliteDb.open(db).use { sqlite ->
            val list = rows(sqlite, "t")
            assertEquals(3, list.size)
            assertEquals(42L, int(list[0].values[0]))
            assertEquals(3.5, (list[0].values[1] as SqlValue.Real).value)
            assertEquals("привет", text(list[0].values[2]))
            assertContentEquals(byteArrayOf(0, 0xFF.toByte(), 0x10), (list[0].values[3] as SqlValue.Blob).bytes)
            assertTrue(list[0].values[4] is SqlValue.Null)
            assertEquals(Long.MIN_VALUE, int(list[1].values[0]))
            assertEquals("", text(list[1].values[2]))
            assertEquals(0, (list[1].values[3] as SqlValue.Blob).bytes.size)
            assertEquals(1L, list[0].rowid)
        }
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }

    @Test
    fun `длинный текст собирается из страниц переполнения`() {
        assumeTrue(haveSqlite, "нет sqlite3")
        val dir = tempDir()
        val long = "я" + "ю".repeat(200_000)
        val db = makeDb(
            dir, "big.db",
            "CREATE TABLE t(s TEXT, k INTEGER);\nINSERT INTO t VALUES('$long', 7);\n"
        )
        SqliteDb.open(db).use { sqlite ->
            val list = rows(sqlite, "t")
            assertEquals(1, list.size)
            assertEquals(long, text(list[0].values[0]))
            assertEquals(7L, int(list[0].values[1]))
        }
    }

    @Test
    fun `большой blob читается целиком`() {
        assumeTrue(haveSqlite, "нет sqlite3")
        val dir = tempDir()
        // 60 000 байт: заведомо несколько страниц переполнения
        val db = makeDb(
            dir, "blob.db",
            "CREATE TABLE t(b BLOB);\nINSERT INTO t VALUES(randomblob(60000));\n"
        )
        SqliteDb.open(db).use { sqlite ->
            val blob = rows(sqlite, "t").single().values[0] as SqlValue.Blob
            assertEquals(60_000, blob.bytes.size)
        }
    }

    @Test
    fun `много строк — обход внутренних страниц дерева`() {
        assumeTrue(haveSqlite, "нет sqlite3")
        val dir = tempDir()
        val db = makeDb(
            dir, "many.db",
            """
            CREATE TABLE t(id INTEGER PRIMARY KEY, s TEXT);
            WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<5000)
            INSERT INTO t SELECT x, 'строка ' || x FROM c;
            """.trimIndent()
        )
        SqliteDb.open(db).use { sqlite ->
            val table = sqlite.objects.first { it.name == "t" }
            assertEquals(5000, sqlite.countRows(table))
            val list = rows(sqlite, "t")
            assertEquals(5000, list.size)
            assertEquals(1L, int(list[0].values[0]))
            assertEquals("строка 5000", text(list[4999].values[1]))
            // Порции идут подряд и не теряют строк
            val cursor = sqlite.cursor(table)
            val first = cursor.next(10)
            val second = cursor.next(10)
            assertEquals(1L, int(first[0].values[0]))
            assertEquals(11L, int(second[0].values[0]))
        }
    }

    @Test
    fun `INTEGER PRIMARY KEY показывается как номер строки`() {
        assumeTrue(haveSqlite, "нет sqlite3")
        val dir = tempDir()
        val db = makeDb(
            dir, "rowid.db",
            "CREATE TABLE t(id INTEGER PRIMARY KEY, s TEXT);\nINSERT INTO t(s) VALUES('a'),('b');\n"
        )
        SqliteDb.open(db).use { sqlite ->
            val list = rows(sqlite, "t")
            // В самой записи на месте ключа лежит пусто — подставляется rowid
            assertEquals(1L, int(list[0].values[0]))
            assertEquals(2L, int(list[1].values[0]))
            assertEquals(2L, list[1].rowid)
        }
    }

    @Test
    fun `таблица без rowid читается из индексного дерева`() {
        assumeTrue(haveSqlite, "нет sqlite3")
        val dir = tempDir()
        val db = makeDb(
            dir, "worid.db",
            """
            CREATE TABLE t(a TEXT, b INTEGER, PRIMARY KEY(a)) WITHOUT ROWID;
            WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<800)
            INSERT INTO t SELECT 'ключ' || x, x FROM c;
            """.trimIndent()
        )
        SqliteDb.open(db).use { sqlite ->
            val table = sqlite.objects.first { it.name == "t" }
            assertTrue(table.withoutRowid)
            val list = rows(sqlite, "t")
            assertEquals(800, list.size)
            assertEquals(800, sqlite.countRows(table))
            assertTrue(list.all { it.rowid == null })
            assertEquals(setOf(800), setOf(list.map { text(it.values[0]) }.toSet().size))
        }
    }

    @Test
    fun `видит записи из журнала WAL`() {
        assumeTrue(haveSqlite, "нет sqlite3")
        val dir = tempDir()
        val db = dir.resolve("wal.db")
        // sqlite3 при обычном выходе сливает журнал в базу, поэтому процесс обрывается принудительно
        val process = ProcessBuilder("sqlite3", db.toString()).redirectErrorStream(true).start()
        val writer = process.outputStream.bufferedWriter()
        writer.write(
            """
            PRAGMA journal_mode=WAL;
            PRAGMA wal_autocheckpoint=0;
            CREATE TABLE t(id INTEGER PRIMARY KEY, s TEXT);
            INSERT INTO t(s) VALUES('в журнале');
            WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<300)
            INSERT INTO t(s) SELECT 'строка ' || x FROM c;
            SELECT 'готово', count(*) FROM t;
            """.trimIndent() + "\n"
        )
        writer.flush()
        val reader = process.inputStream.bufferedReader()
        var done = false
        while (!done) {
            val line = reader.readLine() ?: break
            if (line.startsWith("готово")) done = true
        }
        process.destroyForcibly()
        process.waitFor()
        assumeTrue(done, "sqlite3 не отчитался о записи")
        val wal = dir.resolve("wal.db-wal")
        assumeTrue(Files.isRegularFile(wal) && Files.size(wal) > 0, "журнал не остался на диске")

        SqliteDb.open(db).use { sqlite ->
            assertTrue(sqlite.walFrames > 0, "журнал не прочитан")
            assertEquals(301, sqlite.countRows(sqlite.objects.first { it.name == "t" }))
            assertEquals("в журнале", text(rows(sqlite, "t")[0].values[1]))
        }
        // Без журнала (как файл внутри архива) в базе этих строк ещё нет
        SqliteDb.open(Files.readAllBytes(db)).use { plain ->
            val tables = plain.objects.filter { it.kind == SqlObjectKind.TABLE }
            val count = tables.firstOrNull { it.name == "t" }?.let { plain.countRows(it) } ?: 0
            assertTrue(count < 301, "без журнала строк должно быть меньше, а их $count")
        }
    }

    @Test
    fun `читает базу в кодировке UTF-16`() {
        assumeTrue(haveSqlite, "нет sqlite3")
        val dir = tempDir()
        val db = makeDb(
            dir, "utf16.db",
            """
            PRAGMA encoding='UTF-16le';
            CREATE TABLE t(s TEXT);
            INSERT INTO t VALUES('кириллица 😀');
            """.trimIndent()
        )
        SqliteDb.open(db).use { sqlite ->
            assertEquals("UTF-16LE", sqlite.encodingName)
            assertEquals("кириллица 😀", text(rows(sqlite, "t").single().values[0]))
        }
    }

    @Test
    fun `в схеме видны таблицы, представления и индексы`() {
        assumeTrue(haveSqlite, "нет sqlite3")
        val dir = tempDir()
        val db = makeDb(
            dir, "schema.db",
            """
            CREATE TABLE "заказы"(id INTEGER PRIMARY KEY, "кто" TEXT NOT NULL, сумма REAL);
            CREATE INDEX idx_kto ON "заказы"("кто");
            CREATE VIEW v AS SELECT id FROM "заказы";
            INSERT INTO "заказы"(кто, сумма) VALUES('Аня', 10.5);
            """.trimIndent()
        )
        SqliteDb.open(db).use { sqlite ->
            val names = sqlite.objects.map { it.name }
            assertContains(names, "заказы")
            assertContains(names, "idx_kto")
            assertContains(names, "v")
            val table = sqlite.objects.first { it.name == "заказы" }
            assertEquals(listOf("id", "кто", "сумма"), table.columns.map { it.name })
            assertEquals(listOf("INTEGER", "TEXT", "REAL"), table.columns.map { it.type })
            assertTrue(table.columns[0].rowidAlias)
            assertTrue(table.columns[1].notNull)
            assertEquals(SqlObjectKind.VIEW, sqlite.objects.first { it.name == "v" }.kind)
            // Индекс тоже можно посмотреть: его дерево читается как обычная таблица
            val index = sqlite.objects.first { it.name == "idx_kto" }
            assertEquals(1, sqlite.countRows(index))
            assertEquals("Аня", text(rows(sqlite, "idx_kto").single().values[0]))
        }
    }

    @Test
    fun `база из памяти читается так же`() {
        assumeTrue(haveSqlite, "нет sqlite3")
        val dir = tempDir()
        val db = makeDb(dir, "mem.db", "CREATE TABLE t(a);\nINSERT INTO t VALUES('из архива');\n")
        SqliteDb.open(Files.readAllBytes(db)).use { sqlite ->
            assertEquals("из архива", text(rows(sqlite, "t").single().values[0]))
        }
    }

    @Test
    fun `чужой файл отвергается с понятной ошибкой`() {
        val bytes = ByteArray(4096) { it.toByte() }
        val e = assertFailsWith<SqliteFormatException> { SqliteDb.open(bytes) }
        assertContains(e.message ?: "", "SQLite")
        assertFailsWith<SqliteFormatException> { SqliteDb.open(ByteArray(10)) }
    }

    @Test
    fun `обрезанная база не роняет чтение`() {
        assumeTrue(haveSqlite, "нет sqlite3")
        val dir = tempDir()
        val db = makeDb(
            dir, "cut.db",
            """
            CREATE TABLE t(id INTEGER PRIMARY KEY, s TEXT);
            WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<2000)
            INSERT INTO t SELECT x, 'строка ' || x FROM c;
            """.trimIndent()
        )
        val full = Files.readAllBytes(db)
        // Половина файла: заголовок цел, дерева не хватает
        val cut = full.copyOf(full.size / 2)
        SqliteDb.open(cut).use { sqlite ->
            val table = sqlite.objects.firstOrNull { it.name == "t" }
            if (table != null) {
                val cursor = sqlite.cursor(table)
                var seen = 0
                while (!cursor.finished && seen < 5000) seen += cursor.next(256).size
                // Часть строк прочиталась, про обрыв сказано
                assertTrue(seen < 2000)
                assertTrue(cursor.problem != null || seen == 0)
            }
        }
    }

    @Test
    fun `испорченные байты не вешают разбор`() {
        assumeTrue(haveSqlite, "нет sqlite3")
        val dir = tempDir()
        val db = makeDb(
            dir, "fuzz.db",
            """
            CREATE TABLE t(id INTEGER PRIMARY KEY, s TEXT, b BLOB);
            WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<500)
            INSERT INTO t SELECT x, 'строка ' || x, randomblob(50) FROM c;
            """.trimIndent()
        )
        val original = Files.readAllBytes(db)
        val random = java.util.Random(20260918)
        repeat(200) {
            val bytes = original.copyOf()
            // Заголовок не трогаем: проверяем устойчивость разбора страниц, а не отказ открыть файл
            repeat(1 + random.nextInt(24)) {
                val at = 100 + random.nextInt(bytes.size - 100)
                bytes[at] = random.nextInt(256).toByte()
            }
            val result = runCatching {
                SqliteDb.open(bytes).use { sqlite ->
                    for (obj in sqlite.objects) {
                        if (obj.rootPage <= 0) continue
                        val cursor = sqlite.cursor(obj)
                        var seen = 0
                        while (!cursor.finished && seen < 20_000) seen += cursor.next(256).size
                        sqlite.countRows(obj)
                    }
                }
            }
            val error = result.exceptionOrNull()
            if (error != null && error !is SqliteFormatException) {
                throw AssertionError("неожиданная ошибка на испорченном файле: $error", error)
            }
        }
    }
}
