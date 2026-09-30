package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.input.TextFieldValue
import org.wexclient.app.markdown.Markdown
import org.wexclient.app.markdown.MarkdownView
import org.wexclient.app.util.withUserEnv
import org.wexclient.app.theme.NoctaliaSecondary
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import org.wexclient.app.util.isCtrlShortcut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.wexclient.app.editor.CodeEditorView
import org.wexclient.app.editor.DecodedText
import org.wexclient.app.editor.LineSeparator
import org.wexclient.app.editor.TextCodec
import org.wexclient.app.editor.TextFileFormat
import org.wexclient.app.editor.lang.Languages
import java.nio.file.FileSystems
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.vfs.VfsEntry

@Composable
fun FileViewerDialog(
    entry: VfsEntry,
    content: String,
    onDismiss: () -> Unit,
    /** Запись байтов файла (кодировка и BOM уже применены). */
    onSave: ((ByteArray) -> Unit)? = null,
    decoded: DecodedText? = null,
    originalBytes: ByteArray? = null,
    /** Ссылка из просмотра Markdown на другой файл */
    onOpenFile: ((java.nio.file.Path) -> Unit)? = null,
    /** Открыть на найденной строке (поиск по содержимому) */
    initialTarget: org.wexclient.app.editor.TextTarget? = null,
    /** «Расположение»: показать файл в панели менеджера (null — кнопки нет). */
    onReveal: (() -> Unit)? = null
) {
    val language = remember(entry.name, content) {
        Languages.detect(entry.name, content.take(8192))
    }
    // Ключ — и путь: у двух файлов с одинаковым текстом свой контроллер (путь, кодировка, исходные байты)
    val controller = remember(entry.path, content) { org.wexclient.app.editor.CodeEditorController(content) }
    remember(controller) {
        val localPath = entry.path.takeIf { it.fileSystem == FileSystems.getDefault() }
        controller.attachFile(decoded ?: DecodedText(content, TextFileFormat(), false), originalBytes, localPath)
        true
    }
    // Текст приходит с выбранным переводом строк — кодируем как есть (LF = без замены)
    // Байты, которые не подошли к кодировке, при сохранении станут «�» — предупреждаем и
    // сохраняем только по повторному Ctrl+S
    val lossy = decoded?.hadMalformedInput == true
    var lossyConfirmed by remember(entry.path) { mutableStateOf(false) }
    // Файл на диске (не внутри архива) могут поменять другой программой, пока он открыт здесь
    val disk = remember(entry.path) {
        entry.path.takeIf { it.fileSystem == FileSystems.getDefault() }?.let { DiskWatch(it) }
    }
    var overwriteConfirmed by remember(entry.path) { mutableStateOf(false) }
    /** Новые байты файла, изменённого снаружи, пока здесь есть несохранённые правки. */
    var externalChange by remember(entry.path) { mutableStateOf<ByteArray?>(null) }
    val saveBytes: ((String) -> Unit)? = onSave?.let { write ->
        { text: String ->
            if (lossy && !lossyConfirmed) {
                lossyConfirmed = true
                throw IllegalStateException("В файле есть байты не в кодировке ${controller.charset?.name() ?: "UTF-8"} — они заменятся на «�». Сохраните ещё раз, если согласны")
            }
            // Файл поменяли снаружи (git, другой редактор) — молча его не затираем
            if (disk != null && !overwriteConfirmed) {
                val known = controller.originalBytes
                val onDisk = disk.read()
                if (known != null && onDisk != null && !onDisk.contentEquals(known)) {
                    overwriteConfirmed = true
                    throw IllegalStateException("Файл изменён на диске другой программой. Сохраните ещё раз, чтобы записать поверх")
                }
            }
            val cs = controller.charset ?: Charsets.UTF_8
            val bytes = TextCodec.encode(text, TextFileFormat(cs, controller.hasBom, LineSeparator.LF))
            write(bytes)
            // «Открыть заново в кодировке» после сохранения — от сохранённого, а не от старого
            controller.originalBytes = bytes
            overwriteConfirmed = false
            externalChange = null
            disk?.remember()
        }
    }
    val saver = rememberBackgroundSave()
    val guard = rememberCloseGuard(isDirty = { onSave != null && controller.isDirty }, onClose = onDismiss)
    TrackUnsaved(entry.name, onSave != null && controller.isDirty)
    // Сохранение работает и с вкладки просмотра, где редактора нет
    controller.saver = saveBytes

    val isMarkdown = language.id == "markdown"
    val isHtml = language.id == "html"
    // Оформленный просмотр есть у Markdown и HTML
    val hasPreview = isMarkdown || isHtml
    // К найденной строке — в редакторе, даже у Markdown
    var previewTab by remember(entry.path) { mutableStateOf(if (initialTarget != null) 1 else lastPreviewTab) }
    // Файл на диске открывается там, где его в прошлый раз закрыли (если не просили другую строку)
    val rememberedAt = remember(entry.path) { entry.path.takeIf { it.fileSystem == FileSystems.getDefault() } }
    LaunchedEffect(controller, initialTarget) {
        val target = initialTarget
        if (target == null) {
            rememberedAt?.let { org.wexclient.app.editor.EditorPositions.get(it) }?.let { controller.goTo(it) }
            return@LaunchedEffect
        }
        if (hasPreview) previewTab = 1
        controller.goTo(target)
    }
    androidx.compose.runtime.DisposableEffect(controller) {
        onDispose {
            val path = rememberedAt ?: return@onDispose
            val snap = controller.snapshot
            val caret = controller.value.selection.end.coerceIn(0, snap.text.length)
            val line = snap.lineOf(caret)
            org.wexclient.app.editor.EditorPositions.put(path, line, caret - snap.lineStart(line))
        }
    }
    val toast = rememberToastState()
    var reloadKey by remember(entry.path) { mutableStateOf(0) }
    // Браузеру нужен настоящий файл: внутри архива стили и картинки всё равно не подтянутся
    val pageUrl = remember(entry.path) {
        if (entry.path.fileSystem === java.nio.file.FileSystems.getDefault()) {
            entry.path.toAbsolutePath().toUri().toString()
        } else {
            null
        }
    }
    val openLinked: ((java.nio.file.Path) -> Unit)? = onOpenFile?.let { open ->
        { target: java.nio.file.Path ->
            if (controller.isDirty) toast.error("Сначала сохраните изменения (Ctrl+S)") else open(target)
        }
    }

    Dialog(
        onCloseRequest = { guard.requestClose() },
        state = androidx.compose.ui.window.rememberDialogState(
            size = androidx.compose.ui.unit.DpSize(1400.dp, 900.dp)
        ),
        title = (if (controller.isDirty) "● " else "") + entry.name,
        onPreviewKeyEvent = latestKeyHandler { e ->
            // Ctrl+S в просмотре Markdown (в редакторе сочетание обрабатывает он сам)
            if (hasPreview && previewTab == 0 && onSave != null && e.isCtrlShortcut(Key.S)) {
                controller.saveInBackground(saver) { err ->
                    if (err == null) toast.success("Сохранено") else toast.error("Не сохранено: $err")
                }
                true
            } else false
        },
        // Esc, которую не забрал редактор (поиск, подсказки), закрывает окно — с вопросом о несохранённом
        onKeyEvent = latestKeyHandler { e -> closeOnEscape(e) { guard.requestClose() } }
    ) {
        // Отдельное окно редактора иначе показывает в панели задач кофейный логотип Java
        androidx.compose.runtime.LaunchedEffect(window) {
            org.wexclient.app.util.WindowIcon.applyTo(window)
        }
        // Вернулись в окно — не поменяли ли файл снаружи. Без правок просто перечитываем его,
        // с правками спрашиваем. Байты читаем, только если сменились время или размер файла
        val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focused, disk) {
            if (!focused || disk == null) return@LaunchedEffect
            val known = controller.originalBytes ?: return@LaunchedEffect
            val onDisk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (disk.changed()) disk.read().also { disk.remember() } else null
            } ?: return@LaunchedEffect
            if (onDisk.contentEquals(known) || saver.busy) return@LaunchedEffect
            if (!controller.isDirty) {
                controller.reloadFromDisk(onDisk)
                toast.info("Файл изменён на диске — перечитан")
            } else {
                externalChange = onDisk
            }
        }
        org.wexclient.app.theme.NoctaliaTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
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
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            tint = NoctaliaPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = AppFonts.JetBrainsMono
                            ),
                            color = NoctaliaPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        if (isHtml && previewTab == 0) {
                            IconButton(onClick = { reloadKey++ }) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Обновить страницу",
                                    tint = NoctaliaSecondary
                                )
                            }
                        }
                        if (isHtml && entry.path.fileSystem === java.nio.file.FileSystems.getDefault()) {
                            IconButton(onClick = {
                                // Настоящий вид со стилями и скриптами — дело браузера
                                runCatching {
                                    ProcessBuilder(org.wexclient.app.util.systemOpenCommand(entry.path.toAbsolutePath().toString()))
                                        .withUserEnv().start()
                                }.onFailure { toast.error("Не удалось открыть браузер") }
                            }) {
                                Icon(
                                    Icons.Default.OpenInBrowser,
                                    contentDescription = "Открыть в браузере",
                                    tint = NoctaliaSecondary
                                )
                            }
                        }
                        if (onReveal != null) {
                            IconButton(onClick = onReveal) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = "Расположение — показать файл в панели",
                                    tint = NoctaliaSecondary
                                )
                            }
                        }
                        IconButton(onClick = { guard.requestClose() }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }

                externalChange?.let { changed ->
                    ExternalChangeBar(
                        onReload = {
                            controller.reloadFromDisk(changed)
                            externalChange = null
                            overwriteConfirmed = false
                        },
                        onKeepMine = {
                            // Свои правки важнее: следующее сохранение запишет их без вопроса
                            controller.originalBytes = changed
                            externalChange = null
                        }
                    )
                }

                if (hasPreview) {
                    PreviewTabs(previewTab) {
                        previewTab = it
                        lastPreviewTab = it
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (isHtml && previewTab == 0) {
                        val browser = remember { org.wexclient.app.html.BrowserSession.findBrowser() != null }
                        if (browser && pageUrl != null) {
                            org.wexclient.app.html.BrowserView(
                                url = pageUrl,
                                reloadKey = reloadKey,
                                onStatus = { }
                            )
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = if (!browser) {
                                            "Chromium не найден — показана упрощённая вёрстка без стилей и скриптов"
                                        } else {
                                            "Файл внутри архива — показана упрощённая вёрстка"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                                org.wexclient.app.html.HtmlView(
                                    text = controller.text,
                                    baseDir = entry.path.parent,
                                    onOpenFile = openLinked
                                )
                            }
                        }
                    } else if (isMarkdown && previewTab == 0) {
                        MarkdownView(
                            text = controller.text,
                            baseDir = entry.path.parent,
                            onToggleTask = { offset ->
                                val current = controller.text
                                val next = Markdown.toggleTask(current, offset)
                                if (next != current) {
                                    controller.applyEdit(TextFieldValue(next, controller.value.selection), reveal = false)
                                }
                            },
                            onOpenFile = openLinked
                        )
                    } else {
                        // Большой файл сначала показываем быстрым просмотром: обычный редактор держит
                        // весь текст в одном поле ввода, и Skia раскладывает его целиком на каждую
                        // правку — на мегабайте это доли секунды на букву. Правка — по кнопке.
                        CodeEditorView(
                            initialText = content,
                            language = language,
                            readOnly = false,
                            fileName = entry.name,
                            onSave = saveBytes,
                            controller = controller,
                            backgroundSave = saver,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    ToastHost(toast)
                }
            }

            if (guard.asking) {
                UnsavedChangesDialog(
                    fileName = entry.name,
                    error = guard.saveError,
                    busy = guard.saving,
                    onSave = { guard.saveAndCloseAsync { done -> controller.saveInBackground(saver, done) } },
                    onDiscard = { guard.discard() },
                    onCancel = { guard.cancel() }
                )
            }
        }
    }
}
}

/**
 * Отметка файла на диске: время изменения и размер на момент, когда мы его последний раз читали
 * или писали. По ней видно, что файл трогал кто-то ещё, не читая его целиком.
 */
private class DiskWatch(private val path: java.nio.file.Path) {
    @Volatile
    private var stamp: Pair<Long, Long>? = current()

    private fun current(): Pair<Long, Long>? = runCatching {
        val attrs = java.nio.file.Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
        attrs.lastModifiedTime().toMillis() to attrs.size()
    }.getOrNull()

    fun changed(): Boolean = current().let { it != null && it != stamp }

    fun remember() {
        stamp = current()
    }

    fun read(): ByteArray? = runCatching { java.nio.file.Files.readAllBytes(path) }.getOrNull()
}

/** Полоса над редактором: файл поменяли снаружи, а здесь есть несохранённые правки. */
@Composable
private fun ExternalChangeBar(onReload: () -> Unit, onKeepMine: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                "Файл изменён на диске другой программой, а здесь есть несохранённые правки",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            org.wexclient.app.editor.SmallButton("Перечитать с диска", onClick = onReload)
            Spacer(modifier = Modifier.width(6.dp))
            org.wexclient.app.editor.SmallButton("Оставить мои правки", onClick = onKeepMine)
        }
    }
}

// Последняя вкладка просмотра — для следующего файла (по умолчанию просмотр)
private var lastPreviewTab = 0

/** Вкладки как в окне манифеста: просмотр с оформлением и редактор. */
@Composable
private fun PreviewTabs(selected: Int, onSelect: (Int) -> Unit) {
    TabRow(
        selectedTabIndex = selected,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        indicator = { positions ->
            TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(positions[selected]),
                color = NoctaliaPrimary
            )
        }
    ) {
        listOf(Icons.Default.Visibility to "Просмотр", Icons.Default.Edit to "Редактор").forEachIndexed { i, (icon, title) ->
            Tab(
                selected = selected == i,
                onClick = { onSelect(i) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(title, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }
    }
}
