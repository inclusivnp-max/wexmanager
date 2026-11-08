package org.wexclient.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.wexclient.app.doc.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfTool(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(38.dp).noFocus().then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp)) else Modifier)) {
            Icon(icon, label, Modifier.size(20.dp), tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .3f) else if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PdfReaderMenus(state: PdfReadingState, onChange: (PdfReadingState) -> Unit, onMode: (PdfReadingMode) -> Unit) {
    var modes by remember { mutableStateOf(false) }
    var papers by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { modes = true }) {
            Icon(when (state.mode) { PdfReadingMode.SCROLL -> Icons.Default.ViewDay; PdfReadingMode.PAGE -> Icons.Default.Description; PdfReadingMode.TEXT -> Icons.Default.FormatSize }, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text(when (state.mode) { PdfReadingMode.SCROLL -> "Лента"; PdfReadingMode.PAGE -> "По странице"; PdfReadingMode.TEXT -> "Текст" })
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = modes, onDismissRequest = { modes = false }) {
            listOf(PdfReadingMode.SCROLL to "Непрерывная прокрутка", PdfReadingMode.PAGE to "По одной странице", PdfReadingMode.TEXT to "Текст для чтения").forEach { (mode, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onMode(mode); modes = false }, trailingIcon = { if (state.mode == mode) Icon(Icons.Default.Check, null) }, enabled = mode != PdfReadingMode.TEXT || PdfTools.textAvailable)
            }
        }
    }
    Box {
        TextButton(onClick = { papers = true }) {
            Icon(when (state.paper) { PdfPaper.LIGHT -> Icons.Default.LightMode; PdfPaper.SEPIA -> Icons.Default.AutoStories; PdfPaper.DARK -> Icons.Default.DarkMode }, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text(when (state.paper) { PdfPaper.LIGHT -> "Светлая"; PdfPaper.SEPIA -> "Бумага"; PdfPaper.DARK -> "Ночная" })
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = papers, onDismissRequest = { papers = false }) {
            listOf(PdfPaper.LIGHT to "Светлая — исходные цвета", PdfPaper.SEPIA to "Бумага — тёплый фон", PdfPaper.DARK to "Ночная — тёмные страницы").forEach { (paper, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onChange(state.copy(paper = paper)); papers = false }, trailingIcon = { if (state.paper == paper) Icon(Icons.Default.Check, null) })
            }
        }
    }
}

internal data class PdfPalette(val desk: Color, val sheet: Color, val ink: Color, val filter: ColorFilter?)
internal fun pdfPalette(paper: PdfPaper): PdfPalette = when (paper) {
    PdfPaper.LIGHT -> PdfPalette(Color(0xFFE0E3E8), Color.White, Color(0xFF20242C), null)
    PdfPaper.SEPIA -> PdfPalette(Color(0xFFD9CFBD), Color(0xFFF7ECD4), Color(0xFF382F24), ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        .249f, .488f, .095f, 0f, 35f,
        .24f, .469f, .091f, 0f, 32f,
        .22f, .432f, .084f, 0f, 24f,
        0f, 0f, 0f, 1f, 0f
    ))))
    PdfPaper.DARK -> PdfPalette(Color(0xFF14181E), Color(0xFF1F242C), Color(0xFFE4E0D7), ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        -.78f, 0f, 0f, 0f, 230f,
        0f, -.78f, 0f, 0f, 235f,
        0f, 0f, -.75f, 0f, 235f,
        0f, 0f, 0f, 1f, 0f
    ))))
}

@Composable
internal fun PdfReaderSidebar(session: PdfBookSession, info: PdfInfo, state: PdfReadingState, selected: Int, onTab: (Int) -> Unit, hits: List<PdfSearchHit>, query: String, onPage: (Int) -> Unit, onRemove: (Int) -> Unit) {
    Column(Modifier.width(230.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        if (query.isBlank()) Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TextButton(onClick = { onTab(0) }, modifier = Modifier.weight(1f)) { Text("Страницы", fontWeight = if (selected == 0) FontWeight.Bold else FontWeight.Normal) }
            TextButton(onClick = { onTab(1) }, modifier = Modifier.weight(1f)) { Text("Закладки", fontWeight = if (selected == 1) FontWeight.Bold else FontWeight.Normal) }
        } else Text("Результаты · ${hits.size}", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(selected) { if (selected == 0 && query.isBlank()) list.scrollToItem((state.page - 1).coerceAtLeast(0)) }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    query.isNotBlank() -> {
                        if (hits.isEmpty()) item { Text("Совпадения появятся здесь. Поиск работает по текстовому слою PDF.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
                        items(hits, key = { it.page }) { hit ->
                            Surface(onClick = { onPage(hit.page) }, shape = RoundedCornerShape(10.dp), color = if (state.page == hit.page) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Страница ${hit.page} · ${hit.occurrences}", style = MaterialTheme.typography.labelMedium)
                                    Text(hit.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                    selected == 1 -> {
                        if (state.bookmarks.isEmpty()) item {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.BookmarkBorder, null, tint = MaterialTheme.colorScheme.primary)
                                Text("Нужное место — под рукой", style = MaterialTheme.typography.titleSmall)
                                Text("Нажмите Ctrl+D или значок закладки на панели.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        items(state.bookmarks.sorted(), key = { it }) { page ->
                            Row(Modifier.fillMaxWidth().background(if (page == state.page) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp)).clickable { onPage(page) }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Страница $page", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                PdfTool("Удалить закладку страницы $page", Icons.Default.Close, { onRemove(page) })
                            }
                        }
                    }
                    else -> items(info.pages, key = { it }) { index ->
                        Box(Modifier.fillMaxWidth().clickable { onPage(index + 1) }, contentAlignment = Alignment.Center) {
                            PdfBookPage(session, index + 1, info, state.copy(fit = PdfFit.WIDTH), 188f, 250f, state.page == index + 1, thumbnail = true)
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
