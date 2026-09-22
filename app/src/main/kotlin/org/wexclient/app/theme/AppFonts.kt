package org.wexclient.app.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

object AppFonts {
    val JetBrainsMono: FontFamily by lazy {
        try {
            FontFamily(
                Font("fonts/JetBrainsMono-Regular.ttf", FontWeight.Normal),
                Font("fonts/JetBrainsMono-Bold.ttf", FontWeight.Bold)
            )
        } catch (e: Throwable) {
            FontFamily.Monospace
        }
    }
}
