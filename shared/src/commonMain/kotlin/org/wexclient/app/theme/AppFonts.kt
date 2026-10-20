package org.wexclient.app.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

object AppFonts {
    val JetBrainsMono: FontFamily by lazy {
        try {
            FontFamily(
                resourceFont("fonts/JetBrainsMono-Regular.ttf", FontWeight.Normal),
                resourceFont("fonts/JetBrainsMono-Bold.ttf", FontWeight.Bold)
            )
        } catch (e: Throwable) {
            FontFamily.Monospace
        }
    }
}

/** Шрифт из ресурсов программы (classpath): десктоп читает его оттуда, Android — из копии в кэше. */
expect fun resourceFont(path: String, weight: FontWeight): Font
