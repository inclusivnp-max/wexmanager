package org.wexclient.app.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Разбор заголовков таблиц из текста CREATE TABLE. */
class SqlSchemaTest {

    @Test
    fun `простая таблица`() {
        val columns = SqlSchema.tableColumns("CREATE TABLE t(a INTEGER, b TEXT, c)")
        assertEquals(listOf("a", "b", "c"), columns.map { it.name })
        assertEquals(listOf("INTEGER", "TEXT", ""), columns.map { it.type })
    }

    @Test
    fun `имена в кавычках и скобках`() {
        val columns = SqlSchema.tableColumns(
            "CREATE TABLE \"мои данные\"(\"имя, с запятой\" TEXT, [в скобках] INTEGER, " +
                "`в обратных` REAL, \"с \"\"кавычкой\"\"\" TEXT)"
        )
        assertEquals(listOf("имя, с запятой", "в скобках", "в обратных", "с \"кавычкой\""), columns.map { it.name })
    }

    @Test
    fun `тип со скобками и ограничения не попадают в тип`() {
        val columns = SqlSchema.tableColumns(
            "CREATE TABLE t(a VARCHAR(20) NOT NULL DEFAULT 'x', b DECIMAL(10,2) CHECK(b > 0), c TEXT COLLATE NOCASE)"
        )
        assertEquals(listOf("VARCHAR(20)", "DECIMAL(10,2)", "TEXT"), columns.map { it.type })
        assertTrue(columns[0].notNull)
        assertFalse(columns[2].notNull)
    }

    @Test
    fun `ограничения таблицы не считаются столбцами`() {
        val columns = SqlSchema.tableColumns(
            """CREATE TABLE t(a INTEGER, b TEXT, PRIMARY KEY(a), FOREIGN KEY(b) REFERENCES u(x), CHECK(a > 0))"""
        )
        assertEquals(listOf("a", "b"), columns.map { it.name })
        assertTrue(columns[0].primaryKey)
        // Отдельный PRIMARY KEY(a) по столбцу INTEGER — тоже псевдоним rowid
        assertTrue(columns[0].rowidAlias)
    }

    @Test
    fun `псевдоним rowid только у INTEGER PRIMARY KEY`() {
        assertTrue(SqlSchema.tableColumns("CREATE TABLE t(id INTEGER PRIMARY KEY, s TEXT)")[0].rowidAlias)
        assertFalse(SqlSchema.tableColumns("CREATE TABLE t(id INT PRIMARY KEY, s TEXT)")[0].rowidAlias)
        assertFalse(SqlSchema.tableColumns("CREATE TABLE t(id TEXT PRIMARY KEY, s TEXT)")[0].rowidAlias)
        // По убыванию ключ хранится отдельным деревом, а не как rowid
        assertFalse(SqlSchema.tableColumns("CREATE TABLE t(id INTEGER PRIMARY KEY DESC, s TEXT)")[0].rowidAlias)
        // У таблицы без rowid подставлять нечего
        val without = "CREATE TABLE t(id INTEGER PRIMARY KEY, s TEXT) WITHOUT ROWID"
        assertTrue(SqlSchema.isWithoutRowid(without))
        assertFalse(SqlSchema.tableColumns(without)[0].rowidAlias)
    }

    @Test
    fun `составной ключ — не псевдоним rowid`() {
        val columns = SqlSchema.tableColumns("CREATE TABLE t(a INTEGER, b INTEGER, PRIMARY KEY(a, b))")
        assertTrue(columns.all { it.primaryKey })
        assertTrue(columns.none { it.rowidAlias })
    }

    @Test
    fun `переносы строк и комментарии`() {
        val sql = """
            CREATE TABLE t ( -- заголовок
              a INTEGER, /* пояснение, с запятой */
              b TEXT
            )
        """.trimIndent()
        assertEquals(listOf("a", "b"), SqlSchema.tableColumns(sql).map { it.name })
    }

    @Test
    fun `столбцы индекса и ссылка на строку`() {
        val columns = SqlSchema.indexColumns("CREATE INDEX i ON t(a, b DESC)")
        assertEquals(listOf("a", "b", "rowid"), columns.map { it.name })
    }

    @Test
    fun `битый или пустой текст не ломает разбор`() {
        assertEquals(emptyList(), SqlSchema.tableColumns(null))
        assertEquals(emptyList(), SqlSchema.tableColumns(""))
        assertEquals(emptyList(), SqlSchema.tableColumns("CREATE TABLE t"))
        assertEquals(emptyList(), SqlSchema.tableColumns("CREATE TABLE t(unclosed"))
        assertFalse(SqlSchema.isWithoutRowid("CREATE TABLE t(a)"))
    }

    @Test
    fun `имена столбцов дополняются по номеру`() {
        val obj = SqlObject(SqlObjectKind.TABLE, "t", "t", 2, "CREATE TABLE t(a, b)")
        assertEquals(listOf("a", "b", "столбец 3"), obj.columnNames(3))
    }
}
