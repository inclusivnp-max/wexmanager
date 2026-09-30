package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wexclient.app.icons.MaterialSymbols
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.icons.SymbolInfo
import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.icons.SymbolStyle
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary

private const val CATEGORY_PRESETS = -2
private const val CATEGORY_ALL = -1

/** Выбор иконки из набора Material Symbols Rounded (~4000 шт.). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SymbolPicker(
    selected: SymbolRef,
    onSelect: (SymbolRef) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(CATEGORY_PRESETS) }
    var loaded by remember { mutableStateOf(false) }

    // Индекс ~500 КБ — разбираем вне UI-потока
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { MaterialSymbols.all }
        loaded = true
    }

    val results: List<SymbolInfo> = remember(query, category, loaded) {
        if (!loaded) return@remember emptyList()
        when {
            query.isNotBlank() -> MaterialSymbols.search(query, category.takeIf { it >= 0 })
            // Некоторые имена — синонимы одного глифа: ключи сетки должны быть уникальны
            category == CATEGORY_PRESETS -> MaterialSymbols.folderPresets.mapNotNull { MaterialSymbols.info(it) }
                .distinctBy { it.codepoint }
            category == CATEGORY_ALL -> MaterialSymbols.search("")
            else -> MaterialSymbols.search("", category)
        }
    }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GrailSearchField(
                value = query,
                onChange = { query = it },
                placeholder = "Поиск иконки: folder, игры, код…",
                modifier = Modifier.weight(1f).height(40.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            GrailTabs(
                tabs = SymbolStyle.entries.map { it.title },
                selected = selected.style.ordinal
            ) { onSelect(selected.copy(style = SymbolStyle.entries[it])) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GrailTabChip("Для папок", category == CATEGORY_PRESETS) { category = CATEGORY_PRESETS }
            GrailTabChip("Все", category == CATEGORY_ALL) { category = CATEGORY_ALL }
            MaterialSymbols.categories.forEachIndexed { i, title ->
                GrailTabChip(title, category == i) { category = i }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (loaded) "Найдено: ${results.size}" else "Загрузка набора…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Выбрано: ${selected.name}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                color = NoctaliaPrimary
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            if (!loaded) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    WexSpinner(color = NoctaliaPrimary, modifier = Modifier.size(28.dp))
                }
            } else if (results.isEmpty()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("Ничего не найдено", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                val gridState = rememberLazyGridState()
                LaunchedEffect(query, category) { gridState.scrollToItem(0) }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(44.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().padding(6.dp)
                ) {
                    items(results, key = { it.codepoint }) { info ->
                        val isSelected = info.codepoint == selected.resolvedCodepoint()
                        TooltipArea(
                            tooltip = {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Text(
                                        info.name,
                                        fontSize = 11.sp,
                                        fontFamily = AppFonts.JetBrainsMono,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            },
                            delayMillis = 350,
                            tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp))
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) NoctaliaPrimary.copy(alpha = 0.22f) else Color.Transparent,
                                border = if (isSelected) BorderStroke(1.5.dp, NoctaliaPrimary) else null,
                                modifier = Modifier
                                    .padding(2.dp)
                                    .size(40.dp)
                                    .clickable {
                                        onSelect(SymbolRef(info.name, selected.style, info.codepoint))
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    SymbolIcon(
                                        SymbolRef(info.name, selected.style, info.codepoint),
                                        contentDescription = info.name,
                                        tint = if (isSelected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SymbolIconButton(
    name: String,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 28.dp,
    iconSize: androidx.compose.ui.unit.Dp = 16.dp,
    enabled: Boolean = true
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(size)) {
        SymbolIcon(
            SymbolRef(name),
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = NoctaliaPrimary
    )
}
