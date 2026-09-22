package org.wexclient.app.vfs.ssh

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Сведения о ссылке — как у NIO на диске: без раскрытия это ссылка, а не папка. Обход дерева
 * (удаление, копирование) спрашивает именно так и в ссылку на папку не заходит.
 */
class SshAttributesTest {

    private fun entry(type: Char, target: Char) = RemoteEntry("x", type, target, 10, 0, 0x1ED, "u", "g")

    @Test
    fun `ссылка на папку без раскрытия — ссылка`() {
        val a = SshAttributes(entry('l', 'd'), "/x", follow = false)
        assertTrue(a.isSymbolicLink)
        assertFalse(a.isDirectory)
        assertFalse(a.isRegularFile)
    }

    @Test
    fun `ссылка на папку с раскрытием — папка`() {
        val a = SshAttributes(entry('l', 'd'), "/x", follow = true)
        assertTrue(a.isDirectory)
        assertFalse(a.isSymbolicLink)
    }

    @Test
    fun `обычные папка и файл одинаковы в обоих режимах`() {
        for (follow in listOf(true, false)) {
            assertTrue(SshAttributes(entry('d', 'd'), "/d", follow).isDirectory)
            assertTrue(SshAttributes(entry('f', 'f'), "/f", follow).isRegularFile)
            assertTrue(SshAttributes(entry('p', 'p'), "/p", follow).isOther)
        }
    }
}
