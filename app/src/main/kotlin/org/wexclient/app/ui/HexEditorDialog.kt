package org.wexclient.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
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
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaWarn
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.util.isCtrlShortcut
import org.wexclient.app.util.matchesKey
import org.wexclient.app.vfs.VfsManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Composable
fun HexEditorDialog(
    entry: VfsEntry,
    initialBytes: ByteArray? = null,
    vfsManager: VfsManager,
    onDismiss: () -> Unit,
    onSaved: (() -> Unit)? = null
) {
    var bytesData by remember { mutableStateOf<ByteArray?>(initialBytes) }
    var isLoading by remember { mutableStateOf(initialBytes == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var selectedOffset by remember { mutableStateOf(0) }
    var isDirty by remember { mutableStateOf(false) }

    // Search state
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isHexSearch by remember { mutableStateOf(false) }
    var searchMatchOffsets by remember { mutableStateOf<List<Int>>(emptyList()) }
    var currentMatchIndex by remember { mutableStateOf(0) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()

    // Правка байтов: исходные значения изменённых смещений (для подсветки и отмены)
    val originals = remember { mutableStateMapOf<Int, Byte>() }
    val undoStack = remember { ArrayList<Pair<Int, Byte>>() }
    var pendingNibble by remember { mutableStateOf<Int?>(null) }
    var editVersion by remember { mutableStateOf(0) }

    val saver = rememberBackgroundSave()

    fun setByte(offset: Int, value: Int) {
        val data = bytesData ?: return
        if (offset !in data.indices) return
        // Массив пишется на диск без копии — пока идёт запись, байты не трогаем
        if (saver.busy) {
            toast.info("Идёт сохранение…")
            return
        }
        undoStack.add(offset to data[offset])
        if (!originals.containsKey(offset)) originals[offset] = data[offset]
        data[offset] = value.toByte()
        if (originals[offset] == data[offset]) originals.remove(offset)
        isDirty = originals.isNotEmpty()
        editVersion++
    }

    fun undo() {
        val data = bytesData ?: return
        if (saver.busy) return
        val (offset, old) = undoStack.removeLastOrNull() ?: return
        // Байт мог уже вернуться к исходному — тогда его снова запоминаем, иначе «изменено» пропадало
        if (!originals.containsKey(offset)) originals[offset] = data[offset]
        data[offset] = old
        if (originals[offset] == old) originals.remove(offset)
        isDirty = originals.isNotEmpty()
        selectedOffset = offset
        pendingNibble = null
        editVersion++
    }

    fun moveSelection(delta: Int) {
        val data = bytesData ?: return
        if (data.isEmpty()) return
        selectedOffset = (selectedOffset + delta).coerceIn(0, data.size - 1)
        pendingNibble = null
        val row = selectedOffset / 16
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && (row < visible.first().index || row > visible.last().index - 1)) {
            scope.launch { listState.scrollToItem((row - 2).coerceAtLeast(0)) }
        }
    }

    /** Запись в фоне (файл в архиве — секунды); [done] получает null или текст ошибки. */
    fun saveNow(done: (String?) -> Unit = {}): Boolean {
        val data = bytesData ?: run {
            done("Нет данных")
            return true
        }
        return saver.run({ vfsManager.writeBytes(entry.path, data) }) { err ->
            if (err == null) {
                originals.clear()
                undoStack.clear()
                isDirty = false
                onSaved?.invoke()
                toast.success("Сохранено: ${entry.name}")
            } else {
                toast.error("Не сохранено: $err")
            }
            done(err)
        }
    }

    var searchFocused by remember { mutableStateOf(false) }
    // Поиск — в фоне и с паузой: на большом файле он занимает заметное время
    LaunchedEffect(searchQuery, isHexSearch, bytesData) {
        val buf = bytesData ?: return@LaunchedEffect
        val q = searchQuery
        if (q.isEmpty()) {
            searchMatchOffsets = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(150)
        val found = withContext(Dispatchers.Default) { performSearch(buf, q, isHexSearch) }
        searchMatchOffsets = found
        currentMatchIndex = 0
        if (found.isNotEmpty()) {
            selectedOffset = found[0]
            listState.animateScrollToItem(found[0] / 16)
        }
    }

    val guard = rememberCloseGuard(isDirty = { isDirty }, onClose = onDismiss)
    TrackUnsaved(entry.name, isDirty)

    fun hexDigit(event: androidx.compose.ui.input.key.KeyEvent): Int? {
        val digits = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        val numpad = listOf(Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9)
        digits.indexOf(event.key).takeIf { it >= 0 }?.let { return it }
        numpad.indexOf(event.key).takeIf { it >= 0 }?.let { return it }
        // A–F по физической клавише (работает и в русской раскладке)
        listOf(Key.A, Key.B, Key.C, Key.D, Key.E, Key.F).forEachIndexed { i, k -> if (event.matchesKey(k)) return 10 + i }
        return null
    }

    fun handleKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val data = bytesData ?: return false
        if (event.isCtrlShortcut(Key.S)) {
            if (isDirty) saveNow()
            return true
        }
        // Набор в поле поиска: нажатия букв и цифр доходят и сюда — байты не трогаем
        if (searchFocused) return false
        if (event.isCtrlShortcut(Key.Z)) {
            undo()
            return true
        }
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        when (event.key) {
            Key.DirectionLeft -> moveSelection(-1)
            Key.DirectionRight -> moveSelection(1)
            Key.DirectionUp -> moveSelection(-16)
            Key.DirectionDown -> moveSelection(16)
            Key.PageUp -> moveSelection(-16 * 20)
            Key.PageDown -> moveSelection(16 * 20)
            Key.MoveHome -> moveSelection(-(selectedOffset % 16))
            Key.MoveEnd -> moveSelection(15 - selectedOffset % 16)
            Key.Escape -> pendingNibble = null
            else -> {
                val d = hexDigit(event) ?: return false
                if (selectedOffset !in data.indices) return false
                val high = pendingNibble
                if (high == null) {
                    // Первая цифра — старший полубайт, младший пока прежний
                    setByte(selectedOffset, (d shl 4) or (data[selectedOffset].toInt() and 0x0F))
                    pendingNibble = d
                } else {
                    setByte(selectedOffset, (high shl 4) or d)
                    pendingNibble = null
                    if (selectedOffset < data.size - 1) moveSelection(1)
                }
            }
        }
        return true
    }

    // Load bytes if not provided
    LaunchedEffect(entry.path) {
        if (bytesData == null) {
            isLoading = true
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val size = runCatching { vfsManager.size(entry.path) }.getOrDefault(entry.size)
                    if (size > MAX_HEX_BYTES) {
                        throw java.io.IOException("файл больше ${MAX_HEX_BYTES / (1024 * 1024)} МБ — hex-редактор держит файл в памяти целиком")
                    }
                    vfsManager.readBytes(entry.path)
                }
                bytesData = loaded
            } catch (e: Throwable) {
                errorMessage = "Ошибка чтения: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                isLoading = false
            }
        }
    }

    DialogWindow(
        onCloseRequest = { guard.requestClose() },
        onKeyEvent = latestKeyHandler { handleKey(it) },
        state = rememberDialogState(size = DpSize(1080.dp, 750.dp)),
        title = "Hex-редактор — ${entry.name}"
    ) {
        NoctaliaTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
              Box(modifier = Modifier.fillMaxSize()) {
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
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Memory,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isDirty) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(8.dp)
                                            ) {}
                                        }
                                    }
                                    val sizeStr = bytesData?.let { "${it.size} байт (${entry.formattedSize})" } ?: entry.formattedSize
                                    Text(
                                        text = sizeStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { showSearchBar = !showSearchBar },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = if (showSearchBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = "Поиск байт / текста")
                                }

                                if (isDirty) {
                                    Text(
                                        text = "изменено байт: ${originals.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NoctaliaSecondary,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                    IconButton(
                                        onClick = { undo() },
                                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Отменить (Ctrl+Z)")
                                    }
                                    IconButton(
                                        onClick = { saveNow() },
                                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Icon(Icons.Default.Save, contentDescription = "Сохранить изменения (Ctrl+S)")
                                    }
                                }

                                IconButton(
                                    onClick = { guard.requestClose() },
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                                }
                            }
                        }
                    }

                    // Search Toolbar (Animated)
                    AnimatedVisibility(visible = showSearchBar) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { q -> searchQuery = q },
                                    placeholder = {
                                        Text(
                                            if (isHexSearch) "Hex байты (напр. 50 4B 03 04)..." else "Текстовая строка (ASCII)...",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).height(48.dp)
                                        .onFocusChanged { searchFocused = it.isFocused },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Surface(
                                    onClick = {
                                        isHexSearch = !isHexSearch
                                        bytesData?.let { buf ->
                                            searchMatchOffsets = performSearch(buf, searchQuery, isHexSearch)
                                            currentMatchIndex = 0
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isHexSearch) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.height(40.dp)
                                ) {
                                    Box(modifier = Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = if (isHexSearch) "Режим: HEX" else "Режим: ТЕКСТ",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isHexSearch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                if (searchMatchOffsets.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${currentMatchIndex + 1} из ${searchMatchOffsets.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.outline
                                    )

                                    IconButton(
                                        onClick = {
                                            if (searchMatchOffsets.isNotEmpty()) {
                                                currentMatchIndex = (currentMatchIndex - 1 + searchMatchOffsets.size) % searchMatchOffsets.size
                                                val match = searchMatchOffsets[currentMatchIndex]
                                                selectedOffset = match
                                                scope.launch { listState.animateScrollToItem(match / 16) }
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Предыдущее совпадение")
                                    }

                                    IconButton(
                                        onClick = {
                                            if (searchMatchOffsets.isNotEmpty()) {
                                                currentMatchIndex = (currentMatchIndex + 1) % searchMatchOffsets.size
                                                val match = searchMatchOffsets[currentMatchIndex]
                                                selectedOffset = match
                                                scope.launch { listState.animateScrollToItem(match / 16) }
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Следующее совпадение")
                                    }
                                }
                            }
                        }
                    }

                    // Main Hex Matrix + Data Inspector
                    when {
                        isLoading -> {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                WexSpinner(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        errorMessage != null -> {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        bytesData != null -> {
                            val data = bytesData!!
                            val totalRows = (data.size + 15) / 16

                            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                // Hex Viewport
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        // Header Row: Offsets 00..0F
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Смещение",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.width(88.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))

                                            // 16 Hex Header Numbers
                                            Row(modifier = Modifier.width(360.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                                for (i in 0 until 16) {
                                                    Text(
                                                        text = "%02X".format(i),
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (i == selectedOffset % 16) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(20.dp))
                                            Text(
                                                text = "ASCII",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                                        // Hex Rows LazyColumn
                                        LazyColumn(
                                            state = listState,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(totalRows) { rowIndex ->
                                                @Suppress("UNUSED_VARIABLE")
                                                val version = editVersion // байты мутируют на месте — подписка на перерисовку
                                                val rowStart = rowIndex * 16
                                                val isRowSelected = selectedOffset in rowStart until minOf(data.size, rowStart + 16)

                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(if (isRowSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                                                        .padding(vertical = 1.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // Offset (Hex)
                                                    Text(
                                                        text = "%08X".format(rowStart),
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.outline,
                                                        modifier = Modifier.width(88.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))

                                                    // 16 Hex Bytes
                                                    Row(
                                                        modifier = Modifier.width(360.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        for (col in 0 until 16) {
                                                            val offset = rowStart + col
                                                            if (offset < data.size) {
                                                                val b = data[offset].toInt() and 0xFF
                                                                val isSelected = offset == selectedOffset
                                                                val isMatched = searchMatchOffsets.contains(offset)
                                                                val isEdited = originals.containsKey(offset)

                                                                val byteColor = when {
                                                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                                                    isEdited -> NoctaliaWarn
                                                                    isMatched -> MaterialTheme.colorScheme.primary
                                                                    b == 0 -> Color(0xFF555555) // dimmed null byte
                                                                    b in 32..126 -> MaterialTheme.colorScheme.onSurface // printable
                                                                    b == 0xFF -> Color(0xFFFF8A80) // 0xFF
                                                                    else -> MaterialTheme.colorScheme.tertiary
                                                                }

                                                                val byteBg = when {
                                                                    isSelected && pendingNibble != null -> NoctaliaWarn
                                                                    isSelected -> MaterialTheme.colorScheme.primary
                                                                    isEdited -> NoctaliaWarn.copy(alpha = 0.16f)
                                                                    isMatched -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                                                    else -> Color.Transparent
                                                                }

                                                                Box(
                                                                    modifier = Modifier
                                                                        .size(18.dp)
                                                                        .clip(RoundedCornerShape(3.dp))
                                                                        .background(byteBg)
                                                                        .clickable {
                                                                            selectedOffset = offset
                                                                            pendingNibble = null
                                                                        },
                                                                    contentAlignment = Alignment.Center
                                                                ) {
                                                                    // Высота строки — по шрифту: иначе берётся 24sp из темы,
                                                                    // цифры центрируются в ней, съезжают вниз и срезаются ячейкой 18dp
                                                                    Text(
                                                                        text = "%02X".format(b),
                                                                        fontFamily = FontFamily.Monospace,
                                                                        fontSize = 11.sp,
                                                                        lineHeight = 11.sp,
                                                                        fontWeight = if (isSelected || isMatched) FontWeight.Bold else FontWeight.Normal,
                                                                        color = byteColor
                                                                    )
                                                                }
                                                            } else {
                                                                Spacer(modifier = Modifier.size(18.dp))
                                                            }
                                                        }
                                                    }

                                                    Spacer(modifier = Modifier.width(20.dp))

                                                    // ASCII Representation
                                                    Row {
                                                        for (col in 0 until 16) {
                                                            val offset = rowStart + col
                                                            if (offset < data.size) {
                                                                val b = data[offset].toInt() and 0xFF
                                                                val ch = if (b in 32..126) b.toChar() else '.'
                                                                val isSelected = offset == selectedOffset
                                                                Text(
                                                                    text = ch.toString(),
                                                                    fontFamily = FontFamily.Monospace,
                                                                    fontSize = 12.sp,
                                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else if (ch == '.') Color(0xFF555555) else MaterialTheme.colorScheme.onSurface,
                                                                    modifier = Modifier
                                                                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                                                                        .clickable { selectedOffset = offset }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Data Inspector Sidebar
                                Surface(
                                    modifier = Modifier
                                        .width(300.dp)
                                        .fillMaxHeight(),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    tonalElevation = 6.dp
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Инспектор данных",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Правка: 0–9, A–F; стрелки — выбор байта; Ctrl+Z — отмена; Ctrl+S — сохранить",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                                        // Position
                                        InspectorField(
                                            label = "Смещение (Offset)",
                                            value = "0x%08X (%d)".format(selectedOffset, selectedOffset)
                                        )

                                        val b = if (selectedOffset < data.size) data[selectedOffset].toInt() and 0xFF else 0
                                        InspectorField(label = "Байт (Hex / Dec)", value = "0x%02X (%d)".format(b, b))
                                        InspectorField(
                                            label = "Битовое представление",
                                            value = "%8s".format(Integer.toBinaryString(b)).replace(' ', '0')
                                        )

                                        // Multi-byte values
                                        // Абсолютные чтения: position(0) у wrap(data, off, n) вёл к началу файла
                                        val remaining = data.size - selectedOffset
                                        val le = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                                        val be = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                                        val at = selectedOffset

                                        if (remaining >= 2) {
                                            val u16le = le.getShort(at).toInt() and 0xFFFF
                                            val u16be = be.getShort(at).toInt() and 0xFFFF
                                            InspectorField(label = "UInt16 (LE / BE)", value = "$u16le / $u16be")
                                        }

                                        if (remaining >= 4) {
                                            InspectorField(label = "Int32 (LE / BE)", value = "${le.getInt(at)} / ${be.getInt(at)}")
                                            InspectorField(label = "Float (32-bit LE)", value = le.getFloat(at).toString())
                                        }

                                        if (remaining >= 8) {
                                            InspectorField(label = "Int64 (LE)", value = le.getLong(at).toString())
                                        }

                                        Spacer(modifier = Modifier.weight(1f))

                                        // Quick Copy Selected Hex Bytes
                                        Surface(
                                            onClick = {
                                                val hexStr = "%02X".format(b)
                                                val sel = StringSelection(hexStr)
                                                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Копировать значение", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                ToastHost(toast)
              }
            }

            if (guard.asking) {
                UnsavedChangesDialog(
                    fileName = entry.name,
                    message = "В «${entry.name}» изменено байт: ${originals.size}. Сохранить перед закрытием?",
                    error = guard.saveError,
                    busy = guard.saving,
                    onSave = { guard.saveAndCloseAsync { done -> saveNow(done) } },
                    onDiscard = { guard.discard() },
                    onCancel = { guard.cancel() }
                )
            }
        }
    }
}

@Composable
private fun InspectorField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun performSearch(data: ByteArray, query: String, isHex: Boolean): List<Int> {
    if (query.isBlank()) return emptyList()
    val targetBytes: ByteArray = if (isHex) {
        val clean = query.replace(" ", "").replace("0x", "")
        if (clean.length % 2 != 0) return emptyList()
        try {
            clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Throwable) {
            return emptyList()
        }
    } else {
        query.toByteArray(Charsets.UTF_8)
    }

    if (targetBytes.isEmpty() || targetBytes.size > data.size) return emptyList()

    val matches = mutableListOf<Int>()
    val maxScan = data.size - targetBytes.size
    for (i in 0..maxScan) {
        var found = true
        for (j in targetBytes.indices) {
            if (data[i + j] != targetBytes[j]) {
                found = false
                break
            }
        }
        if (found) {
            matches.add(i)
            if (matches.size >= 1000) break
        }
    }
    return matches
}

/** Больше — не открываем: файл целиком лежит в памяти. */
private const val MAX_HEX_BYTES = 256L * 1024 * 1024
