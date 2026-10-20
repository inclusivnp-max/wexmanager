package org.wexclient.android

import android.app.Application
import android.os.Environment
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.wexclient.android.di.wexModule
import org.wexclient.app.util.AndroidContext
import org.wexclient.app.util.AppDirs

class WexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // До всего остального: настройки WEX читают свои папки при первом обращении
        AndroidContext.app = this
        val storage = Environment.getExternalStorageDirectory().toPath()
        AppDirs.useAndroidDirs(
            userHome = storage,
            configRoot = filesDir.toPath().resolve("config"),
            cacheRoot = cacheDir.toPath(),
            // Корзина — на том же разделе, что и файлы: удаление в неё — быстрое переименование
            trash = storage.resolve(".wex-trash")
        )
        startKoin {
            androidContext(this@WexApplication)
            modules(wexModule)
        }
    }
}
