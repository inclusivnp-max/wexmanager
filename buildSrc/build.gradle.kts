plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinGradlePlugin)
    // Плагины Android — в одном загрузчике с Kotlin (KMP-модуль :shared и :androidApp)
    implementation(libs.androidGradlePlugin)
}
