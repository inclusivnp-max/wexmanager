package org.wexclient.app.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import org.wexclient.app.util.Keymap
import org.wexclient.app.ui.Motion
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Terminal
import org.wexclient.app.ui.WexDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import org.wexclient.app.vfs.ssh.SshFileSystem

/** Настройки консоли (~/.config/wexdesktop/terminal.properties). */
object TerminalPreferences {
    private val file = File(System.getProperty("user.home"), ".config/wexdesktop/terminal.properties")

    var fontSize by mutableFloatStateOf(13f)
        private set
    var shell by mutableStateOf(ShellLocator.loginShell())
        private set
    /** Консоль переходит в папку панели при навигации. */
    var followPane by mutableStateOf(true)
        private set

    /** Курсор над консолью сразу даёт ей фокус — без щелчка. */
    var focusOnHover by mutableStateOf(true)
        private set
    /** Доля высоты панели под консоль. */
    var heightFraction by mutableFloatStateOf(0.42f)
        private set

    init {
        runCatching {
            if (file.isFile) {
                val p = Properties()
                file.inputStream().use { p.load(it) }
                p.getProperty("fontSize")?.toFloatOrNull()?.let { fontSize = it.coerceIn(8f, 32f) }
                p.getProperty("shell")?.takeIf { File(it).canExecute() }?.let { shell = it }
                p.getProperty("followPane")?.toBooleanStrictOrNull()?.let { followPane = it }
                p.getProperty("focusOnHover")?.toBooleanStrictOrNull()?.let { focusOnHover = it }
                p.getProperty("heightFraction")?.toFloatOrNull()?.let { heightFraction = it.coerceIn(0.15f, 0.85f) }
            }
        }
    }

    private fun save() {
        runCatching {
            file.parentFile.mkdirs()
            val p = Properties()
            p.setProperty("fontSize", fontSize.toString())
            p.setProperty("shell", shell)
            p.setProperty("followPane", followPane.toString())
            p.setProperty("focusOnHover", focusOnHover.toString())
            p.setProperty("heightFraction", heightFraction.toString())
            // Атомарно: сбой посреди записи не оставит пустых настроек
            org.wexclient.app.util.AtomicFiles.writeProperties(file, p, "WEX Desktop Terminal")
        }
    }

    /** Отложенная запись: за одну прокрутку колеса размер меняется десяток раз, файл пишем один. */
    private val saveLater by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "wex-terminal-prefs").apply { isDaemon = true }
        }
    }
    private var pendingSave: java.util.concurrent.ScheduledFuture<*>? = null

    fun zoom(delta: Float) {
        val next = if (delta == 0f) 13f else (fontSize + delta).coerceIn(8f, 32f)
        if (next == fontSize) return
        fontSize = next
        pendingSave?.cancel(false)
        pendingSave = runCatching {
            saveLater.schedule({ save() }, 400, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
        if (pendingSave == null) save()
    }

    /** Записать отложенный размер шрифта перед закрытием программы. */
    fun flush() {
        val waiting = pendingSave
        if (waiting != null && !waiting.isDone) {
            waiting.cancel(false)
            save()
        }
    }

    fun updateShell(s: String) { shell = s; save() }
    fun updateFollowPane(v: Boolean) { followPane = v; save() }
    fun updateFocusOnHover(v: Boolean) { focusOnHover = v; save() }
    fun updateHeightFraction(v: Float) { heightFraction = v.coerceIn(0.15f, 0.85f) }
    fun commitHeight() = save()
}

/** Фокус в какой-либо консоли — глобальные сочетания файлового менеджера не перехватывают ввод. */
object TerminalFocus {
    var active by mutableStateOf(false)
        internal set

    /** Фокус в поле поиска по выводу: корневые сочетания панелей не должны красть буквы. */
    var inField by mutableStateOf(false)
        internal set

    /** Клавиши сейчас принадлежат консоли (сам вывод или её поле поиска). */
    val owned: Boolean get() = active || inField
}

private val sessionIds = AtomicInteger()

/** Сервер, на котором лежит папка (user@host), или null для папки на этом компьютере. */
private fun remoteOf(dir: Path): String? = (dir.fileSystem as? SshFileSystem)?.target?.label

/** Вкладки консоли одной панели. */
class TerminalPanelState(val paneName: String) {
    val sessions = mutableStateListOf<TerminalSession>()
    var activeIndex by mutableIntStateOf(0)
    var visible by mutableStateOf(false)
        private set
    var maximized by mutableStateOf(false)
    internal var focusRequest by mutableIntStateOf(0)

    /** Вернуть фокус файловому менеджеру (когда консоль скрыта или закрыта). */
    var onRequestOuterFocus: () -> Unit = {}

    val active: TerminalSession? get() = sessions.getOrNull(activeIndex.coerceIn(0, (sessions.size - 1).coerceAtLeast(0)))

    fun show(dir: Path) {
        val server = remoteOf(dir)
        if (server != null) {
            // Серверная панель — вкладка этого сервера, если уже открыта, иначе новый ssh-сеанс
            val idx = sessions.indexOfFirst { it.remote == server && it.exitCode == null }
            if (idx >= 0) activeIndex = idx else newSession(dir)
        } else if (sessions.isEmpty()) {
            newSession(dir)
        }
        visible = true
        focusRequest++
    }

    fun hide() {
        visible = false
        maximized = false
        TerminalFocus.active = false
        onRequestOuterFocus()
    }

    fun toggle(dir: Path) = if (visible) hide() else show(dir)

    fun newSession(dir: Path, shell: String = TerminalPreferences.shell) {
        val home = Paths.get(System.getProperty("user.home"))
        val server = dir.fileSystem as? SshFileSystem
        val session = if (server != null) {
            // Папка на сервере — консоль там же: ssh по соединению панели, сразу в эту папку
            TerminalSession(
                sessionIds.incrementAndGet(), home, shell,
                command = server.connection.shellCommand(dir.toAbsolutePath().toString()),
                remote = server.target.label
            )
        } else {
            TerminalSession(sessionIds.incrementAndGet(), dir.takeIf { it.fileSystem == FileSystems.getDefault() } ?: home, shell)
        }
        sessions.add(session)
        activeIndex = sessions.size - 1
        visible = true
        focusRequest++
    }

    /**
     * Запуск команды в консоли панели: свободная вкладка — в ней, занятая программой — в новой.
     * Консоль открывается и получает фокус.
     */
    fun runCommand(dir: Path, command: String) {
        val current = active
        // Вкладка должна быть там же, где папка: локальная — для диска, ssh этого сервера — для сервера
        val session = if (current != null && current.exitCode == null && current.remote == remoteOf(dir) && !current.isBusy()) {
            current
        } else {
            newSession(dir)
            active!!
        }
        session.runIn(dir, command)
        visible = true
        focusRequest++
    }

    fun close(session: TerminalSession) {
        val idx = sessions.indexOf(session)
        if (idx < 0) return
        session.close()
        sessions.removeAt(idx)
        if (sessions.isEmpty()) {
            hide()
            activeIndex = 0
        } else {
            activeIndex = activeIndex.coerceAtMost(sessions.size - 1)
            focusRequest++
        }
    }

    /** Следующая или предыдущая вкладка консоли по кругу. */
    fun cycle(step: Int) {
        if (sessions.size < 2) return
        val n = sessions.size
        activeIndex = ((activeIndex + step) % n + n) % n
        focusRequest++
    }

    fun restart(session: TerminalSession) {
        val idx = sessions.indexOf(session)
        if (idx < 0) return
        val dir = session.currentDirectory() ?: session.startDir
        session.close()
        sessions[idx] = TerminalSession(sessionIds.incrementAndGet(), dir, session.shell, session.command, session.remote)
        focusRequest++
    }

    fun dispose() {
        sessions.forEach { it.closeNow() }
        sessions.clear()
    }
}

@Composable
private fun PanelIcon(icon: ImageVector, hint: String, active: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Подложка и цвет значка меняются плавно: включённый режим видно и по цвету, и по движению
    val background by animateColorAsState(
        when {
            active -> NoctaliaPrimary.copy(alpha = if (hovered) 0.22f else 0.16f)
            hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        tween(Motion.TINT),
        label = "panel-icon-bg"
    )
    val tint by animateColorAsState(
        if (active) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(Motion.TINT),
        label = "panel-icon-tint"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(26.dp)
            .background(background, RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .clickable(onClick = onClick)
    ) {
        Icon(icon, contentDescription = hint, tint = tint, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun SessionTab(session: TerminalSession, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Звонок — вкладка подсвечивается на секунду
    var bell by remember { mutableStateOf(false) }
    LaunchedEffect(session.bellAt) {
        if (session.bellAt > 0) {
            bell = true
            delay(900)
            bell = false
        }
    }
    val exited = session.exitCode != null
    // Звонок вспыхивает и гаснет, выбор вкладки перетекает — без рывка цвета
    val background by animateColorAsState(
        when {
            bell -> NoctaliaPrimary.copy(alpha = 0.35f)
            selected -> NoctaliaPrimary.copy(alpha = 0.18f)
            hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
            else -> Color.Transparent
        },
        tween(if (bell) Motion.TINT else Motion.ENTER),
        label = "session-tab-bg"
    )
    val iconTint by animateColorAsState(
        when {
            exited -> MaterialTheme.colorScheme.outline
            selected -> NoctaliaPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        tween(Motion.TINT),
        label = "session-tab-tint"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(26.dp)
            .background(background, RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .clickable(onClick = onSelect)
            .padding(start = 8.dp, end = 2.dp)
    ) {
        Icon(Icons.Default.Terminal, null, tint = iconTint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            session.title.ifBlank { session.shell.substringAfterLast('/') },
            fontSize = 11.sp,
            fontFamily = AppFonts.JetBrainsMono,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp)
        )
        val closeInteraction = remember { MutableInteractionSource() }
        val closeHovered by closeInteraction.collectIsHoveredAsState()
        val closeTint by animateColorAsState(
            if (closeHovered) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            tween(Motion.TINT),
            label = "session-tab-close"
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(20.dp).hoverable(closeInteraction).clickable(onClick = onClose)
        ) {
            Icon(Icons.Default.Close, "Закрыть вкладку", tint = closeTint, modifier = Modifier.size(12.dp))
        }
    }
}

/**
 * Консоль панели: вкладки, панель действий и экран терминала.
 * [currentDir] — папка панели (консоль следует за ней, если включено).
 */
@Composable
fun TerminalPanel(
    state: TerminalPanelState,
    currentDir: Path,
    onOpenPath: (Path) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    var shellMenu by remember { mutableStateOf(false) }
    val active = state.active

    // Поиск по выводу: Ctrl+Shift+F
    var findOpen by remember(active) { mutableStateOf(false) }
    var findQuery by remember(active) { mutableStateOf("") }
    var findMatches by remember(active) { mutableStateOf<List<TerminalSession.Found>>(emptyList()) }
    var findIndex by remember(active) { mutableStateOf(0) }
    val findFocus = remember { FocusRequester() }

    fun runFind(query: String) {
        val s = active ?: return
        findQuery = query
        findMatches = if (query.length >= 2) s.find(query) else emptyList()
        findIndex = 0
        findMatches.firstOrNull()?.let { s.reveal(it) }
    }

    fun stepFind(step: Int) {
        val s = active ?: return
        if (findMatches.isEmpty()) return
        findIndex = ((findIndex + step) % findMatches.size + findMatches.size) % findMatches.size
        s.reveal(findMatches[findIndex])
    }

    // Следовать за папкой панели
    LaunchedEffect(currentDir, TerminalPreferences.followPane) {
        if (!TerminalPreferences.followPane) return@LaunchedEffect
        val s = state.active ?: return@LaunchedEffect
        if (currentDir.fileSystem != FileSystems.getDefault()) return@LaunchedEffect
        // ssh-сеанс работает на сервере: локальный cd туда набирать нельзя
        if (s.remote != null) return@LaunchedEffect
        delay(150)
        val cwd = s.currentDirectory()
        if (cwd != null && runCatching { cwd.toRealPath() == currentDir.toRealPath() }.getOrDefault(false)) return@LaunchedEffect
        if (s.pid != null) s.changeDirectory(currentDir)
    }

    LaunchedEffect(state.focusRequest, active, findOpen) {
        if (findOpen) return@LaunchedEffect
        if (state.focusRequest > 0) {
            repeat(10) {
                delay(16)
                if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }

    // Вернулись в окно WEX из другой программы — фокус остаётся у консоли, если он был у неё
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(active) {
        androidx.compose.runtime.snapshotFlow { windowInfo.isWindowFocused }.collect { windowFocused ->
            if (windowFocused && TerminalFocus.active && !findOpen) {
                delay(40)
                runCatching { focus.requestFocus() }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { TerminalFocus.active = false }
    }

    Column(
        modifier
            .background(MaterialTheme.colorScheme.background)
            // Ctrl+колесо меняет размер шрифта в любом месте консоли — не только над текстом.
            // Перехват на Initial: иначе колесо сначала прокручивает то, над чем курсор
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (ev.type != PointerEventType.Scroll) continue
                        if (!ev.keyboardModifiers.isCtrlPressed && !ev.keyboardModifiers.isMetaPressed) continue
                        val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy == 0f) continue
                        ev.changes.forEach { it.consume() }
                        TerminalPreferences.zoom(if (dy < 0) 1f else -1f)
                    }
                }
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
            ) {
                state.sessions.forEachIndexed { i, s ->
                    key(s.id) {
                        // Новая вкладка раскрывается, а не появляется рывком
                        var shown by remember(s.id) { mutableStateOf(false) }
                        LaunchedEffect(s.id) { shown = true }
                        AnimatedVisibility(
                            visible = shown,
                            enter = expandHorizontally(tween(Motion.ENTER)) + fadeIn(tween(Motion.ENTER)),
                            exit = shrinkHorizontally(tween(Motion.EXIT)) + fadeOut(tween(Motion.EXIT))
                        ) {
                            SessionTab(
                                session = s,
                                selected = i == state.activeIndex,
                                onSelect = {
                                    state.activeIndex = i
                                    state.focusRequest++
                                },
                                onClose = { state.close(s) }
                            )
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                }
                PanelIcon(Icons.Default.Add, "Новая вкладка (" + Keymap.label("term.newTab") + ")") {
                    state.newSession(active?.currentDirectory() ?: currentDir)
                }
                Box {
                    PanelIcon(Icons.Default.KeyboardArrowDown, "Оболочка") { shellMenu = true }
                    WexDropdownMenu(expanded = shellMenu, onDismissRequest = { shellMenu = false }) {
                        Text(
                            "Новая вкладка с оболочкой",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = NoctaliaPrimary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                        )
                        for (sh in remember { ShellLocator.available() }) {
                            DropdownMenuItem(
                                text = { Text(sh, fontSize = 13.sp, fontFamily = AppFonts.JetBrainsMono) },
                                leadingIcon = {
                                    if (sh == TerminalPreferences.shell) Icon(Icons.Default.Check, null, tint = NoctaliaPrimary, modifier = Modifier.size(14.dp))
                                },
                                onClick = {
                                    shellMenu = false
                                    TerminalPreferences.updateShell(sh)
                                    state.newSession(active?.currentDirectory() ?: currentDir, sh)
                                },
                                modifier = Modifier.height(32.dp)
                            )
                        }
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                        DropdownMenuItem(
                            text = { Text("Сбросить консоль", fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(15.dp))
                            },
                            onClick = {
                                shellMenu = false
                                active?.resetTerminal()
                                state.focusRequest++
                            },
                            modifier = Modifier.height(32.dp)
                        )
                        DropdownMenuItem(
                            text = { Text("Фокус при наведении", fontSize = 13.sp) },
                            leadingIcon = {
                                if (TerminalPreferences.focusOnHover) {
                                    Icon(Icons.Default.Check, null, tint = NoctaliaPrimary, modifier = Modifier.size(14.dp))
                                }
                            },
                            onClick = {
                                TerminalPreferences.updateFocusOnHover(!TerminalPreferences.focusOnHover)
                            },
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }
            }
            PanelIcon(Icons.Default.FolderOpen, "Открыть папку консоли в панели") {
                active?.currentDirectory()?.let(onOpenPath)
            }
            PanelIcon(
                if (TerminalPreferences.followPane) Icons.Default.Link else Icons.Default.LinkOff,
                if (TerminalPreferences.followPane) "Консоль следует за папкой панели (вкл.)" else "Консоль следует за папкой панели (выкл.)",
                active = TerminalPreferences.followPane
            ) {
                TerminalPreferences.updateFollowPane(!TerminalPreferences.followPane)
            }
            PanelIcon(Icons.Default.ContentPaste, "Вставить (" + Keymap.label("term.paste") + ")") {
                clipboard.getText()?.text?.let { active?.paste(it) }
                state.focusRequest++
            }
            PanelIcon(Icons.Default.DeleteSweep, "Очистить") {
                active?.clearScreenAndHistory()
                state.focusRequest++
            }
            Text(
                "${TerminalPreferences.fontSize.toInt()}",
                fontSize = 10.sp,
                fontFamily = AppFonts.JetBrainsMono,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .clickable { TerminalPreferences.zoom(0f) }
                    .padding(horizontal = 4.dp)
            )
            PanelIcon(
                if (state.maximized) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                if (state.maximized) "Вернуть размер" else "Развернуть на всю панель",
                active = state.maximized
            ) {
                state.maximized = !state.maximized
                state.focusRequest++
            }
            PanelIcon(Icons.Default.Close, "Скрыть консоль (" + Keymap.label("app.terminal2") + ")") { state.hide() }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(start = 2.dp, end = 2.dp, bottom = 8.dp)) {
            val s = active
            if (s == null) {
                Text(
                    "Нет открытых вкладок",
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                // Строка поиска идёт над выводом: в Box она оказалась бы под ним
                Column(Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = findOpen,
                        enter = expandVertically(tween(Motion.ENTER)) + fadeIn(tween(Motion.ENTER)),
                        exit = shrinkVertically(tween(Motion.EXIT)) + fadeOut(tween(Motion.EXIT))
                    ) {
                        TerminalFindBar(
                            query = findQuery,
                            count = findMatches.size,
                            index = findIndex,
                            focusRequester = findFocus,
                            onQuery = { runFind(it) },
                            onStep = { stepFind(it) },
                            onClose = {
                                findOpen = false
                                active?.selection = null
                                state.focusRequest++
                            }
                        )
                    }
                    key(s.id) {
                        TerminalView(
                            session = s,
                            fontSizeSp = TerminalPreferences.fontSize,
                            focusRequester = focus,
                            onOpenPath = onOpenPath,
                            onNewTab = { state.newSession(s.currentDirectory() ?: currentDir) },
                            onCloseTab = { state.close(s) },
                            onZoom = { TerminalPreferences.zoom(it) },
                            onRestart = { state.restart(s) },
                            onFind = { findOpen = true },
                            onClear = { s.clearScreenAndHistory() },
                            onReset = { s.resetTerminal() },
                            onCycleTab = { state.cycle(it) },
                            findOpen = findOpen,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** Разделитель между списком файлов и консолью (перетаскивается). */
@Composable
fun TerminalSplitter(totalHeightPx: () -> Float, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.N_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { TerminalPreferences.commitHeight() }) { change, drag ->
                    change.consume()
                    val total = totalHeightPx().coerceAtLeast(1f)
                    TerminalPreferences.updateHeightFraction(TerminalPreferences.heightFraction - drag.y / total)
                }
            }
    ) {
        Box(
            Modifier
                .width(48.dp)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp))
        )
    }
}

/** Поиск по выводу консоли: поле, счётчик и переходы по найденному. */
@Composable
private fun TerminalFindBar(
    query: String,
    count: Int,
    index: Int,
    focusRequester: FocusRequester,
    onQuery: (String) -> Unit,
    onStep: (Int) -> Unit,
    onClose: () -> Unit
) {
    // Поле появляется вместе с анимацией: до её начала узел ещё не размещён и фокус не берётся
    LaunchedEffect(Unit) {
        repeat(12) {
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    DisposableEffect(Unit) { onDispose { TerminalFocus.inField = false } }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (query.isEmpty()) {
                    Text(
                        "Найти в выводе",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                var value by remember {
                    mutableStateOf(TextFieldValue(query, androidx.compose.ui.text.TextRange(0, query.length)))
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = { value = it; onQuery(it.text) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = AppFonts.JetBrainsMono
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(NoctaliaPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { TerminalFocus.inField = it.isFocused }
                        .onPreviewKeyEvent { e ->
                            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                e.key == Key.Escape -> { onClose(); true }
                                e.key == Key.Enter -> { onStep(if (e.isShiftPressed) -1 else 1); true }
                                else -> false
                            }
                        }
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (count == 0) (if (query.length >= 2) "нет" else "") else "${index + 1} из $count",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
            PanelIcon(Icons.Default.KeyboardArrowUp, "Предыдущее (Shift+Enter)", active = count > 0) { onStep(-1) }
            PanelIcon(Icons.Default.KeyboardArrowDown, "Следующее (Enter)", active = count > 0) { onStep(1) }
            PanelIcon(Icons.Default.Close, "Закрыть (Esc)") { onClose() }
        }
    }
}
