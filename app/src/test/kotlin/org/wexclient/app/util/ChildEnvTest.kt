package org.wexclient.app.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ChildEnvTest {
    @Test
    fun `launcher overrides are undone for child processes`() {
        // Сессия без JAVA_HOME/JAVA_OPTS, WEX запущен лаунчером на JDK 26
        val env = ChildEnv.userEnv(
            mapOf(
                "PATH" to "/usr/bin",
                "JAVA_HOME" to "/usr/lib/jvm/java-26-openjdk",
                "JAVA_OPTS" to "--enable-native-access=ALL-UNNAMED -XX:AOTCacheOutput=/home/u/.cache/wexdesktop/app.aot ",
                "_JAVA_AWT_WM_NONREPARENTING" to "1",
                "WEX_ORIG_JAVA_HOME" to "",
                "WEX_ORIG_JAVA_OPTS" to "",
                "WEX_ORIG__JAVA_AWT_WM_NONREPARENTING" to ""
            )
        )
        assertEquals(mapOf("PATH" to "/usr/bin"), env)

        // Свои значения пользователя возвращаются, в том числе пустые
        val own = ChildEnv.userEnv(
            mapOf(
                "JAVA_HOME" to "/usr/lib/jvm/java-26-openjdk",
                "JAVA_OPTS" to "--enable-native-access=ALL-UNNAMED -Xmx2g",
                "_JAVA_AWT_WM_NONREPARENTING" to "1",
                "WEX_ORIG_JAVA_HOME" to "=/usr/lib/jvm/java-17-openjdk",
                "WEX_ORIG_JAVA_OPTS" to "=-Xmx2g",
                "WEX_ORIG__JAVA_AWT_WM_NONREPARENTING" to "="
            )
        )
        assertEquals(
            mapOf(
                "JAVA_HOME" to "/usr/lib/jvm/java-17-openjdk",
                "JAVA_OPTS" to "-Xmx2g",
                "_JAVA_AWT_WM_NONREPARENTING" to ""
            ),
            own
        )
    }

    @Test
    fun `without launcher only the wm flag is dropped`() {
        val env = ChildEnv.userEnv(mapOf("JAVA_HOME" to "/opt/jdk", "JAVA_OPTS" to "-Xmx1g", "_JAVA_AWT_WM_NONREPARENTING" to "1"))
        assertEquals(mapOf("JAVA_HOME" to "/opt/jdk", "JAVA_OPTS" to "-Xmx1g"), env)
    }

    @Test
    fun `shell encoding used by the launcher`() {
        // Та же запись, что в ~/.local/bin/wexdesktop, затем подмена, как для JDK 26
        val script = """
            export WEX_ORIG_JAVA_HOME="${'$'}{JAVA_HOME+=${'$'}JAVA_HOME}"
            export WEX_ORIG_JAVA_OPTS="${'$'}{JAVA_OPTS+=${'$'}JAVA_OPTS}"
            export WEX_ORIG__JAVA_AWT_WM_NONREPARENTING="${'$'}{_JAVA_AWT_WM_NONREPARENTING+=${'$'}_JAVA_AWT_WM_NONREPARENTING}"
            export _JAVA_AWT_WM_NONREPARENTING=1
            export JAVA_HOME=/usr/lib/jvm/java-26-openjdk
            export JAVA_OPTS="-XX:AOTCache=/tmp/app.aot ${'$'}JAVA_OPTS"
            env -0
        """.trimIndent()

        fun launch(vararg vars: Pair<String, String?>): Map<String, String> {
            val pb = ProcessBuilder("sh", "-c", script)
            pb.environment().keys.removeIf { it.startsWith("WEX_") || "JAVA" in it }
            for ((k, v) in vars) if (v != null) pb.environment()[k] = v
            val p = pb.start()
            val out = p.inputStream.readAllBytes().decodeToString()
            assertEquals(0, p.waitFor())
            val launched = out.split(Char(0)).filter { it.isNotEmpty() }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            return ChildEnv.userEnv(launched).filterKeys { "JAVA" in it || it.startsWith("WEX_") }
        }

        assertEquals(emptyMap(), launch())
        assertEquals(
            mapOf("JAVA_HOME" to "", "JAVA_OPTS" to "-Da=\"x y\" -Xmx2g", "_JAVA_AWT_WM_NONREPARENTING" to "1"),
            launch("JAVA_HOME" to "", "JAVA_OPTS" to "-Da=\"x y\" -Xmx2g", "_JAVA_AWT_WM_NONREPARENTING" to "1")
        )
    }
}
