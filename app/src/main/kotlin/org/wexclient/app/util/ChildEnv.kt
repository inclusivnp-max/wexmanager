package org.wexclient.app.util

/**
 * Окружение для программ, которые запускает пользователь из WEX: консоль, скрипты,
 * внешний терминал, xdg-open.
 *
 * Лаунчер (~/.local/bin/wexdesktop) меняет JAVA_HOME (JDK 26), JAVA_OPTS (флаги AOT-кэша)
 * и _JAVA_AWT_WM_NONREPARENTING только ради самого WEX, а исходные значения сохраняет
 * в WEX_ORIG_<имя>: «=значение» — переменная была задана, пусто — её не было.
 * Без восстановления gradlew из консоли WEX запускался на JDK 26 и падал.
 */
object ChildEnv {
    private const val SAVED = "WEX_ORIG_"
    private val launcherVars = listOf("JAVA_HOME", "JAVA_OPTS", "_JAVA_AWT_WM_NONREPARENTING")

    fun userEnv(parent: Map<String, String> = System.getenv()): MutableMap<String, String> =
        HashMap(parent).also(::restore)

    fun restore(env: MutableMap<String, String>) {
        for (name in launcherVars) {
            val saved = env[SAVED + name]
            when {
                saved == null -> {
                    // Запуск без лаунчера (gradle run, release/run.sh): эту переменную WEX ставит всегда
                    if (name == "_JAVA_AWT_WM_NONREPARENTING") env.remove(name)
                }
                saved.startsWith("=") -> env[name] = saved.substring(1)
                else -> env.remove(name)
            }
        }
        env.keys.removeIf { it.startsWith(SAVED) }
    }
}

/** Запустить с окружением пользователя, а не лаунчера WEX. */
fun ProcessBuilder.withUserEnv(): ProcessBuilder = apply { ChildEnv.restore(environment()) }
