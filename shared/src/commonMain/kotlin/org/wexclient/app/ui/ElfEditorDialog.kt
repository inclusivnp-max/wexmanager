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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.wexclient.app.elf.ElfDocument
import org.wexclient.app.elf.ElfHeaderField
import org.wexclient.app.elf.ElfParser
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary

private const val BYTES_PER_ROW = 16

@Composable
fun ElfEditorDialog(
    doc: ElfDocument,
    fileName: String,
    onDismiss: () -> Unit,
    onSave: (ByteArray) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    // doc.data мутирует in-place — счётчик версий дёргает рекомпозицию таблиц
    var docVersion by remember { mutableStateOf(0) }
    var dirty by remember { mutableStateOf(doc.isDirty()) }
    var saveMsg by remember { mutableStateOf<String?>(null) }
    val toast = rememberToastState()

    val saver = rememberBackgroundSave()

    // Снимок — в потоке окна, запись (в APK — перезапись архива) — в фоне
    fun saveNow(done: (String?) -> Unit = {}): Boolean {
        val bytes = doc.toByteArray()
        return saver.run({ onSave(bytes) }) { err ->
            if (err == null) {
                doc.markSaved(bytes)
                dirty = doc.isDirty()
                saveMsg = null
                toast.success("Сохранено: $fileName")
            } else {
                saveMsg = "Ошибка: $err"
                toast.error("Не сохранено: $err")
            }
            done(err)
        }
    }

    fun touched() {
        docVersion++
        dirty = doc.isDirty()
        saveMsg = null
    }

    GrailDialogShell(
        title = "ELF Редактор - $fileName",
        badge = "ELF",
        heading = "ELF Редактор: $fileName",
        subtitle = "${if (doc.is64Bit) "64-bit" else "32-bit"}, " +
                "${if (doc.littleEndian) "LE" else "BE"}, " +
                ElfParser.machineName(doc.parsed.header.machine),
        isDirty = dirty,
        onDismiss = onDismiss,
        onSave = { done -> saveNow(done) },
        toast = toast,
        actions = {
            GrailTabs(
                listOf("Заголовок", "Сегменты", "Секции", "Символы", "Динамика", "Hex", "Дизасм"),
                tab
            ) { tab = it }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = { saveNow() },
                enabled = dirty && !saver.busy,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NoctaliaPrimary,
                    disabledContainerColor = NoctaliaPrimary.copy(alpha = 0.2f),
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (saver.busy) "Сохранение…" else "Сохранить (Ctrl+S)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) {

                    saveMsg?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (msg.startsWith("Ошибка")) MaterialTheme.colorScheme.error else NoctaliaSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            0 -> ElfHeaderTab(doc) { touched() }
                            1 -> ElfProgramsTab(doc, docVersion)
                            2 -> ElfSectionsTab(doc, docVersion)
                            3 -> ElfSymbolsTab(doc, docVersion)
                            4 -> ElfDynamicsTab(doc, docVersion)
                            5 -> ElfHexTab(doc, docVersion, fileName) { touched() }
                            else -> NativeDisasmTab(doc, docVersion)
                        }
                    }
    }
}

@Composable
private fun ElfInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(220.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = AppFonts.JetBrainsMono,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// --- Вкладка заголовка ---

@Composable
private fun ElfHeaderTab(doc: ElfDocument, onChanged: () -> Unit) {
    val h = doc.parsed.header
    val fieldTexts = remember {
        mutableStateMapOf<ElfHeaderField, String>().apply {
            ElfHeaderField.entries.forEach { f ->
                put(f, ElfDocument.formatHex(doc.getHeaderField(f)))
            }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            ElfInfoRow("Класс", if (h.is64Bit) "ELF64" else "ELF32")
            ElfInfoRow("Порядок байт", if (h.littleEndian) "Little-endian" else "Big-endian")
            ElfInfoRow("OS/ABI", ElfParser.osAbiName(h.osAbi))
            ElfInfoRow("Размер файла", "${doc.data.size} байт")
            ElfInfoRow("Секций / Сегментов", "${doc.parsed.sections.size} / ${doc.parsed.programs.size}")
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Редактируемые поля (hex 0x… или decimal)",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = NoctaliaPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        items(ElfHeaderField.entries.toList()) { field ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = field.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(220.dp)
                )
                OutlinedTextField(
                    value = fieldTexts[field] ?: "",
                    onValueChange = { fieldTexts[field] = it },
                    singleLine = true,
                    modifier = Modifier.width(220.dp).heightIn(min = 56.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = AppFonts.JetBrainsMono),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NoctaliaPrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                Spacer(modifier = Modifier.width(10.dp))
                val (off, size) = doc.headerFieldMeta(field)
                Text(
                    text = "оффсет 0x${Integer.toHexString(off).uppercase()} · $size байт",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        item {
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    for ((field, text) in fieldTexts) {
                        val v = ElfDocument.parseNumber(text)
                        if (v == null) {
                            error = "Не число: «$text» (${field.title})"
                            return@Button
                        }
                    }
                    fieldTexts.forEach { (field, text) ->
                        doc.setHeaderField(field, ElfDocument.parseNumber(text)!!)
                    }
                    error = null
                    onChanged()
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NoctaliaPrimary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Применить к заголовку", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Тип: 0=NONE, 1=REL, 2=EXEC, 3=DYN · Машина: 40=ARM, 183=AArch64, 3=x86, 62=x86-64",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// --- Вкладки таблиц ---

@Composable
private fun MonoCell(text: String, width: androidx.compose.ui.unit.Dp, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppFonts.JetBrainsMono, fontSize = 11.sp),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(end = 8.dp)
    )
}

@Composable
private fun ElfProgramsTab(doc: ElfDocument, version: Int) {
    @Suppress("UNUSED_EXPRESSION") version
    val programs = remember(version) { doc.programs() }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(modifier = Modifier.padding(bottom = 6.dp)) {
                MonoCell("#", 36.dp, MaterialTheme.colorScheme.outline)
                MonoCell("Тип", 130.dp, MaterialTheme.colorScheme.outline)
                MonoCell("Флаги", 60.dp, MaterialTheme.colorScheme.outline)
                MonoCell("Смещение", 110.dp, MaterialTheme.colorScheme.outline)
                MonoCell("VAddr", 150.dp, MaterialTheme.colorScheme.outline)
                MonoCell("FileSize", 110.dp, MaterialTheme.colorScheme.outline)
                MonoCell("MemSize", 110.dp, MaterialTheme.colorScheme.outline)
            }
        }
        items(programs) { p ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                MonoCell("${p.index}", 36.dp, NoctaliaSecondary)
                MonoCell(ElfParser.progTypeName(p.type), 130.dp, NoctaliaPrimary)
                MonoCell(ElfParser.progFlagsString(p.flags), 60.dp)
                MonoCell(ElfDocument.formatHex(p.offset, 6), 110.dp)
                MonoCell(ElfDocument.formatHex(p.vaddr, if (doc.is64Bit) 16 else 8), 150.dp)
                MonoCell("${p.fileSize}", 110.dp)
                MonoCell("${p.memSize}", 110.dp)
            }
        }
        if (programs.isEmpty()) {
            item { Text("Сегментов нет", color = MaterialTheme.colorScheme.outline) }
        }
    }
}

@Composable
private fun ElfSectionsTab(doc: ElfDocument, version: Int) {
    @Suppress("UNUSED_EXPRESSION") version
    val sections = remember(version) { doc.sections() }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(modifier = Modifier.padding(bottom = 6.dp)) {
                MonoCell("#", 40.dp, MaterialTheme.colorScheme.outline)
                MonoCell("Имя", 260.dp, MaterialTheme.colorScheme.outline)
                MonoCell("Тип", 120.dp, MaterialTheme.colorScheme.outline)
                MonoCell("Флаги", 90.dp, MaterialTheme.colorScheme.outline)
                MonoCell("Смещение", 110.dp, MaterialTheme.colorScheme.outline)
                MonoCell("Размер", 110.dp, MaterialTheme.colorScheme.outline)
            }
        }
        items(sections) { s ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                MonoCell("${s.index}", 40.dp, NoctaliaSecondary)
                MonoCell(s.name.ifEmpty { "<без имени>" }, 260.dp, NoctaliaPrimary)
                MonoCell(ElfParser.sectionTypeName(s.type), 120.dp)
                MonoCell(ElfDocument.formatHex(s.flags, if (doc.is64Bit) 16 else 8), 90.dp)
                MonoCell(ElfDocument.formatHex(s.offset, 6), 110.dp)
                MonoCell("${s.size}", 110.dp)
            }
        }
        if (sections.isEmpty()) {
            item { Text("Секций нет (stripped?)", color = MaterialTheme.colorScheme.outline) }
        }
    }
}

@Composable
private fun ElfSymbolsTab(doc: ElfDocument, version: Int) {
    @Suppress("UNUSED_EXPRESSION") version
    var filter by remember { mutableStateOf("") }
    val symbols = remember(version) { doc.symbols() }
    val shown = remember(symbols, filter) {
        val f = filter.trim()
        val base = if (f.isBlank()) symbols else symbols.filter { it.name.contains(f, ignoreCase = true) }
        base.take(3000)
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        GrailSearchField(
            value = filter,
            onChange = { filter = it },
            placeholder = "Фильтр символов..."
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Всего: ${symbols.size}, показано: ${shown.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(4.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(modifier = Modifier.padding(bottom = 6.dp)) {
                    MonoCell("Имя", 420.dp, MaterialTheme.colorScheme.outline)
                    MonoCell("Значение", 150.dp, MaterialTheme.colorScheme.outline)
                    MonoCell("Размер", 90.dp, MaterialTheme.colorScheme.outline)
                    MonoCell("Bind", 80.dp, MaterialTheme.colorScheme.outline)
                    MonoCell("Type", 90.dp, MaterialTheme.colorScheme.outline)
                }
            }
            items(shown) { s ->
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    MonoCell(s.name.ifEmpty { "<без имени>" }, 420.dp, NoctaliaPrimary)
                    MonoCell(ElfDocument.formatHex(s.value, if (doc.is64Bit) 16 else 8), 150.dp)
                    MonoCell("${s.size}", 90.dp)
                    MonoCell(ElfParser.symbolBindName(s.bind), 80.dp, NoctaliaSecondary)
                    MonoCell(ElfParser.symbolTypeName(s.type), 90.dp)
                }
            }
        }
    }
}

@Composable
private fun ElfDynamicsTab(doc: ElfDocument, version: Int) {
    @Suppress("UNUSED_EXPRESSION") version
    val needed = remember(version) { doc.neededLibraries() }
    val soname = remember(version) { doc.soname() }
    val dynamics = remember(version) { doc.dynamics() }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            ElfInfoRow("SONAME", soname ?: "—")
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Импортируемые библиотеки (NEEDED): ${needed.size}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = NoctaliaPrimary
            )
            needed.forEach { lib ->
                Text(
                    text = "• $lib",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppFonts.JetBrainsMono),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Все записи .dynamic: ${dynamics.size}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = NoctaliaPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        items(dynamics) { d ->
            Row(modifier = Modifier.padding(vertical = 1.dp)) {
                MonoCell(ElfParser.dynamicTagName(d.tag), 220.dp, NoctaliaSecondary)
                MonoCell(ElfDocument.formatHex(d.value, if (doc.is64Bit) 16 else 8), 220.dp)
            }
        }
    }
}

// --- Hex-редактор ---

@Composable
private fun ElfHexTab(doc: ElfDocument, version: Int, fileName: String, onChanged: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") version
    val data = doc.data
    val rowCount = (data.size + BYTES_PER_ROW - 1) / BYTES_PER_ROW
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf<Int?>(null) }
    var gotoText by remember { mutableStateOf("") }
    var byteText by remember { mutableStateOf("") }
    var hexError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GrailCompactField(
                value = gotoText,
                onChange = { gotoText = it },
                placeholder = "Смещение (0x…)",
                modifier = Modifier.width(170.dp),
                mono = true
            )
            Spacer(modifier = Modifier.width(6.dp))
            Button(
                onClick = {
                    val off = ElfDocument.parseNumber(gotoText)?.toInt()
                    if (off == null || off !in data.indices) {
                        hexError = "Нет такого смещения"
                    } else {
                        hexError = null
                        selected = off
                        scope.launch { listState.scrollToItem(off / BYTES_PER_ROW) }
                    }
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = NoctaliaPrimary
                )
            ) { Text("Перейти", fontSize = 12.sp) }
            Spacer(modifier = Modifier.width(16.dp))
            GrailCompactField(
                value = byteText,
                onChange = { byteText = it },
                placeholder = "Байт (FF)",
                modifier = Modifier.width(130.dp),
                mono = true
            )
            Spacer(modifier = Modifier.width(6.dp))
            Button(
                onClick = {
                    val off = selected
                    val v = ElfDocument.parseNumber(byteText)
                    if (off == null) {
                        hexError = "Сначала выбери байт кликом"
                    } else if (v == null || v < 0 || v > 255) {
                        hexError = "Байт: 00–FF или 0–255"
                    } else {
                        doc.setByte(off, v.toInt())
                        hexError = null
                        onChanged()
                    }
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NoctaliaPrimary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text("Задать", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = if (selected != null) {
                    "0x${Integer.toHexString(selected!!).padStart(8, '0').uppercase()} = ${data[selected!!].toInt() and 0xFF} (выбран)"
                } else {
                    "Размер: ${data.size} байт · клик по байту выбирает"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                color = hexError?.let { MaterialTheme.colorScheme.error } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(rowCount) { row ->
                val base = row * BYTES_PER_ROW
                Row(
                    modifier = Modifier.padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "0x" + Integer.toHexString(base).padStart(8, '0').uppercase(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = AppFonts.JetBrainsMono,
                            fontSize = 11.sp
                        ),
                        color = NoctaliaSecondary,
                        modifier = Modifier.width(90.dp)
                    )
                    for (i in 0 until BYTES_PER_ROW) {
                        val off = base + i
                        if (off >= data.size) {
                            Spacer(modifier = Modifier.width(26.dp))
                        } else {
                            val b = data[off].toInt() and 0xFF
                            val isSel = selected == off
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = if (isSel) NoctaliaPrimary.copy(alpha = 0.3f) else Color.Transparent,
                                border = if (isSel) BorderStroke(1.dp, NoctaliaPrimary) else null,
                                modifier = Modifier
                                    .width(26.dp)
                                    .clickable {
                                        selected = off
                                        byteText = "%02X".format(b)
                                    }
                            ) {
                                Text(
                                    text = "%02X".format(b),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = AppFonts.JetBrainsMono,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (b == 0) {
                                        MaterialTheme.colorScheme.outline
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                                )
                            }
                        }
                        if (i == 7) Spacer(modifier = Modifier.width(8.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    val ascii = buildString {
                        for (i in 0 until BYTES_PER_ROW) {
                            val off = base + i
                            if (off >= data.size) break
                            val b = data[off].toInt() and 0xFF
                            append(if (b in 0x20..0x7E) b.toChar() else '.')
                        }
                    }
                    Text(
                        text = ascii,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = AppFonts.JetBrainsMono,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
