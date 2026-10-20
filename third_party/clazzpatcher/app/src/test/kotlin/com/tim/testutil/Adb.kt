package com.tim.testutil

import java.io.File

/**
 * Запуск DEX в настоящем ART через adb: файлы кладутся в /data/local/tmp/clazzpatcher-test,
 * выполняются `dalvikvm64` (верификатор ART + интерпретатор/JIT) и удаляются в [cleanup].
 * Устройство задаётся `-Padb=<serial>` (свойство clazzpatcher.adb) или CLAZZPATCHER_ADB.
 */
class Adb(val serial: String) {
    private val adb: String = run {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: (System.getProperty("user.home") + "/Android/Sdk")
        File(sdk, "platform-tools/adb").takeIf { it.isFile }?.path ?: "adb"
    }

    val dir = "/data/local/tmp/clazzpatcher-test"

    fun exec(vararg args: String): Pair<Int, String> =
        JvmVerifier.run(listOf(adb, "-s", serial) + args, File(System.getProperty("java.io.tmpdir")))

    fun prepare() {
        exec("shell", "rm", "-rf", dir)
        val (code, out) = exec("shell", "mkdir", "-p", dir)
        require(code == 0) { "adb mkdir: $out" }
    }

    /** Выполнить `main` класса [mainClass] из [dex]; вывод dalvikvm (stdout+stderr) и код возврата. */
    fun runDex(dex: File, mainClass: String): Pair<Int, String> {
        val remote = "$dir/${dex.name}"
        val (pc, pout) = exec("push", dex.path, remote)
        require(pc == 0) { "adb push: $pout" }
        return exec("shell", "dalvikvm64", "-cp", remote, mainClass)
    }

    /**
     * Верификация DEX настоящим ART без запуска: `dex2oat --compiler-filter=verify
     * --abort-on-hard-verifier-error`. Код 0 — ни одной жёсткой ошибки верификатора;
     * иначе — причины из logcat. Методы, не прошедшие проверку блокировок («failed lock
     * verification»: работают, но медленнее — d8 такого не порождает), тоже попадают в вывод.
     */
    fun verifyDex(name: String, dexes: List<File>): Pair<Int, String> {
        val remote = "$dir/$name"
        exec("shell", "mkdir", "-p", remote)
        val args = dexes.map { d ->
            val (c, o) = exec("push", d.path, "$remote/${d.name}")
            require(c == 0) { "adb push: $o" }
            "--dex-file=$remote/${d.name}"
        }
        exec("logcat", "-c")
        val (code, _) = exec(*(listOf("shell", "/apex/com.android.art/bin/dex2oat64") + args +
            listOf("--oat-file=$remote/out.odex", "--compiler-filter=verify", "--abort-on-hard-verifier-error")).toTypedArray())
        val log = exec("logcat", "-d").second.lineSequence()
            .filter { (code != 0 && ("failed to verify" in it || "Verification error" in it)) || "failed lock verification" in it }
            .take(20).joinToString("\n")
        exec("shell", "rm", "-rf", remote)
        return code to log
    }

    fun cleanup() {
        exec("shell", "rm", "-rf", dir)
    }

    companion object {
        fun configured(): Adb? =
            (System.getProperty("clazzpatcher.adb")?.takeIf { it.isNotBlank() } ?: System.getenv("CLAZZPATCHER_ADB")?.takeIf { it.isNotBlank() })
                ?.let { Adb(it) }
    }
}
