@file:JvmName("wexdesktop")

import java.util.jar.JarFile

/** Имя класса задаёт WM_CLASS в X11 и должно совпадать с wexdesktop.desktop. */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "--help", "-h" -> {
            println("""
                WEX Desktop — файловый менеджер для Linux и Windows

                Запуск: WEXdesktop [--background] [путь]
                  --background, -b  Запустить без открытия окна
                  --help, -h        Показать эту справку
                  --version         Показать версию

                Путь открывается в панели. Полный выход из приложения — Ctrl+Q.
            """.trimIndent())
            return
        }
        "--version" -> {
            println("WEX Desktop ${launcherVersion()}")
            return
        }
    }
    org.wexclient.app.launchWex(args)
}

private class LauncherInfo

private fun launcherVersion(): String {
    System.getProperty("wex.version")?.let { return it }
    val source = LauncherInfo::class.java.protectionDomain.codeSource?.location ?: return "dev"
    val file = java.io.File(source.toURI())
    if (!file.isFile) return "dev"
    return JarFile(file).use { it.manifest?.mainAttributes?.getValue("Implementation-Version") } ?: "dev"
}
