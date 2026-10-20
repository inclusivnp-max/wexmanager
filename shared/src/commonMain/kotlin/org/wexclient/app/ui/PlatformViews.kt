package org.wexclient.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import org.wexclient.app.theme.ShaderPrefs

/** Есть чем показать HTML-страницу (десктоп — найден Chromium, Android — WebView есть всегда). */
expect val htmlPreviewAvailable: Boolean

/** Предпросмотр страницы [url] (file://): десктоп — headless Chromium, Android — WebView. */
@Composable
expect fun HtmlPreviewView(url: String, reloadKey: Int, onStatus: (String) -> Unit, modifier: Modifier = Modifier)

/** Живой фон в окошке настроек — там, где он есть (десктоп); на Android пусто. */
@Composable
expect fun ShaderPreviewLayer(prefs: ShaderPrefs, modifier: Modifier = Modifier)

/** Нужна ли настройка «Фокус окна под Wayland» (только десктоп под Wayland). */
expect val windowFocusRepairNeeded: Boolean

/** Картинка-подложка окна, которую повторяют «стеклянные» меню и диалоги; null — подложки нет. */
internal expect fun currentBackdropImage(): ImageBitmap?
