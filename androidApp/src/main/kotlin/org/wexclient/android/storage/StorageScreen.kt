package org.wexclient.android.storage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.wexclient.app.theme.NoctaliaPrimary

/**
 * Первый запуск: файловому менеджеру нужен доступ ко всем файлам — объясняем зачем и ведём в настройки.
 * Дали ли его, проверяет MainViewModel при возвращении в приложение.
 */
@Composable
fun StorageScreen() {
    val storage: StorageAccess = koinInject()
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(64.dp))
        Text("Доступ к файлам", style = MaterialTheme.typography.headlineSmall, color = NoctaliaPrimary)
        Text(
            "WEX — файловый менеджер: ему нужен доступ ко всем файлам, чтобы показывать папки, копировать, " +
                "распаковывать и править APK. Без этого видна только папка самого приложения.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = {
            if (storage.needsSettings) settings.launch(storage.settingsIntent()) else legacy.launch(storage.legacyPermissions)
        }) {
            Text("Разрешить доступ")
        }
    }
}
