pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        // Ядро эмулятора терминала JediTerm (из IntelliJ) публикуется только здесь — берём из репозитория лишь его
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            content { includeGroup("org.jetbrains.jediterm") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":app")
include(":shared")
include(":androidApp")

// clazzpatcher (DEX ⇄ smali, DEX → .class) — отдельный проект рядом; путь можно переопределить свойством
// clazzpatcher.dir. Собирается вместе с WEX тем же Kotlin, модулем com.tim:clazzpatcher.
val clazzpatcherDir = file(providers.gradleProperty("clazzpatcher.dir").getOrElse("../../Tools/clazzpather-for-wex"))
includeBuild(clazzpatcherDir) {
    dependencySubstitution {
        substitute(module("com.tim:clazzpatcher")).using(project(":app"))
    }
}

rootProject.name = "WEXdesktop"
