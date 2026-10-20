package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.adb.AdbDevice
import org.wexclient.app.adb.AdbDeviceState
import org.wexclient.app.adb.AdbManager
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaDanger
import org.wexclient.app.theme.NoctaliaError
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaWarn
import org.wexclient.app.vfs.adb.AdbFileSystemProvider
import java.nio.file.Path

/**
 * Телефон по adb: подключённые устройства, у каждого — «Файлы» (панель открывает его общую память)
 * и «Приложения». Список обновляется сам, пока окно открыто: телефон можно подключить и после.
 */
@Composable
fun AdbDevicesDialog(
    onOpenFiles: (Path) -> Unit,
    onOpenApps: (AdbDevice) -> Unit,
    onDismiss: () -> Unit
) {
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var adbMissing by remember { mutableStateOf(false) }
    var firstLoad by remember { mutableStateOf(true) }
    /** Какой телефон сейчас открывается в панели. */
    var opening by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        adbMissing = withContext(Dispatchers.IO) { AdbManager.adbPath() == null }
        while (isActive && !adbMissing) {
            if (opening == null) {
                val result = withContext(Dispatchers.IO) { runCatching { AdbManager.listDevices() } }
                result.onSuccess {
                    devices = it
                    listError = null
                }
                result.onFailure { listError = it.message ?: it.javaClass.simpleName }
                firstLoad = false
            }
            delay(2500)
        }
    }

    fun openFiles(device: AdbDevice) {
        opening = device.serial
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AdbFileSystemProvider.instance.open(device.serial, device.displayName) }
            }
            opening = null
            result.onSuccess { onOpenFiles(it.homePath()) }
            result.onFailure { error = it.message ?: "Телефон не открылся" }
        }
    }

    val ready = devices.filter { it.state == AdbDeviceState.READY }

    ModalDialog(onDismissRequest = { if (opening == null) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false), dismissible = opening == null) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            modifier = Modifier.width(600.dp).dialogGlass(RoundedCornerShape(16.dp)).onPreviewKeyEvent { e ->
                // Enter открывает файлы, когда телефон один — самый частый случай
                dialogKeys(e, { ready.singleOrNull()?.let { if (opening == null) openFiles(it) } }, { if (opening == null) onDismiss() })
            }
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(SymbolRef("smartphone"), contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Телефон по adb",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NoctaliaPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    DialogClose(onDismiss)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Файлы открываются в панели — дальше всё как с диском: F5, F6, Delete, правка текста. " +
                        "Видно всё, куда Android пускает без root: общая память, Android/data, /data/local/tmp.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(14.dp))

                when {
                    adbMissing -> HintCard(
                        icon = "error",
                        color = NoctaliaError,
                        title = "adb не установлен",
                        text = "Установите пакет android-tools:  sudo pacman -S android-tools"
                    )
                    firstLoad -> Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        WexSpinner(color = NoctaliaPrimary, modifier = Modifier.size(26.dp))
                    }
                    listError != null && devices.isEmpty() -> HintCard("error", NoctaliaError, "Ошибка adb", listError!!)
                    devices.isEmpty() -> HintCard(
                        icon = "phonelink_off",
                        color = MaterialTheme.colorScheme.outline,
                        title = "Телефон не найден",
                        text = "Подключите телефон кабелем и включите «Отладку по USB» в параметрах разработчика. " +
                            "Список обновляется сам."
                    )
                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        devices.forEach { d ->
                            DevicePickRow(
                                device = d,
                                opening = opening == d.serial,
                                enabled = opening == null,
                                onFiles = { openFiles(d) },
                                onApps = { onOpenApps(d) }
                            )
                        }
                    }
                }

                error?.let {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(it, fontSize = 12.sp, color = NoctaliaDanger.copy(alpha = 0.85f))
                }
            }
        }
    }
}

@Composable
private fun DevicePickRow(
    device: AdbDevice,
    opening: Boolean,
    enabled: Boolean,
    onFiles: () -> Unit,
    onApps: () -> Unit
) {
    val ready = device.state == AdbDeviceState.READY
    val stateColor = when (device.state) {
        AdbDeviceState.READY -> NoctaliaOk
        AdbDeviceState.UNAUTHORIZED -> NoctaliaWarn
        else -> NoctaliaError
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolIcon(
                SymbolRef(
                    when {
                        device.isEmulator -> "developer_mode"
                        device.isWireless -> "mobile_friendly"
                        else -> "smartphone"
                    }
                ),
                contentDescription = null,
                tint = if (ready) NoctaliaPrimary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val details = buildList {
                    add(device.serial)
                    device.androidVersion?.let { add("Android $it" + (device.sdk?.let { s -> " (API $s)" } ?: "")) }
                    if (device.isWireless) add("Wi‑Fi")
                }.joinToString("  ·  ")
                Text(
                    details,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val note = when (device.state) {
                    AdbDeviceState.UNAUTHORIZED -> "Подтвердите «Разрешить отладку по USB» на экране устройства"
                    AdbDeviceState.OFFLINE -> "Устройство не отвечает — переподключите кабель или перезапустите ADB"
                    AdbDeviceState.NO_PERMISSIONS -> "Нет прав на USB-устройство: нужны udev-правила (пакет android-udev)"
                    AdbDeviceState.OTHER -> "Режим «${device.rawState}» — файлы недоступны"
                    AdbDeviceState.READY -> null
                }
                if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = stateColor)
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (ready) {
                if (opening) {
                    WexSpinner(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NoctaliaPrimary)
                    Spacer(modifier = Modifier.width(10.dp))
                }
                OutlinedButton(
                    onClick = onApps,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    SymbolIcon(SymbolRef("apps"), null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Приложения", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(6.dp))
                OutlinedButton(
                    onClick = onFiles,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = if (enabled) 1f else 0.3f)),
                    modifier = Modifier.height(32.dp)
                ) {
                    SymbolIcon(SymbolRef("folder_open"), null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Файлы", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NoctaliaPrimary)
                }
            } else {
                StatusChip(device.state.title, stateColor)
            }
        }
    }
}
