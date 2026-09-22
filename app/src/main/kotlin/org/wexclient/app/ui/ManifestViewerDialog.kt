package org.wexclient.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.wexclient.app.axml.ManifestInfo
import org.wexclient.app.editor.CodeEditorView
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun ManifestViewerDialog(
    xmlContent: String,
    onDismiss: () -> Unit,
    onSave: ((String) -> Unit)? = null
) {
    val manifestInfo = remember(xmlContent) { ManifestInfo.parse(xmlContent) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Info, 1: XML Code
    // Текст редактора держим здесь: при переключении вкладок правки не теряются
    val controller = org.wexclient.app.editor.rememberCodeEditorController(xmlContent)
    val saver = rememberBackgroundSave()
    val guard = rememberCloseGuard(isDirty = { onSave != null && controller.isDirty }, onClose = onDismiss)
    TrackUnsaved("AndroidManifest.xml", onSave != null && controller.isDirty)

    Dialog(
        onCloseRequest = { guard.requestClose() },
        state = androidx.compose.ui.window.rememberDialogState(
            size = androidx.compose.ui.unit.DpSize(1400.dp, 900.dp)
        ),
        title = "AndroidManifest.xml",
        // Esc, которую не забрал редактор (поиск), закрывает окно — с вопросом о несохранённом
        onKeyEvent = latestKeyHandler { e -> closeOnEscape(e) { guard.requestClose() } }
    ) {
        org.wexclient.app.theme.NoctaliaTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Android,
                        contentDescription = null,
                        tint = NoctaliaPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AndroidManifest.xml",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NoctaliaPrimary,
                        modifier = Modifier.weight(1f)
                    )

                    // Copy XML button
                    IconButton(onClick = {
                        val sel = StringSelection(controller.text)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", tint = NoctaliaSecondary)
                    }

                    IconButton(onClick = { guard.requestClose() }) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                // Tabs: Info vs XML Code
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    indicator = { tabPositions ->
                        SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = NoctaliaPrimary
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Сводка / Инфо", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Декомпилированный XML", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                }

                // Tab Content
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp)
                ) {
                    if (selectedTab == 0) {
                        // Overview Info Card
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                InfoCardRow(label = "Package Name", value = manifestInfo.packageName.ifEmpty { "(не задан)" }, isAccent = true)
                                InfoCardRow(label = "Version Name", value = manifestInfo.versionName.ifEmpty { "-" })
                                InfoCardRow(label = "Version Code", value = manifestInfo.versionCode.ifEmpty { "-" })
                                InfoCardRow(label = "Min SDK", value = manifestInfo.minSdkVersion.ifEmpty { "-" })
                                InfoCardRow(label = "Target SDK", value = manifestInfo.targetSdkVersion.ifEmpty { "-" })
                                InfoCardRow(label = "Launcher Activity", value = manifestInfo.launcherActivity ?: "Не найден")

                                Spacer(modifier = Modifier.height(16.dp))

                                // Permissions Header
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Security, contentDescription = null, tint = NoctaliaTertiary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Разрешения (${manifestInfo.permissions.size}):",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = NoctaliaTertiary
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            items(manifestInfo.permissions) { perm ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = perm,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Активности / Activities (${manifestInfo.activities.size}):",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = NoctaliaSecondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            items(manifestInfo.activities) { act ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = act,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        ),
                                        color = if (act == manifestInfo.launcherActivity) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // XML Code View with JetBrains Mono, syntax highlighting and editing
                        CodeEditorView(
                            initialText = xmlContent,
                            language = org.wexclient.app.editor.lang.Languages.XML,
                            // Без onSave (бинарный AXML) — только просмотр
                            readOnly = onSave == null,
                            fileName = "AndroidManifest.xml",
                            onSave = onSave,
                            controller = controller,
                            backgroundSave = saver,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            if (guard.asking) {
                UnsavedChangesDialog(
                    fileName = "AndroidManifest.xml",
                    error = guard.saveError,
                    busy = guard.saving,
                    onSave = { guard.saveAndCloseAsync { done -> controller.saveInBackground(saver, done) } },
                    onDiscard = { guard.discard() },
                    onCancel = { guard.cancel() }
                )
            }
        }
    }
}
}

@Composable
private fun InfoCardRow(label: String, value: String, isAccent: Boolean = false) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = if (isAccent) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
