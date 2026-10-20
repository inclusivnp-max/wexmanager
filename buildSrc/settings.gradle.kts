dependencyResolutionManagement {

    @Suppress("UnstableApiUsage")
    repositories {
        // Android Gradle Plugin публикуется только в репозитории Google
        google()
        mavenCentral()
    }

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "buildSrc"