package org.wexclient.app.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Подсказки пути: разбор введённого и отбор папок. */
class PathSuggestionsTest {

    @Test
    fun `делит путь на папку и начало имени`() {
        assertEquals(PathSuggestions.Split("/home", "za"), PathSuggestions.split("/home/za"))
        assertEquals(PathSuggestions.Split("/home", ""), PathSuggestions.split("/home/"))
        assertEquals(PathSuggestions.Split("/", "home"), PathSuggestions.split("/home"))
        assertEquals(PathSuggestions.Split("", "проект"), PathSuggestions.split("проект"))
        assertEquals(PathSuggestions.Split("/a/b/c", "d"), PathSuggestions.split("/a/b/c/d"))
    }

    @Test
    fun `тильда превращается в домашнюю папку`() {
        assertEquals("/home/u", PathSuggestions.expand("~", "/home/u"))
        assertEquals("/home/u/Загрузки", PathSuggestions.expand("~/Загрузки", "/home/u"))
        // Внутри пути «~» не трогаем
        assertEquals("/tmp/~x", PathSuggestions.expand("/tmp/~x", "/home/u"))
    }

    @Test
    fun `отбор по началу имени без учёта регистра`() {
        assertTrue(PathSuggestions.matches("Downloads", "down"))
        assertTrue(PathSuggestions.matches("Проекты", "про"))
        assertTrue(PathSuggestions.matches("что угодно", ""))
        assertFalse(PathSuggestions.matches("Documents", "down"))
        // Скрытые показываем, только если их просят точкой
        assertFalse(PathSuggestions.matches(".config", ""))
        assertTrue(PathSuggestions.matches(".config", ".co"))
    }

    @Test
    fun `подсказки берутся из настоящей папки`() {
        val root = Files.createTempDirectory("wex-hints")
        listOf("Проекты", "Программы", "Музыка", ".скрытая").forEach { Files.createDirectory(root.resolve(it)) }
        Files.write(root.resolve("файл.txt"), byteArrayOf(1))

        val all = PathSuggestions.forInput("$root/", root, null).map { it.fileName.toString() }
        assertEquals(listOf("Музыка", "Программы", "Проекты"), all)

        val filtered = PathSuggestions.forInput("$root/Про", root, null).map { it.fileName.toString() }
        assertEquals(listOf("Программы", "Проекты"), filtered)

        val hidden = PathSuggestions.forInput("$root/.", root, null).map { it.fileName.toString() }
        assertEquals(listOf(".скрытая"), hidden)
    }

    @Test
    fun `относительный путь считается от текущей папки`() {
        val root = Files.createTempDirectory("wex-hints-rel")
        Files.createDirectories(root.resolve("вложенная/глубже"))
        val hints: List<Path> = PathSuggestions.forInput("вложенная/", root, null)
        assertEquals(listOf("глубже"), hints.map { it.fileName.toString() })
    }

    @Test
    fun `несуществующая папка не роняет подсказки`() {
        val root = Files.createTempDirectory("wex-hints-none")
        assertTrue(PathSuggestions.forInput("/такого/пути/нет/", root, null).isEmpty())
        assertTrue(PathSuggestions.forInput("", root, null).isEmpty())
    }

    @Test
    fun `дополнение добавляет косую черту`() {
        val root = Files.createTempDirectory("wex-hints-complete")
        val dir = Files.createDirectory(root.resolve("папка"))
        assertTrue(PathSuggestions.completion(dir).endsWith("папка/"))
    }
}
