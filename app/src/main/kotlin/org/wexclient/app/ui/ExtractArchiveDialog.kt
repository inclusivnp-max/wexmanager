package org.wexclient.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary
import org.wexclient.app.vfs.VfsEntry
import java.nio.file.Path
import java.nio.file.Paths

@Composable
fun ExtractArchiveDialog(
    entry: VfsEntry,
    currentPath: Path,
    otherPath: Path?,
    onExtract: (destinationDir: Path) -> Unit,
    onDismiss: () -> Unit
) {
    val baseName = entry.archiveBaseName
    // Готовые варианты — пути со своей ФС (панель может быть внутри архива);
    // набранный вручную текст — путь на диске
    val subfolder = remember(currentPath, baseName) { currentPath.resolve(baseName) }
    val otherSubfolder = remember(otherPath, baseName) { otherPath?.resolve(baseName) }
    var chosen by remember { mutableStateOf<Path?>(subfolder) }
    var destinationText by remember { mutableStateOf(subfolder.toAbsolutePath().toString()) }
    fun choose(p: Path) {
        chosen = p
        destinationText = p.toAbsolutePath().toString()
    }
    var createSubfolder by remember { mutableStateOf(true) }

    fun extract() {
        val typed = destinationText.trim()
        val path = chosen ?: try {
            val home = System.getProperty("user.home")
            Paths.get(if (typed == "~" || typed.startsWith("~/")) home + typed.removePrefix("~") else typed)
                .toAbsolutePath()
        } catch (_: Exception) {
            null
        }
        if (path != null) onExtract(path)
    }

    ModalDialog(onDismissRequest = onDismiss) {
        // Путь правится сразу, Enter извлекает, Esc закрывает
        val pathFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { org.wexclient.app.editor.requestFocusSafely(pathFocus) }
        Surface(
            modifier = Modifier.width(540.dp).dialogGlass(RoundedCornerShape(16.dp))
                .onPreviewKeyEvent { dialogKeys(it, ::extract, onDismiss) },
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, NoctaliaTertiary.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = NoctaliaTertiary.copy(alpha = 0.15f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.FolderZip,
                                contentDescription = null,
                                tint = NoctaliaTertiary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Извлечение архива",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = NoctaliaTertiary
                        )
                        Text(
                            text = "${entry.name} (${entry.formattedSize})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DialogClose(onDismiss)
                }

                // Quick Destination Presets
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Быстрый выбор каталога:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = NoctaliaSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Subfolder in current pane
                        // Длинное имя архива сжимает только этот чип — «В текущую» и «В соседнюю» не срезаются
                        FilterChip(
                            selected = chosen == subfolder,
                            onClick = { choose(subfolder) },
                            modifier = Modifier.weight(1f, fill = false),
                            label = { Text("В «$baseName/»", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NoctaliaTertiary.copy(alpha = 0.2f),
                                selectedLabelColor = NoctaliaTertiary
                            )
                        )

                        // Current folder directly
                        FilterChip(
                            selected = chosen == currentPath,
                            onClick = { choose(currentPath) },
                            label = { Text("В текущую", maxLines = 1) },
                            leadingIcon = {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NoctaliaTertiary.copy(alpha = 0.2f),
                                selectedLabelColor = NoctaliaTertiary
                            )
                        )

                        // Other pane
                        if (otherPath != null && otherPath != currentPath) {
                            FilterChip(
                                selected = chosen == otherSubfolder,
                                onClick = { otherSubfolder?.let { choose(it) } },
                                label = { Text("В соседнюю", maxLines = 1) },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NoctaliaPrimary.copy(alpha = 0.2f),
                                    selectedLabelColor = NoctaliaPrimary
                                )
                            )
                        }
                    }
                }

                // Destination Path Input
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Путь для извлечения файлов",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = NoctaliaSecondary
                    )
                    OutlinedTextField(
                        value = destinationText,
                        onValueChange = {
                            destinationText = it
                            chosen = null
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(pathFocus),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Отмена")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = ::extract,
                        colors = ButtonDefaults.buttonColors(containerColor = NoctaliaTertiary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Извлечь", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
