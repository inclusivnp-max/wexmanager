package org.wexclient.app.ui

import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jf.dexlib2.iface.Method
import org.wexclient.app.dex.DexClassNode
import org.wexclient.app.dex.DexEditorEngine
import org.wexclient.app.dex.GhidraLine
import org.wexclient.app.dex.GhidraLineKind
import org.wexclient.app.dex.GhidraMethodListing
import org.wexclient.app.dex.GhidraRefTarget
import org.wexclient.app.dex.GhidraXref
import org.wexclient.app.dex.buildGhidraListing
import org.wexclient.app.dex.findCallers
import org.wexclient.app.dex.methodKey
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private val GhidraComment = Color(0xFF86EFAC)

private fun GhidraMethodListing.renderText(): String = buildString {
    appendLine("; class $className")
    for (l in lines) {
        when (l.kind) {
            GhidraLineKind.INSTRUCTION -> {
                val addr = "0x" + Integer.toHexString(l.address * 2).uppercase().padStart(4, '0')
                append(addr).append("  ")
                append(l.bytesHex.padEnd(23)).append("  ")
                append(l.mnemonic.padEnd(22)).append("  ")
                append(l.operands)
                if (l.comment.isNotEmpty()) append("  ; ").append(l.comment)
                appendLine()
            }
            else -> {
                val head = "${l.mnemonic} ${l.operands} ${l.comment}".trim()
                if (head.isNotEmpty()) appendLine("; $head")
            }
        }
    }
}

private fun defaultMethod(
    engine: DexEditorEngine,
    classNode: DexClassNode,
    methodKey: String?
): Method? {
    val methods = try {
        classNode.classDef.methods.toList()
    } catch (_: Exception) {
        emptyList()
    }
    if (methods.isEmpty()) return null
    methodKey?.let { key ->
        methods.firstOrNull { m ->
            try {
                engine.methodKey(m) == key
            } catch (_: Exception) {
                false
            }
        }?.let { return it }
    }
    return methods.firstOrNull { m ->
        try {
            m.implementation != null
        } catch (_: Exception) {
            false
        }
    } ?: methods.firstOrNull()
}

@Composable
fun GhidraListingView(
    engine: DexEditorEngine,
    classNode: DexClassNode,
    methodKey: String?,
    onMethodSelect: (String) -> Unit,
    onNavigateToMethod: (className: String, methodKey: String) -> Unit
) {
    val methods = remember(classNode) {
        try {
            classNode.classDef.methods.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }
    if (methods.isEmpty()) {
        GrailEmptyState("В классе нет методов")
        return
    }
    val method = remember(classNode, methodKey) {
        defaultMethod(engine, classNode, methodKey)
    } ?: run {
        GrailEmptyState("Метод не найден")
        return
    }
    val currentKey = remember(method) {
        try {
            engine.methodKey(method)
        } catch (_: Exception) {
            ""
        }
    }

    val listing = remember(engine, classNode, method) {
        try {
            engine.buildGhidraListing(classNode, method)
        } catch (e: Exception) {
            GhidraMethodListing(
                className = classNode.fullName,
                methodKey = currentKey,
                methodSignature = method.name,
                lines = listOf(
                    GhidraLine(-1, "", "", "Ошибка построения листинга: ${e.message ?: e.javaClass.simpleName}", "", GhidraLineKind.NOTE, null)
                ),
                addrToIndex = emptyMap()
            )
        }
    }
    val listState: LazyListState = remember(method) { LazyListState() }
    val scope = rememberCoroutineScope()
    val chipsScroll = rememberScrollState()

    var showXrefs by remember(method) { mutableStateOf(false) }
    var xrefs by remember(method) { mutableStateOf<List<GhidraXref>?>(null) }
    if (showXrefs && xrefs == null) {
        LaunchedEffect(engine, classNode, method) {
            xrefs = try {
                withContext(Dispatchers.IO) {
                    engine.findCallers(classNode.fullName, currentKey)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    // Сколько строк занимают XREF-блоки (для прокрутки к адресам)
    val xrefRowCount = 1 + if (!showXrefs) {
        0
    } else if (xrefs == null || xrefs!!.isEmpty()) {
        1
    } else {
        xrefs!!.size
    }

    fun scrollToAddress(addr: Int) {
        val idx = listing.addrToIndex[addr] ?: return
        scope.launch { listState.scrollToItem(xrefRowCount + idx) }
    }

    fun copyListing() {
        val sel = StringSelection(listing.renderText())
        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Методы класса + действия
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(chipsScroll),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                methods.forEach { m ->
                    val key = try {
                        engine.methodKey(m)
                    } catch (_: Exception) {
                        return@forEach
                    }
                    val isSel = key == currentKey
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSel) NoctaliaPrimary.copy(alpha = 0.25f) else Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSel) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.clickable { onMethodSelect(key) }
                    ) {
                        Text(
                            text = m.name,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = AppFonts.JetBrainsMono,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (isSel) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { copyListing() }, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Копировать листинг",
                    tint = NoctaliaSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            // XREF-панель
            item(key = "xref-toggle") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showXrefs = !showXrefs }) {
                        Text(
                            text = "XREF",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = AppFonts.JetBrainsMono,
                                fontWeight = FontWeight.Bold
                            ),
                            color = NoctaliaPrimary
                        )
                        androidx.compose.material3.Icon(
                            if (showXrefs) androidx.compose.material.icons.Icons.Default.ExpandMore
                            else androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (showXrefs) "Свернуть" else "Развернуть",
                            tint = NoctaliaPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (showXrefs) {
                        if (xrefs == null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = NoctaliaPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "сканирую вызовы...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            Text(
                                text = if (xrefs!!.isEmpty()) "вызовов не найдено" else "вызовов: ${xrefs!!.size}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (showXrefs) {
                val xr = xrefs
                if (xr == null) {
                    item(key = "xref-loading") { Spacer(modifier = Modifier.height(4.dp)) }
                } else if (xr.isEmpty()) {
                    item(key = "xref-empty") {
                        Text(
                            text = "Метод никто не вызывает (или вызовы вне DEX)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
                        )
                    }
                } else {
                    items(xr, key = { "x:${it.callerClass}#${it.callerMethodKey}@${it.address}" }) { x ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToMethod(x.callerClass, x.callerMethodKey) }
                                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "→ ${x.callerClass}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = AppFonts.JetBrainsMono,
                                    fontSize = 12.sp
                                ),
                                color = NoctaliaPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = x.callerMethodKey.substringBefore("("),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = AppFonts.JetBrainsMono,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                            Text(
                                text = "0x" + Integer.toHexString(x.address * 2).uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // Строки листинга
            itemsIndexed(listing.lines, key = { i, l -> "l$i:${l.address}:${l.mnemonic}" }) { _, line ->
                when (line.kind) {
                    GhidraLineKind.METHOD_HEADER -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = "${line.mnemonic} ${line.operands} ${line.comment}".trim(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = AppFonts.JetBrainsMono,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                ),
                                color = NoctaliaPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    GhidraLineKind.TRY_HEADER, GhidraLineKind.NOTE -> {
                        Text(
                            text = "; ${line.mnemonic} ${line.operands} ${line.comment}".trim(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = AppFonts.JetBrainsMono,
                                fontSize = 11.sp
                            ),
                            color = NoctaliaSecondary,
                            modifier = Modifier.padding(start = 96.dp, top = 1.dp, bottom = 1.dp)
                        )
                    }
                    GhidraLineKind.INSTRUCTION -> {
                        val target = line.refTarget
                        val navigable = when (target) {
                            is GhidraRefTarget.Address -> true
                            is GhidraRefTarget.Method ->
                                engine.classes.any { it.fullName == target.className }
                            null -> false
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (navigable) {
                                        Modifier.clickable {
                                            when (target) {
                                                is GhidraRefTarget.Address -> scrollToAddress(target.address)
                                                is GhidraRefTarget.Method ->
                                                    onNavigateToMethod(target.className, target.methodKey)
                                                null -> {}
                                            }
                                        }
                                    } else Modifier
                                )
                                .padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "0x" + Integer.toHexString(line.address * 2).uppercase().padStart(4, '0'),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = AppFonts.JetBrainsMono,
                                    fontSize = 11.sp
                                ),
                                color = NoctaliaSecondary,
                                modifier = Modifier.width(72.dp)
                            )
                            Text(
                                text = line.bytesHex,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = AppFonts.JetBrainsMono,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(150.dp)
                            )
                            Text(
                                text = line.mnemonic,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = AppFonts.JetBrainsMono,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (navigable) NoctaliaPrimary else NoctaliaTertiary,
                                maxLines = 1,
                                modifier = Modifier.width(170.dp)
                            )
                            Text(
                                text = line.operands,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = AppFonts.JetBrainsMono,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (line.comment.isNotEmpty()) {
                                Text(
                                    text = "; " + line.comment,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = AppFonts.JetBrainsMono,
                                        fontSize = 11.sp,
                                        fontStyle = FontStyle.Italic
                                    ),
                                    color = GhidraComment,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
