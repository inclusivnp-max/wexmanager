package org.wexclient.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaWarn
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsEntry
import java.nio.file.Path

enum class ArchiveFormat(val ext: String, val label: String) {
    ZIP(".zip", "ZIP (Стандарт)"),
    TAR_GZ(".tar.gz", "TAR.GZ (Linux)"),
    APK(".apk", "APK (Android)"),
    SEVEN_ZIP(".7z", "7Z (Высокое сжатие)")
}

@Composable
fun CompressDialog(
    sources: List<VfsEntry>,
    currentDirectory: Path,
    oppositeDirectory: Path,
    onDismiss: () -> Unit,
    onCompress: (targets: List<Path>, destinationFile: Path, format: ArchiveFormat) -> Unit
) {
    var selectedFormat by remember { mutableStateOf(ArchiveFormat.ZIP) }
    val defaultBaseName = remember(sources) {
        if (sources.size == 1) sources.first().name.substringBeforeLast('.') else "archive"
    }
    // Имя по умолчанию не должно совпадать ни с чем в папке (archive.zip → archive (2).zip)
    var archiveName by remember {
        mutableStateOf(
            org.wexclient.app.vfs.FileTransfers.freeName(currentDirectory, defaultBaseName + selectedFormat.ext)
                .fileName.toString()
        )
    }
    var useOppositeDir by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    val destination = remember(archiveName, useOppositeDir) {
        runCatching { (if (useOppositeDir) oppositeDirectory else currentDirectory).resolve(archiveName.trim()) }.getOrNull()
    }
    // Архив поверх того, что сжимается, — нельзя; поверх другого файла — с предупреждением
    val insideSources = destination != null && sources.any { src ->
        src.path.fileSystem === destination.fileSystem &&
            destination.toAbsolutePath().normalize().startsWith(src.path.toAbsolutePath().normalize())
    }
    val exists = remember(destination) {
        destination != null && runCatching { java.nio.file.Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)
    }
    val nameProblem = when {
        archiveName.isBlank() -> "Введите имя архива"
        '/' in archiveName || archiveName == "." || archiveName == ".." -> "Имя не может содержать «/»"
        insideSources -> "Архив не может заменить то, что в него сжимается"
        else -> null
    }

    fun updateFormat(fmt: ArchiveFormat) {
        val base = archiveName.substringBeforeLast('.')
            .let { if (it.endsWith(".tar")) it.substringBeforeLast('.') else it }
        selectedFormat = fmt
        archiveName = base + fmt.ext
    }

    fun compress() {
        val destFile = destination ?: return
        if (isProcessing || nameProblem != null) return
        isProcessing = true
        onCompress(sources.map { it.path }, destFile, selectedFormat)
    }

    ModalDialog(onDismissRequest = onDismiss) {
        // Имя архива правится сразу, Enter сжимает, Esc закрывает
        val nameFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { org.wexclient.app.editor.requestFocusSafely(nameFocus) }
        Surface(
            modifier = Modifier.width(560.dp).dialogGlass(RoundedCornerShape(16.dp))
                .onPreviewKeyEvent { dialogKeys(it, ::compress) { if (!isProcessing) onDismiss() } },
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                    // Header
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = NoctaliaPrimary.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Archive,
                                    contentDescription = null,
                                    tint = NoctaliaPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Создание архива",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = NoctaliaPrimary
                            )
                            Text(
                                text = "Выбрано элементов: ${sources.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Format selector chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Формат архива",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = NoctaliaSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ArchiveFormat.values().forEach { fmt ->
                                val isSelected = selectedFormat == fmt
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { updateFormat(fmt) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) NoctaliaPrimary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) NoctaliaPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = fmt.ext.uppercase(),
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = if (isSelected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = when (fmt) {
                                                ArchiveFormat.ZIP -> "Zip"
                                                ArchiveFormat.TAR_GZ -> "Tar.gz"
                                                ArchiveFormat.APK -> "Apk"
                                                ArchiveFormat.SEVEN_ZIP -> "7z"
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Archive File Name
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Имя архивного файла",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = NoctaliaSecondary
                        )
                        OutlinedTextField(
                            value = archiveName,
                            onValueChange = { archiveName = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            leadingIcon = {
                                Icon(Icons.Default.Inventory2, contentDescription = null, tint = NoctaliaSecondary, modifier = Modifier.size(20.dp))
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NoctaliaPrimary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }

                    // Target Location Option
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Куда сохранить архив",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = NoctaliaSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { useOppositeDir = false },
                                shape = RoundedCornerShape(8.dp),
                                color = if (!useOppositeDir) NoctaliaSecondary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, if (!useOppositeDir) NoctaliaSecondary else Color.Transparent)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = NoctaliaSecondary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("В текущую папку", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { useOppositeDir = true },
                                shape = RoundedCornerShape(8.dp),
                                color = if (useOppositeDir) NoctaliaTertiary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, if (useOppositeDir) NoctaliaTertiary else Color.Transparent)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = NoctaliaTertiary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("В соседнюю панель", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    val warning = nameProblem ?: if (exists) "Такой файл уже есть — прежний уйдёт в корзину (Ctrl+Z вернёт)" else null
                    if (warning != null) {
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (nameProblem != null) MaterialTheme.colorScheme.error else NoctaliaWarn,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isProcessing
                        ) {
                            Text("Отмена")
                        }

                        Button(
                            onClick = ::compress,
                            modifier = Modifier.weight(1.3f).height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isProcessing && nameProblem == null && destination != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NoctaliaPrimary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Сжать", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
