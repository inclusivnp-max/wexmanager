package org.wexclient.app.util

import android.content.Context

/**
 * Контекст приложения для общего кода, которому нужен Android (буфер обмена, папки, установка APK).
 * Задаётся первым делом в Application.onCreate.
 */
object AndroidContext {
    lateinit var app: Context
}
