package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.window.Dialog
import org.wexclient.app.arsc.ArscConfig
import org.wexclient.app.arsc.ArscDocument
import org.wexclient.app.arsc.ArscEntry
import org.wexclient.app.arsc.ArscPackage
import org.wexclient.app.arsc.ArscParser
import org.wexclient.app.arsc.ArscType
import org.wexclient.app.editor.CodeEditorController
import org.wexclient.app.editor.CodeEditorView
import org.wexclient.app.util.isCtrlShortcut
import androidx.compose.ui.input.key.Key
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary
import org.wexclient.app.theme.NoctaliaTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Запись в истории недавно открытых файлов конфигураций.
 */
private data class ArscHistoryItem(
    val title: String,
    val path: String,
    val time: String,
    val xmlSupplier: () -> String,
    val config: ArscConfig?
)

/**
 * Редактор arsc+ в точном соответствии со стилем MT Manager:
 * - 4 вкладки: ОБЗОР, ИСТОРИЯ, ПОИСК, СТРОКИ
 * - Дерево: Пакеты [7F] -> Типы [14] -> Конфигурации (type-info, string, string-af, string-ru...) + package-info
 * - Клик по конфигурации открывает полноценный XML-редактор (CodeEditorView)
 */
@Composable
fun ArscEditorDialog(
    doc: ArscDocument,
    fileName: String,
    onDismiss: () -> Unit,
    onSave: (ByteArray) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var docVersion by remember { mutableStateOf(0) }
    var dirty by remember { mutableStateOf(doc.isDirty()) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    // Состояние открытого XML редактора
    var activeXmlPath by remember { mutableStateOf<String?>(null) }
    var activeXmlContent by remember { mutableStateOf("") }
    var activeConfig by remember { mutableStateOf<ArscConfig?>(null) }

    // История открытий
    val history = remember { mutableStateListOf<ArscHistoryItem>() }

    fun touched() {
        docVersion++
        dirty = doc.isDirty()
        statusMsg = null
    }

    fun openXml(title: String, path: String, contentSupplier: () -> String, config: ArscConfig?) {
        val content = contentSupplier()
        activeXmlPath = path
        activeXmlContent = content
        activeConfig = config
        // Добавляем в историю (без дубликатов наверху)
        val timeNow = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        history.removeAll { it.path == path }
        history.add(0, ArscHistoryItem(title, path, timeNow, contentSupplier, config))
    }

    fun saveCurrentXml(newXml: String) {
        val cfg = activeConfig
        if (cfg != null) {
            val pool = doc.currentGlobalStrings()
            val stringRegex = Regex("<string\\s+name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            var patchedCount = 0
            var errorCount = 0
            var lastErr: String? = null

            for (m in stringRegex.findAll(newXml)) {
                val name = m.groupValues[1]
                val text = ArscParser.unescapeXml(m.groupValues[2])
                val entry = cfg.entries.firstOrNull { it.name == name }
                if (entry != null && entry.valueType == 0x03) {
                    val oldText = pool.getOrNull(entry.valueData) ?: ""
                    if (oldText != text) {
                        try {
                            doc.setGlobalString(entry.valueData, text)
                            patchedCount++
                        } catch (e: Exception) {
                            errorCount++
                            lastErr = e.message
                        }
                    }
                }
            }
            touched()
            statusMsg = if (errorCount > 0) {
                "Обновлено строк: $patchedCount, ошибок: $errorCount ($lastErr)"
            } else if (patchedCount > 0) {
                "Успешно сохранено: обновлено $patchedCount строк"
            } else {
                "XML сохранён (изменений строк не обнаружено)"
            }
        } else {
            statusMsg = "Информационный файл только для чтения"
        }
    }

    val toast = rememberToastState()
    // Текст открытого XML (строки конфигурации); «сохранить» в нём = применить к таблице
    val xmlController = remember(activeXmlPath) { CodeEditorController(activeXmlContent) }
    val anyDirty = dirty || (activeXmlPath != null && xmlController.isDirty)
    TrackUnsaved(fileName, anyDirty)
    var askLeaveXml by remember { mutableStateOf(false) }

    val saver = rememberBackgroundSave()

    // Правки XML применяются и снимок берётся в потоке окна; запись в APK — в фоне
    fun saveDoc(done: (String?) -> Unit = {}): Boolean {
        if (saver.busy) return false
        if (activeXmlPath != null && xmlController.isDirty) {
            xmlController.save()?.let { err ->
                done(err)
                return true
            }
        }
        val bytes = doc.toByteArray()
        statusMsg = "Сохранение…"
        return saver.run({ onSave(bytes) }) { err ->
            if (err == null) {
                doc.markSaved(bytes)
                dirty = doc.isDirty()
                statusMsg = "ARSC файл успешно сохранён"
                toast.success("Сохранено: $fileName")
            } else {
                statusMsg = "Ошибка сохранения: $err"
                toast.error("Не сохранено: $err")
            }
            done(err)
        }
    }

    val guard = rememberCloseGuard(isDirty = { anyDirty }, onClose = onDismiss)

    Dialog(
        onCloseRequest = { guard.requestClose() },
        onPreviewKeyEvent = latestKeyHandler { e ->
            // В XML-режиме Ctrl+S обрабатывает сам редактор (применить к таблице)
            if (activeXmlPath == null && e.isCtrlShortcut(Key.S)) {
                if (dirty) saveDoc()
                true
            } else false
        },
        state = androidx.compose.ui.window.rememberDialogState(
            size = androidx.compose.ui.unit.DpSize(1400.dp, 900.dp)
        ),
        title = (if (anyDirty) "● " else "") + "Редактор arsc+ - $fileName"
    ) {
        NoctaliaTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
              Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // --- Header Bar ---
                    if (activeXmlPath != null) {
                        // XML Editor Header (Image 2 style)
                        ArscXmlEditorHeader(
                            filePath = activeXmlPath!!,
                            isDirty = anyDirty,
                            onBack = {
                                if (xmlController.isDirty) askLeaveXml = true else activeXmlPath = null
                            },
                            onSave = {
                                xmlController.save()?.let { toast.error("Не применено: $it") }
                                    ?: toast.success("Изменения применены к таблице")
                            }
                        )
                    } else {
                        // Unified Desktop Header with integrated GrailTabs
                        ArscMainHeader(
                            fileName = fileName,
                            isDirty = dirty,
                            selectedTab = tab,
                            onSelectTab = { tab = it },
                            onSave = { saveDoc() },
                            onDismiss = { guard.requestClose() }
                        )
                    }

                    statusMsg?.let { msg ->
                        Surface(
                            color = if (msg.contains("Ошиб")) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (msg.contains("Ошиб")) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { statusMsg = null }, modifier = Modifier.size(16.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }

                    // --- Content Area ---
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (activeXmlPath != null) {
                            // Full-screen XML Editor View
                            CodeEditorView(
                                initialText = activeXmlContent,
                                language = Languages.XML,
                                readOnly = false,
                                fileName = activeXmlPath!!,
                                onSave = { newText ->
                                    activeXmlContent = newText
                                    saveCurrentXml(newText)
                                },
                                controller = xmlController,
                                savedMessage = "Применено к таблице",
                                saveButtonText = "Применить (Ctrl+S)",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            when (tab) {
                                0 -> ArscOverviewTab(
                                    doc = doc,
                                    version = docVersion,
                                    onOpenConfig = { cfg ->
                                        openXml(cfg.configTitle, cfg.fullXmlPath, { cfg.toXml(doc.currentGlobalStrings()) }, cfg)
                                    },
                                    onOpenTypeInfo = { pkg, t ->
                                        openXml("type-info", "${pkg.name}/${t.name}/type-info.xml", { t.toTypeInfoXml() }, null)
                                    },
                                    onOpenPackageInfo = { pkg ->
                                        openXml("package-info", "${pkg.name}/package-info.xml", { pkg.toPackageInfoXml() }, null)
                                    }
                                )
                                1 -> ArscHistoryTab(
                                    history = history,
                                    onSelect = { item ->
                                        openXml(item.title, item.path, item.xmlSupplier, item.config)
                                    },
                                    onClear = { history.clear() }
                                )
                                2 -> ArscSearchTab(
                                    doc = doc,
                                    version = docVersion,
                                    onSelectConfig = { cfg ->
                                        openXml(cfg.configTitle, cfg.fullXmlPath, { cfg.toXml(doc.currentGlobalStrings()) }, cfg)
                                    }
                                )
                                3 -> ArscStringsTab(
                                    doc = doc,
                                    version = docVersion,
                                    onChanged = { touched() }
                                )
                            }
                        }
                    }
                }
                ToastHost(toast)
              }
            }

            if (askLeaveXml) {
                UnsavedChangesDialog(
                    fileName = activeXmlPath ?: "XML",
                    message = "Правки в XML ещё не применены к таблице ресурсов. Применить их?",
                    saveText = "Применить",
                    discardText = "Отменить правки",
                    onSave = {
                        val err = xmlController.save()
                        if (err == null) {
                            askLeaveXml = false
                            activeXmlPath = null
                        } else {
                            toast.error("Не применено: $err")
                        }
                    },
                    onDiscard = {
                        askLeaveXml = false
                        activeXmlPath = null
                    },
                    onCancel = { askLeaveXml = false }
                )
            }

            if (guard.asking) {
                UnsavedChangesDialog(
                    fileName = fileName,
                    error = guard.saveError,
                    busy = guard.saving,
                    onSave = { guard.saveAndCloseAsync { done -> saveDoc(done) } },
                    onDiscard = { guard.discard() },
                    onCancel = { guard.cancel() }
                )
            }
        }
    }
}

// --- Хедеры ---

@Composable
private fun ArscMainHeader(
    fileName: String,
    isDirty: Boolean,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Бейдж ARSC+
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.6f)),
                modifier = Modifier.padding(end = 10.dp)
            ) {
                Text(
                    text = "ARSC+",
                    fontSize = 11.sp,
                    fontFamily = AppFonts.JetBrainsMono,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            // Название и путь файла
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Редактор arsc+",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NoctaliaPrimary
                    )
                    if (isDirty) {
                        Text(
                            text = " *",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = NoctaliaPrimary
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isDirty) {
                        Spacer(modifier = Modifier.width(10.dp))
                        DirtyMark()
                    }
                }
            }

            // Сегментные вкладки GrailTabs прямо в тулбаре
            GrailTabs(
                tabs = listOf("Обзор", "История", "Поиск", "Строки"),
                selected = selectedTab,
                onSelect = onSelectTab
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Кнопка собрать/сохранить
            Button(
                onClick = onSave,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDirty) NoctaliaPrimary else MaterialTheme.colorScheme.surface,
                    contentColor = if (isDirty) MaterialTheme.colorScheme.onPrimary else NoctaliaPrimary
                ),
                border = if (!isDirty) BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.5f)) else null,
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Собрать", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun ArscXmlEditorHeader(
    filePath: String,
    isDirty: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад к обзору",
                    tint = NoctaliaPrimary
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.5f)),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(
                    text = "XML",
                    fontSize = 11.sp,
                    fontFamily = AppFonts.JetBrainsMono,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = filePath,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = AppFonts.JetBrainsMono,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onSave,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDirty) NoctaliaPrimary else MaterialTheme.colorScheme.surface,
                    contentColor = if (isDirty) MaterialTheme.colorScheme.onPrimary else NoctaliaPrimary
                ),
                border = if (!isDirty) BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.5f)) else null,
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Сохранить", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// --- Круглый бейдж <> для XML-конфигураций ---

@Composable
fun CodeBadge(modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = NoctaliaPrimary.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.4f)),
        modifier = modifier.size(20.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Code,
                contentDescription = null,
                tint = NoctaliaPrimary,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

// --- Вкладка 1: ОБЗОР (Точная копия дерева MT Manager) ---

private sealed interface OverviewRow {
    data class PkgRow(val pkg: ArscPackage, val expanded: Boolean) : OverviewRow
    data class TypeHeaderRow(val pkg: ArscPackage, val type: ArscType, val expanded: Boolean) : OverviewRow
    data class TypeInfoRow(val pkg: ArscPackage, val type: ArscType) : OverviewRow
    data class ConfigRow(val pkg: ArscPackage, val type: ArscType, val config: ArscConfig) : OverviewRow
    data class PkgInfoRow(val pkg: ArscPackage) : OverviewRow
}

@Composable
private fun ArscOverviewTab(
    doc: ArscDocument,
    version: Int,
    onOpenConfig: (ArscConfig) -> Unit,
    onOpenTypeInfo: (ArscPackage, ArscType) -> Unit,
    onOpenPackageInfo: (ArscPackage) -> Unit
) {
    @Suppress("UNUSED_EXPRESSION") version
    var expandedPkgs by remember { mutableStateOf(doc.packages.map { it.id }.toSet()) }
    var expandedTypes by remember { mutableStateOf(setOf<String>()) }

    val rows = remember(doc.packages, expandedPkgs, expandedTypes) {
        val out = ArrayList<OverviewRow>()
        for (pkg in doc.packages) {
            val pkgOpen = pkg.id in expandedPkgs
            out.add(OverviewRow.PkgRow(pkg, pkgOpen))
            if (pkgOpen) {
                for (t in pkg.types) {
                    val key = "${pkg.id}:${t.id}"
                    val typeOpen = key in expandedTypes
                    out.add(OverviewRow.TypeHeaderRow(pkg, t, typeOpen))
                    if (typeOpen) {
                        out.add(OverviewRow.TypeInfoRow(pkg, t))
                        for (cfg in t.configs) {
                            out.add(OverviewRow.ConfigRow(pkg, t, cfg))
                        }
                    }
                }
                out.add(OverviewRow.PkgInfoRow(pkg))
            }
        }
        out
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        items(rows, key = {
            when (it) {
                is OverviewRow.PkgRow -> "pkg_${it.pkg.id}"
                is OverviewRow.TypeHeaderRow -> "th_${it.pkg.id}_${it.type.id}"
                is OverviewRow.TypeInfoRow -> "ti_${it.pkg.id}_${it.type.id}"
                is OverviewRow.ConfigRow -> "cfg_${it.pkg.id}_${it.type.id}_${it.config.configTitle}"
                is OverviewRow.PkgInfoRow -> "pi_${it.pkg.id}"
            }
        }) { row ->
            when (row) {
                is OverviewRow.PkgRow -> {
                    // client.wex [7F]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedPkgs = if (row.expanded) expandedPkgs - row.pkg.id else expandedPkgs + row.pkg.id
                            }
                            .padding(vertical = 5.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (row.expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = NoctaliaPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = row.pkg.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "[%02X]".format(row.pkg.id),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = AppFonts.JetBrainsMono,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                is OverviewRow.TypeHeaderRow -> {
                    // anim [01], string [14]...
                    val key = "${row.pkg.id}:${row.type.id}"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp)
                            .clickable {
                                expandedTypes = if (row.expanded) expandedTypes - key else expandedTypes + key
                            }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (row.expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (row.expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                            contentDescription = null,
                            tint = NoctaliaSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = row.type.name,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "[%02X]".format(row.type.id),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = AppFonts.JetBrainsMono,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                is OverviewRow.TypeInfoRow -> {
                    // <> type-info
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp)
                            .clickable { onOpenTypeInfo(row.pkg, row.type) }
                            .padding(vertical = 3.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CodeBadge()
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "type-info",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = AppFonts.JetBrainsMono,
                                fontSize = 12.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                is OverviewRow.ConfigRow -> {
                    // <> string, <> string-af, <> string-ru...
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp)
                            .clickable { onOpenConfig(row.config) }
                            .padding(vertical = 3.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CodeBadge()
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = row.config.configTitle,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = AppFonts.JetBrainsMono,
                                fontSize = 12.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(${row.config.entries.size})",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                is OverviewRow.PkgInfoRow -> {
                    // <> package-info
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, top = 4.dp, bottom = 6.dp)
                            .clickable { onOpenPackageInfo(row.pkg) }
                            .padding(vertical = 3.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CodeBadge()
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "package-info",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = AppFonts.JetBrainsMono,
                                fontSize = 12.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// --- Вкладка 2: ИСТОРИЯ ---

@Composable
private fun ArscHistoryTab(
    history: List<ArscHistoryItem>,
    onSelect: (ArscHistoryItem) -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Недавно открытые файлы ресурсов (${history.size})",
                style = MaterialTheme.typography.titleSmall,
                color = NoctaliaPrimary,
                modifier = Modifier.weight(1f)
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text("Очистить историю", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "История пуста. Откройте любой XML ресурс из вкладки «ОБЗОР».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(history) { item ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable { onSelect(item) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CodeBadge()
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.path,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = AppFonts.JetBrainsMono,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Конфигурация: ${item.title}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text(
                                text = item.time,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                                color = NoctaliaSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Вкладка 3: ПОИСК ---

private data class SearchResultItem(
    val pkg: ArscPackage,
    val type: ArscType,
    val config: ArscConfig,
    val entry: ArscEntry,
    val valueStr: String
)

@Composable
private fun ArscSearchTab(
    doc: ArscDocument,
    version: Int,
    onSelectConfig: (ArscConfig) -> Unit
) {
    @Suppress("UNUSED_EXPRESSION") version
    var query by remember { mutableStateOf("") }
    val pool = remember(version) { doc.currentGlobalStrings() }

    val results = remember(query, pool) {
        val q = query.trim()
        if (q.isBlank()) return@remember emptyList<SearchResultItem>()
        val out = ArrayList<SearchResultItem>()

        for (pkg in doc.packages) {
            for (t in pkg.types) {
                for (c in t.configs) {
                    for (e in c.entries) {
                        val valStr = e.displayValue(pool)
                        val idHex = "0x%08x".format(e.resId)
                        val match = e.name.contains(q, ignoreCase = true) ||
                                idHex.contains(q, ignoreCase = true) ||
                                valStr.contains(q, ignoreCase = true)
                        if (match) {
                            out.add(SearchResultItem(pkg, t, c, e, valStr))
                            if (out.size >= 500) return@remember out
                        }
                    }
                }
            }
        }
        out
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        GrailSearchField(query, { query = it }, "Поиск по имени, ID (0x7f...) или значению...")
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Найдено записей: ${results.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results) { res ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable { onSelectConfig(res.config) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CodeBadge(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = res.entry.name,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = AppFonts.JetBrainsMono,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = NoctaliaPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "0x%08X".format(res.entry.resId),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text(
                                text = "${res.config.fullXmlPath} = ${res.valueStr.take(70)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Вкладка 4: СТРОКИ (Глобальный пул) ---

@Composable
private fun ArscStringsTab(doc: ArscDocument, version: Int, onChanged: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") version
    val pool = remember(version) { doc.currentGlobalStrings() }
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }

    val shown = remember(pool, filter) {
        val f = filter.trim()
        val all = pool.mapIndexedNotNull { i, s -> if (s.isEmpty()) null else i to s }
        val base = if (f.isBlank()) all else all.filter { (i, s) ->
            s.contains(f, ignoreCase = true) || i.toString() == f
        }
        base.take(2000)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        GrailSearchField(filter, { filter = it }, "Фильтр строк глобального пула...")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Всего: ${pool.size}, показано: ${shown.size} · in-place правка пула",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(shown, key = { it.first }) { (idx, text) ->
                val isSel = selected == idx
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSel) NoctaliaPrimary.copy(alpha = 0.18f) else Color.Transparent,
                    border = if (isSel) BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.6f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                        .clickable {
                            selected = idx
                            editText = doc.getGlobalString(idx)
                            editError = null
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "#$idx",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                            color = NoctaliaSecondary,
                            modifier = Modifier.width(70.dp)
                        )
                        Text(
                            text = text.ifEmpty { "<пусто>" },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = AppFonts.JetBrainsMono,
                                fontSize = 12.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        selected?.let { idx ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Строка #$idx",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = NoctaliaPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "доступно ${doc.globalStringEncodedLength(idx)} байт",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it; editError = null },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = AppFonts.JetBrainsMono),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NoctaliaPrimary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    editError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                try {
                                    doc.setGlobalString(idx, editText)
                                    editError = null
                                    onChanged()
                                } catch (e: Exception) {
                                    editError = e.message
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NoctaliaPrimary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) { Text("Применить", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        TextButton(
                            onClick = { selected = null; editError = null }
                        ) { Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}
