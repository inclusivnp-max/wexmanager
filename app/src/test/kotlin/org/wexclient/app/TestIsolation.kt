package org.wexclient.app

import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.wexclient.app.util.FileTree
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * Тесты пишут в ~/.config/wexdesktop (ключи подписи, закладки, история, настройки). Gradle подменяет
 * user.home (build.gradle.kts), а раннер IDE — нет: тогда подменяем здесь, до первого теста класса.
 * Подключается само (META-INF/services + junit-platform.properties) при любом способе запуска.
 */
class TestIsolation : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) = ensure()

    companion object {
        @Synchronized
        fun ensure() {
            if (System.getProperty("wex.isolated") == "true" && !isRealHome()) return
            removeStale()
            val home = Files.createTempDirectory(PREFIX)
            // Историю переходов дописывает свой хук выхода — что останется, уберёт следующий запуск
            Runtime.getRuntime().addShutdownHook(Thread { FileTree.delete(home) })
            System.setProperty("user.home", home.toAbsolutePath().toString())
            System.setProperty("wex.isolated", "true")
        }

        private const val PREFIX = "wex-test-home"

        /** Профили прошлых запусков старше шести часов (свои, не ссылки). */
        private fun removeStale() = runCatching {
            val tmp = Paths.get(System.getProperty("java.io.tmpdir"))
            val cutoff = System.currentTimeMillis() - 6 * 3600_000L
            val me = System.getProperty("user.name")
            Files.newDirectoryStream(tmp, "$PREFIX*").use { dirs ->
                for (dir in dirs) {
                    val attrs = runCatching {
                        Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    }.getOrNull() ?: continue
                    if (!attrs.isDirectory || attrs.lastModifiedTime().toMillis() > cutoff) continue
                    if (runCatching { Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS).name }.getOrNull() != me) continue
                    FileTree.delete(dir)
                }
            }
        }

        private fun isRealHome(): Boolean {
            val real = System.getenv("HOME")?.takeIf { it.isNotBlank() } ?: return false
            val home = System.getProperty("user.home") ?: return true
            return runCatching { File(home).canonicalFile == File(real).canonicalFile }.getOrDefault(true)
        }

        /** Ключи подписи тестового профиля; в настоящей домашней папке не удаляет ничего и никогда. */
        fun clearTestKeys() {
            ensure()
            check(!isRealHome()) { "user.home указывает на настоящую домашнюю папку — тест остановлен" }
            FileTree.delete(File(System.getProperty("user.home"), ".config/wexdesktop/keys"))
        }
    }
}
