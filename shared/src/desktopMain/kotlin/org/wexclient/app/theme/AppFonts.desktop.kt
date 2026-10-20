package org.wexclient.app.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight

actual fun resourceFont(path: String, weight: FontWeight): Font = androidx.compose.ui.text.platform.Font(path, weight)
