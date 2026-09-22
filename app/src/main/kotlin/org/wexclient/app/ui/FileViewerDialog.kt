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
    val saveBytes: ((String) -> Unit)? = onSave?.let { write ->
        { text: String ->
            if (lossy && !lossyConfirmed) {
                lossyConfirmed = true
                throw IllegalStateException("В файле есть байты не в кодировке ${controller.charset?.name() ?: "UTF-8"} — они заменятся на «�». Сохраните ещё раз, если согласны")
            }
            val cs = controller.charset ?: Charsets.UTF_8
            val bytes = TextCodec.encode(text, TextFileFormat(cs, controller.hasBom, LineSeparator.LF))
            write(bytes)
            // «Открыть заново в кодировке» после сохранения — от сохранённого, а не от старого
            controller.originalBytes = bytes
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
    LaunchedEffect(controller, initialTarget) {
        val target = initialTarget ?: return@LaunchedEffect
        if (hasPreview) previewTab = 1
        controller.goTo(target)
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
                                    ProcessBuilder("xdg-open", entry.path.toAbsolutePath().toString())
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
