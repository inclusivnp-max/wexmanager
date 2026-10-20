package org.wexclient.app.vfs

import kotlin.test.Test
import kotlin.test.assertEquals

/** Ключи порядка внутри APK считаются без аллокаций — и так же, как прежняя версия через lowercase(). */
class ApkOrderKeysTest {
    private fun oldPriority(name: String, dir: Boolean, arch: Boolean): Int {
        if (dir || arch) return 0
        val lower = name.lowercase()
        return when {
            lower == "androidmanifest.xml" -> 1
            lower.endsWith(".dex") -> 2
            lower == "resources.arsc" -> 3
            else -> 4
        }
    }

    private fun oldDexIndex(name: String): Int {
        val lower = name.lowercase()
        if (!lower.endsWith(".dex")) return Int.MAX_VALUE
        if (lower == "classes.dex") return 1
        return lower.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 999
    }

    private val names = listOf(
        "classes.dex", "CLASSES.DEX", "classes2.dex", "classes10.dex", "Classes3.Dex", "classesX.dex",
        "classes.dex.bak", "2.dex", "foo.dex", ".dex", "classes0.dex", "classes999999999.dex",
        "AndroidManifest.xml", "androidmanifest.XML", "resources.arsc", "RESOURCES.ARSC", "res", "lib",
        "classes12345678901.dex", "a.txt", "", "dex"
    )

    @Test
    fun `new keys match the old ones`() {
        for (n in names) {
            assertEquals(oldDexIndex(n), VfsEntry.getDexNaturalIndex(n), "dex index: «$n»")
            for (dir in listOf(false, true)) {
                assertEquals(oldPriority(n, dir, false), VfsEntry.getApkItemPriority(n, dir, false), "priority: «$n» dir=$dir")
            }
        }
    }
}
