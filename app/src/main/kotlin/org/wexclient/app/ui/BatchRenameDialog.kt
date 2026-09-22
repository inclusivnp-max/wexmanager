package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsManager

enum class CaseTransform(val label: String) {
    NONE("Как есть"),
    LOWER("все строчные"),
    UPPER("ВСЕ ЗАГЛАВНЫЕ"),
    TITLE("Первая Заглавная")
}

data class BatchRenameRule(
    val findText: String = "",
    val replaceText: String = "",
    val isRegex: Boolean = false,
    val prefix: String = "",
    val suffix: String = "",
    val enableNumbering: Boolean = false,
    val numberStart: Int = 1,
    val numberDigits: Int = 2,
    val numberReplaceAll: Boolean = false,
    val newExtension: String = "",
    val caseOption: CaseTransform = CaseTransform.NONE
)

object BatchRenameEngine {
    fun computeNewName(
        origName: String,
        isDirectory: Boolean,
        index: Int,
        rule: BatchRenameRule
    ): String {
        val baseName = if (isDirectory) origName else origName.substringBeforeLast('.')
        val origExt = if (isDirectory || !origName.contains('.')) "" else origName.substringAfterLast('.')

        var newBase = baseName

        // 1. Find & Replace
        if (rule.findText.isNotEmpty()) {
            newBase = try {
                if (rule.isRegex) {
                    newBase.replace(Regex(rule.findText), rule.replaceText)
                } else {
                    newBase.replace(rule.findText, rule.replaceText)
                }
            } catch (_: Throwable) {
                newBase
            }
        }

        // 2. Numbering
        if (rule.enableNumbering) {
            val num = rule.numberStart + index
            // Разрядность из поля ввода: 0 давал формат "%00d" и падение всего приложения
            val digits = rule.numberDigits.coerceIn(1, 12)
            val numStr = "%0${digits}d".format(num)
            newBase = if (rule.numberReplaceAll) {
                numStr
            } else {
                "${newBase}_$numStr"
            }
        }

        // 3. Prefix & Suffix
        newBase = "${rule.prefix}$newBase${rule.suffix}"

        // 4. Case transform
        newBase = when (rule.caseOption) {
            CaseTransform.NONE -> newBase
            CaseTransform.LOWER -> newBase.lowercase()
            CaseTransform.UPPER -> newBase.uppercase()
            CaseTransform.TITLE -> newBase.split(" ", "_", "-").joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }

        // 5. Extension
        val ext = if (rule.newExtension.isNotBlank()) {
            rule.newExtension.removePrefix(".")
        } else {
            origExt
        }

        return if (ext.isNotEmpty() && !isDirectory) "$newBase.$ext" else newBase
    }
}

@Composable
fun BatchRenameDialog(
    entries: List<VfsEntry>,
    vfsManager: VfsManager,
    onDismiss: () -> Unit,
    /** Переименовано (было → стало) — для отмены и обновления панелей */
    onApplied: (List<Pair<java.nio.file.Path, java.nio.file.Path>>) -> Unit
) {
    val scope = rememberCoroutineScope()

    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }

    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }

    var enableNumbering by remember { mutableStateOf(false) }
    var numberStart by remember { mutableStateOf("1") }
    var numberDigits by remember { mutableStateOf("2") }
    var numberReplaceAll by remember { mutableStateOf(false) }

    var newExtension by remember { mutableStateOf("") }
    var caseOption by remember { mutableStateOf(CaseTransform.NONE) }

    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Live calculation of preview items
    val previewItems = remember(
        entries, findText, replaceText, isRegex, prefix, suffix,
        enableNumbering, numberStart, numberDigits, numberReplaceAll,
        newExtension, caseOption
    ) {
        val startInt = numberStart.toIntOrNull() ?: 1
        val digitsInt = numberDigits.toIntOrNull() ?: 2

        val rule = BatchRenameRule(
            findText = findText,
            replaceText = replaceText,
            isRegex = isRegex,
            prefix = prefix,
            suffix = suffix,
            enableNumbering = enableNumbering,
            numberStart = startInt,
            numberDigits = digitsInt,
            numberReplaceAll = numberReplaceAll,
            newExtension = newExtension,
            caseOption = caseOption
        )

        entries.mapIndexed { index, entry ->
            val finalName = BatchRenameEngine.computeNewName(entry.name, entry.isDirectory, index, rule)
            Triple(entry, entry.name, finalName)
        }
    }
    // Одинаковые новые имена, занятые имена, «/» — показываем до нажатия
    val planProblem = remember(previewItems) {
        org.wexclient.app.vfs.BatchRenamer.problem(previewItems.map { (e, _, n) -> e.path to n })
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        onKeyEvent = latestKeyHandler { closeOnEscape(it, onDismiss) },
        state = rememberDialogState(size = DpSize(960.dp, 720.dp)),
        title = "Пакетное переименование — ${entries.size} элементов"
    ) {
        NoctaliaTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header Bar
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.DriveFileRenameOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Пакетное переименование",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${entries.size} файлов / папок выбрано",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            IconButton(
                                onClick = onDismiss,
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Закрыть")
                            }
                        }
                    }

                    // Main Content: Settings on Left, Live Preview Table on Right
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Left Settings Panel
                        Surface(
                            modifier = Modifier
                                .width(380.dp)
                                .fillMaxHeight(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            tonalElevation = 2.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Правила переименования",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                // 1. Find & Replace
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = findText,
                                        onValueChange = { findText = it },
                                        label = { Text("Найти") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                    OutlinedTextField(
                                        value = replaceText,
                                        onValueChange = { replaceText = it },
                                        label = { Text("Заменить на") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = isRegex,
                                        onCheckedChange = { isRegex = it },
                                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                    )
                                    Text("Регулярные выражения (Regex)", style = MaterialTheme.typography.bodySmall)
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                                // 2. Prefix & Suffix
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = prefix,
                                        onValueChange = { prefix = it },
                                        label = { Text("Префикс") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = suffix,
                                        onValueChange = { suffix = it },
                                        label = { Text("Суффикс") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                                // 3. Numbering
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = enableNumbering,
                                        onCheckedChange = { enableNumbering = it },
                                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                    )
                                    Text("Автонумерация [01, 02...]", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }

                                if (enableNumbering) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = numberStart,
                                            onValueChange = { numberStart = it.filter { c -> c.isDigit() } },
                                            label = { Text("Старт с") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = numberDigits,
                                            onValueChange = { numberDigits = it.filter { c -> c.isDigit() } },
                                            label = { Text("Разрядов (01)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = numberReplaceAll,
                                            onCheckedChange = { numberReplaceAll = it }
                                        )
                                        Text("Заменить исходное имя номером", style = MaterialTheme.typography.labelSmall)
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                                // 4. Extension & Case
                                OutlinedTextField(
                                    value = newExtension,
                                    onValueChange = { newExtension = it },
                                    label = { Text("Новое расширение (без точки)") },
                                    placeholder = { Text("например: bak, png, txt") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.weight(1f))

                                errorMessage?.let { err ->
                                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        // Right Live Preview Table
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Предпросмотр результатов",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            ) {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(previewItems) { idx, (entry, orig, new) ->
                                        val isChanged = orig != new
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(if (isChanged) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f) else Color.Transparent)
                                                .padding(horizontal = 12.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${idx + 1}.",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.width(28.dp)
                                            )

                                            Text(
                                                text = orig,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )

                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = null,
                                                tint = if (isChanged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(16.dp).padding(horizontal = 2.dp)
                                            )

                                            Text(
                                                text = new,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (isChanged) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isChanged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1.2f)
                                            )
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            planProblem?.let {
                                Text(
                                    "Нельзя применить: $it",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }

                            // Action Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(onClick = onDismiss) {
                                    Text("Отмена")
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Button(
                                    onClick = {
                                        isProcessing = true
                                        val plan = previewItems.map { (entry, _, new) -> entry.path to new }
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val renamed = org.wexclient.app.vfs.BatchRenamer.apply(vfsManager, plan)
                                                withContext(Dispatchers.Main) {
                                                    isProcessing = false
                                                    onApplied(renamed)
                                                    onDismiss()
                                                }
                                            } catch (e: Throwable) {
                                                withContext(Dispatchers.Main) {
                                                    isProcessing = false
                                                    // Откат уже сделан — список в панелях всё равно перечитываем
                                                    onApplied(emptyList())
                                                    errorMessage = "Ошибка переименования: ${e.message ?: e.javaClass.simpleName}"
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isProcessing && planProblem == null && previewItems.any { it.second != it.third },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Применить к ${previewItems.count { it.second != it.third }} файлам", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
