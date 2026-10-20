// Плагины Compose объявлены здесь один раз, чтобы модули (:app, :shared, :androidApp) делили один загрузчик классов.
// Kotlin и Android Gradle Plugin приходят из buildSrc (там же, в одном загрузчике)
plugins {
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}
