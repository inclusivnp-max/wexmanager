package org.wexclient.android.nav

import androidx.navigation3.runtime.NavKey

/** Экраны приложения (Navigation 3): стек — в [MainViewModel]. */
sealed interface WexRoute : NavKey {
    /** Нет доступа к общему хранилищу — объясняем и просим. */
    data object Storage : WexRoute

    /** Файловый менеджер: две панели. */
    data object Files : WexRoute
}
