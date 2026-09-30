package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import org.wexclient.app.ui.WexDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wexclient.app.apk.ApkAnalyzer
import org.wexclient.app.apk.ApkDetails
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary
import org.wexclient.app.vfs.ApkIconLoader
import org.wexclient.app.vfs.VfsEntry
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun ApkDetailDialog(
    apkEntry: VfsEntry,
    onEnterArchive: () -> Unit,
    /** Подпись с выбором схем и имени файла (SignApkDialog). */
    onSignApk: () -> Unit,
    onOpenDexEditor: () -> Unit,
    onViewManifest: () -> Unit,
    onDismiss: () -> Unit,
    /** Быстрая подпись поверх исходного файла (null — APK не на диске). */
    onSignInPlace: (() -> Unit)? = null,
    /** Подпись поверх исходного файла выбранным в меню ключом (null — APK не на диске). */
    onSignWithKey: ((org.wexclient.app.signing.SigningKey) -> Unit)? = null,
    onInstall: ((ApkDetails?) -> Unit)? = null,
    signing: Boolean = false,
    /** Меняется после подписи — данные перечитываются. */
    refreshKey: Int = 0,
    /** Итог подписи из этого окна: показывается здесь, а не в главном окне за ним. */
    notice: Pair<Boolean, String>? = null,
    onNoticeShown: () -> Unit = {}
) {
    val toast = rememberToastState()
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        if (n.first) toast.success(n.second) else toast.error(n.second)
        onNoticeShown()
    }
    var details by remember { mutableStateOf<ApkDetails?>(null) }
    var apkIcon by remember { mutableStateOf<ImageBitmap?>(ApkIconLoader.getCachedIcon(apkEntry.path, apkEntry.lastModified)) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    val keyDialogs = rememberKeyDialogsState()
    val isBundle = remember(apkEntry.name) { ApkAnalyzer.isBundle(apkEntry.name) }
    val onDisk = remember(apkEntry.path) { apkEntry.path.fileSystem === java.nio.file.FileSystems.getDefault() }

    LaunchedEffect(apkEntry.path, refreshKey) {
        withContext(Dispatchers.IO) {
            details = ApkAnalyzer.analyzePath(apkEntry.path)
            if (apkIcon == null) {
                apkIcon = ApkIconLoader.loadIcon(apkEntry.path, apkEntry.lastModified)
            }
        }
    }

    Dialog(
        onCloseRequest = onDismiss,
        onKeyEvent = latestKeyHandler { closeOnEscape(it, onDismiss) },
        state = androidx.compose.ui.window.rememberDialogState(
            size = androidx.compose.ui.unit.DpSize(1000.dp, 820.dp)
        ),
        title = "APK: ${apkEntry.name}"
    ) {
        org.wexclient.app.theme.NoctaliaTheme {
            // Без тонального подъёма: он подмешивал цвет темы, и окно выходило светлее главного
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header: App icon, Title, Version, Close button
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Large App Icon
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (apkIcon != null) {
                                Image(
                                    bitmap = apkIcon!!,
                                    contentDescription = null,
                                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp))
                                )
                            } else {
                                Icon(
                                    Icons.Default.Android,
                                    contentDescription = null,
                                    tint = NoctaliaPrimary,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(18.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = details?.appName ?: apkEntry.name.substringBeforeLast("."),
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = NoctaliaPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = NoctaliaSecondary.copy(alpha = 0.2f),
                                    border = BorderStroke(1.dp, NoctaliaSecondary.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = "v${details?.versionName ?: "—"} (${details?.versionCode ?: "—"})",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = AppFonts.JetBrainsMono,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = NoctaliaSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = apkEntry.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }

                // Content: Scrollable M3 Information Cards
                val scrollState = rememberScrollState()
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (details == null) {
                        WexSpinner(
                            modifier = Modifier.align(Alignment.Center),
                            color = NoctaliaPrimary
                        )
                    } else {
                        val d = details!!
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Проверка целостности подписи (как в MT Manager)
                            val problem = d.signatureProblem
                            if (problem != null && !isBundle) {
                                HintCard(
                                    icon = "gpp_bad",
                                    color = MaterialTheme.colorScheme.error,
                                    title = "Ошибка проверки подписи — требуется подписать",
                                    text = problem + if (onSignInPlace == null) ". APK лежит внутри архива — сначала извлеките его." else
                                        ". Без подписи Android не установит этот APK.",
                                    action = onSignInPlace?.let { cb -> (if (signing) "Подпись…" else "Подписать") to { if (!signing) cb() } },
                                    secondaryAction = "С параметрами…" to {
                                        onDismiss()
                                        onSignApk()
                                    }
                                )
                            }

                            // Block 1: Package Identity
                            M3InfoCard(
                                title = "Идентификация приложения",
                                icon = Icons.Default.Info
                            ) {
                                InfoRow(
                                    label = "Пакет:",
                                    value = d.packageName,
                                    copyable = true
                                )
                                if (!d.launcherActivity.isNullOrEmpty()) {
                                    InfoRow(
                                        label = "Главная активность:",
                                        value = d.launcherActivity,
                                        copyable = true
                                    )
                                }
                                InfoRow(
                                    label = "Компоненты:",
                                    value = "${d.activitiesCount} Активностей, ${d.permissionsCount} Разрешений"
                                )
                            }

                            // Block 2: Protector & Security Detection (Детектор защиты)
                            M3InfoCard(
                                title = "Защита и целостность",
                                icon = Icons.Default.Security
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Детектор защиты:",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (d.isProtected) Color(0xFF421C1B) else Color(0xFF1B3D2B),
                                        border = BorderStroke(1.dp, if (d.isProtected) NoctaliaDanger else NoctaliaOk)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (d.isProtected) Icons.Default.Warning else Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = if (d.isProtected) NoctaliaDanger else NoctaliaOk,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = d.protection,
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = if (d.isProtected) Color(0xFFFCA5A5) else Color(0xFF86EFAC)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                InfoRow(
                                    label = "Проверка подписи:",
                                    value = when {
                                        isBundle -> "— (набор split-APK)"
                                        d.signatureProblem == null -> "Подпись верна"
                                        else -> d.signatureProblem
                                    },
                                    icon = when {
                                        isBundle -> null
                                        d.signatureProblem == null -> Icons.Default.CheckCircle
                                        else -> Icons.Default.Cancel
                                    },
                                    iconTint = if (d.signatureProblem == null) NoctaliaOk else MaterialTheme.colorScheme.error
                                )
                                InfoRow(
                                    label = "Схема подписи:",
                                    value = d.signatureScheme
                                )
                                InfoRow(
                                    label = "Издатель ключа:",
                                    value = d.certIssuer
                                )
                                if (d.certSha256 != "—") {
                                    InfoRow(
                                        label = "SHA-256:",
                                        value = d.certSha256,
                                        copyable = true
                                    )
                                }
                            }

                            // Block 3: SDK Requirements
                            M3InfoCard(
                                title = "Требования Android SDK",
                                icon = Icons.Default.Android
                            ) {
                                InfoRow(
                                    label = "Минимум SDK:",
                                    value = "API ${d.minSdk} — ${d.minSdkName}"
                                )
                                InfoRow(
                                    label = "Целевой SDK:",
                                    value = "API ${d.targetSdk} — ${d.targetSdkName}"
                                )
                            }

                            // Block 4: File Details
                            M3InfoCard(
                                title = "Параметры файла",
                                icon = Icons.Default.FolderOpen
                            ) {
                                InfoRow(
                                    label = "Размер:",
                                    value = "${d.fileSizeFormatted} (${d.fileSizeBytes} байт)"
                                )
                                InfoRow(
                                    label = "Изменен:",
                                    value = d.lastModifiedFormatted
                                )
                                InfoRow(
                                    label = "Путь:",
                                    value = apkEntry.path.toString(),
                                    copyable = true
                                )
                            }
                        }
                    }
                }

                // Action Buttons Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        // Options Dropdown Menu Button
                        Box {
                            OutlinedButton(
                                onClick = { showOptionsMenu = true },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = NoctaliaPrimary
                                ),
                                border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.5f)),
                                modifier = Modifier.height(42.dp)
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Опции", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                            }

                            WexDropdownMenu(
                                expanded = showOptionsMenu,
                                onDismissRequest = { showOptionsMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("DEX Редактор+") },
                                    leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                                    onClick = {
                                        showOptionsMenu = false
                                        onDismiss()
                                        onOpenDexEditor()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Просмотр AndroidManifest.xml") },
                                    leadingIcon = { Icon(Icons.Default.Android, contentDescription = null) },
                                    onClick = {
                                        showOptionsMenu = false
                                        onDismiss()
                                        onViewManifest()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Подписать с параметрами…") },
                                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                    onClick = {
                                        showOptionsMenu = false
                                        onDismiss()
                                        onSignApk()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Скопировать всю информацию об APK") },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                    onClick = {
                                        showOptionsMenu = false
                                        details?.let { d ->
                                            val summary = """
                                                Приложение: ${d.appName}
                                                Пакет: ${d.packageName}
                                                Версия: v${d.versionName} (${d.versionCode})
                                                Защита: ${d.protection}
                                                Подпись: ${d.signatureScheme} (${d.certIssuer})
                                                SHA-256: ${d.certSha256}
                                                Min SDK: API ${d.minSdk} (${d.minSdkName})
                                                Target SDK: API ${d.targetSdk} (${d.targetSdkName})
                                                Размер: ${d.fileSizeFormatted}
                                            """.trimIndent()
                                            val sel = StringSelection(summary)
                                            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        // Подпись: меню выбора ключа (свои и встроенные), экспорт/импорт ключей
                        SignKeyButton(
                            signing = signing,
                            details = details,
                            isBundle = isBundle,
                            onSignWithKey = onSignWithKey,
                            onOpenSignDialog = {
                                onDismiss()
                                onSignApk()
                            },
                            dialogs = keyDialogs
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        if (onInstall != null) {
                            Button(
                                onClick = { onInstall(details) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NoctaliaSecondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                ),
                                modifier = Modifier.height(42.dp)
                            ) {
                                Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Установить", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.width(10.dp))
                        }

                        // Enter Archive Button (MT Manager Style)
                        Button(
                            onClick = {
                                onDismiss()
                                onEnterArchive()
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NoctaliaPrimary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Открыть архив", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
            Box(modifier = Modifier.fillMaxSize()) { ToastHost(toast) }
            KeyDialogs(
                state = keyDialogs,
                exportDir = if (onDisk) apkEntry.path.toAbsolutePath().parent?.toFile() else null
            )
    }
}
}

@Composable
private fun M3InfoCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = NoctaliaPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = NoctaliaPrimary
                )
            }

            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    copyable: Boolean = false,
    icon: ImageVector? = null,
    iconTint: Color = NoctaliaPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp)
        )

        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (copyable) AppFonts.JetBrainsMono else null,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (copyable) {
            IconButton(
                onClick = {
                    val sel = StringSelection(value)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Копировать",
                    tint = NoctaliaSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
