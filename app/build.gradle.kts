plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    application

    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    // Project "app" depends on project "utils". (Project paths are separated with ":", so ":utils" refers to the top-level "utils" project.)
    implementation(project(":utils"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // Dispatchers.Main на десктопе (AWT EDT) для фоновых скоупов вне композиции
    implementation(libs.kotlinxCoroutinesSwing)

    // MT Manager Core Engines
    implementation("org.smali:dexlib2:2.5.2")
    // smali/baksmali — только тестам (сборка фикстур); в программе smali ⇄ DEX делает clazzpatcher
    testImplementation("org.smali:baksmali:2.5.2")
    testImplementation("org.smali:smali:2.5.2")
    // DEX ⇄ smali и DEX → .class без сторонних библиотек (settings.gradle.kts: includeBuild)
    implementation("com.tim:clazzpatcher:1.0")
    implementation("io.github.skylot:jadx-core:1.5.6")
    implementation("io.github.skylot:jadx-dex-input:1.5.6")
    implementation("com.android.tools.build:apksig:8.5.0")
    // Наборы split-APK (.apks/.xapk/.apkm) → один APK: слияние resources.arsc и чистка манифеста (как в APKEditor)
    implementation("io.github.reandroid:ARSCLib:1.4.0")

    // CFR Java decompiler (alternative Java engine: DEX -> class via dex2jar -> Java via CFR)
    implementation("org.benf:cfr:0.152")
    // Vineflower Java decompiler (maintained Fernflower successor)
    implementation("org.vineflower:vineflower:1.12.0")
    // Procyon Java decompiler (strong with enums, string-switch, annotations)
    implementation("org.bitbucket.mstrobel:procyon-compilertools:0.6.0")
    implementation("de.femtopedia.dex2jar:dex-tools:2.4.38")
    implementation("de.femtopedia.dex2jar:dex-translator:2.4.38")
    // Markdown: разбор CommonMark + GitHub (таблицы, зачёркивание, задачи, ссылки), YAML-шапка Obsidian
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.30.0")
    implementation("org.commonmark:commonmark-ext-yaml-front-matter:0.30.0")
    // Разбор вывода ripgrep --json (та же версия, что уже приходит с jadx)
    implementation("com.google.code.gson:gson:2.14.0")
    // Листинг байт-кода .class (Textifier); та же версия, что приходит с dex2jar
    implementation("org.ow2.asm:asm-util:9.10.1")

    // Встроенная консоль: PTY (pty4j) и эмулятор xterm (ядро JediTerm), отрисовка — своя на Compose
    implementation("org.jetbrains.pty4j:pty4j:0.13.13")
    implementation("org.jetbrains.jediterm:jediterm-core:3.72")

    // Cross-platform runtime for Windows users
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.18")

    testImplementation(kotlin("test"))
}

/**
 * Доступ к X11-пиру фокуса AWT: закрытое окно из него убирает util/SwingLeaks.kt — под Wayland сам AWT
 * этого не делает, и последний закрытый диалог оставался в памяти целиком.
 */
val awtX11Exports = "--add-exports=java.desktop/sun.awt.X11=ALL-UNNAMED"

/**
 * glibc не возвращает системе память, освобождённую в аренах malloc (Skia, драйвер, JNA — у каждого потока своя
 * арена): за три часа работы там лежало 288 МБ свободного (замер malloc_trim на живом процессе). JVM раз в минуту
 * отдаёт их сама, в своём потоке. Только Linux с glibc — на Windows флаг не нужен.
 */
val trimNativeHeap = "-XX:TrimNativeHeapInterval=60000"

/**
 * skiko (FrameWatcher) каждые 30 с, если за них нарисовано больше 1000 кадров, зовёт System.gc() — так он
 * освобождает нативную память Skia. У G1 это полная сборка с остановкой всех потоков: 33 мс рывка раз в
 * полминуты при анимированном фоне или консоли. С этим флагом та же сборка идёт параллельно (паузы 2–4 мс, изредка до 14),
 * а объекты Skia освобождаются так же. Касается и NativeMemory.releaseSoon.
 */
val concurrentExplicitGc = "-XX:+ExplicitGCInvokesConcurrent"

/**
 * В простое (нет кадров — FrameWatcher не срабатывает, аллокаций нет) сборка не приходит годами, и нативная
 * память Skia от закрытых картинок и окон так и висит. После 2 минут без единой сборки — полная сборка:
 * она же сжимает кучу (параллельный цикл G1 её не отдаёт). Пауза ~30 мс приходится на простой, когда её
 * некому заметить; в работе сборки идут постоянно, и эта не срабатывает.
 * Замер (простой с окном в фокусе): куча 184 → 57 МБ, RSS 674 → 566 МБ.
 */
val idleGc = listOf("-XX:G1PeriodicGCInterval=120000", "-XX:-G1PeriodicGCInvokesConcurrent")

/**
 * G1 по умолчанию расширяет кучу, когда сборка занимает больше ~8 % времени: всплеск при старте (классы,
 * шрифты, первая папка) раздувал её до 180 МБ при 25–30 МБ живых данных, и так она и оставалась.
 * С 20 % — 57 МБ, паузы те же (замер со спиннером в консоли: RSS 696 → 591 МБ).
 */
val heapGrowth = "-XX:GCTimeRatio=4"

/**
 * Переменные окружения, которые нужны процессу до старта JVM (флагом их не задать):
 *  - MALLOC_ARENA_MAX=2: glibc заводит арену malloc чуть ли не на каждый поток (до 8 × ядер). В 78 аренах
 *    живого процесса лежало 510 МБ свободной памяти, которую ни malloc_trim, ни TrimNativeHeap не вернули —
 *    куски разбросаны по аренам. С двумя аренами на стенде: 336 → 199 МБ malloc, CPU тот же.
 *  - __GL_YIELD=USLEEP: драйвер NVIDIA ждёт glFinish после vsync (так делает skiko) в холостом цикле
 *    на clock_gettime — четверть загрузки потока окна. С USLEEP он спит. Прочим драйверам всё равно.
 * Заданное пользователем не перебиваем; исходное значение — в WEX_ORIG_* для программ из консоли (ChildEnv).
 */
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

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
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

// ---------------------------------------------------------------- пакеты для друзей
//
// Внутрь пакета кладётся своя Java — ставить получателю ничего не нужно.
//   ./gradlew :app:packageApp    Linux:   build/packages/WEXdesktop-linux-x64.tar.gz (распаковать и запустить bin/WEXdesktop)
//                                Windows: build/packages/WEXdesktop-1.0.0.msi (обычный установщик)
// jpackage собирает только под ту систему, где запущен: пакет для Windows собирается на Windows.

private val appVersion = "1.0.0"

// jpackage берёт список библиотек из манифеста главного jar
tasks.named<Jar>("jar") {
    val libraries = configurations.runtimeClasspath.map { files -> files.joinToString(" ") { it.name } }
    manifest {
        attributes("Main-Class" to "wexdesktop", "Class-Path" to libraries)
    }
}

private val windowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/** Библиотеки для пакета: отрисовка чужой системы в него не едет (это десятки мегабайт). */
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

/** Linux: один файл, который можно просто отдать — распаковал и запустил. */
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
    description = "Готовый пакет для друзей: tar.gz на Linux, .msi на Windows"
    dependsOn(if (windowsHost) packageBinary else packageTar)
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("WEXdesktop")
    archiveClassifier.set("all")
    archiveVersion.set("1.0.0")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            // Точка входа — wexdesktop (@file:JvmName в WexLauncher.kt), а не AppKt:
            // со старым именем fat jar не запускался
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

tasks.register<JavaExec>("runTestApk") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.wexclient.app.TestRunnerKt")
}

