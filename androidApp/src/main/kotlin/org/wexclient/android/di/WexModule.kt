package org.wexclient.android.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation
import org.wexclient.android.files.FilesScreen
import org.wexclient.android.files.FilesViewModel
import org.wexclient.android.nav.MainViewModel
import org.wexclient.android.nav.WexRoute
import org.wexclient.android.storage.StorageAccess
import org.wexclient.android.storage.StorageScreen
import org.wexclient.app.vfs.VfsManager

@OptIn(KoinExperimentalAPI::class)
val wexModule = module {
    // Один VFS на процесс: архивы, открытые одной панелью, видит и другая
    single { VfsManager() }
    single { StorageAccess(androidContext()) }

    viewModelOf(::MainViewModel)
    viewModelOf(::FilesViewModel)

    // Экраны Navigation 3: стек — в MainViewModel, содержимое маршрутов — здесь
    navigation<WexRoute.Storage> { StorageScreen() }
    navigation<WexRoute.Files> { FilesScreen() }
}
