package org.wexclient.app.vfs.remote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Сведения о ссылке — как у NIO на диске: без раскрытия это ссылка, а не папка. Обход дерева
 * (удаление, копирование) спрашивает именно так и в ссылку на папку не заходит.
 */
class RemoteAttributesTest {

    private fun entry(type: Char, target: Char) = RemoteEntry("x", type, target, 10, 0, 0x1ED, "u", "g")

    @Test
    fun `ссылка на папку без раскрытия — ссылка`() {
        val a = RemoteAttributes(entry('l', 'd'), "/x", follow = false)
        assertTrue(a.isSymbolicLink)
        assertFalse(a.isDirectory)
        assertFalse(a.isRegularFile)
    }

    @Test
    fun `ссылка на папку с раскрытием — папка`() {
        val a = RemoteAttributes(entry('l', 'd'), "/x", follow = true)
        assertTrue(a.isDirectory)
        assertFalse(a.isSymbolicLink)
    }

    @Test
    fun `обычные папка и файл одинаковы в обоих режимах`() {
        for (follow in listOf(true, false)) {
            assertTrue(RemoteAttributes(entry('d', 'd'), "/d", follow).isDirectory)
            assertTrue(RemoteAttributes(entry('f', 'f'), "/f", follow).isRegularFile)
            assertTrue(RemoteAttributes(entry('p', 'p'), "/p", follow).isOther)
        }
    }
}
