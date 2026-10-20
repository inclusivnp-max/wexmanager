package org.wexclient.app.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.wexclient.app.theme.ShaderPrefs

actual val htmlPreviewAvailable: Boolean get() = true

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun HtmlPreviewView(url: String, reloadKey: Int, onStatus: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            // Страница — локальный файл: её стили, картинки и скрипты лежат рядом
            @Suppress("DEPRECATION")
            settings.allowFileAccess = true
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    title?.let(onStatus)
                }
            }
        }
    }
    LaunchedEffect(url, reloadKey) { view.loadUrl(url) }
    AndroidView(factory = { view }, modifier = modifier)
}

@Composable
actual fun ShaderPreviewLayer(prefs: ShaderPrefs, modifier: Modifier) {
}

actual val windowFocusRepairNeeded: Boolean get() = false

internal actual fun currentBackdropImage(): ImageBitmap? = null
