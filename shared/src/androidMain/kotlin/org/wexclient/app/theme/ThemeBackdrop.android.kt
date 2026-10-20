package org.wexclient.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** На Android фон окна однотонный — живой фон Skia есть только на десктопе. */
@Composable
actual fun ThemeBackdrop(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(ThemePreferences.colorScheme.background)) {
        content()
    }
}
