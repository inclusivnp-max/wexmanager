package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import org.wexclient.app.ui.WexDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.vfs.BookmarkItem
import org.wexclient.app.vfs.BookmarksManager
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Панель быстрого доступа: чипы мест с иконками, прокрутка колесом по горизонтали,
 * «+» для добавления, ПКМ по чипу — изменить/переместить/удалить.
 */
@Composable
fun QuickAccessBar(
    currentPath: Path,
    onNavigate: (Path) -> Unit,
    onOpenInOtherPane: ((Path) -> Unit)? = null,
    /** Соседняя панель справа: стрелка «открыть в другой панели» смотрит туда же. */
    otherPaneOnRight: Boolean = true,
    onMessage: (String) -> Unit = {},
    onOpenTrash: (() -> Unit)? = null,
    /** Отключиться от сервера или телефона, на котором сейчас панель. */
    onDisconnect: ((org.wexclient.app.vfs.remote.RemoteFileSystem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    var editing by remember { mutableStateOf<BookmarkItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val currentStr = remember(currentPath) {
        if (currentPath.fileSystem === FileSystems.getDefault()) currentPath.toAbsolutePath().normalize().toString() else null
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Панель на сервере или телефоне — это видно всегда, даже когда начало пути ушло за край
        (currentPath.fileSystem as? org.wexclient.app.vfs.remote.RemoteFileSystem)?.let { server ->
            ServerChip(
                label = server.label,
                phone = server.kind == "adb",
                onHome = { onNavigate(server.homePath()) },
                onDisconnect = onDisconnect?.let { cb -> { cb(server) } }
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Box(modifier = Modifier.weight(1f)) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            // Обычное колесо мыши прокручивает чипы по горизонтали
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type != PointerEventType.Scroll) continue
                                    val change = event.changes.firstOrNull() ?: continue
                                    val d = change.scrollDelta
                                    if (d.y != 0f && d.x == 0f && scroll.maxValue > 0) {
                                        scroll.dispatchRawDelta(d.y * 48.dp.toPx())
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                        .horizontalScroll(scroll),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val probeScope = rememberCoroutineScope()
                    BookmarksManager.userBookmarks.forEach { item ->
                        PlaceChip(
                            item = item,
                            isCurrent = item.pathStr == currentStr,
                            onClick = {
                                // Проверка папки — не в потоке окна: недоступный сетевой диск её вешает
                                probeScope.launch {
                                    val ok = withContext(PlaceProbe.dispatcher) { File(item.pathStr).isDirectory }
                                    if (ok) onNavigate(Paths.get(item.pathStr))
                                    else onMessage("Папка «${item.title}» не найдена:\n${item.pathStr}")
                                }
                            },
                            onOpenInOtherPane = onOpenInOtherPane?.let { cb ->
                                {
                                    probeScope.launch {
                                        val ok = withContext(PlaceProbe.dispatcher) { File(item.pathStr).isDirectory }
                                        if (ok) cb(Paths.get(item.pathStr))
                                    }
                                }
                            },
                            onEdit = { editing = item },
                            otherPaneOnRight = otherPaneOnRight,
                            onMove = { delta -> BookmarksManager.move(item, delta) },
                            onRemove = { BookmarksManager.remove(item) },
                            onReset = { confirmReset = true },
                            isFirst = item == BookmarksManager.userBookmarks.firstOrNull(),
                            isLast = item == BookmarksManager.userBookmarks.lastOrNull()
                        )
                    }
                }
                if (scroll.maxValue > 0) {
                    CompositionLocalProvider(
                        LocalScrollbarStyle provides LocalScrollbarStyle.current.copy(
                            thickness = 3.dp,
                            minimalHeight = 24.dp,
                            unhoverColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            hoverColor = NoctaliaPrimary.copy(alpha = 0.7f)
                        )
                    ) {
                        HorizontalScrollbar(
                            adapter = rememberScrollbarAdapter(scroll),
                            modifier = Modifier.fillMaxWidth().padding(top = 1.dp)
                        )
                    }
                }
            }
            // Мягкие края, когда чипы уходят за границу
            val fade = MaterialTheme.colorScheme.surface
            if (scroll.value > 0) {
                Box(
                    modifier = Modifier.align(Alignment.CenterStart).width(18.dp).height(26.dp)
                        .background(Brush.horizontalGradient(listOf(fade, Color.Transparent)))
                )
            }
            if (scroll.value < scroll.maxValue) {
                Box(
                    modifier = Modifier.align(Alignment.CenterEnd).width(18.dp).height(26.dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, fade)))
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Корзина: число элементов перечитывается при каждом её изменении
        if (onOpenTrash != null) {
            val trashCount by androidx.compose.runtime.produceState(0, org.wexclient.app.vfs.Trash.version) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { org.wexclient.app.vfs.Trash.count() }
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onOpenTrash() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SymbolIcon(
                        SymbolRef(if (trashCount > 0) "delete" else "delete_outline", codepoint = if (trashCount > 0) 0xe872 else 0xe92e),
                        contentDescription = "Корзина",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        if (trashCount > 0) "Корзина · $trashCount" else "Корзина",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Добавить место
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = NoctaliaPrimary.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.35f)),
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { adding = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SymbolIcon(SymbolRef("add", codepoint = 0xe145), contentDescription = "Добавить место", tint = NoctaliaPrimary, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(2.dp))
                Text("Добавить", style = MaterialTheme.typography.labelSmall, color = NoctaliaPrimary)
            }
        }
    }

    if (adding) {
        val initialPath = currentStr ?: System.getProperty("user.home")
        PlaceEditDialog(
            title = "Новое место быстрого доступа",
            initial = BookmarkItem(
                title = Paths.get(initialPath).fileName?.toString() ?: initialPath,
                pathStr = initialPath
            ),
            currentPath = currentStr,
            onConfirm = { t, p, icon ->
                BookmarksManager.add(t, p, icon)
                adding = false
            },
            onDismiss = { adding = false }
        )
    }

    editing?.let { item ->
        PlaceEditDialog(
            title = "Изменить место",
            initial = item,
            currentPath = currentStr,
            onConfirm = { t, p, icon ->
                BookmarksManager.update(item, t, p, icon)
                editing = null
            },
            onDelete = {
                BookmarksManager.remove(item)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Сбросить панель",
            message = "Вернуть стандартный набор мест (Домой, Корень, Загрузки, Стол, Проекты)? Добавленные вами места будут удалены.",
            confirmText = "Сбросить",
            isDestructive = true,
            onConfirm = {
                BookmarksManager.resetToDefaults()
                confirmReset = false
            },
            onDismiss = { confirmReset = false }
        )
    }
}

@Composable
private fun PlaceChip(
    item: BookmarkItem,
    isCurrent: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onOpenInOtherPane: (() -> Unit)?,
    otherPaneOnRight: Boolean = true,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onReset: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    val exists by produceState(true, item.pathStr) {
        value = withContext(PlaceProbe.dispatcher) { File(item.pathStr).isDirectory }
    }

    Box {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isCurrent) NoctaliaPrimary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
            border = if (isCurrent) BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.6f)) else null,
            modifier = Modifier
                .padding(vertical = 2.dp)
                .alpha(if (exists) 1f else 0.5f)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(item) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                                val pos = e.changes.first().position
                                menuOffset = DpOffset(pos.x.toDp(), pos.y.toDp() - 26.dp)
                                menu = true
                                e.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SymbolIcon(
                    item.icon,
                    contentDescription = null,
                    tint = if (isCurrent) NoctaliaPrimary else NoctaliaSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    item.title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (isCurrent) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }

        WexDropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = menuOffset) {
            ReturnFocusOnClose()
            Text(
                item.pathStr,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono, fontSize = 10.sp),
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(240.dp)
            )
            HorizontalDivider()
            if (onOpenInOtherPane != null) {
                MenuEntry(
                    "tab_move",
                    "Открыть в другой панели",
                    enabled = exists,
                    pointsRight = otherPaneOnRight
                ) { menu = false; onOpenInOtherPane() }
            }
            MenuEntry("edit", "Изменить…") { menu = false; onEdit() }
            MenuEntry("arrow_back", "Переместить левее", enabled = !isFirst) { onMove(-1) }
            MenuEntry("arrow_forward", "Переместить правее", enabled = !isLast) { onMove(1) }
            MenuEntry("delete", "Удалить с панели", destructive = true) { menu = false; onRemove() }
            HorizontalDivider()
            MenuEntry("restart_alt", "Сбросить панель…") { menu = false; onReset() }
        }
    }
}

@Composable
internal fun MenuEntry(
    icon: String,
    text: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    checked: Boolean? = null,
    trailing: String? = null,
    /** Значок со стрелкой: false — она смотрит влево (соседняя панель слева). */
    pointsRight: Boolean = true,
    onClick: () -> Unit
) {
    val flip by androidx.compose.animation.core.animateFloatAsState(
        if (pointsRight) 1f else -1f,
        androidx.compose.animation.core.tween(Motion.ENTER),
        label = "menu-entry-flip"
    )
    val color = when {
        destructive -> MaterialTheme.colorScheme.error
        checked == true -> NoctaliaPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    DropdownMenuItem(
        modifier = Modifier.height(32.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp),
        leadingIcon = {
            SymbolIcon(
                SymbolRef(icon, if (checked == false) org.wexclient.app.icons.SymbolStyle.OUTLINED else org.wexclient.app.icons.SymbolStyle.FILLED),
                contentDescription = null,
                tint = if (enabled) (if (destructive) color else if (checked == true) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant) else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp).graphicsLayer { scaleX = flip }
            )
        },
        trailingIcon = when {
            checked == true -> {
                { SymbolIcon(SymbolRef("check", codepoint = 0xe5ca), contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp)) }
            }
            trailing != null -> {
                { Text(trailing, fontSize = 10.sp, fontFamily = AppFonts.JetBrainsMono, color = MaterialTheme.colorScheme.outline) }
            }
            else -> null
        },
        text = {
            Text(
                text,
                fontSize = 12.sp,
                fontWeight = if (checked == true) FontWeight.SemiBold else FontWeight.Medium,
                color = if (enabled) color else MaterialTheme.colorScheme.outline,
                maxLines = 1
            )
        },
        onClick = onClick
    )
}

/** Диалог места: название, путь (текущая папка / выбор), иконка из Material Symbols. */
@Composable
fun PlaceEditDialog(
    title: String,
    initial: BookmarkItem,
    currentPath: String?,
    onConfirm: (title: String, path: String, icon: SymbolRef) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initial.title) }
    var path by remember { mutableStateOf(initial.pathStr) }
    var icon by remember { mutableStateOf(initial.icon) }
    var nameTouched by remember { mutableStateOf(onDelete != null) }

    val expanded = BookmarksManager.expand(path)
    val pathOk = path.isNotBlank() && File(expanded).isDirectory
    val duplicate = BookmarksManager.userBookmarks.any { it.pathStr == expanded && it != initial }

    fun setPath(p: String) {
        path = p
        if (!nameTouched) {
            name = Paths.get(BookmarksManager.expand(p)).fileName?.toString() ?: p
        }
    }

    ModalDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 6.dp,
            modifier = Modifier.width(660.dp).dialogGlass(RoundedCornerShape(16.dp)).height(640.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = NoctaliaPrimary.copy(alpha = 0.16f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            SymbolIcon(icon, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = NoctaliaPrimary)
                        Text(
                            "Кнопка появится на панели под строкой пути",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    SymbolIconButton("close", "Закрыть", MaterialTheme.colorScheme.outline, onDismiss)
                }

                Spacer(modifier = Modifier.height(14.dp))

                SectionTitle("Название")
                Spacer(modifier = Modifier.height(4.dp))
                GrailCompactField(
                    value = name,
                    onChange = { name = it; nameTouched = true },
                    placeholder = "Например: Игры",
                    height = 38.dp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                SectionTitle("Папка")
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GrailCompactField(
                        value = path,
                        onChange = { setPath(it) },
                        placeholder = "~/Папка или /полный/путь",
                        mono = true,
                        height = 38.dp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { currentPath?.let { setPath(it) } },
                        enabled = currentPath != null,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(38.dp)
                    ) { Text("Текущая", fontSize = 12.sp) }
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { chooseDirectory(expanded)?.let { setPath(it) } },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(38.dp)
                    ) { Text("Выбрать…", fontSize = 12.sp) }
                }
                val hint = when {
                    path.isBlank() -> null
                    !pathOk -> "Папка не найдена: $expanded"
                    duplicate -> "Это место уже есть на панели"
                    else -> null
                }
                Text(
                    text = hint ?: " ",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!pathOk) MaterialTheme.colorScheme.error else NoctaliaSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))
                SectionTitle("Иконка (Material Symbols)")
                Spacer(modifier = Modifier.height(6.dp))
                SymbolPicker(
                    selected = icon,
                    onSelect = { icon = it },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )

                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onDelete != null) {
                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("Удалить") }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(name.ifBlank { File(expanded).name.ifBlank { expanded } }, expanded, icon) },
                        enabled = pathOk,
                        colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary)
                    ) { Text(if (onDelete != null) "Сохранить" else "Добавить") }
                }
            }
        }
    }
}

private fun chooseDirectory(start: String): String? {
    return try {
        val chooser = javax.swing.JFileChooser(File(start).takeIf { it.isDirectory } ?: File(System.getProperty("user.home")))
        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Выберите папку"
        chooser.isFileHidingEnabled = false
        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    } catch (_: Exception) {
        null
    }
}

/** Проверки мест на диске: отдельные потоки — зависший сетевой диск не займёт общий пул. */
internal object PlaceProbe {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val dispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(4)
}

/** Чип сервера: щелчок — домашняя папка на сервере, значок справа — отключиться. */
@Composable
private fun ServerChip(label: String, phone: Boolean, onHome: () -> Unit, onDisconnect: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = NoctaliaPrimary.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.45f))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onHome)
                    .padding(start = 6.dp, end = if (onDisconnect != null) 4.dp else 6.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SymbolIcon(
                    if (phone) SymbolRef("smartphone") else SymbolRef("dns", codepoint = 0xe875),
                    contentDescription = if (phone) "Телефон" else "Сервер",
                    tint = NoctaliaPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NoctaliaPrimary,
                    maxLines = 1
                )
            }
            if (onDisconnect != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onDisconnect)
                        .padding(start = 2.dp, end = 5.dp, top = 3.dp, bottom = 3.dp)
                ) {
                    SymbolIcon(
                        SymbolRef("link_off", codepoint = 0xe16f),
                        contentDescription = "Отключиться",
                        tint = NoctaliaPrimary.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
