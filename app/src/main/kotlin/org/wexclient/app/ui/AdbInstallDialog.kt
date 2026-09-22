package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.adb.AdbDevice
import org.wexclient.app.adb.AdbDeviceState
import org.wexclient.app.adb.AdbManager
import org.wexclient.app.adb.InstallOptions
import org.wexclient.app.adb.InstallResult
import org.wexclient.app.adb.InstallSuggestion
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaWarn
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaError
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTheme
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private val OkGreen = NoctaliaOk
private val WarnOrange = NoctaliaWarn

/**
 * Установка APK/XAPK на устройство через adb.
 * signatureValid=false — показываем предупреждение и кнопку «Подписать».
 */
@Composable
fun AdbInstallDialog(
    apkPath: Path,
    apkName: String,
    packageName: String?,
    versionLabel: String?,
    signatureValid: Boolean?,
    onRequestSign: (() -> Unit)?,
    /** Итог подписи, запрошенной из этого окна: показывается здесь, а не в главном окне за ним. */
    notice: Pair<Boolean, String>? = null,
    onNoticeShown: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val cancelFlag = remember { AtomicBoolean(false) }
    DialogWindow(
        onCloseRequest = {
            cancelFlag.set(true)
            onDismiss()
        },
        state = rememberDialogState(size = DpSize(780.dp, 720.dp)),
        title = "Установка через ADB — $apkName"
    ) {
        NoctaliaTheme {
            AdbInstallContent(apkPath, apkName, packageName, versionLabel, signatureValid, onRequestSign, cancelFlag, onDismiss, notice, onNoticeShown)
        }
    }
}

@Composable
internal fun AdbInstallContent(
    apkPath: Path,
    apkName: String,
    packageName: String?,
    versionLabel: String?,
    signatureValid: Boolean?,
    onRequestSign: (() -> Unit)?,
    cancelFlag: AtomicBoolean,
    onDismiss: () -> Unit,
    notice: Pair<Boolean, String>? = null,
    onNoticeShown: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        if (n.first) toast.success(n.second) else toast.error(n.second)
        onNoticeShown()
    }

    var adbVersion by remember { mutableStateOf<String?>(null) }
    var adbMissing by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var firstLoad by remember { mutableStateOf(true) }
    var selectedSerial by remember { mutableStateOf<String?>(null) }
    // Удаление с устройства стирает и данные приложения — только после вопроса
    var confirmUninstall by remember { mutableStateOf(false) }
    var installedVersion by remember { mutableStateOf<String?>(null) }

    var reinstall by remember { mutableStateOf(true) }
    var allowDowngrade by remember { mutableStateOf(false) }
    var grantPermissions by remember { mutableStateOf(false) }
    var allowTest by remember { mutableStateOf(true) }
    var launchAfter by remember { mutableStateOf(false) }

    var installing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var result by remember { mutableStateOf<InstallResult?>(null) }
    var showWifi by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    val selected = devices.firstOrNull { it.serial == selectedSerial }

    suspend fun refreshDevices() {
        try {
            val list = withContext(Dispatchers.IO) { AdbManager.listDevices() }
            devices = list
            listError = null
            if (selectedSerial == null || list.none { it.serial == selectedSerial }) {
                selectedSerial = list.firstOrNull { it.state == AdbDeviceState.READY }?.serial ?: list.firstOrNull()?.serial
            }
        } catch (e: Exception) {
            listError = e.message
        } finally {
            firstLoad = false
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            adbMissing = AdbManager.adbPath() == null
            adbVersion = AdbManager.version()
        }
    }

    // Автообновление списка, пока окно открыто
    LaunchedEffect(refreshTick) {
        while (isActive) {
            if (!installing && busy == null && !adbMissing) refreshDevices()
            delay(2500)
        }
    }

    LaunchedEffect(selectedSerial, selected?.state, result) {
        installedVersion = null
        val s = selected ?: return@LaunchedEffect
        if (s.state == AdbDeviceState.READY && !packageName.isNullOrBlank()) {
            installedVersion = withContext(Dispatchers.IO) { AdbManager.installedVersion(s.serial, packageName) }
        }
    }

    fun runAction(label: String, action: () -> String) {
        busy = label
        scope.launch {
            try {
                val msg = withContext(Dispatchers.IO) { action() }
                toast.success(msg.lines().first().take(140))
            } catch (e: Exception) {
                toast.error(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = null
                refreshTick++
            }
        }
    }

    fun startInstall(
        options: InstallOptions = InstallOptions(reinstall, allowDowngrade, grantPermissions, allowTest),
        uninstallFirst: Boolean = false
    ) {
        val device = selected ?: return
        installing = true
        result = null
        log.clear()
        cancelFlag.set(false)
        // Строки приходят из потока чтения adb — в список добавляем на UI-потоке по порядку
        fun addLog(line: String) {
            scope.launch(Dispatchers.Main) { log.add(line) }
        }
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    if (uninstallFirst && !packageName.isNullOrBlank()) {
                        addLog("$ uninstall $packageName")
                        addLog(AdbManager.uninstall(device.serial, packageName))
                    }
                    AdbManager.install(device.serial, apkPath, options, isCancelled = { cancelFlag.get() }) { line ->
                        addLog(line)
                    }.also { res ->
                        if (res.success && launchAfter && !packageName.isNullOrBlank()) {
                            try {
                                addLog(AdbManager.launch(device.serial, packageName))
                            } catch (e: Exception) {
                                addLog("Запуск: ${e.message ?: e.javaClass.simpleName}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    InstallResult(false, null, e.message ?: e.javaClass.simpleName, "")
                }
            }
            result = r
            installing = false
            if (r.success) toast.success("Установлено на ${device.displayName}")
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // --- Заголовок ---
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = RoundedCornerShape(10.dp), color = NoctaliaPrimary.copy(alpha = 0.16f), modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                SymbolIcon(SymbolRef("install_mobile"), null, tint = NoctaliaPrimary, modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Установка через ADB",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = NoctaliaPrimary
                            )
                            Text(
                                listOfNotNull(apkName, versionLabel, packageName).joinToString("  ·  "),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        SymbolIconButton("close", "Закрыть", MaterialTheme.colorScheme.outline, {
                            cancelFlag.set(true)
                            onDismiss()
                        }, size = 34.dp, iconSize = 20.dp)
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)
                ) {
                    // --- Статус adb и действия ---
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(
                            text = when {
                                adbMissing -> "adb не найден"
                                adbVersion != null -> "ADB $adbVersion"
                                else -> "ADB…"
                            },
                            color = if (adbMissing) NoctaliaError else OkGreen
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        ToolButton("refresh", "Обновить", enabled = busy == null && !installing) {
                            scope.launch { refreshDevices() }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        ToolButton("restart_alt", "Перезапустить ADB", enabled = busy == null && !installing && !adbMissing) {
                            runAction("Перезапуск сервера ADB…") { AdbManager.restartServer() }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        ToolButton("wifi", "Wi‑Fi…", enabled = busy == null && !installing && !adbMissing) { showWifi = true }
                    }

                    busy?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NoctaliaPrimary)
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    SectionTitle("Устройства")
                    Spacer(modifier = Modifier.height(6.dp))

                    when {
                        adbMissing -> HintCard(
                            icon = "error",
                            color = NoctaliaError,
                            title = "adb не установлен",
                            text = "Установите пакет android-tools:  sudo pacman -S android-tools"
                        )
                        firstLoad -> Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = NoctaliaPrimary, modifier = Modifier.size(26.dp))
                        }
                        listError != null && devices.isEmpty() -> HintCard("error", NoctaliaError, "Ошибка adb", listError!!)
                        devices.isEmpty() -> HintCard(
                            icon = "phonelink_off",
                            color = MaterialTheme.colorScheme.outline,
                            title = "Устройства не найдены",
                            text = "Подключите телефон кабелем и включите «Отладку по USB» в параметрах разработчика, " +
                                "либо подключитесь по Wi‑Fi. Список обновляется автоматически."
                        )
                        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            devices.forEach { d ->
                                DeviceRow(
                                    device = d,
                                    selected = d.serial == selectedSerial,
                                    installedVersion = if (d.serial == selectedSerial) installedVersion else null,
                                    enabled = !installing,
                                    onSelect = { selectedSerial = d.serial },
                                    onDisconnect = if (d.isWireless && !d.isEmulator) {
                                        { runAction("Отключение ${d.serial}…") { AdbManager.disconnect(d.serial) } }
                                    } else null
                                )
                            }
                        }
                    }

                    if (signatureValid == false) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HintCard(
                            icon = "gpp_bad",
                            color = NoctaliaError,
                            title = "Ошибка проверки подписи",
                            text = "APK не подписан или изменён после подписи — Android не установит его. Сначала подпишите APK.",
                            action = onRequestSign?.let { cb -> "Подписать" to cb }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    SectionTitle("Параметры установки")
                    Spacer(modifier = Modifier.height(4.dp))
                    Row {
                        Column(modifier = Modifier.weight(1f)) {
                            OptionCheck("Переустановить с сохранением данных (-r)", reinstall, !installing) { reinstall = it }
                            OptionCheck("Разрешить понижение версии (-d)", allowDowngrade, !installing) { allowDowngrade = it }
                            OptionCheck("Разрешить тестовые APK (-t)", allowTest, !installing) { allowTest = it }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            OptionCheck("Выдать все разрешения (-g)", grantPermissions, !installing) { grantPermissions = it }
                            OptionCheck("Запустить после установки", launchAfter, !installing && !packageName.isNullOrBlank()) { launchAfter = it }
                        }
                    }

                    if (installing || log.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionTitle("Журнал")
                        Spacer(modifier = Modifier.height(4.dp))
                        if (installing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NoctaliaPrimary)
                        val logState = rememberLazyListState()
                        LaunchedEffect(log.size) { if (log.isNotEmpty()) logState.scrollToItem(log.lastIndex) }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 150.dp)
                        ) {
                            LazyColumn(state = logState, modifier = Modifier.padding(8.dp)) {
                                items(log) { line ->
                                    Text(
                                        line,
                                        fontFamily = AppFonts.JetBrainsMono,
                                        fontSize = 11.sp,
                                        color = if (line.startsWith("$")) NoctaliaSecondary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    result?.let { r ->
                        Spacer(modifier = Modifier.height(12.dp))
                        ResultCard(
                            result = r,
                            onRetryWith = { opts, uninstall ->
                                if (opts.allowDowngrade) allowDowngrade = true
                                if (opts.allowTestOnly) allowTest = true
                                startInstall(opts, uninstall)
                            },
                            baseOptions = InstallOptions(reinstall, allowDowngrade, grantPermissions, allowTest),
                            onSign = onRequestSign,
                            onLaunch = if (r.success && !packageName.isNullOrBlank() && selected?.state == AdbDeviceState.READY) {
                                { runAction("Запуск…") { AdbManager.launch(selected.serial, packageName) } }
                            } else null
                        )
                    }
                }

                // --- Нижняя панель ---
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (installedVersion != null && !packageName.isNullOrBlank() && selected != null && !installing) {
                            TextButton(
                                onClick = { confirmUninstall = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = NoctaliaError),
                                enabled = busy == null
                            ) { Text("Удалить с устройства") }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (installing) {
                            OutlinedButton(onClick = { cancelFlag.set(true) }) { Text("Прервать") }
                        } else {
                            OutlinedButton(onClick = onDismiss) { Text("Закрыть") }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = { startInstall() },
                            enabled = !installing && busy == null && selected?.state == AdbDeviceState.READY,
                            colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary),
                            modifier = Modifier.height(40.dp)
                        ) {
                            if (installing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                SymbolIcon(SymbolRef("install_mobile"), null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when {
                                    installing -> "Установка…"
                                    selected != null -> "Установить на ${selected.displayName}"
                                    else -> "Установить"
                                },
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            ToastHost(toast)
        }
    }

    if (confirmUninstall) {
        val serial = selected?.serial
        val pkg = packageName
        ConfirmDialog(
            title = "Удалить с устройства?",
            message = "$pkg будет удалено с ${selected?.let { it.model ?: it.serial } ?: "устройства"} вместе с данными приложения. Отменить это нельзя.",
            confirmText = "Удалить",
            isDestructive = true,
            onConfirm = {
                confirmUninstall = false
                if (serial != null && !pkg.isNullOrBlank()) {
                    runAction("Удаление $pkg…") { AdbManager.uninstall(serial, pkg) }
                }
            },
            onDismiss = { confirmUninstall = false }
        )
    }

    if (showWifi) {
        WifiConnectDialog(
            onDismiss = { showWifi = false },
            onPair = { host, code ->
                runAction("Сопряжение с $host…") { AdbManager.pair(host, code) }
            },
            onConnect = { host ->
                showWifi = false
                runAction("Подключение к $host…") { AdbManager.connect(host) }
            }
        )
    }
}

@Composable
internal fun DeviceRow(
    device: AdbDevice,
    selected: Boolean,
    installedVersion: String?,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDisconnect: (() -> Unit)?
) {
    val ready = device.state == AdbDeviceState.READY
    val stateColor = when (device.state) {
        AdbDeviceState.READY -> OkGreen
        AdbDeviceState.UNAUTHORIZED -> WarnOrange
        else -> NoctaliaError
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) NoctaliaPrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, if (selected) NoctaliaPrimary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect)
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                enabled = enabled,
                colors = RadioButtonDefaults.colors(selectedColor = NoctaliaPrimary)
            )
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
                    device.abi?.let { add(it) }
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
                    AdbDeviceState.OTHER -> "Режим «${device.rawState}» — установка недоступна"
                    AdbDeviceState.READY -> installedVersion?.let { "Уже установлено: $it" }
                }
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ready) NoctaliaSecondary else stateColor
                    )
                }
            }
            if (onDisconnect != null) {
                SymbolIconButton("link_off", "Отключить", MaterialTheme.colorScheme.outline, onDisconnect, enabled = enabled)
                Spacer(modifier = Modifier.width(4.dp))
            }
            StatusChip(device.state.title, stateColor)
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f))
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun ToolButton(icon: String, text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(32.dp)
    ) {
        SymbolIcon(SymbolRef(icon), null, tint = if (enabled) NoctaliaPrimary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, fontSize = 12.sp)
    }
}

@Composable
private fun OptionCheck(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) }.padding(end = 8.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary)
        )
        Text(text, fontSize = 12.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}

@Composable
internal fun HintCard(
    icon: String,
    color: Color,
    title: String,
    text: String,
    action: Pair<String, () -> Unit>? = null,
    secondaryAction: Pair<String, () -> Unit>? = null
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolIcon(SymbolRef(icon), null, tint = color, modifier = Modifier.size(26.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = color)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            if (secondaryAction != null) {
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = secondaryAction.second, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(secondaryAction.first, fontSize = 12.sp)
                }
            }
            if (action != null) {
                Spacer(modifier = Modifier.width(8.dp))
                // Тоном, как кнопки в других окнах, а не сплошной яркой заливкой
                Button(
                    onClick = action.second,
                    colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.18f), contentColor = color),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.7f)),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) { Text(action.first, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: InstallResult,
    baseOptions: InstallOptions,
    onRetryWith: (InstallOptions, Boolean) -> Unit,
    onSign: (() -> Unit)?,
    onLaunch: (() -> Unit)?
) {
    if (result.success) {
        HintCard(
            icon = "check_circle",
            color = OkGreen,
            title = "Установлено",
            text = result.explanation ?: "Приложение установлено",
            action = onLaunch?.let { "Запустить" to it }
        )
        return
    }
    val action: Pair<String, () -> Unit>? = when (result.suggestion) {
        InstallSuggestion.UNINSTALL_AND_RETRY -> "Удалить и установить" to { onRetryWith(baseOptions, true) }
        InstallSuggestion.ALLOW_DOWNGRADE -> "Повторить с -d" to { onRetryWith(baseOptions.copy(allowDowngrade = true), false) }
        InstallSuggestion.ALLOW_TEST -> "Повторить с -t" to { onRetryWith(baseOptions.copy(allowTestOnly = true), false) }
        InstallSuggestion.SIGN -> onSign?.let { "Подписать" to it }
        null -> null
    }
    HintCard(
        icon = "error",
        color = NoctaliaError,
        title = "Ошибка установки" + (result.code?.let { " · $it" } ?: ""),
        text = result.explanation ?: "adb вернул ошибку",
        action = action,
        secondaryAction = "Ещё раз" to { onRetryWith(baseOptions, false) }
    )
}

@Composable
private fun WifiConnectDialog(
    onDismiss: () -> Unit,
    onPair: (String, String) -> Unit,
    onConnect: (String) -> Unit
) {
    var host by remember { mutableStateOf("") }
    var pairHost by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    ModalDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent, tonalElevation = 6.dp, modifier = Modifier.width(460.dp).dialogGlass(RoundedCornerShape(16.dp))) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(SymbolRef("wifi"), null, tint = NoctaliaPrimary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Отладка по Wi‑Fi", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = NoctaliaPrimary)
                }
                Spacer(modifier = Modifier.height(12.dp))
                SectionTitle("Подключение")
                Spacer(modifier = Modifier.height(4.dp))
                GrailCompactField(host, { host = it }, "192.168.1.20:5555 или адрес из «Беспроводной отладки»", mono = true, height = 38.dp, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(14.dp))
                SectionTitle("Сопряжение (Android 11+, один раз)")
                Spacer(modifier = Modifier.height(2.dp))
                GrailHint("Параметры разработчика → Беспроводная отладка → «Подключить с помощью кода»")
                Spacer(modifier = Modifier.height(6.dp))
                Row {
                    GrailCompactField(pairHost, { pairHost = it }, "IP:порт сопряжения", mono = true, height = 38.dp, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(6.dp))
                    GrailCompactField(code, { code = it.filter(Char::isDigit).take(6) }, "Код", mono = true, height = 38.dp, modifier = Modifier.width(110.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { onPair(pairHost, code) },
                        enabled = pairHost.contains(':') && code.length == 6,
                        modifier = Modifier.height(38.dp)
                    ) { Text("Сопрячь", fontSize = 12.sp) }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConnect(host) },
                        enabled = host.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary)
                    ) { Text("Подключить") }
                }
            }
        }
    }
}
