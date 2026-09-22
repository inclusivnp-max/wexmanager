package org.wexclient.app.util

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/**
 * Рабочие файлы одного запуска: <base>/<pid>-<время старта>, только для владельца. Своя папка
 * удаляется при выходе, папки завершившихся процессов (kill, сбой питания) — при следующем запуске;
 * папки других работающих копий WEX не трогаются.
 */
internal class ProcessScratch(private val base: () -> Path) {
    val dir: Path by lazy {
        val root = base()
        Files.createDirectories(root)
        ownerOnly(root)
        val key = processKey(ProcessHandle.current())
        removeStale(root, key)
        val mine = root.resolve(key)
        Files.createDirectories(mine)
        ownerOnly(mine)
        runCatching { Runtime.getRuntime().addShutdownHook(Thread({ FileTree.delete(mine) }, "wex-scratch-cleanup")) }
        mine
    }

    fun newDir(prefix: String): Path = Files.createTempDirectory(dir, prefix)

    fun newFile(prefix: String, suffix: String?): Path = Files.createTempFile(dir, prefix, suffix)

    companion object {
        private val KEY = Regex("\\d+-\\d+")

        fun processKey(handle: ProcessHandle): String =
            handle.pid().toString() + "-" + handle.info().startInstant().map { it.toEpochMilli() }.orElse(0L)

        /** Декомпиляторы, проверки, форматтеры: ~/.cache/wexdesktop/tmp — не /tmp, который обычно в памяти. */
        val work = ProcessScratch { Paths.get(System.getProperty("user.home"), ".cache", "wexdesktop", "tmp") }

        private fun ownerOnly(p: Path) {
            runCatching { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------")) }
        }

        private fun removeStale(root: Path, current: String) {
            runCatching {
                Files.newDirectoryStream(root).use { children ->
                    for (child in children) {
                        val name = child.fileName.toString()
                        if (name == current || !KEY.matches(name)) continue
                        val pid = name.substringBefore('-').toLongOrNull() ?: continue
                        val alive = ProcessHandle.of(pid).map { processKey(it) == name }.orElse(false)
                        if (!alive) FileTree.delete(child)
                    }
                }
            }
        }

        private val LEGACY_TMP_PREFIXES = listOf(
            "wex_cfr_", "wex_vf_", "wex_dexjar_", "wex_class_", "wex-lint", "wex-fmt", "wex-extract-",
            "wex-adb-install", "wex_disasm_", "wex-key-", "wex-empty-", "wex_sign_"
        )

        /**
         * Рабочие папки прежних версий прямо в /tmp (в памяти): после аварийного выхода они жили до
         * перезагрузки. Удаляются свои, старше суток — их владелец, скорее всего, давно завершился.
         */
        fun removeLegacyTemp() {
            if (System.getProperty("wex.isolated") == "true") return
            removeLegacyTemp(Paths.get(System.getProperty("java.io.tmpdir")))
        }

        internal fun removeLegacyTemp(tmp: Path) {
            runCatching {
                val cutoff = System.currentTimeMillis() - 24L * 3600_000
                val me = System.getProperty("user.name")
                Files.newDirectoryStream(tmp) { p -> LEGACY_TMP_PREFIXES.any { p.fileName.toString().startsWith(it) } }.use { found ->
                    for (p in found) {
                        val attrs = runCatching {
                            Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        }.getOrNull() ?: continue
                        if (attrs.isSymbolicLink || attrs.lastModifiedTime().toMillis() > cutoff) continue
                        if (runCatching { Files.getOwner(p, LinkOption.NOFOLLOW_LINKS).name }.getOrNull() != me) continue
                        FileTree.delete(p)
                    }
                }
            }
        }
    }
}
