package org.wexclient.app.ui

import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import kotlinx.coroutines.withContext
import org.wexclient.app.elf.ElfDocument
import org.wexclient.app.elf.NativeCode
import org.wexclient.app.elf.NativeDisasm
import org.wexclient.app.elf.NativeFunc
import org.wexclient.app.elf.NativeInsn
import org.wexclient.app.elf.NativeJump
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTertiary

private val NativeComment = Color(0xFF86EFAC)

private sealed interface DisasmState {
    data object Loading : DisasmState
    data class Error(val message: String) : DisasmState
    data class Ready(val code: NativeCode) : DisasmState
}

@Composable
fun NativeDisasmTab(doc: ElfDocument, docVersion: Int) {
    @Suppress("UNUSED_EXPRESSION") docVersion
    var runId by remember(doc) { mutableStateOf(0) }
    var state by remember(doc) { mutableStateOf<DisasmState>(DisasmState.Loading) }

    LaunchedEffect(doc, runId) {
        state = DisasmState.Loading
        state = try {
            val snapshot = doc.toByteArray()
            val machine = doc.parsed.header.machine
            val code = withContext(Dispatchers.IO) {
                NativeDisasm.disassembleBytes(snapshot, machine)
            }
            DisasmState.Ready(code)
        } catch (e: Exception) {
            DisasmState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    when (val s = state) {
        is DisasmState.Loading -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                WexSpinner(color = NoctaliaPrimary)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Дизассемблирую… (первый прогон долгий)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        is DisasmState.Error -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Нечем дизассемблировать: ${s.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                GrailHint("Нужен llvm-objdump (ARM) или binutils objdump (x86)")
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { runId++ },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NoctaliaPrimary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) { Text("Повторить", fontWeight = FontWeight.Bold) }
            }
        }
        is DisasmState.Ready -> NativeFuncBrowser(doc, s.code, onRefresh = { runId++ })
    }
}

@Composable
private fun NativeFuncBrowser(
    doc: ElfDocument,
    code: NativeCode,
    onRefresh: () -> Unit
) {
    var filter by remember { mutableStateOf("") }
    val funcs = remember(code) { NativeDisasm.functionsOf(doc) }
    val shown = remember(funcs, filter) {
        val f = filter.trim()
        val base = if (f.isBlank()) funcs else funcs.filter { it.name.contains(f, ignoreCase = true) }
        base.take(5000)
    }
    var selected by remember(code) { mutableStateOf(shown.firstOrNull()) }
    if (selected != null && shown.none { it.vaddr == selected!!.vaddr && it.name == selected!!.name }) {
        selected = shown.firstOrNull()
    }
    // Отложенная прокрутка после межфункционального перехода:
    // ставится целью, листинг скроллит сам (или переключает функцию и скроллит потом)
    var pendingScroll by remember(code) { mutableStateOf<Long?>(null) }

    Row(modifier = Modifier.fillMaxSize()) {
        // Функции слева
        Column(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GrailHint("Функций: ${funcs.size} · ${code.toolName}")
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Обновить дизасм",
                        tint = NoctaliaSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            GrailSearchField(filter, { filter = it }, "Фильтр функций...")
            Spacer(modifier = Modifier.height(6.dp))
            if (funcs.isEmpty()) {
                GrailEmptyState("FUNC-символов нет (stripped?)")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { "${it.vaddr}:${it.name}" }) { f ->
                        val isSel = selected?.vaddr == f.vaddr && selected?.name == f.name
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSel) NoctaliaPrimary.copy(alpha = 0.18f) else Color.Transparent,
                            border = if (isSel) BorderStroke(
                                1.dp,
                                NoctaliaPrimary.copy(alpha = 0.6f)
                            ) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                                .clickable { selected = f }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(
                                    text = f.name,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = AppFonts.JetBrainsMono,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isSel) NoctaliaPrimary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "0x" + java.lang.Long.toHexString(f.vaddr).uppercase() +
                                            if (f.size > 0) " · ${f.size} байт" else "",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant
        ) {}

        // Листинг справа
        val func = selected
        if (func == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                GrailEmptyState("Выбери функцию слева")
            }
        } else {
            NativeFuncListing(
                code = code,
                func = func,
                allFuncs = funcs,
                pendingScroll = pendingScroll,
                onPendingConsumed = { pendingScroll = null },
                onSelectFunc = { selected = it },
                onJumpTo = { pendingScroll = it }
            )
        }
    }
}

@Composable
private fun NativeFuncListing(
    code: NativeCode,
    func: NativeFunc,
    allFuncs: List<NativeFunc>,
    pendingScroll: Long?,
    onPendingConsumed: () -> Unit,
    onSelectFunc: (NativeFunc) -> Unit,
    onJumpTo: (Long) -> Unit
) {
    val idx = allFuncs.indexOfFirst { it.vaddr == func.vaddr && it.name == func.name }
    val nextVaddr = allFuncs.subList(idx + 1, allFuncs.size).firstOrNull()?.vaddr
    val rows = remember(code, func) { NativeDisasm.sliceForFunc(code.insns, func, nextVaddr) }
    val addrToIndex = remember(rows) { rows.mapIndexed { i, r -> r.vaddr to i }.toMap() }
    val listState: LazyListState = remember(func) { LazyListState() }

    var showXrefs by remember(func) { mutableStateOf(false) }
    val xrefs = remember(code, func, nextVaddr) {
        if (showXrefs) NativeDisasm.xrefsTo(code.insns, func, nextVaddr) else emptyList()
    }
    val xrefRowCount = 1 + if (!showXrefs) 0 else if (xrefs.isEmpty()) 1 else xrefs.size

    fun funcForAddr(addr: Long): NativeFunc? {
        // Функция, чей диапазон содержит адрес
        var lo = 0
        var hi = allFuncs.size - 1
        var best: NativeFunc? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val f = allFuncs[mid]
            if (addr < f.vaddr) {
                hi = mid - 1
            } else {
                best = f
                lo = mid + 1
            }
        }
        if (best == null) return null
        val end = if (best.size > 0) best.vaddr + best.size else {
            val ni = allFuncs.indexOfFirst { it.vaddr == best.vaddr && it.name == best.name }
            allFuncs.subList(ni + 1, allFuncs.size).firstOrNull()?.vaddr ?: (best.vaddr + 4096)
        }
        return if (addr < end) best else null
    }

    // Отложенный скролл: сначала пробуем тут, иначе переключаем функцию
    LaunchedEffect(pendingScroll, rows) {
        val t = pendingScroll ?: return@LaunchedEffect
        val local = addrToIndex[t]
        if (local != null) {
            listState.scrollToItem(xrefRowCount + local)
            onPendingConsumed()
        } else {
            funcForAddr(t)?.let { onSelectFunc(it) }
        }
    }

    fun copyListing() {
        val text = buildString {
            appendLine("; ${func.name} @ 0x" + java.lang.Long.toHexString(func.vaddr).uppercase())
            for (r in rows) {
                append("0x").append(java.lang.Long.toHexString(r.vaddr).uppercase())
                    .append("  ").append(r.bytesHex.padEnd(23))
                    .append("  ").append(r.mnemonic.padEnd(12))
                    .append("  ").append(r.operands)
                    .appendLine()
            }
        }
        org.wexclient.app.util.TextClipboard.copy(text)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${func.name} · ${rows.size} инстр.",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = AppFonts.JetBrainsMono,
                    fontWeight = FontWeight.Bold
                ),
                color = NoctaliaPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (code.truncated) {
                Text(
                    text = "урезан",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
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
            item(key = "nxr") {
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
                        Text(
                            text = if (xrefs.isEmpty()) "вызовов не найдено" else "вызовов: ${xrefs.size}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (showXrefs) {
                if (xrefs.isEmpty()) {
                    item(key = "nxr-empty") {
                        Text(
                            text = "Никто не ссылается (или ссылки вне .text)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
                        )
                    }
                } else {
                    items(xrefs, key = { "x:${it.vaddr}" }) { x ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJumpTo(x.vaddr) }
                                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "→ 0x" + java.lang.Long.toHexString(x.vaddr).uppercase(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = AppFonts.JetBrainsMono,
                                    fontSize = 12.sp
                                ),
                                color = NoctaliaPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = x.mnemonic,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = AppFonts.JetBrainsMono,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            itemsIndexed(rows, key = { i, r -> "n$i:${r.vaddr}" }) { _, r ->
                val clickableTarget: Long? = when (r.jump) {
                    NativeJump.JUMP, NativeJump.CALL -> r.targetVaddr
                    else -> null
                }
                val canGo = clickableTarget != null &&
                        (addrToIndex.containsKey(clickableTarget) || funcForAddr(clickableTarget) != null)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (canGo) {
                                Modifier.clickable { onJumpTo(clickableTarget!!) }
                            } else Modifier
                        )
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "0x" + java.lang.Long.toHexString(r.vaddr).uppercase(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = AppFonts.JetBrainsMono,
                            fontSize = 11.sp
                        ),
                        color = NoctaliaSecondary,
                        maxLines = 1,
                        modifier = Modifier.width(130.dp)
                    )
                    Text(
                        text = r.bytesHex,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = AppFonts.JetBrainsMono,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(170.dp)
                    )
                    Text(
                        text = r.mnemonic,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = AppFonts.JetBrainsMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (canGo) NoctaliaPrimary else NoctaliaTertiary,
                        maxLines = 1,
                        modifier = Modifier.width(110.dp)
                    )
                    Text(
                        text = r.operands,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = AppFonts.JetBrainsMono,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    val comment = when (r.jump) {
                        NativeJump.RET -> "возврат"
                        NativeJump.INDIRECT -> "косвенно"
                        else -> ""
                    }
                    if (comment.isNotEmpty()) {
                        Text(
                            text = "; $comment",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = AppFonts.JetBrainsMono,
                                fontSize = 11.sp,
                                fontStyle = FontStyle.Italic
                            ),
                            color = NativeComment
                        )
                    }
                }
            }
        }
    }
}
