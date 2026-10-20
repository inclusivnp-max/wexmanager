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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import org.wexclient.app.dex.DexHeader
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary

@Composable
fun DexViewerDialog(
    fileName: String,
    header: DexHeader?,
    classes: List<String>,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var classSearch by remember { mutableStateOf("") }

    val filteredClasses = remember(classes, classSearch) {
        if (classSearch.isBlank()) classes
        else classes.filter { it.contains(classSearch, ignoreCase = true) }
    }

    ModalDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.dialogGlass(RoundedCornerShape(16.dp))
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 8.dp
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
                        Icons.Default.Code,
                        contentDescription = null,
                        tint = NoctaliaSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "DEX Инспектор: $fileName",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NoctaliaPrimary,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                // Tabs: Stats vs Classes
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
                                Text("Заголовок и Статистика", fontWeight = FontWeight.SemiBold)
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
                                Text("Классы (${classes.size})", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                }

                // Content
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp)
                ) {
                    if (selectedTab == 0) {
                        if (header != null) {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    DexStatRow(label = "Версия Magic", value = header.magic)
                                    DexStatRow(label = "Размер файла", value = "${header.fileSize} байт")
                                    DexStatRow(label = "Всего классов (class_defs)", value = "${header.classDefsSize}", isAccent = true)
                                    DexStatRow(label = "Всего методов (method_ids)", value = "${header.methodIdsSize}", isAccent = true)
                                    DexStatRow(label = "Всего полей (field_ids)", value = "${header.fieldIdsSize}")
                                    DexStatRow(label = "Всего прототипов (proto_ids)", value = "${header.protoIdsSize}")
                                    DexStatRow(label = "Всего строк (string_ids)", value = "${header.stringIdsSize}")
                                    DexStatRow(label = "Всего типов (type_ids)", value = "${header.typeIdsSize}")
                                    DexStatRow(label = "Контрольная сумма (Adler32)", value = "0x" + java.lang.Long.toHexString(header.checksum))
                                    DexStatRow(label = "SHA-1 Сигнатура", value = header.signature)
                                }
                            }
                        } else {
                            Text("Не удалось распарсить заголовок DEX файла", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        // Class list with search
                        Column(modifier = Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = classSearch,
                                onValueChange = { classSearch = it },
                                placeholder = { Text("Фильтр классов...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                trailingIcon = {
                                    if (classSearch.isNotEmpty()) {
                                        IconButton(onClick = { classSearch = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = null)
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NoctaliaPrimary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Найдено классов: ${filteredClasses.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(filteredClasses) { className ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = className,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 12.sp
                                                ),
                                                color = NoctaliaPrimary,
                                                modifier = Modifier.weight(1f)
                                            )

                                            IconButton(
                                                onClick = {
                                                    org.wexclient.app.util.TextClipboard.copy(className)
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
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DexStatRow(label: String, value: String, isAccent: Boolean = false) {
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
