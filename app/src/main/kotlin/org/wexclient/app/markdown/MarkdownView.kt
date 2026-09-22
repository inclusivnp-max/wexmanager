package org.wexclient.app.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.editor.HighlightModel
import org.wexclient.app.editor.HighlightSnapshot
import org.wexclient.app.editor.lang.Language
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.editor.lang.SyntaxColors
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaTertiary
import org.wexclient.app.util.withUserEnv
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path

private data class MdContext(
    val baseDir: Path?,
    val onToggleTask: ((Int) -> Unit)?,
    val onLink: (String) -> Unit,
    val text: Color,
    val muted: Color,
    val link: Color,
    val codeBackground: Color,
    val border: Color
)

/**
 * Просмотр Markdown в духе GitHub/Obsidian: заголовки, списки с задачами, таблицы, врезки,
 * код с подсветкой, картинки. [onToggleTask] — щелчок по задаче (смещение в исходнике).
 */
@Composable
fun MarkdownView(
    text: String,
    baseDir: Path?,
    modifier: Modifier = Modifier,
    onToggleTask: ((Int) -> Unit)? = null,
    onOpenFile: ((Path) -> Unit)? = null
) {
    // Разбор не должен ронять окно: в худшем случае — исходный текст
    val blocks = remember(text) { runCatching { Markdown.parse(text) }.getOrElse { listOf(MdBlock.Code("", text)) } }
    MarkdownBlocksView(blocks, baseDir, modifier, onToggleTask, onOpenFile)
}

/** То же оформление для готовых блоков — им показывается и разобранная страница HTML. */
@Composable
fun MarkdownBlocksView(
    blocks: List<MdBlock>,
    baseDir: Path?,
    modifier: Modifier = Modifier,
    onToggleTask: ((Int) -> Unit)? = null,
    onOpenFile: ((Path) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    fun openLink(url: String) {
        when {
            url.startsWith("#") -> {
                val anchor = Markdown.anchor(java.net.URLDecoder.decode(url.substring(1), Charsets.UTF_8))
                val index = blocks.indexOfFirst { it is MdBlock.Heading && it.anchor == anchor }
                if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
            }
            url.startsWith("wiki:") -> {
                val target = url.removePrefix("wiki:").substringBefore('#').trim()
                // Поиск по подпапкам — в фоне: большое хранилище заметок иначе морозило окно на щелчке
                scope.launch {
                    val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { findNote(target, baseDir) }
                    found?.let { onOpenFile?.invoke(it) }
                }
            }
            // Наружу — только веб и почта: file://…/x.desktop, steam://, vscode:// и прочие
            // обработчики через xdg-open могли бы что-то запустить по одному щелчку
            isExternalLink(url) -> {
                runCatching { ProcessBuilder("xdg-open", url).withUserEnv().start() }
            }
            url.startsWith("file:", ignoreCase = true) -> {
                val path = MarkdownImages.resolve(url, baseDir)
                if (path != null && Files.exists(path)) onOpenFile?.invoke(path)
            }
            url.contains("://") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url) -> Unit
            else -> {
                val path = MarkdownImages.resolve(url, baseDir)
                if (path != null && Files.exists(path)) onOpenFile?.invoke(path)
            }
        }
    }

    val ctx = MdContext(
        baseDir = baseDir,
        onToggleTask = onToggleTask,
        onLink = ::openLink,
        text = cs.onSurface,
        muted = cs.onSurfaceVariant,
        link = NoctaliaPrimary,
        codeBackground = cs.surfaceVariant.copy(alpha = 0.7f),
        border = cs.outlineVariant.copy(alpha = 0.7f)
    )

    Box(modifier = modifier.fillMaxSize().background(cs.background)) {
        if (blocks.isEmpty()) {
            Text(
                "Пустой документ — перейдите на вкладку «Редактор»",
                style = MaterialTheme.typography.bodyMedium,
                color = ctx.muted,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        // Длина строки как на GitHub — читать удобнее, чем во всю ширину окна
        SelectionContainer(modifier = Modifier.align(Alignment.TopCenter).fillMaxHeight().widthIn(max = 980.dp).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(blocks) { _, block -> BlockView(block, ctx) }
            }
        }
    }
}

/** Заметка Obsidian по имени: рядом, в подпапках (неглубоко), с .md и без. */
private fun findNote(target: String, baseDir: Path?): Path? {
    if (baseDir == null || target.isBlank()) return null
    val names = if (target.contains('.')) listOf(target, "$target.md") else listOf("$target.md")
    for (n in names) {
        val direct = runCatching { baseDir.resolve(n).normalize() }.getOrNull()
        if (direct != null && Files.isRegularFile(direct)) return direct
    }
    val leaf = names.map { it.substringAfterLast('/') }
    return runCatching {
        Files.walk(baseDir, 4).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString() in leaf }.findFirst().orElse(null)
        }
    }.getOrNull()
}

@Composable
private fun BlockView(block: MdBlock, ctx: MdContext, depth: Int = 0) {
    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 24.sp, color = ctx.text)
    when (block) {
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> 28.sp
                2 -> 23.sp
                3 -> 19.sp
                4 -> 17.sp
                5 -> 15.sp
                else -> 14.sp
            }
            Column(modifier = Modifier.padding(top = if (block.level <= 2) 10.dp else 4.dp)) {
                InlineContent(
                    block.content,
                    body.copy(
                        fontSize = size,
                        lineHeight = size * 1.3f,
                        fontWeight = if (block.level <= 3) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (block.level == 6) ctx.muted else ctx.text
                    ),
                    ctx
                )
                // H1/H2 подчёркнуты, как на GitHub
                if (block.level <= 2) {
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = ctx.border)
                }
            }
        }
        is MdBlock.Paragraph -> InlineContent(block.content, body, ctx)
        is MdBlock.Code -> CodeBlock(block, ctx)
        is MdBlock.Quote -> {
            val callout = block.callout
            if (callout == null) {
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Box(modifier = Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(ctx.border))
                    Column(
                        modifier = Modifier.padding(start = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CompositionMuted(ctx) { muted -> block.blocks.forEach { BlockView(it, muted, depth) } }
                    }
                }
            } else {
                CalloutView(callout, block.blocks, ctx, depth)
            }
        }
        is MdBlock.ListBlock -> ListView(block, ctx, depth)
        is MdBlock.Table -> TableView(block, ctx)
        MdBlock.Rule -> HorizontalDivider(thickness = 3.dp, color = ctx.border, modifier = Modifier.padding(vertical = 6.dp))
        is MdBlock.Html -> InlineContent(
            block.content,
            body,
            ctx,
            align = if (block.centered) TextAlign.Center else TextAlign.Start
        )
        is MdBlock.Properties -> PropertiesView(block, ctx)
    }
}

/** Цитата — приглушённым цветом. */
@Composable
private fun CompositionMuted(ctx: MdContext, content: @Composable (MdContext) -> Unit) {
    content(ctx.copy(text = ctx.muted))
}

private fun calloutStyle(kind: String): Pair<Color, ImageVector> = when (kind) {
    "tip", "hint", "important" -> Color(0xFF34D399) to Icons.Default.Lightbulb
    "success", "check", "done" -> Color(0xFF4ADE80) to Icons.Default.CheckCircle
    "question", "help", "faq" -> Color(0xFFFBBF24) to Icons.Default.HelpOutline
    "warning", "caution", "attention" -> Color(0xFFFB923C) to Icons.Default.Warning
    "failure", "fail", "missing", "danger", "error" -> Color(0xFFF87171) to Icons.Default.Error
    "bug" -> Color(0xFFF87171) to Icons.Default.BugReport
    "example" -> Color(0xFFA78BFA) to Icons.Default.ListAlt
    "quote", "cite" -> Color(0xFF94A3B8) to Icons.Default.FormatQuote
    "abstract", "summary", "tldr" -> Color(0xFF22D3EE) to Icons.Default.Summarize
    else -> Color(0xFF60A5FA) to Icons.Default.Info
}

@Composable
private fun CalloutView(callout: MdCallout, blocks: List<MdBlock>, ctx: MdContext, depth: Int) {
    val (color, icon) = calloutStyle(callout.kind)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(callout.title, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            blocks.forEach { BlockView(it, ctx, depth) }
        }
    }
}

@Composable
private fun ListView(list: MdBlock.ListBlock, ctx: MdContext, depth: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        list.items.forEachIndexed { i, item ->
            Row(verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.width(if (list.ordered) 30.dp else 24.dp).height(24.dp), contentAlignment = Alignment.CenterEnd) {
                    when {
                        item.checked != null -> {
                            val offset = item.markerOffset
                            val toggle = ctx.onToggleTask
                            Checkbox(
                                checked = item.checked,
                                onCheckedChange = if (offset != null && toggle != null) ({ toggle(offset) }) else null,
                                colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        list.ordered -> Text("${list.start + i}.", color = ctx.muted, fontSize = 15.sp)
                        else -> Text(
                            when (depth % 3) {
                                0 -> "•"
                                1 -> "◦"
                                else -> "▪"
                            },
                            color = ctx.muted,
                            fontSize = 16.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                // Выполненная задача — приглушённо
                val itemCtx = if (item.checked == true) ctx.copy(text = ctx.muted) else ctx
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.blocks.forEach { BlockView(it, itemCtx, depth + 1) }
                }
            }
        }
    }
}

// ---------- Строки ----------

private fun annotated(spans: List<MdSpan>, ctx: MdContext): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        var style = SpanStyle()
        if (s.bold) style = style.merge(SpanStyle(fontWeight = FontWeight.Bold))
        if (s.italic) style = style.merge(SpanStyle(fontStyle = FontStyle.Italic))
        if (s.strike) style = style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
        if (s.code) style = style.merge(SpanStyle(fontFamily = AppFonts.JetBrainsMono, fontSize = 13.5.sp, background = ctx.codeBackground))
        if (s.highlight) style = style.merge(SpanStyle(background = Color(0x55FACC15)))
        if (s.math) style = style.merge(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, color = NoctaliaTertiary))
        if (s.tag) style = style.merge(SpanStyle(color = ctx.link, background = ctx.link.copy(alpha = 0.12f)))
        val link = s.link
        if (link != null) {
            val linkStyle = SpanStyle(
                color = ctx.link,
                textDecoration = if (s.wiki) null else TextDecoration.Underline
            )
            withLink(LinkAnnotation.Clickable(link, TextLinkStyles(style = linkStyle)) { ctx.onLink(link) }) {
                withStyle(style) { append(s.text) }
            }
        } else {
            withStyle(style) { append(s.text) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineContent(content: List<MdInline>, style: TextStyle, ctx: MdContext, align: TextAlign = TextAlign.Start) {
    // Строки между переносами; строка с картинками — поток (значки README встают в ряд)
    val lines = ArrayList<List<MdInline>>()
    var current = ArrayList<MdInline>()
    for (part in content) {
        if (part is MdInline.Break) {
            lines.add(current)
            current = ArrayList()
        } else current.add(part)
    }
    lines.add(current)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start
    ) {
        for (line in lines) {
            if (line.isEmpty()) continue
            if (line.none { it is MdInline.Picture }) {
                val spans = line.filterIsInstance<MdInline.Spans>().flatMap { it.spans }
                Text(annotated(spans, ctx), style = style.copy(textAlign = align), modifier = Modifier.fillMaxWidth())
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (part in line) {
                        when (part) {
                            is MdInline.Picture -> ImageView(part.image, ctx)
                            is MdInline.Spans -> if (part.spans.any { it.text.isNotBlank() }) {
                                // Пробел между картинкой и текстом в потоке уже есть
                                val spans = part.spans.mapIndexed { i, s -> if (i == 0) s.copy(text = s.text.trimStart()) else s }
                                Text(annotated(spans, ctx), style = style, modifier = Modifier.align(Alignment.CenterVertically))
                            }
                            MdInline.Break -> Unit
                        }
                    }
                }
            }
        }
    }
}

/** Ссылки, которые можно отдать браузеру или почтовой программе. */
internal fun isExternalLink(url: String): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    return (scheme in setOf("http", "https", "ftp") && url.substring(scheme.length).startsWith("://")) || scheme == "mailto"
}

@Composable
private fun ImageView(image: MdImage, ctx: MdContext) {
    // Картинку из локальной сети загружаем только по щелчку
    var allowPrivate by remember(image.src) { mutableStateOf(false) }
    // Внешние адреса — по настройке: выключено, значит документ не «звонит» на чужой сервер сам
    var allowRemote by remember(image.src) { mutableStateOf(false) }
    val remoteBlocked = MarkdownImages.isRemote(image.src) &&
        !org.wexclient.app.ui.ViewPreferences.remoteImages && !allowRemote
    val loaded by produceState(
        MarkdownImages.cached(image.src, ctx.baseDir)?.let { Result.success(it) },
        image.src, ctx.baseDir, allowPrivate, remoteBlocked
    ) {
        if (remoteBlocked) {
            value = null
            return@produceState
        }
        if (value == null || allowPrivate) {
            value = withContext(Dispatchers.IO) { MarkdownImages.load(image.src, ctx.baseDir, allowPrivate) }
        }
    }
    if (remoteBlocked) {
        val host = runCatching { java.net.URI(image.src).host }.getOrNull() ?: image.src
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(ctx.codeBackground)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { allowRemote = true }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(Icons.Default.BrokenImage, contentDescription = null, tint = ctx.muted, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "Картинка с $host — нажмите, чтобы загрузить",
                color = ctx.muted,
                fontSize = 13.sp
            )
        }
        return
    }
    val result = loaded
    val privateHost = (result?.exceptionOrNull() as? PrivateAddressException)?.host
    if (privateHost != null && !allowPrivate) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(ctx.codeBackground)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { allowPrivate = true }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(Icons.Default.BrokenImage, contentDescription = null, tint = ctx.muted, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "Картинка с адреса в локальной сети ($privateHost) — нажмите, чтобы загрузить",
                color = ctx.muted,
                fontSize = 13.sp
            )
        }
        return
    }
    val bmp = result?.getOrNull()
    val clickable = image.link?.let { link ->
        Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { ctx.onLink(link) }
    } ?: Modifier
    when {
        bmp != null -> {
            val width = (image.width?.toFloat() ?: bmp.cssWidth).coerceAtLeast(1f)
            val height = width * bmp.cssHeight / bmp.cssWidth.coerceAtLeast(1f)
            Image(
                bitmap = bmp.bitmap,
                contentDescription = image.alt,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = clickable
                    .widthIn(max = width.dp)
                    .fillMaxWidth()
                    .then(if (height > 0f && height.isFinite()) Modifier.aspectRatio(width / height) else Modifier)
            )
        }
        result == null -> Box(
            modifier = Modifier.size(width = (image.width ?: 64).coerceAtMost(240).dp, height = 20.dp)
                .clip(RoundedCornerShape(4.dp)).background(ctx.codeBackground)
        )
        else -> Row(verticalAlignment = Alignment.CenterVertically, modifier = clickable) {
            Icon(Icons.Default.BrokenImage, contentDescription = null, tint = ctx.muted, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(image.alt.ifBlank { image.src.substringAfterLast('/') }, color = ctx.muted, fontSize = 13.sp)
        }
    }
}

// ---------- Код ----------

private val languageAliases = mapOf(
    "sh" to "shell", "bash" to "shell", "zsh" to "shell", "console" to "shell", "shell-session" to "shell", "shellscript" to "shell",
    "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript", "ts" to "typescript", "tsx" to "typescript",
    "py" to "python", "python3" to "python", "kt" to "kotlin", "kts" to "kotlin", "yml" to "yaml", "rs" to "rust",
    "c++" to "cpp", "cxx" to "cpp", "cs" to "csharp", "c#" to "csharp", "md" to "markdown", "gradle" to "groovy",
    "rb" to "ruby", "ps1" to "powershell", "pwsh" to "powershell", "bat" to "batch", "cmd" to "batch",
    "patch" to "diff", "pinescript" to "pine", "golang" to "go", "objective-c" to "objc", "vim" to "vim", "text" to "text"
)

fun markdownCodeLanguage(info: String): Language? {
    val l = info.trim().lowercase()
    if (l.isEmpty()) return null
    Languages.byId(languageAliases[l] ?: l)?.let { return it }
    return Languages.detect("x.$l").takeIf { it !== Languages.PLAIN }
}

private fun highlight(code: String, language: Language?): AnnotatedString {
    // Длинный блок с сотнями цветных отрезков Skia раскладывает квадратично — оставляем как есть
    if (language == null || code.length > MAX_HIGHLIGHTED_CODE) return AnnotatedString(code)
    val model = HighlightModel(language)
    model.reset(code)
    val snap = model.snapshot
    return buildAnnotatedString {
        append(code)
        for (line in 0 until snap.lineCount) {
            val base = snap.lineStart(line)
            val t = snap.lineTokens(line)
            var k = 0
            while (k + 2 < t.size) {
                SyntaxColors.style(HighlightSnapshot.TOKS[t[k + 2]])?.let { st ->
                    val s = (base + t[k]).coerceIn(0, code.length)
                    val e = (base + t[k + 1]).coerceIn(s, code.length)
                    if (e > s) addStyle(st, s, e)
                }
                k += 3
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code, ctx: MdContext) {
    val language = remember(block.language) { markdownCodeLanguage(block.language) }
    val highlighted = remember(block.code, language) { highlight(block.code, language) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ctx.codeBackground,
        border = BorderStroke(1.dp, ctx.border.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    (language?.name ?: block.language).ifBlank { "код" },
                    fontSize = 11.sp,
                    color = ctx.muted,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val sel = StringSelection(block.code)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Копировать код", tint = ctx.muted, modifier = Modifier.size(14.dp))
                }
            }
            Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                Text(
                    highlighted,
                    fontFamily = AppFonts.JetBrainsMono,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = ctx.text,
                    softWrap = false
                )
            }
        }
    }
}

// ---------- Таблицы и свойства ----------

@Composable
private fun TableView(table: MdBlock.Table, ctx: MdContext) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val body = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, color = ctx.text)
    val headerBackground = ctx.codeBackground
    val stripe = ctx.codeBackground.copy(alpha = ctx.codeBackground.alpha * 0.45f)
    val rows = listOf(table.header) + table.rows
    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        TableLayout(
            columns = columns,
            rowCount = rows.size,
            border = ctx.border,
            headerBackground = headerBackground,
            stripe = stripe,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, ctx.border, RoundedCornerShape(6.dp))
        ) {
            rows.forEachIndexed { r, row ->
                for (c in 0 until columns) {
                    val cell = row.getOrNull(c).orEmpty()
                    val align = when (table.align.getOrNull(c)) {
                        MdAlign.CENTER -> TextAlign.Center
                        MdAlign.RIGHT -> TextAlign.End
                        else -> TextAlign.Start
                    }
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        InlineContent(
                            cell,
                            if (r == 0) body.copy(fontWeight = FontWeight.Bold) else body,
                            ctx,
                            align = align
                        )
                    }
                }
            }
        }
    }
}

/** Сетка: ширина колонки — по самой широкой ячейке (не шире 360dp), высота строки — по самой высокой. */
@Composable
private fun TableLayout(
    columns: Int,
    rowCount: Int,
    border: Color,
    headerBackground: Color,
    stripe: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var geometry: Pair<IntArray, IntArray>? = null
    Layout(
        content = content,
        modifier = modifier.drawBehind {
            val (xs, ys) = geometry ?: return@drawBehind
            drawRect(headerBackground, size = androidx.compose.ui.geometry.Size(size.width, ys[1].toFloat()))
            for (r in 1 until rowCount) {
                if (r % 2 == 0) {
                    drawRect(stripe, topLeft = Offset(0f, ys[r].toFloat()), size = androidx.compose.ui.geometry.Size(size.width, (ys[r + 1] - ys[r]).toFloat()))
                }
                drawLine(border, Offset(0f, ys[r].toFloat()), Offset(size.width, ys[r].toFloat()), strokeWidth = 1f)
            }
            for (c in 1 until columns) {
                drawLine(border, Offset(xs[c].toFloat(), 0f), Offset(xs[c].toFloat(), size.height), strokeWidth = 1f)
            }
        }
    ) { measurables, constraints ->
        val maxCell = 360.dp.roundToPx()
        val widths = IntArray(columns)
        measurables.forEachIndexed { i, m ->
            val c = i % columns
            widths[c] = maxOf(widths[c], m.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(maxCell))
        }
        val placeables = measurables.mapIndexed { i, m ->
            val w = widths[i % columns]
            m.measure(Constraints(minWidth = w, maxWidth = w))
        }
        val heights = IntArray(rowCount) { r -> (0 until columns).maxOf { c -> placeables[r * columns + c].height } }
        val xs = IntArray(columns + 1).also { a -> for (c in 0 until columns) a[c + 1] = a[c] + widths[c] }
        val ys = IntArray(rowCount + 1).also { a -> for (r in 0 until rowCount) a[r + 1] = a[r] + heights[r] }
        geometry = xs to ys
        layout(xs[columns], ys[rowCount]) {
            placeables.forEachIndexed { i, p -> p.place(xs[i % columns], ys[i / columns]) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PropertiesView(block: MdBlock.Properties, ctx: MdContext) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ctx.codeBackground.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, ctx.border.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Свойства", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ctx.muted)
            for ((key, values) in block.entries) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(key, fontSize = 13.sp, color = ctx.muted, modifier = Modifier.width(140.dp))
                    if (values.size <= 1) {
                        Text(values.firstOrNull().orEmpty(), fontSize = 13.sp, color = ctx.text)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (v in values) {
                                Surface(shape = RoundedCornerShape(10.dp), color = ctx.link.copy(alpha = 0.12f)) {
                                    Text(v, fontSize = 12.sp, color = ctx.link, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Блок кода крупнее — показываем без подсветки. */
private const val MAX_HIGHLIGHTED_CODE = 50_000
