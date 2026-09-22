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
    implementation("org.smali:baksmali:2.5.2")
    implementation("org.smali:smali:2.5.2")
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
        "-Dsun.jnu.encoding=UTF-8"
    )
}

tasks.withType<Test>().configureEach {
    // Менеджеры истории/закладок/настроек пишут в ~/.config/wexdesktop:
    // тесты не должны трогать настоящий профиль пользователя
    val testHome = layout.buildDirectory.dir("test-home").get().asFile
    systemProperty("user.home", testHome.absolutePath)
    // Остальное настоящее (корзины других разделов, настройки Gear Lever) тесты не видят
    systemProperty("wex.isolated", "true")
    doFirst { testHome.mkdirs() }
}

tasks.withType<JavaExec>().configureEach {
    environment("_JAVA_AWT_WM_NONREPARENTING", "1")
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
        "--java-options", "-Dsun.jnu.encoding=UTF-8"
    )
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

