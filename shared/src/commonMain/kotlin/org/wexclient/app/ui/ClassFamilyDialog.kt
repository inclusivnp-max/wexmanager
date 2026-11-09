package org.wexclient.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.wexclient.app.theme.AppFonts

internal data class ClassFamilyItem(val id: String, val name: String, val detail: String)

/** Один выбор для DEX и .class; весь класс открывается без ручного выбора десятков частей. */
@Composable
internal fun ClassFamilyDialog(
    name: String,
    members: List<ClassFamilyItem>,
    partHint: String,
    onPart: (String) -> Unit,
    onWhole: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember(name, members) { mutableStateOf("") }
    val visible = remember(members, query) { members.filter { it.name.contains(query, ignoreCase = true) || it.detail.contains(query, ignoreCase = true) } }
    AlertDialog(
        modifier = Modifier.widthIn(max = 760.dp).testTag("class-family-dialog"),
        onDismissRequest = onDismiss,
        title = { Text("Открыть класс") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, fontFamily = AppFonts.JetBrainsMono, fontWeight = FontWeight.SemiBold)
                Text("Частей: ${members.size}. $partHint", style = MaterialTheme.typography.bodySmall)
                if (members.size > 8) GrailCompactField(
                    value = query, onChange = { query = it }, placeholder = "Найти часть класса"
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("class-family-list")) {
                    items(visible, key = { it.id }) { member ->
                        Surface(Modifier.fillMaxWidth().testTag("class-family-part:${member.id}").clickable { onPart(member.id) }) {
                            Column(Modifier.padding(vertical = 9.dp, horizontal = 6.dp)) {
                                Text(member.name, fontFamily = AppFonts.JetBrainsMono, style = MaterialTheme.typography.bodyMedium)
                                if (member.detail.isNotBlank()) Text(member.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (visible.isEmpty()) item { Text("Часть класса не найдена", Modifier.padding(8.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onWhole, modifier = Modifier.testTag("class-family-whole")) { Text("Открыть класс целиком") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
