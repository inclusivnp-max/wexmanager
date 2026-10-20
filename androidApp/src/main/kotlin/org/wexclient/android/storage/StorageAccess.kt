package org.wexclient.android.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Доступ ко всем файлам. Android 11+ — особое разрешение «Доступ ко всем файлам» (его дают в системных
 * настройках); Android 8–10 — обычные разрешения на чтение и запись хранилища.
 */
class StorageAccess(private val context: Context) {
    fun granted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Нужны ли системные настройки (Android 11+), а не обычный запрос разрешения. */
    val needsSettings: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    val legacyPermissions: Array<String> = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
}
