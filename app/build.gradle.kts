import java.util.zip.ZipFile

plugins {
    id("buildsrc.convention.kotlin-jvm")

    application

    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)

    // Windows-библиотека включается в дистрибутив при сборке на Linux.
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.150.1")
}

/** Доступ к X11-пиру AWT для освобождения закрытых окон в util/SwingLeaks.kt. */
val awtX11Exports = "--add-exports=java.desktop/sun.awt.X11=ALL-UNNAMED"

/** Периодический возврат свободной нативной памяти glibc. Только для Linux. */
val trimNativeHeap = "-XX:TrimNativeHeapInterval=60000"

/** Обрабатывать вызовы System.gc() из Skiko параллельным циклом G1. */
val concurrentExplicitGc = "-XX:+ExplicitGCInvokesConcurrent"

/** Периодическая сборка G1 в простое освобождает нативные ресурсы Skia и сокращает кучу. */
val idleGc = listOf("-XX:G1PeriodicGCInterval=120000", "-XX:-G1PeriodicGCInvokesConcurrent")

/** Порог затрат G1 на сборку мусора перед расширением кучи. */
val heapGrowth = "-XX:GCTimeRatio=4"

/** Ограничения арен glibc и ожидания vsync в драйвере NVIDIA задаются до запуска JVM.
 * Пользовательские значения сохраняются в WEX_ORIG_* для дочерних процессов (ChildEnv). */
val nativeEnv = mapOf("MALLOC_ARENA_MAX" to "2", "__GL_YIELD" to "USLEEP")

tasks.named<CreateStartScripts>("startScripts") {
    // Копия: лямбда doLast не может держать ссылку на сам скрипт (configuration cache)
    val env = nativeEnv.toMap()
    doLast {
        val script = unixScript
        val exports = env.entries.joinToString("\n", postfix = "\n") { (k, v) ->
            "[ -z \"\${WEX_ORIG_$k+x}\" ] && export WEX_ORIG_$k=\"\${$k+=\$$k}\"\nexport $k=\"\${$k:-$v}\""
        }
        val marker = "\nexec \"\$JAVACMD\" \"\$@\""
        val text = script.readText()
        check(marker in text) { "Не нашлась строка запуска Java в ${script.name}" }
        script.writeText(text.replace(marker, "\n# Окружение процесса WEX (см. nativeEnv в build.gradle.kts)\n$exports$marker"))
    }
}

/**
 * С Compose 1.12 пакеты JetBrains для десктопа (org.jetbrains.compose.*, org.jetbrains.androidx.*) — пустые
 * оболочки над одноимёнными jar от androidx. В папку lib обе копии не лягут (одно имя), и копия без классов
 * не нужна вовсе — jar, в которых нет ничего, кроме META-INF, туда не кладём.
 */
distributions {
    main {
        contents {
            eachFile {
                val f = file
                // Оболочка — только META-INF; нативные jar skiko (без классов, но с .so/.dll) не трогаем
                if (f.extension == "jar" && ZipFile(f).use { z -> z.entries().asSequence().all { it.name.startsWith("META-INF/") } }) exclude()
            }
        }
    }
}

application {
    mainClass = "wexdesktop"
    applicationDefaultJvmArgs = listOf(
        "-Dskiko.renderApi=OPENGL",
        "-Dcompose.swing.render.on.graphics=true",
        "-Dawt.useSystemAAFontSettings=on",
        "-Dswing.aatext=true",
        // Пути с кириллицей/иероглифами и буфер обмена не должны зависеть от локали запуска
        "-Dfile.encoding=UTF-8",
        "-Dsun.jnu.encoding=UTF-8",
        awtX11Exports,
        trimNativeHeap,
        concurrentExplicitGc,
        heapGrowth
    ) + idleGc
}

tasks.withType<Test>().configureEach {
    // Менеджеры истории/закладок/настроек пишут в ~/.config/wexdesktop:
    // тесты не должны трогать настоящий профиль пользователя
    val testHome = layout.buildDirectory.dir("test-home").get().asFile
    systemProperty("user.home", testHome.absolutePath)
    // Остальное настоящее (корзины других разделов, настройки Gear Lever) тесты не видят
    systemProperty("wex.isolated", "true")
    jvmArgs(awtX11Exports)
    doFirst { testHome.mkdirs() }
}

tasks.withType<JavaExec>().configureEach {
    val nativeEnvCopy = nativeEnv.toMap()
    environment("_JAVA_AWT_WM_NONREPARENTING", "1")
    nativeEnvCopy.forEach { (k, v) -> if (System.getenv(k) == null) environment(k, v) }
    systemProperty("skiko.renderApi", "OPENGL")
    systemProperty("compose.swing.render.on.graphics", "true")
}

// Дистрибутивы со встроенной Java. jpackage собирает пакет для текущей ОС.

private val appVersion = "1.0.0"

// jpackage берёт список библиотек из манифеста главного jar
tasks.named<Jar>("jar") {
    val libraries = configurations.runtimeClasspath.map { files -> files.joinToString(" ") { it.name } }
    manifest {
        attributes("Main-Class" to "wexdesktop", "Class-Path" to libraries)
    }
}

private val windowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/** Зависимости дистрибутива без нативного Skiko для другой ОС. */
val packageLibs = tasks.register<Sync>("packageLibs") {
    dependsOn(tasks.named("installDist"))
    from(layout.buildDirectory.dir("install/app/lib"))
    into(layout.buildDirectory.dir("packageLibs"))
    if (windowsHost) exclude("skiko-awt-runtime-linux-*.jar") else exclude("skiko-awt-runtime-windows-*.jar")
}

val packageBinary = tasks.register<Exec>("packageBinary") {
    group = "distribution"
    description = "Программа со своей Java внутри (jpackage)"
    dependsOn(packageLibs)

    val jpackage = javaToolchains.launcherFor(java.toolchain).get()
        .metadata.installationPath.file("bin/jpackage").asFile.absolutePath
    val input = layout.buildDirectory.dir("packageLibs").get().asFile
    val outDir = layout.buildDirectory.dir("packages").get().asFile
    val icon = project.file(
        if (windowsHost) "src/main/resources/icons/wexdesktop.ico" else "src/main/resources/icons/wexdesktop.png"
    )

    doFirst {
        outDir.mkdirs()
        File(outDir, "WEXdesktop").deleteRecursively()
    }

    val options = mutableListOf(
        jpackage,
        "--type", if (windowsHost) "msi" else "app-image",
        "--name", "WEXdesktop",
        "--app-version", appVersion,
        "--vendor", "WEX",
        "--description", "Двухпанельный файловый менеджер с инструментами APK",
        "--input", input.absolutePath,
        "--main-jar", "app.jar",
        "--main-class", "wexdesktop",
        "--dest", outDir.absolutePath,
        // Отражение в jadx, dexlib2 и apksig ломается на урезанной Java — кладём её целиком
        "--add-modules", "ALL-MODULE-PATH",
        "--java-options", "-Dskiko.renderApi=OPENGL",
        "--java-options", "-Dcompose.swing.render.on.graphics=true",
        "--java-options", "-Dawt.useSystemAAFontSettings=on",
        "--java-options", "-Dswing.aatext=true",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dsun.jnu.encoding=UTF-8",
        "--java-options", awtX11Exports,
        "--java-options", concurrentExplicitGc,
        "--java-options", heapGrowth,
        "--java-options", idleGc[0],
        "--java-options", idleGc[1]
    )
    if (!windowsHost) options += listOf("--java-options", trimNativeHeap)
    if (icon.isFile) options += listOf("--icon", icon.absolutePath)
    if (windowsHost) {
        options += listOf(
            "--win-menu", "--win-menu-group", "WEXdesktop",
            "--win-shortcut", "--win-dir-chooser", "--win-per-user-install",
            // Постоянный номер: следующая версия ставится поверх, а не второй копией
            "--win-upgrade-uuid", "8f3b6d9c-2c47-4c8a-9a9d-3a1f2b6d5e10"
        )
    }
    commandLine(options)
}

/** Linux-дистрибутив со скриптом запуска и встроенной Java. */
val packageTar = tasks.register<Tar>("packageTar") {
    group = "distribution"
    description = "Архив с программой и Java внутри (Linux)"
    dependsOn(packageBinary)
    val forWindows = windowsHost
    onlyIf { !forWindows }
    compression = Compression.GZIP
    archiveFileName.set("WEXdesktop-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("packages"))
    // Скрипт запуска и короткая памятка рядом с программой
    from(project.file("packaging")) {
        into("WEXdesktop")
        filesMatching("start.sh") { permissions { unix("0755") } }
    }
    from(layout.buildDirectory.dir("packages/WEXdesktop")) {
        into("WEXdesktop")
        // Gradle кладёт в архив всё с правами 0644 — возвращаем бит запуска программам и Java внутри
        filesMatching(
            listOf("bin/**", "lib/runtime/bin/**", "lib/runtime/lib/jspawnhelper", "lib/runtime/lib/jexec", "**/*.so", "**/*.so.*")
        ) {
            permissions { unix("0755") }
        }
    }
}

tasks.register("packageApp") {
    group = "distribution"
    description = "Дистрибутив со встроенной Java: tar.gz для Linux, MSI для Windows"
    dependsOn(if (windowsHost) packageBinary else packageTar)
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("WEXdesktop")
    archiveClassifier.set("all")
    archiveVersion.set("1.0.0")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            // Имя задано через @file:JvmName в WexLauncher.kt.
            "Main-Class" to "wexdesktop",
            "Implementation-Title" to "WEXdesktop",
            "Implementation-Version" to "1.0.0"
        )
    }

    from(sourceSets["main"].output)
    dependsOn(configurations["runtimeClasspath"])
    from({
        configurations["runtimeClasspath"].filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}
