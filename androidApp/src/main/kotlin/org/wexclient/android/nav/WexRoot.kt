package org.wexclient.android.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.koin.compose.navigation3.koinEntryProvider
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.ui.FileChooserHost
import org.wexclient.app.ui.WindowLayers
import org.wexclient.app.ui.WindowLayersHost

/**
 * Корень приложения: тема Material 3 Expressive, стек экранов Navigation 3 (маршруты — в модуле Koin),
 * над ним — окна-инструменты (редакторы и просмотры, открытые из панелей).
 */
@OptIn(KoinExperimentalAPI::class)
@Composable
fun WexRoot(vm: MainViewModel = koinViewModel()) {
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }
    NoctaliaTheme {
        val layers = remember { WindowLayers() }
        WindowLayersHost(layers) {
            NavDisplay(
                backStack = vm.backStack,
                onBack = { vm.back() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    // У каждого экрана свои ViewModel — и уходят вместе с ним
                    rememberViewModelStoreNavEntryDecorator()
                ),
                entryProvider = koinEntryProvider()
            )
        }
        // Выбор файла по просьбе общего кода (ключ подписи, фоновая картинка, папка закладки)
        FileChooserHost()
    }
}
