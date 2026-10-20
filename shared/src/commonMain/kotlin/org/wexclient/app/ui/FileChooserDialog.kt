package org.wexclient.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.util.AppDirs
import org.wexclient.app.util.FileChooserRequest
import org.wexclient.app.util.FileChooserRequests
import java.io.File

/** Показывает просьбы [FileChooserRequests] — там, где нет системного окна выбора (Android). */
@Composable
fun FileChooserHost() {
    val request = FileChooserRequests.current ?: return
    FileChooserDialog(request) { FileChooserRequests.answer(it) }
}

@Composable
private fun FileChooserDialog(request: FileChooserRequest, onDone: (File?) -> Unit) {
    val start = remember(request) {
        (request.start?.let { if (it.isDirectory) it else it.parentFile } ?: AppDirs.userHome.toFile())
            .takeIf { it.isDirectory } ?: AppDirs.userHome.toFile()
    }
    var dir by remember(request) { mutableStateOf(start) }
    var name by remember(request) { mutableStateOf(request.suggestedName.orEmpty()) }
    val listing = remember(dir) {
        val all = dir.listFiles()?.toList().orEmpty().filter { !it.isHidden || it.isDirectory }
        val dirs = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = if (request.mode == FileChooserRequest.Mode.OPEN_DIRECTORY) emptyList() else all.filter { f ->
            f.isFile && (request.extensions == null || f.extension.lowercase() in request.extensions)
        }.sortedBy { it.name.lowercase() }
        dirs + files
    }
    ModalDialog(onDismissRequest = { onDone(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(request.title, style = MaterialTheme.typography.titleMedium, color = NoctaliaPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { dir.parentFile?.takeIf { it.canRead() }?.let { dir = it } }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Выше")
                    }
                    Text(dir.path, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(listing, key = { it.path }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                when {
                                    f.isDirectory -> dir = f
                                    request.mode == FileChooserRequest.Mode.SAVE -> name = f.name
                                    else -> onDone(f)
                                }
                            }.padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (f.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = if (f.isDirectory) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (request.mode == FileChooserRequest.Mode.SAVE) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Имя файла") }, modifier = Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onDone(null) }) { Text("Отмена") }
                    when (request.mode) {
                        FileChooserRequest.Mode.OPEN_DIRECTORY -> TextButton(onClick = { onDone(dir) }) { Text("Выбрать эту папку") }
                        FileChooserRequest.Mode.SAVE -> TextButton(
                            enabled = name.isNotBlank(),
                            onClick = { onDone(File(dir, name.trim())) }
                        ) { Text("Сохранить") }
                        FileChooserRequest.Mode.OPEN_FILE -> Unit
                    }
                }
            }
        }
    }
}
