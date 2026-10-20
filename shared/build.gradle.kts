/** Общий интерфейс и движки WEX для JVM и Android. Платформенный код — в desktopMain и androidMain. */
plugins {
    // Kotlin Gradle plugin уже на classpath из buildSrc — версию не указываем
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)

    jvm("desktop")

    android {
        namespace = "org.wexclient.shared"
        compileSdk = 37
        // java.nio.file (VFS целиком на нём) есть в Android с API 26
        minSdk = 26
        withHostTest {}
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(libs.composeMaterial3)
            api(libs.composeMaterialIconsExtended)
            api(libs.kotlinxCoroutines)

            // MVVM + навигация + внедрение зависимостей
            api(libs.lifecycleViewmodelCompose)
            api(libs.lifecycleRuntimeCompose)
            api(libs.navigation3Ui)
            api(libs.lifecycleViewmodelNavigation3)
            api(project.dependencies.platform(libs.koinBom))
            api(libs.koinCore)
            api(libs.koinCompose)
            api(libs.koinComposeViewmodel)
            api(libs.koinComposeNavigation3)

            // Движки APK/DEX — обычные JVM-библиотеки, работают и на Android
            api("org.smali:dexlib2:2.5.2")
            // DEX ⇄ smali и DEX → .class без сторонних библиотек (settings.gradle.kts: includeBuild)
            api("com.tim:clazzpatcher:1.0")
            api("io.github.skylot:jadx-core:1.5.6")
            api("io.github.skylot:jadx-dex-input:1.5.6")
            api("com.android.tools.build:apksig:8.5.0")
            // Наборы split-APK (.apks/.xapk/.apkm) → один APK: слияние resources.arsc и чистка манифеста
            api("io.github.reandroid:ARSCLib:1.4.0")
            api("org.benf:cfr:0.152")
            api("org.vineflower:vineflower:1.12.0")
            api("org.bitbucket.mstrobel:procyon-compilertools:0.6.0")
            api("de.femtopedia.dex2jar:dex-tools:2.4.38")
            api("de.femtopedia.dex2jar:dex-translator:2.4.38")
            // Markdown: CommonMark + GitHub (таблицы, зачёркивание, задачи, ссылки), YAML-шапка Obsidian
            api("org.commonmark:commonmark:0.30.0")
            api("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
            api("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
            api("org.commonmark:commonmark-ext-task-list-items:0.30.0")
            api("org.commonmark:commonmark-ext-autolink:0.30.0")
            api("org.commonmark:commonmark-ext-yaml-front-matter:0.30.0")
            api("com.google.code.gson:gson:2.14.0")
            api("org.ow2.asm:asm-util:9.10.1")
        }
        val desktopMain by getting {
            dependencies {
                api(compose.desktop.common)
                // Dispatchers.Main на десктопе (AWT EDT) для фоновых скоупов вне композиции
                api(libs.kotlinxCoroutinesSwing)
                // Встроенная консоль: PTY (pty4j) и эмулятор xterm (ядро JediTerm), отрисовка — своя на Compose
                api("org.jetbrains.pty4j:pty4j:0.13.13")
                api("org.jetbrains.jediterm:jediterm-core:3.72")
                // Запрос фокуса у X-сервера (util/XFocus.kt)
                api("net.java.dev.jna:jna:5.14.0")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // Жесты и окна интерфейса — через тестовый движок Compose (без окна на экране)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
                // Тестам нужна отрисовка Skia (раскладки текста, картинки) — нативная часть под эту систему
                implementation(compose.desktop.currentOs)
                // smali/baksmali — сборка фикстур в тестах; в программе smali ⇄ DEX делает clazzpatcher
                implementation("org.smali:baksmali:2.5.2")
                implementation("org.smali:smali:2.5.2")
            }
        }
        androidMain.dependencies {
            api(libs.kotlinxCoroutinesAndroid)
            api(libs.androidxActivityCompose)
            // SVG (иконки AppImage, картинки в Markdown) — на десктопе это умеет Skia
            implementation("com.caverock:androidsvg-aar:1.4")
            // Миниатюра из EXIF снимка без декодирования всего JPEG
            implementation("androidx.exifinterface:exifinterface:1.4.1")
            // zip/APK как файловая система (в Android нет JDK zipfs): тот же zipfs из JDK 7, лицензия BSD
            implementation("com.github.marschall:zipfilesystem-standalone:1.0.1")
            api(libs.koinAndroid)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
        )
    }
}

tasks.named<Test>("desktopTest") {
    // Менеджеры истории/закладок/настроек пишут в ~/.config/wexdesktop:
    // тесты не должны трогать настоящий профиль пользователя
    val testHome = layout.buildDirectory.dir("test-home").get().asFile
    systemProperty("user.home", testHome.absolutePath)
    // Остальное настоящее (корзины других разделов, настройки Gear Lever) тесты не видят
    systemProperty("wex.isolated", "true")
    // Доступ к X11-пиру фокуса AWT (util/SwingLeaks.kt)
    jvmArgs("--add-exports=java.desktop/sun.awt.X11=ALL-UNNAMED")
    doFirst { testHome.mkdirs() }
}
