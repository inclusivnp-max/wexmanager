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

// DEX ⇄ smali и DEX → .class. По умолчанию собираем включённые исходники;
// clazzpatcher.dir позволяет подключить отдельную рабочую копию.
val clazzpatcherDir = file(providers.gradleProperty("clazzpatcher.dir").getOrElse("third_party/clazzpatcher"))
includeBuild(clazzpatcherDir) {
    dependencySubstitution {
        substitute(module("com.tim:clazzpatcher")).using(project(":app"))
    }
}

rootProject.name = "WEXdesktop"
