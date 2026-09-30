package org.wexclient.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.wexclient.app.util.frozen
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.wexclient.app.doc.DocAlign
import org.wexclient.app.doc.DocBlock
import org.wexclient.app.doc.DocRun
import org.wexclient.app.doc.DocxDocument
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.vfs.VfsEntry

/**
 * Документ Word: страница с полями, заголовки, списки, таблицы и картинки.
 * Двойной щелчок по абзацу — правка текста, Ctrl+S — запись обратно в файл.
 */
@Composable
fun DocumentViewerDialog(entry: VfsEntry, onDismiss: () -> Unit) {
    val path = entry.path
    var reloads by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val edits = remember { mutableStateMapOf<Int, String>() }
    // Добавленные абзацы (после какого идут) и убранные — уходят в файл вместе с правками
    val inserts = remember { mutableStateMapOf<Int, MutableList<String>>() }
    val removed = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
    val saver = rememberBackgroundSave()
    val toast = rememberToastState()

    val loaded by produceState<Result<DocxDocument>?>(null, path, reloads) {
        value = withContext(Dispatchers.IO) { runCatching { DocxDocument.openAny(path) } }
    }

    val result = loaded
    val doc = result?.getOrNull()
    val dirty = edits.isNotEmpty() || inserts.isNotEmpty() || removed.isNotEmpty()

    val subtitle = when {
        result == null -> "чтение…"
        doc == null -> "не открылось"
        else -> buildList {
            add("${doc.paragraphCount} " + org.wexclient.app.util.Plural.ru(doc.paragraphCount.toLong(), "абзац", "абзаца", "абзацев"))
            val tables = doc.blocks.count { it is DocBlock.Table }
            if (tables > 0) add("$tables " + org.wexclient.app.util.Plural.ru(tables.toLong(), "таблица", "таблицы", "таблиц"))
            val pictures = doc.blocks.count { it is DocBlock.Picture }
            if (pictures > 0) add("$pictures " + org.wexclient.app.util.Plural.ru(pictures.toLong(), "картинка", "картинки", "картинок"))
            doc.properties["Автор"]?.let { add(it) }
        }.joinToString(" · ")
    }

    GrailDialogShell(
        title = entry.name,
        badge = doc?.format ?: "DOC",
        heading = doc?.properties?.get("Название")?.takeIf { it.isNotBlank() } ?: entry.name,
        subtitle = subtitle,
        isDirty = dirty,
        closeOnEsc = !dirty,
        dialogWidth = 1050.dp,
        dialogHeight = 900.dp,
        onDismiss = onDismiss,
        toast = toast,
        onSave = if (doc == null || doc.readOnly) null else { done ->
            val texts = edits.toMap()
            val added = inserts.mapValues { (_, list) -> list.toList() }
            val gone = removed.toSet()
            saver.run(
                write = { doc.saveWithStructure(texts, added, gone) },
                done = { error ->
                    if (error == null) {
                        edits.clear()
                        inserts.clear()
                        removed.clear()
                        reloads++
                    }
                    done(error)
                }
            )
        },
        actions = {
            if (doc != null) {
                // Выгрузка рядом с документом: Markdown для заметок и вики, текст — куда угодно
                listOf("В Markdown" to "md", "В текст" to "txt").forEach { (label, ext) ->
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = NoctaliaPrimary.copy(alpha = 0.10f),
                        modifier = Modifier.padding(end = 6.dp).clickable {
                            val name = path.fileName.toString().substringBeforeLast('.') + "." + ext
                            val target = path.toAbsolutePath().parent.resolve(name)
                            val saved = runCatching {
                                java.nio.file.Files.writeString(target, if (ext == "md") doc.markdown else doc.plainText)
                            }
                            if (saved.isSuccess) toast.info("Сохранено: $name") else toast.error("Не записалось: " + saved.exceptionOrNull()?.message)
                        }
                    ) {
                        Text(label, fontSize = 11.sp, color = NoctaliaPrimary, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
                Text(
                    text = when {
                        doc.readOnly -> "только просмотр"
                        dirty -> "правок: ${edits.size} · Ctrl+S"
                        else -> "двойной щелчок — правка"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dirty) NoctaliaPrimary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    ) {
        when {
            result == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                WexSpinner(color = NoctaliaPrimary)
            }
            doc == null -> GrailEmptyState(
                "Не удалось прочитать документ: " + (result.exceptionOrNull()?.message ?: "неизвестная ошибка")
            )
            doc.blocks.isEmpty() -> GrailEmptyState("Документ пуст")
            else -> Box(modifier = Modifier.fillMaxSize().padding(vertical = 12.dp), contentAlignment = Alignment.TopCenter) {
                // Лист с полями: читать сплошную строку во всю ширину окна неудобно
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    modifier = Modifier.widthIn(max = 820.dp).fillMaxSize()
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 46.dp, vertical = 28.dp)) {
                        itemsIndexed(doc.blocks) { _, block ->
                            when (block) {
                                is DocBlock.Paragraph -> {
                                    val text = edits[block.index] ?: block.text
                                    if (editing == block.index) {
                                        ParagraphEditor(
                                            initial = text,
                                            onDone = { value ->
                                                if (value != block.text) edits[block.index] = value
                                                else edits.remove(block.index)
                                                editing = null
                                            },
                                            onCancel = { editing = null }
                                        )
                                    } else if (block.index !in removed) {
                                        ParagraphView(
                                            block = block,
                                            overrideText = edits[block.index],
                                            onEdit = { if (!doc.readOnly) editing = block.index },
                                            onInsertAfter = {
                                                if (!doc.readOnly) inserts.getOrPut(block.index) { mutableListOf() }.add("Новый абзац")
                                            },
                                            onRemove = { if (!doc.readOnly) removed.add(block.index) }
                                        )
                                    }
                                    // Только что добавленные абзацы показываем сразу под исходным
                                    inserts[block.index]?.forEachIndexed { position, text ->
                                        AddedParagraph(
                                            text = text,
                                            onChange = { value ->
                                                inserts[block.index]?.set(position, value)
                                            },
                                            onRemove = {
                                                inserts[block.index]?.removeAt(position)
                                                if (inserts[block.index]?.isEmpty() == true) inserts.remove(block.index)
                                            }
                                        )
                                    }
                                }
                                is DocBlock.Table -> DocTable(block)
                                is DocBlock.Picture -> DocPicture(block)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParagraphView(
    block: DocBlock.Paragraph,
    overrideText: String?,
    onEdit: () -> Unit,
    onInsertAfter: () -> Unit = {},
    onRemove: () -> Unit = {}
) {
    var menu by remember { mutableStateOf(false) }
    val changed = overrideText != null
    val annotated = if (overrideText != null) AnnotatedString(overrideText) else annotate(block.runs)
    val baseSize = when (block.heading) {
        1 -> 24.sp
        2 -> 20.sp
        3 -> 17.sp
        4, 5, 6 -> 15.sp
        else -> 14.sp
    }
    val weight = if (block.heading != null) FontWeight.Bold else FontWeight.Normal
    val align = when (block.align) {
        DocAlign.CENTER -> TextAlign.Center
        DocAlign.RIGHT -> TextAlign.End
        DocAlign.JUSTIFY -> TextAlign.Justify
        else -> TextAlign.Start
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (changed) NoctaliaPrimary.copy(alpha = 0.07f) else Color.Transparent, RoundedCornerShape(4.dp))
            .paragraphClicks(onEdit = onEdit, onMenu = { menu = true })
            .padding(
                start = ((block.listLevel ?: 0) * 18).dp,
                top = if (block.heading != null) 12.dp else 3.dp,
                bottom = 3.dp
            )
    ) {
        if (block.listLevel != null) {
            Text(
                text = if (block.numbered) "•" else "•",
                fontSize = baseSize,
                color = NoctaliaPrimary,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        WexDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            ReturnFocusOnClose()
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Правка абзаца", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(15.dp)) },
                onClick = { menu = false; onEdit() },
                modifier = Modifier.height(32.dp)
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Добавить абзац ниже", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(15.dp)) },
                onClick = { menu = false; onInsertAfter() },
                modifier = Modifier.height(32.dp)
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Убрать абзац", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(15.dp)) },
                onClick = { menu = false; onRemove() },
                modifier = Modifier.height(32.dp)
            )
        }
        if (annotated.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Text(
                text = annotated,
                fontSize = baseSize,
                fontWeight = weight,
                lineHeight = baseSize * 1.45f,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = align,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Куски абзаца со своим начертанием собираются в одну размеченную строку. */
private fun annotate(runs: List<DocRun>): AnnotatedString = buildAnnotatedString {
    for (run in runs) {
        withStyle(
            SpanStyle(
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                textDecoration = when {
                    run.underline && run.strike -> TextDecoration.combine(
                        listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                    )
                    run.underline -> TextDecoration.Underline
                    run.strike -> TextDecoration.LineThrough
                    else -> null
                },
                fontSize = run.sizePt?.sp ?: androidx.compose.ui.unit.TextUnit.Unspecified,
                color = run.color?.let { hex ->
                    runCatching { Color(("ff" + hex.removePrefix("#")).toLong(16)) }.getOrDefault(Color.Unspecified)
                } ?: Color.Unspecified,
                fontFamily = if (run.mono) org.wexclient.app.theme.AppFonts.JetBrainsMono else null
            )
        ) {
            append(run.text)
        }
    }
}

@Composable
private fun ParagraphEditor(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    // Курсор в конце текста: правят обычно хвост абзаца, а не начало
    var value by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = initial,
                selection = androidx.compose.ui.text.TextRange(initial.length)
            )
        )
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 20.sp),
            cursorBrush = SolidColor(NoctaliaPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.5.dp, NoctaliaPrimary), RoundedCornerShape(5.dp))
                .padding(8.dp)
                .focusRequester(focus)
                .onPreviewKeyEventCompat(
                    onEnter = { onDone(value.text) },
                    onEscape = onCancel
                )
        )
        Row(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = "Enter — принять, Esc — отменить",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun DocTable(table: DocBlock.Table) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        for ((rowIndex, row) in table.rows.withIndex()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (cell in row) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                RoundedCornerShape(0.dp)
                            )
                            .background(
                                if (rowIndex == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = cell.joinToString("\n") { it.text },
                            fontSize = 13.sp,
                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocPicture(picture: DocBlock.Picture) {
    // Не в потоке окна и сразу в размер показа (не шире 640 dp — с запасом на HiDPI): фото в 20 Мп
    // из DOCX разбиралось целиком прямо при прокрутке к нему. null — ещё грузится
    val loaded by androidx.compose.runtime.produceState<Result<androidx.compose.ui.graphics.ImageBitmap?>?>(null, picture.name) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                org.wexclient.app.util.ImageScale.decodeScaled(picture.bytes, maxPx = 1600, maxSourcePx = 16384, maxSourcePixels = 100_000_000)
                    // Прокрутили дальше, пока разбирали, — пиксели сразу отдаём
                    ?.also { b -> if (!isActive) b.asSkiaBitmap().close() }
            }
        }
    }
    val bitmap = loaded?.getOrNull()
    // Картинка ушла из списка — пиксели закрываем сами: сборщик мусора нативную память не видит
    androidx.compose.runtime.DisposableEffect(bitmap) {
        onDispose { bitmap?.let { runCatching { it.asSkiaBitmap().close() } } }
    }
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        if (loaded == null) {
            Box(modifier = Modifier.height(48.dp))
        } else if (bitmap == null) {
            Text(
                text = "[картинка ${picture.name} не читается]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = picture.name,
                modifier = Modifier.widthIn(max = 640.dp)
            )
        }
    }
}

/** Двойной щелчок — правка абзаца, долгое нажатие — меню; одиночный ничего не делает. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.paragraphClicks(onEdit: () -> Unit, onMenu: () -> Unit): Modifier =
    this.combinedClickable(onClick = {}, onDoubleClick = onEdit, onLongClick = onMenu)

/** Абзац, добавленный в этом сеансе: правится прямо на месте. */
@Composable
private fun AddedParagraph(text: String, onChange: (String) -> Unit, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(NoctaliaPrimary.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        BasicTextField(
            value = text,
            onValueChange = onChange,
            textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 20.sp),
            cursorBrush = SolidColor(NoctaliaPrimary),
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.Delete,
            contentDescription = "Убрать абзац",
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(15.dp).clickable(onClick = onRemove)
        )
    }
}

/** Enter принимает правку, Esc отменяет. */
private fun Modifier.onPreviewKeyEventCompat(onEnter: () -> Unit, onEscape: () -> Unit): Modifier =
    this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Enter -> { onEnter(); true }
            Key.Escape -> { onEscape(); true }
            else -> false
        }
    }
