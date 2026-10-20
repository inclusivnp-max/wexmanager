package org.wexclient.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wexclient.app.dex.ClassFileSet
import org.wexclient.app.dex.ClassFiles
import org.wexclient.app.dex.ClassView
import org.wexclient.app.editor.CodeEditorView
import org.wexclient.app.editor.lang.Languages
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsManager

// Последняя вкладка — для следующего открытого класса
private var lastClassView = ClassView.VINEFLOWER

/** Просмотр .class: декомпиляция тремя движками и листинг байт-кода. */
@Composable
fun ClassFileViewerDialog(entry: VfsEntry, vfsManager: VfsManager, onDismiss: () -> Unit) {
    var view by remember { mutableStateOf(lastClassView) }
    var classSet by remember { mutableStateOf<ClassFileSet?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val texts = remember { mutableStateMapOf<ClassView, String>() }
    val toast = rememberToastState()

    LaunchedEffect(entry.path) {
        withContext(Dispatchers.IO) {
            try {
                classSet = ClassFiles.load(
                    entry.path,
                    readBytes = { vfsManager.readBytesLimited(it, 64 shl 20) },
                    listDir = { dir -> vfsManager.listEntries(dir).filter { !it.isDirectory }.map { it.path } }
                )
            } catch (e: Exception) {
                loadError = e.message ?: e.javaClass.simpleName
            }
        }
    }
    LaunchedEffect(classSet, view) {
        val set = classSet ?: return@LaunchedEffect
        if (view in texts) return@LaunchedEffect
        texts[view] = withContext(Dispatchers.IO) { ClassFiles.render(set, view) }
    }

    val info = classSet?.info
    val text = texts[view]

    WexWindow(
        onCloseRequest = onDismiss,
        onKeyEvent = latestKeyHandler { closeOnEscape(it, onDismiss) },
        size = DpSize(1400.dp, 900.dp),
        title = entry.name
    ) {
        org.wexclient.app.theme.NoctaliaTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Coffee, contentDescription = null, tint = ClassFileTint, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = AppFonts.JetBrainsMono
                                        ),
                                        color = NoctaliaPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (info != null) {
                                        Text(
                                            text = listOfNotNull(
                                                info.className,
                                                info.kind,
                                                "${info.javaVersion} (${info.major}.${info.minor})",
                                                "Kotlin".takeIf { info.kotlin }
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = AppFonts.JetBrainsMono,
                                                fontSize = 11.sp
                                            ),
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                GrailTabs(ClassView.entries.map { it.title }, view.ordinal) {
                                    view = ClassView.entries[it]
                                    lastClassView = view
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                IconButton(
                                    onClick = {
                                        val current = texts[view] ?: return@IconButton
                                        org.wexclient.app.util.TextClipboard.copy(current)
                                        toast.success("Скопировано: ${view.title}")
                                    },
                                    enabled = text != null
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Копировать код", tint = NoctaliaSecondary)
                                }
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            val error = loadError
                            when {
                                error != null -> GrailEmptyState("Не удалось открыть класс: $error")
                                text == null -> Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    WexSpinner(color = NoctaliaPrimary)
                                    Spacer(modifier = Modifier.size(10.dp))
                                    Text(
                                        if (info == null) "Чтение класса…" else "${view.title}: декомпиляция…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                else -> key(view) {
                                    val simple = info?.simpleName ?: entry.name.substringBeforeLast('.')
                                    CodeEditorView(
                                        initialText = text,
                                        language = if (view == ClassView.BYTECODE) Languages.JVM_BYTECODE else Languages.JAVA,
                                        readOnly = true,
                                        fileName = if (view == ClassView.BYTECODE) "$simple.class" else "$simple.java"
                                    )
                                }
                            }
                        }
                    }
                    ToastHost(toast)
                }
            }
        }
    }
}

/** Цвет значка .class (как в списке файлов). */
internal val ClassFileTint = Color(0xFFF59E0B)
