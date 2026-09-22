package org.wexclient.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wexclient.app.theme.NoctaliaTheme
import org.wexclient.app.vfs.VfsEntry
import org.wexclient.app.vfs.VfsManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

data class HashResult(
    val md5: String = "",
    val sha1: String = "",
    val sha256: String = "",
    val sha512: String = "",
    val crc32: String = ""
)

object FileChecksumEngine {
    /**
     * Потоком — файл любого размера без чтения в память. [isCancelled] проверяется между блоками,
     * [onProgress] вызывается не чаще раза на процент.
     */
    fun computeHashes(
        input: java.io.InputStream,
        total: Long,
        isCancelled: () -> Boolean = { false },
        onProgress: ((Float) -> Unit)? = null
    ): HashResult? {
        val md5Dig = MessageDigest.getInstance("MD5")
        val sha1Dig = MessageDigest.getInstance("SHA-1")
        val sha256Dig = MessageDigest.getInstance("SHA-256")
        val sha512Dig = MessageDigest.getInstance("SHA-512")
        val crc = CRC32()
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var reported = -1
        while (true) {
            if (isCancelled()) return null
            val n = input.read(buf)
            if (n < 0) break
            md5Dig.update(buf, 0, n)
            sha1Dig.update(buf, 0, n)
            sha256Dig.update(buf, 0, n)
            sha512Dig.update(buf, 0, n)
            crc.update(buf, 0, n)
            done += n
            if (total > 0 && onProgress != null) {
                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                if (pct != reported) {
                    reported = pct
                    onProgress(pct / 100f)
                }
            }
        }
        onProgress?.invoke(1f)
        fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
        return HashResult(
            md5 = toHex(md5Dig.digest()),
            sha1 = toHex(sha1Dig.digest()),
            sha256 = toHex(sha256Dig.digest()),
            sha512 = toHex(sha512Dig.digest()),
            crc32 = "%08X".format(crc.value)
        )
    }

    fun computeHashes(bytes: ByteArray, onProgress: ((Float) -> Unit)? = null): HashResult {
        val md5Dig = MessageDigest.getInstance("MD5")
        val sha1Dig = MessageDigest.getInstance("SHA-1")
        val sha256Dig = MessageDigest.getInstance("SHA-256")
        val sha512Dig = MessageDigest.getInstance("SHA-512")
        val crc = CRC32()

        val bufferSize = 65536
        var offset = 0
        val total = bytes.size

        while (offset < total) {
            val len = minOf(bufferSize, total - offset)
            md5Dig.update(bytes, offset, len)
            sha1Dig.update(bytes, offset, len)
            sha256Dig.update(bytes, offset, len)
            sha512Dig.update(bytes, offset, len)
            crc.update(bytes, offset, len)
            offset += len
            onProgress?.invoke(if (total > 0) offset.toFloat() / total else 1f)
        }

        fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

        return HashResult(
            md5 = toHex(md5Dig.digest()),
            sha1 = toHex(sha1Dig.digest()),
            sha256 = toHex(sha256Dig.digest()),
            sha512 = toHex(sha512Dig.digest()),
            crc32 = "%08X".format(crc.value)
        )
    }
}

@Composable
fun FileChecksumDialog(
    entry: VfsEntry,
    vfsManager: VfsManager,
    onDismiss: () -> Unit
) {
    var isCalculating by remember { mutableStateOf(true) }
    var hashResult by remember { mutableStateOf(HashResult()) }
    var calcProgress by remember { mutableStateOf(0f) }
    var verifyInput by remember { mutableStateOf("") }
    var copiedLabel by remember { mutableStateOf<String?>(null) }

    var calcError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.path) {
        val job = coroutineContext[kotlinx.coroutines.Job]
        withContext(Dispatchers.IO) {
            try {
                val total = runCatching { vfsManager.size(entry.path) }.getOrDefault(entry.size)
                // Потоком: образ на несколько гигабайт в память не влезал и суммы молча не считались
                vfsManager.openInputStream(entry.path).use { input ->
                    FileChecksumEngine.computeHashes(input, total, isCancelled = { job?.isActive == false }) { prog ->
                        calcProgress = prog
                    }
                }?.let { hashResult = it }
            } catch (e: Exception) {
                calcError = e.message ?: e.javaClass.simpleName
            } finally {
                isCalculating = false
            }
        }
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        onKeyEvent = latestKeyHandler { closeOnEscape(it, onDismiss) },
        state = rememberDialogState(size = DpSize(720.dp, 640.dp)),
        title = "Контрольные суммы — ${entry.name}"
    ) {
        NoctaliaTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header Bar
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Fingerprint,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Вычисление криптографических хэшей • ${entry.formattedSize}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            IconButton(
                                onClick = onDismiss,
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Закрыть")
                            }
                        }
                    }

                    // Progress indicator if calculating
                    if (isCalculating) {
                        LinearProgressIndicator(
                            progress = { calcProgress },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        calcError?.let {
                            Text(
                                "Не удалось прочитать файл: $it",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        // Verification input
                        OutlinedTextField(
                            value = verifyInput,
                            onValueChange = { verifyInput = it.trim() },
                            label = { Text("Проверить хэш (вставьте ожидаемую контрольную сумму)") },
                            placeholder = { Text("например: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        )

                        // Match verdict banner
                        if (verifyInput.isNotBlank() && !isCalculating) {
                            val cleanInput = verifyInput.lowercase()
                            val matchedAlgo = when {
                                cleanInput == hashResult.sha256.lowercase() -> "SHA-256"
                                cleanInput == hashResult.md5.lowercase() -> "MD5"
                                cleanInput == hashResult.sha1.lowercase() -> "SHA-1"
                                cleanInput == hashResult.crc32.lowercase() -> "CRC32"
                                cleanInput == hashResult.sha512.lowercase() -> "SHA-512"
                                else -> null
                            }

                            if (matchedAlgo != null) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF1B5E20).copy(alpha = 0.25f),
                                    border = BorderStroke(1.dp, Color(0xFF4CAF50)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Хэш совпадает с алгоритмом $matchedAlgo! Файл подлинный.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF81C784)
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Хэш не совпадает ни с одной контрольной суммой этого файла.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Hash Rows
                        HashRow("SHA-256", hashResult.sha256, isCalculating) {
                            copyToClipboard(hashResult.sha256)
                            copiedLabel = "SHA-256 скопирован"
                        }

                        HashRow("MD5", hashResult.md5, isCalculating) {
                            copyToClipboard(hashResult.md5)
                            copiedLabel = "MD5 скопирован"
                        }

                        HashRow("SHA-1", hashResult.sha1, isCalculating) {
                            copyToClipboard(hashResult.sha1)
                            copiedLabel = "SHA-1 скопирован"
                        }

                        HashRow("CRC32", hashResult.crc32, isCalculating) {
                            copyToClipboard(hashResult.crc32)
                            copiedLabel = "CRC32 скопирован"
                        }

                        HashRow("SHA-512", hashResult.sha512, isCalculating) {
                            copyToClipboard(hashResult.sha512)
                            copiedLabel = "SHA-512 скопирован"
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Bottom Actions: Copy All
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            copiedLabel?.let { msg ->
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } ?: Spacer(modifier = Modifier.width(1.dp))

                            ElevatedButton(
                                onClick = {
                                    val report = """
                                        Файл: ${entry.name} (${entry.formattedSize})
                                        SHA-256: ${hashResult.sha256}
                                        MD5:     ${hashResult.md5}
                                        SHA-1:   ${hashResult.sha1}
                                        CRC32:   ${hashResult.crc32}
                                        SHA-512: ${hashResult.sha512}
                                    """.trimIndent()
                                    copyToClipboard(report)
                                    copiedLabel = "Все хэши скопированы"
                                },
                                enabled = !isCalculating,
                                colors = ButtonDefaults.elevatedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Копировать все хэши", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HashRow(
    label: String,
    value: String,
    isLoading: Boolean,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(72.dp)
            )

            if (isLoading) {
                Text(
                    text = "Вычисление...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Копировать",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(text: String) {
    val sel = StringSelection(text)
    Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
}
