package org.wexclient.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import org.wexclient.app.html.BrowserSession
import org.wexclient.app.html.BrowserView
import org.wexclient.app.theme.ShaderPrefs

actual val htmlPreviewAvailable: Boolean get() = BrowserSession.findBrowser() != null

@Composable
actual fun HtmlPreviewView(url: String, reloadKey: Int, onStatus: (String) -> Unit, modifier: Modifier) {
    BrowserView(url = url, modifier = modifier, reloadKey = reloadKey, onStatus = onStatus)
}

@Composable
actual fun ShaderPreviewLayer(prefs: ShaderPrefs, modifier: Modifier) {
    val renderer = remember { ShaderBackgroundRenderer() }
    Box(modifier = modifier.fillMaxSize().observeBackgroundTouches(renderer, prefs.touch)) {
        ShaderBackground(prefs, renderer, animate = true)
        if (prefs.touch) ShaderTouchOverlay(renderer)
    }
}

actual val windowFocusRepairNeeded: Boolean get() = org.wexclient.app.util.WindowFocusRepair.needed

internal actual fun currentBackdropImage(): ImageBitmap? = BackgroundImage.current()
