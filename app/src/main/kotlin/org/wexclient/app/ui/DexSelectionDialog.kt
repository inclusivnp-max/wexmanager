package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.vfs.VfsEntry

@Composable
fun DexSelectionDialog(
    archiveOrFolderName: String,
    dexEntries: List<VfsEntry>,
    initialSelected: List<VfsEntry> = dexEntries,
    onConfirm: (List<VfsEntry>) -> Unit,
    onDismiss: () -> Unit
) {
    val sortedDexEntries = remember(dexEntries) {
        dexEntries.sortedWith(compareBy({ VfsEntry.getDexNaturalIndex(it.name) }, { it.name }))
    }

    var selectedSet by remember {
        mutableStateOf(if (initialSelected.isNotEmpty()) initialSelected.toSet() else sortedDexEntries.toSet())
    }

    val isAllSelected = selectedSet.size == sortedDexEntries.size

    ModalDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.3f)),
            modifier = Modifier.width(480.dp).dialogGlass(RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.DataObject,
                        contentDescription = null,
                        tint = NoctaliaPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DEX Редактор+",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = NoctaliaPrimary
                        )
                        Text(
                            text = "Выберите DEX файлы для редактирования ($archiveOrFolderName)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Body
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    // Select All toggle bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedSet = if (isAllSelected) emptySet() else sortedDexEntries.toSet()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isAllSelected,
                            onCheckedChange = { checked ->
                                selectedSet = if (checked) sortedDexEntries.toSet() else emptySet()
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = NoctaliaPrimary,
                                uncheckedColor = MaterialTheme.colorScheme.outline
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Выбрать все",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "${selectedSet.size} из ${sortedDexEntries.size}",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = AppFonts.JetBrainsMono),
                            color = NoctaliaSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    // List of DEX files
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        items(sortedDexEntries, key = { it.path.toString() }) { dex ->
                            val isChecked = selectedSet.contains(dex)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isChecked) NoctaliaPrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isChecked) NoctaliaPrimary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable {
                                        selectedSet = if (isChecked) {
                                            selectedSet - dex
                                        } else {
                                            selectedSet + dex
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            selectedSet = if (checked) {
                                                selectedSet + dex
                                            } else {
                                                selectedSet - dex
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = NoctaliaPrimary,
                                            uncheckedColor = MaterialTheme.colorScheme.outline
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))

                                    Icon(
                                        Icons.Default.DataObject,
                                        contentDescription = null,
                                        tint = Color(0xFFE5A93C),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))

                                    Text(
                                        text = dex.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = AppFonts.JetBrainsMono,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = if (isChecked) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Text(
                                        text = dex.formattedSize,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = AppFonts.JetBrainsMono
                                        ),
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Отмена")
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Button(
                            onClick = {
                                if (selectedSet.isNotEmpty()) {
                                    onConfirm(selectedSet.toList().sortedWith(compareBy({ VfsEntry.getDexNaturalIndex(it.name) }, { it.name })))
                                }
                            },
                            enabled = selectedSet.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Открыть (${selectedSet.size})",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
