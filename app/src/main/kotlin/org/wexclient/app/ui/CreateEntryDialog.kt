package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import java.nio.file.Path

enum class NewEntryKind(val title: String, val icon: String, val defaultName: String) {
    FOLDER("Папка", "create_new_folder", "Новая папка"),
    FILE("Файл", "note_add", "новый файл.txt")
}

/** Проверка имени нового файла или папки; null — имя подходит. */
fun newEntryNameProblem(name: String, exists: (String) -> Boolean): String? = when {
    name.isBlank() -> "Введите имя"
    name.contains('/') || name.contains(Char(0)) -> "Имя не может содержать «/»"
    name == "." || name == ".." -> "Недопустимое имя"
    name.toByteArray().size > 255 -> "Слишком длинное имя"
    exists(name) -> "«$name» уже существует"
    else -> null
}

/** Свободное имя: «Новая папка», «Новая папка (2)», «новый файл (2).txt»… */
fun uniqueEntryName(base: String, exists: (String) -> Boolean): String {
    if (!exists(base)) return base
    val dot = base.lastIndexOf('.').takeIf { it > 0 }
    val stem = if (dot != null) base.substring(0, dot) else base
    val ext = if (dot != null) base.substring(dot) else ""
    var i = 2
    while (exists("$stem ($i)$ext")) i++
    return "$stem ($i)$ext"
}

/** Создание папки или файла в [directory]: выбор вида, проверка имени, открытие файла после создания. */
@Composable
fun CreateEntryDialog(
    initialKind: NewEntryKind,
    directory: Path,
    exists: (String) -> Boolean,
    onCreate: (kind: NewEntryKind, name: String, openAfter: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var kind by remember { mutableStateOf(initialKind) }
    var openAfter by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }

    fun suggested(k: NewEntryKind): TextFieldValue {
        val name = uniqueEntryName(k.defaultName, exists)
        // Выделено имя без расширения — сразу печатать своё
        val end = if (k == NewEntryKind.FILE) name.lastIndexOf('.').takeIf { it > 0 } ?: name.length else name.length
        return TextFieldValue(name, TextRange(0, end))
    }

    var field by remember { mutableStateOf(suggested(initialKind)) }
    var edited by remember { mutableStateOf(false) }
    // Проверка — только когда меняется текст: движение курсора и выделение её не повторяют
    // (на сервере каждая проверка — запрос по сети)
    val problem = remember(field.text) { newEntryNameProblem(field.text.trim(), exists) }

    LaunchedEffect(Unit) { org.wexclient.app.editor.requestFocusSafely(focus) }

    fun switchKind(k: NewEntryKind) {
        if (k == kind) return
        kind = k
        if (!edited) field = suggested(k)
    }

    fun confirm() {
        if (problem == null) onCreate(kind, field.text.trim(), openAfter)
    }

    ModalDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 8.dp,
            modifier = Modifier.width(460.dp).dialogGlass(RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(10.dp), color = NoctaliaPrimary.copy(alpha = 0.16f), modifier = Modifier.size(38.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            SymbolIcon(SymbolRef(kind.icon), null, tint = NoctaliaPrimary, modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Создать", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = NoctaliaPrimary)
                        Text(
                            directory.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DialogClose(onDismiss)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NewEntryKind.entries.forEach { k ->
                        KindChip(k, selected = k == kind) { switchKind(k) }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    if (kind == NewEntryKind.FOLDER) "Имя папки" else "Имя файла",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, if (problem != null && edited) MaterialTheme.colorScheme.error else NoctaliaPrimary),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = 12.dp)) {
                        BasicTextField(
                            value = field,
                            onValueChange = {
                                if (it.text != field.text) edited = true
                                field = it
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(NoctaliaPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.Enter, Key.NumPadEnter -> {
                                            confirm()
                                            true
                                        }
                                        Key.Escape -> {
                                            onDismiss()
                                            true
                                        }
                                        else -> false
                                    }
                                }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                val showError = problem != null && (edited || field.text.isNotBlank())
                Text(
                    text = when {
                        showError -> problem!!
                        kind == NewEntryKind.FILE -> "Enter — создать · расширение задаёт подсветку в редакторе"
                        else -> "Enter — создать, Esc — отмена"
                    },
                    fontSize = 11.sp,
                    color = if (showError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (kind == NewEntryKind.FILE) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 6.dp).clickable { openAfter = !openAfter }
                    ) {
                        Checkbox(
                            checked = openAfter,
                            onCheckedChange = { openAfter = it },
                            colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Открыть в редакторе после создания", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { confirm() },
                        enabled = problem == null,
                        colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary)
                    ) {
                        Text(if (kind == NewEntryKind.FOLDER) "Создать папку" else "Создать файл")
                    }
                }
            }
        }
    }
}

@Composable
private fun KindChip(kind: NewEntryKind, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) NoctaliaPrimary.copy(alpha = 0.18f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            SymbolIcon(
                SymbolRef(kind.icon),
                null,
                tint = if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                kind.title,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
