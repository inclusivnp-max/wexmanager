/** Android-приложение на базе :shared; Kotlin-плагин встроен в AGP. */
plugins {
    id("com.android.application")
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "org.wexclient.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "client.wexmanager"
        // java.nio.file (VFS целиком на нём) есть в Android с API 26
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // Движки (jadx, декомпиляторы, smali) работают через отражение — без минификации
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Общий код (:shared) пишется под JDK: Lint ловит API новее minSdk, которых нет на старых Android
        checkDependencies = true
        checkOnly += setOf("NewApi")
        abortOnError = false
        textReport = true
    }

    packaging {
        resources {
            // У jar-библиотек движков совпадают служебные файлы — в APK они не нужны
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "module-info.class",
                "**/module-info.class",
            )
            pickFirsts += listOf("META-INF/services/**")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// dex2jar (d2j-external) несёт в себе полное ядро ASM 9 — второй его экземпляр Android не пропустит
// (дубли классов). На десктопе оба лежат в classpath и это не мешает
configurations.matching { it.name.endsWith("RuntimeClasspath") || it.name.endsWith("CompileClasspath") }.configureEach {
    exclude(group = "org.ow2.asm", module = "asm")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidxActivityCompose)
    implementation(libs.koinAndroid)
    implementation("androidx.core:core-ktx:1.17.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
