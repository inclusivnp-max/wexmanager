package org.wexclient.android.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import org.wexclient.android.storage.StorageAccess

/** Навигация верхнего уровня: стек экранов Navigation 3 переживает поворот экрана вместе с ViewModel. */
class MainViewModel(private val storage: StorageAccess) : ViewModel() {
    val backStack = mutableStateListOf<WexRoute>(if (storage.granted()) WexRoute.Files else WexRoute.Storage)

    /** Вернулись в приложение (из системных настроек доступа) — проверяем, дали ли доступ. */
    fun onResume() {
        if (backStack.lastOrNull() == WexRoute.Storage && storage.granted()) {
            backStack.clear()
            backStack += WexRoute.Files
        }
    }

    fun open(route: WexRoute) {
        if (backStack.lastOrNull() != route) backStack += route
    }

    /** false — назад некуда (системное «назад» закроет приложение). */
    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }
}
