package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Save
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wexclient.app.dex.DexClassNode
import org.wexclient.app.dex.DexEditorEngine
import org.wexclient.app.editor.CodeEditorView
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.editor.rememberCodeEditorController
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.util.isCtrlShortcut
import androidx.compose.ui.input.key.Key
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import org.wexclient.app.dex.DexPackageNode

@Composable
fun DexEditorPlusDialog(
    dexName: String,
    engine: DexEditorEngine,
    onDismiss: () -> Unit,
    /** Запись изменённых DEX (вызывается из фонового потока; бросает исключение при ошибке). */
    onSaveDex: ((List<org.wexclient.app.dex.DexSource>) -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedClass by remember { mutableStateOf<DexClassNode?>(engine.classes.firstOrNull()) }

    var editorTab by remember { mutableStateOf(0) } // 0: Smali, 1: JADX, 2: CFR, 3: VF, 4: Procyon, 5: Ghidra

    // Выбранный метод для Ghidra-листинга (ключ; сбрасывается при смене класса)
    var ghidraMethodKey by remember(selectedClass) { mutableStateOf<String?>(null) }

    var smaliCode by remember { mutableStateOf("") }
    var javaCode by remember { mutableStateOf("") }
    var cfrCode by remember { mutableStateOf("") }
    var vfCode by remember { mutableStateOf("") }
    var procyonCode by remember { mutableStateOf("") }
    var isLoadingCode by remember { mutableStateOf(false) }

    // --- Правка smali и запись DEX ---
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()
    val smaliController = rememberCodeEditorController(smaliCode)
    var dexDirty by remember { mutableStateOf(engine.hasUnsavedChanges) }
    var editedCount by remember { mutableStateOf(0) }
    var savingDex by remember { mutableStateOf(false) }
    var pendingSelect by remember { mutableStateOf<DexClassNode?>(null) }
    var askClose by remember { mutableStateOf(false) }
    var closeError by remember { mutableStateOf<String?>(null) }
    val canWriteDex = onSaveDex != null && engine.canSave
    val anyDirty = smaliController.isDirty || dexDirty
    TrackUnsaved("Dex Редактор+: $dexName", anyDirty)

    fun closeEditor() {
        engine.close()
        onDismiss()
    }

    /** Выбор класса: несобранные правки smali не теряем молча. */
    fun selectClass(node: DexClassNode) {
        if (node == selectedClass) return
        if (smaliController.isDirty) pendingSelect = node else selectedClass = node
    }

    /** null — собрано; иначе текст ошибок smali. */
    fun compileSmaliNow(): String? {
        val node = selectedClass ?: return "Класс не выбран"
        return smaliController.save().also { if (it == null) editedCount = engine.classes.count { c -> engine.isEdited(c) } }
    }

    /** Пересборка и запись DEX в фоне; onDone(null) — успех. */
    fun saveDex(onDone: (String?) -> Unit = {}) {
        val writer = onSaveDex
        if (writer == null || !engine.canSave) {
            onDone("Этот DEX открыт только для просмотра — записать его некуда")
            return
        }
        savingDex = true
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    val modified = engine.modifiedDex()
                    BackgroundSave.tracked { writer(modified) }
                    engine.markSaved()
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            savingDex = false
            if (err == null) {
                dexDirty = false
                toast.success("DEX сохранён")
            } else {
                toast.error("DEX не сохранён: $err")
            }
            onDone(err)
        }
    }

    fun requestClose() {
        if (anyDirty) {
            closeError = null
            askClose = true
        } else {
            closeEditor()
        }
    }

    var searchMode by remember { mutableStateOf(org.wexclient.app.dex.DexSearchMode.CLASSES) }

    // Package tree state
    var compactMode by remember { mutableStateOf(true) }
    val rootPackageNode = remember(engine, compactMode) {
        engine.getPackageTree(compactMode)
    }

    var expandedPackages by remember(engine, compactMode) {
        val initial = mutableSetOf<String>()
        selectedClass?.let { node ->
            initial.addAll(engine.getAncestorPathsForClass(node, compactMode))
        }
        mutableStateOf<Set<String>>(initial)
    }

    val visibleTreeRows = remember(rootPackageNode, expandedPackages) {
        flattenDexTree(rootPackageNode, expandedPackages)
    }

    val searchResults = remember(searchQuery, searchMode) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            engine.search(searchQuery, searchMode)
        }
    }

    // Фоновый прогрев трансляции DEX -> jar, чтобы вкладки CFR/VF открывались без задержки
    LaunchedEffect(engine) {
        withContext(Dispatchers.IO) {
            engine.prewarmAltEngines()
        }
    }

    // Load Smali / Java when selected class changes
    LaunchedEffect(selectedClass, editorTab) {
        val node = selectedClass ?: return@LaunchedEffect
        isLoadingCode = true
        withContext(Dispatchers.IO) {
            if (editorTab == 0) {
                smaliCode = try {
                    engine.disassembleToSmali(node)
                } catch (e: Exception) {
                    "// Ошибка дизассемблирования Smali: ${e.message ?: e.javaClass.simpleName}"
                }
            } else if (editorTab == 1) {
                javaCode = try {
                    engine.decompileToJava(node)
                } catch (e: Exception) {
                    "// Ошибка декомпиляции Java: ${e.message ?: e.javaClass.simpleName}"
                }
            } else if (editorTab == 2) {
                cfrCode = try {
                    engine.decompileToJavaCfr(node)
                } catch (e: Exception) {
                    "// Ошибка декомпиляции CFR: ${e.message ?: e.javaClass.simpleName}"
                }
            } else if (editorTab == 3) {
                vfCode = try {
                    engine.decompileToJavaVineflower(node)
                } catch (e: Exception) {
                    "// Ошибка декомпиляции Vineflower: ${e.message ?: e.javaClass.simpleName}"
                }
            } else if (editorTab == 4) {
                procyonCode = try {
                    engine.decompileToJavaProcyon(node)
                } catch (e: Exception) {
                    "// Ошибка декомпиляции Procyon: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            // editorTab == 5 (Ghidra): грузится сам внутри GhidraListingView
            isLoadingCode = false
        }
    }

    Dialog(
        onCloseRequest = { requestClose() },
        onPreviewKeyEvent = latestKeyHandler { e ->
            // Ctrl+Shift+S — пересобрать и записать DEX (Ctrl+S в smali — собрать класс)
            if (e.isCtrlShortcut(Key.S, shift = true)) {
                if (smaliController.isDirty) compileSmaliNow()?.let { toast.error("Ошибка smali: ${it.lineSequence().first()}") }
                if (dexDirty && !savingDex && smaliController.saveError == null) saveDex()
                true
            } else false
        },
        state = androidx.compose.ui.window.rememberDialogState(
            size = androidx.compose.ui.unit.DpSize(1550.dp, 950.dp)
        ),
        title = (if (anyDirty) "● " else "") + "Dex Редактор+ - $dexName"
    ) {
        org.wexclient.app.theme.NoctaliaTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
            Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Header Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.6f)),
                            modifier = Modifier.padding(end = 10.dp)
                        ) {
                            Text(
                                text = "DEX+",
                                fontSize = 11.sp,
                                fontFamily = AppFonts.JetBrainsMono,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dex Редактор+: $dexName",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = NoctaliaPrimary
                            )
                            selectedClass?.let { node ->
                                Text(
                                    text = node.fullName,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = AppFonts.JetBrainsMono,
                                        fontSize = 11.sp
                                    ),
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Segmented switcher: Smali / Java engines / Ghidra
                        GrailTabs(
                            listOf("Smali", "JADX", "CFR", "VF", "Procyon", "Ghidra"),
                            editorTab
                        ) { editorTab = it }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Copy code button (у Ghidra своя кнопка копирования листинга)
                        IconButton(
                            onClick = {
                                val currentText = when (editorTab) {
                                    0 -> smaliCode
                                    1 -> javaCode
                                    2 -> cfrCode
                                    3 -> vfCode
                                    4 -> procyonCode
                                    else -> ""
                                }
                                val sel = StringSelection(currentText)
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                            },
                            enabled = editorTab != 5
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Копировать код", tint = NoctaliaSecondary)
                        }

                        if (dexDirty || savingDex) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = { saveDex() },
                                enabled = canWriteDex && !savingDex,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                if (savingDex) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (savingDex) "Запись DEX…" else "Сохранить DEX ($editedCount) · Ctrl+Shift+S",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        IconButton(onClick = { requestClose() }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }

                // Workspace: Left Search & Class Tree (350dp) | Right IntelliJ-like Code View
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Left: Search & Class Browser / Tree
                    Surface(
                        modifier = Modifier
                            .width(350.dp)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                            // Search field
                            GrailCompactField(
                                value = searchQuery,
                                onChange = { searchQuery = it },
                                placeholder = "Поиск ${searchMode.label.lowercase()}...",
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { searchQuery = "" },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            if (searchQuery.isNotBlank()) {
                                // Search mode toggle chips: [Классы] [Методы] [Поля] [Строки]
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    org.wexclient.app.dex.DexSearchMode.entries.forEach { mode ->
                                        val isCur = searchMode == mode
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isCur) NoctaliaPrimary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
                                            border = BorderStroke(1.dp, if (isCur) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { searchMode = mode }
                                        ) {
                                            Text(
                                                text = mode.label,
                                                fontSize = 10.sp,
                                                fontFamily = AppFonts.JetBrainsMono,
                                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCur) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(vertical = 4.dp),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "Результатов: ${searchResults.size}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = AppFonts.JetBrainsMono,
                                        fontSize = 10.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(searchResults, key = { "${it.type}_${it.subtitle}_${it.title}" }) { res ->
                                        val isSelected = res.classNode != null && res.classNode == selectedClass
                                        val rowBg = if (isSelected) NoctaliaPrimary.copy(alpha = 0.2f) else Color.Transparent
                                        val rowBorder = if (isSelected) BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.6f)) else null

                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 1.dp)
                                                .clickable {
                                                    if (res.classNode != null) {
                                                        selectClass(res.classNode)
                                                        expandedPackages = expandedPackages + engine.getAncestorPathsForClass(res.classNode, compactMode)
                                                    }
                                                },
                                            color = rowBg,
                                            shape = RoundedCornerShape(6.dp),
                                            border = rowBorder
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = when (res.type) {
                                                        org.wexclient.app.dex.DexSearchMode.CLASSES -> Icons.Default.DataObject
                                                        org.wexclient.app.dex.DexSearchMode.METHODS -> Icons.Default.Code
                                                        org.wexclient.app.dex.DexSearchMode.FIELDS -> Icons.Default.DataObject
                                                        org.wexclient.app.dex.DexSearchMode.STRINGS -> Icons.Default.ContentCopy
                                                    },
                                                    contentDescription = null,
                                                    tint = if (isSelected) NoctaliaPrimary else NoctaliaSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = res.title,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontFamily = AppFonts.JetBrainsMono,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                            fontSize = 12.sp
                                                        ),
                                                        color = if (isSelected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (res.subtitle.isNotEmpty()) {
                                                        Text(
                                                            text = res.subtitle,
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontFamily = AppFonts.JetBrainsMono,
                                                                fontSize = 10.sp
                                                            ),
                                                            color = MaterialTheme.colorScheme.outline,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Hierarchical Package & Class Tree Toolbar
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${engine.classes.size} классов",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = AppFonts.JetBrainsMono,
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Spacer(modifier = Modifier.weight(1f))

                                    // Compact packages toggle
                                    IconButton(
                                        onClick = { compactMode = !compactMode },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Tune,
                                            contentDescription = "Компактные пакеты",
                                            tint = if (compactMode) NoctaliaPrimary else MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Expand all
                                    IconButton(
                                        onClick = { expandedPackages = engine.getAllPackagePaths(compactMode) },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.UnfoldMore,
                                            contentDescription = "Развернуть всё",
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Collapse all
                                    IconButton(
                                        onClick = { expandedPackages = emptySet<String>() },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.UnfoldLess,
                                            contentDescription = "Свернуть всё",
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Locate current class
                                    IconButton(
                                        onClick = {
                                            selectedClass?.let {
                                                expandedPackages = expandedPackages + engine.getAncestorPathsForClass(it, compactMode)
                                            }
                                        },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.MyLocation,
                                            contentDescription = "Показать активный класс",
                                            tint = NoctaliaSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Hierarchical Tree LazyColumn
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(visibleTreeRows, key = { it.id }) { item ->
                                        when (item) {
                                            is DexTreeRow.PackageRow -> {
                                                val pkg = item.node
                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 1.dp)
                                                        .clickable {
                                                            expandedPackages = if (item.isExpanded) {
                                                                expandedPackages - pkg.fullPath
                                                            } else {
                                                                expandedPackages + pkg.fullPath
                                                            }
                                                        },
                                                    color = Color.Transparent,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 4.dp, horizontal = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Spacer(modifier = Modifier.width((item.level * 14).dp))

                                                        Icon(
                                                            imageVector = if (item.isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.outline,
                                                            modifier = Modifier.size(16.dp)
                                                        )

                                                        Spacer(modifier = Modifier.width(3.dp))

                                                        Icon(
                                                            imageVector = if (item.isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                                                            contentDescription = null,
                                                            tint = Color(0xFFE5A93C),
                                                            modifier = Modifier.size(16.dp)
                                                        )

                                                        Spacer(modifier = Modifier.width(6.dp))

                                                        Text(
                                                            text = pkg.name,
                                                            style = MaterialTheme.typography.bodySmall.copy(
                                                                fontFamily = AppFonts.JetBrainsMono,
                                                                fontSize = 12.sp
                                                            ),
                                                            color = Color(0xFFEDE0DE),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f, fill = false)
                                                        )

                                                        Text(
                                                            text = " (${pkg.totalClassesCount})",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontFamily = AppFonts.JetBrainsMono,
                                                                fontSize = 10.sp
                                                            ),
                                                            color = Color(0xFF75605E),
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                            is DexTreeRow.ClassRow -> {
                                                val cls = item.classNode
                                                val isSelected = cls == selectedClass
                                                val rowBg = if (isSelected) NoctaliaPrimary.copy(alpha = 0.2f) else Color.Transparent
                                                val rowBorder = if (isSelected) BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.6f)) else null

                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 1.dp)
                                                        .clickable { selectClass(cls) },
                                                    color = rowBg,
                                                    shape = RoundedCornerShape(4.dp),
                                                    border = rowBorder
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 3.dp, horizontal = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Spacer(modifier = Modifier.width((item.level * 14 + 18).dp))

                                                        ClassTypeBadge(cls)

                                                        Spacer(modifier = Modifier.width(6.dp))

                                                        Text(
                                                            text = cls.simpleName,
                                                            style = MaterialTheme.typography.bodySmall.copy(
                                                                fontFamily = AppFonts.JetBrainsMono,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                fontSize = 12.sp
                                                            ),
                                                            color = if (isSelected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
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
                        }
                    }

                    // Divider
                    Surface(
                        modifier = Modifier.width(1.dp).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant
                    ) {}

                    // Right: Code Editor View
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (isLoadingCode) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = NoctaliaPrimary
                            )
                        } else {
                            if (editorTab == 0) {
                                CodeEditorView(
                                    initialText = smaliCode,
                                    language = Languages.SMALI,
                                    readOnly = false,
                                    fileName = "${selectedClass?.simpleName ?: "Class"}.smali",
                                    onSave = { updatedSmali ->
                                        val node = selectedClass ?: throw IllegalStateException("Класс не выбран")
                                        engine.compileSmali(node, updatedSmali)
                                        dexDirty = true
                                    },
                                    controller = smaliController,
                                    savedMessage = "Класс собран — DEX запишется по Ctrl+Shift+S или при закрытии",
                                    saveButtonText = "Собрать (Ctrl+S)",
                                    errorPrefix = "Ошибка smali"
                                )
                            } else if (editorTab == 1) {
                                CodeEditorView(
                                    initialText = javaCode,
                                    language = Languages.JAVA,
                                    readOnly = true,
                                    fileName = "${selectedClass?.simpleName ?: "Class"}.java"
                                )
                            } else if (editorTab == 2) {
                                CodeEditorView(
                                    initialText = cfrCode,
                                    language = Languages.JAVA,
                                    readOnly = true,
                                    fileName = "${selectedClass?.simpleName ?: "Class"}.java"
                                )
                            } else if (editorTab == 3) {
                                CodeEditorView(
                                    initialText = vfCode,
                                    language = Languages.JAVA,
                                    readOnly = true,
                                    fileName = "${selectedClass?.simpleName ?: "Class"}.java"
                                )
                            } else if (editorTab == 4) {
                                CodeEditorView(
                                    initialText = procyonCode,
                                    language = Languages.JAVA,
                                    readOnly = true,
                                    fileName = "${selectedClass?.simpleName ?: "Class"}.java"
                                )
                            } else {
                                val ghidraClass = selectedClass
                                if (ghidraClass == null) {
                                    GrailEmptyState("Выбери класс слева")
                                } else {
                                    GhidraListingView(
                                        engine = engine,
                                        classNode = ghidraClass,
                                        methodKey = ghidraMethodKey,
                                        onMethodSelect = { ghidraMethodKey = it },
                                        onNavigateToMethod = { clsName, mKey ->
                                            engine.classes.firstOrNull { it.fullName == clsName }?.let {
                                                selectClass(it)
                                                if (!smaliController.isDirty) {
                                                    ghidraMethodKey = mKey
                                                    editorTab = 5
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ToastHost(toast)
            }

            pendingSelect?.let { next ->
                UnsavedChangesDialog(
                    fileName = "${selectedClass?.simpleName ?: "класс"}.smali",
                    message = "Правки smali в «${selectedClass?.fullName}» ещё не собраны. Собрать перед переходом к «${next.simpleName}»?",
                    saveText = "Собрать",
                    discardText = "Отменить правки",
                    error = smaliController.saveError,
                    onSave = {
                        if (compileSmaliNow() == null) {
                            pendingSelect = null
                            selectedClass = next
                        }
                    },
                    onDiscard = {
                        pendingSelect = null
                        smaliController.reset(smaliCode)
                        selectedClass = next
                    },
                    onCancel = { pendingSelect = null }
                )
            }

            if (askClose) {
                UnsavedChangesDialog(
                    fileName = dexName,
                    message = if (canWriteDex) {
                        "Есть несохранённые правки DEX ($dexName). Собрать и записать их перед закрытием?"
                    } else {
                        "Есть правки smali, но этот DEX открыт только для просмотра — сохранить их некуда."
                    },
                    busy = savingDex,
                    error = closeError,
                    canSave = canWriteDex,
                    onSave = {
                        closeError = null
                        val compileErr = if (smaliController.isDirty) compileSmaliNow() else null
                        if (compileErr != null) {
                            closeError = "Ошибка smali: " + compileErr.lineSequence().first()
                        } else if (dexDirty) {
                            saveDex { err -> if (err == null) closeEditor() else closeError = err }
                        } else {
                            closeEditor()
                        }
                    },
                    onDiscard = { closeEditor() },
                    onCancel = { askClose = false }
                )
            }
        }
    }
}
}

sealed interface DexTreeRow {
    val id: String
    val level: Int

    data class PackageRow(
        val node: DexPackageNode,
        override val level: Int,
        val isExpanded: Boolean
    ) : DexTreeRow {
        override val id: String get() = "pkg:${node.fullPath}"
    }

    data class ClassRow(
        val classNode: DexClassNode,
        override val level: Int
    ) : DexTreeRow {
        override val id: String get() = "cls:${classNode.fullName}"
    }
}

private fun flattenDexTree(
    root: DexPackageNode,
    expandedPackages: Set<String>
): List<DexTreeRow> {
    val result = ArrayList<DexTreeRow>()

    fun traverse(pkg: DexPackageNode, level: Int) {
        val isExpanded = expandedPackages.contains(pkg.fullPath)
        result.add(DexTreeRow.PackageRow(pkg, level = level, isExpanded = isExpanded))
        if (isExpanded) {
            // Packages first
            for (sub in pkg.subPackages) {
                traverse(sub, level + 1)
            }
            // Classes second
            for (cls in pkg.classes) {
                result.add(DexTreeRow.ClassRow(cls, level = level + 1))
            }
        }
    }

    // Root packages FIRST
    for (pkg in root.subPackages) {
        traverse(pkg, level = 0)
    }

    // Root-level classes LAST
    for (cls in root.classes) {
        result.add(DexTreeRow.ClassRow(cls, level = 0))
    }

    return result
}

@Composable
private fun ClassTypeBadge(classNode: DexClassNode) {
    val (label, circleColor) = when {
        classNode.isInterface -> "I" to Color(0xFF9D5BD2)   // Purple
        classNode.isEnum -> "E" to Color(0xFFE5A93C)        // Amber
        classNode.isAnnotation -> "@" to NoctaliaDanger   // Coral Red
        classNode.isAbstract -> "A" to Color(0xFF4A90E2)     // Blue
        else -> "C" to Color(0xFF2EB886)                     // Emerald Green
    }

    Box(
        modifier = Modifier
            .size(16.dp)
            .background(color = circleColor, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            lineHeight = 9.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}


