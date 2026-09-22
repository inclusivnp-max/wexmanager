package org.wexclient.app.ui

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.vfs.ssh.SshFileSystemProvider
import org.wexclient.app.vfs.ssh.SshServers
import org.wexclient.app.vfs.ssh.SshTarget
import java.nio.file.Path

/**
 * Подключение к серверу по SSH. Вход — по ключу или через агент, как у самого ssh:
 * пароли программа не спрашивает и не хранит.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SshConnectDialog(
    onConnected: (Path) -> Unit,
    onDismiss: () -> Unit
) {
    var address by remember { mutableStateOf(SshServers.recent.firstOrNull().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val hosts = remember { SshServers.configHosts() }

    fun connect(text: String) {
        val target = SshTarget.parse(text)
        if (target == null) {
            error = "Адрес вида user@host, user@host:2222 или имя из ~/.ssh/config"
            return
        }
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SshFileSystemProvider.instance.open(target) }
            }
            busy = false
            result.onSuccess { fs ->
                SshServers.remember(target.label)
                onConnected(fs.homePath())
            }
            result.onFailure { error = it.message ?: "Не подключилось" }
        }
    }

    ModalDialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false), dismissible = !busy) {
        // Адрес набирается сразу, Enter подключает — без щелчка по полю и кнопке
        val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { org.wexclient.app.editor.requestFocusSafely(fieldFocus) }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            modifier = Modifier.width(520.dp).dialogGlass(RoundedCornerShape(16.dp)).onPreviewKeyEvent { e ->
                dialogKeys(e, { if (address.isNotBlank() && !busy) connect(address) }, { if (!busy) onDismiss() })
            }
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Dns, contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Подключиться к серверу",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NoctaliaPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    DialogClose(onDismiss)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Вход по ключу, как у обычного ssh. Панель откроет домашнюю папку на сервере — дальше всё как с диском: F5, F6, Delete, правка файлов.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(14.dp))

                GrailCompactField(
                    value = address,
                    onChange = {
                        address = it
                        error = null
                    },
                    placeholder = "user@host, user@host:2222 или имя из ~/.ssh/config",
                    mono = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                )

                val suggestions = (SshServers.recent + hosts).distinct().filter { it != address }.take(10)
                if (suggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (SshServers.recent.isNotEmpty()) "Недавние и из ~/.ssh/config" else "Из ~/.ssh/config",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { item ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.clickable(enabled = !busy) {
                                    address = item
                                    connect(item)
                                }
                            ) {
                                Text(
                                    text = item,
                                    fontSize = 12.sp,
                                    fontFamily = org.wexclient.app.theme.AppFonts.JetBrainsMono,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                error?.let {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(it, fontSize = 12.sp, color = NoctaliaDanger.copy(alpha = 0.85f))
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NoctaliaPrimary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Подключение…", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.height(36.dp)) {
                        Text("Отмена", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    val ready = address.isNotBlank() && !busy
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = NoctaliaPrimary.copy(alpha = if (ready) 0.18f else 0.06f),
                        border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = if (ready) 1f else 0.3f)),
                        modifier = Modifier.height(36.dp).clickable(enabled = ready) { connect(address) }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight().padding(horizontal = 18.dp)) {
                            Text("Подключиться", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NoctaliaPrimary)
                        }
                    }
                }
            }
        }
    }
}
