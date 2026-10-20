package org.wexclient.app.apk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import java.io.IOException
import kotlin.test.assertTrue

/** Сравнение кода и ресурсов двух APK. */
class ApkCodeDiffTest {

    private fun cls(name: String, fingerprint: Long, methods: Set<String> = emptySet(), fields: Set<String> = emptySet()) =
        name to DexClassSignature(name, fingerprint, methods, fields)

    @Test
    fun `видит новые, пропавшие и переписанные классы`() {
        val old = mapOf(
            cls("com.app.Main", 1, setOf("onCreate(): void")),
            cls("com.app.Old", 2),
            cls("com.app.Same", 3)
        )
        val new = mapOf(
            cls("com.app.Main", 99, setOf("onCreate(): void", "onResume(): void")),
            cls("com.app.New", 4),
            cls("com.app.Same", 3)
        )

        val diff = ApkDexDiff.compare(old, new)
        val byName = diff.classes.associateBy { it.name }

        assertEquals(DiffKind.CHANGED, byName["com.app.Main"]?.kind)
        assertEquals(listOf("onResume(): void"), byName["com.app.Main"]?.addedMethods)
        assertEquals(DiffKind.ADDED, byName["com.app.New"]?.kind)
        assertEquals(DiffKind.REMOVED, byName["com.app.Old"]?.kind)
        // Совпавший класс в список изменений не попадает
        assertFalse(byName.containsKey("com.app.Same"))
        assertEquals(1, diff.changed)
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
    }

    @Test
    fun `правка только тела помечается отдельно`() {
        val old = mapOf(cls("com.app.Util", 10, setOf("calc(int): int")))
        val new = mapOf(cls("com.app.Util", 11, setOf("calc(int): int")))

        val change = ApkDexDiff.compare(old, new).classes.single()
        assertEquals(DiffKind.CHANGED, change.kind)
        assertTrue(change.bodyOnly)
    }

    @Test
    fun `изменения полей видны`() {
        val old = mapOf(cls("com.app.Config", 1, emptySet(), setOf("URL: java.lang.String")))
        val new = mapOf(cls("com.app.Config", 2, emptySet(), setOf("URL: java.lang.String", "TIMEOUT: int")))

        val change = ApkDexDiff.compare(old, new).classes.single()
        assertEquals(listOf("TIMEOUT: int"), change.addedFields)
        assertFalse(change.bodyOnly)
    }

    @Test
    fun `имена типов читаемые`() {
        assertEquals("com.example.Foo", ApkDexDiff.readableName("Lcom/example/Foo;"))
        assertEquals("int[]", ApkDexDiff.prettyType("[I"))
        assertEquals("java.lang.String[][]", ApkDexDiff.prettyType("[[Ljava/lang/String;"))
        assertEquals("void", ApkDexDiff.prettyType("V"))
        assertEquals(
            "setText(java.lang.CharSequence, int): boolean",
            ApkDexDiff.methodKey("setText", listOf("Ljava/lang/CharSequence;", "I"), "Z")
        )
    }

    @Test
    fun `битый dex сообщает об ошибке`() {
        assertFailsWith<IOException> { ApkDexDiff.read(listOf(byteArrayOf(1, 2, 3))) }
        assertTrue(ApkDexDiff.read(emptyList()).isEmpty())
    }

    @Test
    fun `ресурсы- добавленные, убранные и изменённые`() {
        val old = mapOf(
            "app:string/hello" to "Привет",
            "app:string/bye" to "Пока",
            "app:color/accent" to "#FF0000"
        )
        val new = mapOf(
            "app:string/hello" to "Здравствуйте",
            "app:color/accent" to "#FF0000",
            "app:string/new_key" to "Новое"
        )

        val diff = ApkResourceDiff.compare(old, new)
        val byKey = diff.changes.associateBy { it.key }

        assertEquals(DiffKind.CHANGED, byKey["app:string/hello"]?.kind)
        assertEquals("Привет", byKey["app:string/hello"]?.oldValue)
        assertEquals("Здравствуйте", byKey["app:string/hello"]?.newValue)
        assertEquals(DiffKind.ADDED, byKey["app:string/new_key"]?.kind)
        assertEquals(DiffKind.REMOVED, byKey["app:string/bye"]?.kind)
        assertFalse(byKey.containsKey("app:color/accent"))
        assertEquals(3, diff.oldCount)
        assertEquals(3, diff.newCount)
    }

    @Test
    fun `изменённые ресурсы идут первыми`() {
        val old = mapOf("a:string/x" to "1", "a:string/gone" to "2")
        val new = mapOf("a:string/x" to "9", "a:string/added" to "3")
        assertEquals(
            listOf(DiffKind.CHANGED, DiffKind.ADDED, DiffKind.REMOVED),
            ApkResourceDiff.compare(old, new).changes.map { it.kind }
        )
    }

    @Test
    fun `битый arsc сообщает об ошибке`() {
        assertFailsWith<IOException> { ApkResourceDiff.read(byteArrayOf(0, 1, 2, 3)) }
        assertFailsWith<IOException> { ApkResourceDiff.read(ByteArray(0)) }
    }
}
