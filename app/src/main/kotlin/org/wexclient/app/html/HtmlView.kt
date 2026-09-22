package org.wexclient.app.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.wexclient.app.markdown.MarkdownBlocksView
import org.wexclient.app.markdown.MdBlock
import java.nio.file.Path

/**
 * Просмотр страницы HTML: разметка показывается так же, как Markdown — заголовки, списки,
 * таблицы, картинки и ссылки. Скрипты не выполняются, запросов в сеть страница не делает
 * (кроме картинок, которые и в Markdown грузятся по щелчку).
 */
@Composable
fun HtmlView(
    text: String,
    baseDir: Path?,
    modifier: Modifier = Modifier,
    onOpenFile: ((Path) -> Unit)? = null
) {
    // Разбор не должен ронять окно: в худшем случае показываем исходный текст
    val blocks = remember(text) {
        runCatching { HtmlBlocks.render(text) }.getOrElse { listOf(MdBlock.Code("html", text)) }
    }
    MarkdownBlocksView(blocks = blocks, baseDir = baseDir, modifier = modifier, onOpenFile = onOpenFile)
}
