package com.tim.testutil

import java.io.File

/**
 * Тестовые фикстуры из src/test/resources/fixtures:
 * user/classes.dex (DEX пользователя), sw/classes.dex (3 switch в одном методе), gson/classes.dex,
 * gen/<имя>.dex (собираются src/test/fixtures-src/build-fixtures.sh), classes/<имя>.class и user/Main.class.
 */
object Fixtures {
    private val root: File by lazy {
        val url = Fixtures::class.java.getResource("/fixtures") ?: throw IllegalStateException("нет ресурса /fixtures")
        require(url.protocol == "file") { "ресурсы фикстур должны быть каталогом, а не $url" }
        File(url.toURI())
    }

    /** Имена DEX-фикстур: "user", "sw", "gson", "gen/<имя>". */
    val dexNames: List<String> by lazy {
        val gen = File(root, "gen").listFiles { f -> f.name.endsWith(".dex") }.orEmpty().map { "gen/" + it.name.removeSuffix(".dex") }
        listOf("user", "sw", "gson") + gen.sorted()
    }

    /** Небольшие DEX-фикстуры (без больших user/gson) — для дорогих проверок. */
    val smallDexNames: List<String> by lazy { dexNames.filter { it != "user" && it != "gson" } }

    fun dexFile(name: String): File {
        val f = if (name.startsWith("gen/")) File(root, "$name.dex") else File(root, "$name/classes.dex")
        if (!f.isFile) throw IllegalArgumentException("нет DEX-фикстуры $name (${f.path})")
        return f
    }

    fun dex(name: String): ByteArray = dexFile(name).readBytes()

    /** .class-фикстуры: user/Main.class и все .class из каталога classes. */
    val classFiles: List<File> by lazy {
        listOf(File(root, "user/Main.class")) + File(root, "classes").listFiles { f -> f.name.endsWith(".class") }.orEmpty().sortedBy { it.name }
    }
}
