package org.wexclient.app.util

import org.wexclient.app.terminal.ShellLocator
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortableHelpersTest {
    @Test
    fun `system open uses xdg-open here`() {
        if (java.io.File.separatorChar == '\\') {
            assertEquals(listOf("rundll32", "url.dll,FileProtocolHandler", "C:\\a b.txt"), systemOpenCommand("C:\\a b.txt"))
        } else {
            assertEquals(listOf("xdg-open", "/tmp/a b.txt"), systemOpenCommand("/tmp/a b.txt"))
        }
    }

    @Test
    fun `shell name for the tab title works for both path styles`() {
        assertEquals("fish", ShellLocator.displayName("/usr/bin/fish"))
        assertEquals("cmd.exe", ShellLocator.displayName("C:\\Windows\\System32\\cmd.exe"))
        assertEquals("pwsh", ShellLocator.displayName("pwsh"))
    }

    @Test
    fun `strict delete removes the tree but not what a link points to`() {
        val root = Files.createTempDirectory("wex-del")
        val outside = Files.createTempDirectory("wex-keep")
        try {
            outside.resolve("precious.txt").writeText("не трогать")
            val tree = root.resolve("tree").createDirectories()
            tree.resolve("a/b/c").createDirectories()
            tree.resolve("a/b/c/file.txt").writeText("x")
            tree.resolve("a/top.txt").writeText("y")
            // Ссылка на папку снаружи — удаляется сама ссылка, содержимое цели остаётся
            Files.createSymbolicLink(tree.resolve("a/link"), outside)
            FileTree.deleteOrThrow(tree)
            assertFalse(tree.exists())
            assertTrue(outside.resolve("precious.txt").exists())
        } finally {
            FileTree.delete(root)
            FileTree.delete(outside)
        }
    }
}
