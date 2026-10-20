package org.wexclient.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper

actual object TextClipboard {
    private val manager: ClipboardManager
        get() = AndroidContext.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    actual fun copy(text: String) {
        // Буфер обмена — системная служба: зовём из главного потока, откуда бы ни пришёл вызов
        val put = { runCatching { manager.setPrimaryClip(ClipData.newPlainText("WEX", text)) } }
        if (Looper.myLooper() == Looper.getMainLooper()) put() else Handler(Looper.getMainLooper()).post { put() }
    }

    actual fun text(): String? = runCatching {
        manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(AndroidContext.app)?.toString()
    }.getOrNull()
}
