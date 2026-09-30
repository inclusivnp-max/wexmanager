package org.wexclient.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.adb.AdbManager
import org.wexclient.app.adb.AdbPackages
import org.wexclient.app.adb.DevicePackage
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaError
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.theme.NoctaliaTheme
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Приложения на телефоне: поиск по имени пакета, выгрузка APK на компьютер — в [saveDir], обычно
 * папку соседней панели, — запуск, остановка, очистка данных, удаление. Необратимое — после вопроса.
 */
@Composable
fun AdbAppsDialog(
    serial: String,
    deviceName: String,
    saveDir: Path,
    /** APK сохранён: показать его в панели. */
    onSaved: (Path) -> Unit,
    /** Открыть папку приложения в общей памяти телефона (Android/data/…) в панели. */
    onOpenDataFolder: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFlag = remember { AtomicBoolean(false) }
    DialogWindow(
        onCloseRequest = {
            cancelFlag.set(true)
            onDismiss()
        },
        state = rememberDialogState(size = DpSize(820.dp, 720.dp)),
        title = "Приложения — $deviceName"
    ) {
        NoctaliaTheme {
            AdbAppsContent(serial, deviceName, saveDir, onSaved, onOpenDataFolder, cancelFlag, onDismiss)
        }
    }
}

/** Что спросить перед необратимым действием. */
private enum class AppConfirm { CLEAR, UNINSTALL }

@Composable
private fun AdbAppsContent(
    serial: String,
    deviceName: String,
    saveDir: Path,
    onSaved: (Path) -> Unit,
    onOpenDataFolder: (String) -> Unit,
    cancelFlag: AtomicBoolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()

    var packages by remember { mutableStateOf<List<DevicePackage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    /** Идёт выгрузка APK: её можно прервать. */
    var saving by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<AppConfirm, DevicePackage>?>(null) }

    LaunchedEffect(refreshTick) {
        loading = true
        val result = withContext(Dispatchers.IO) { runCatching { AdbPackages.list(serial) } }
        result.onSuccess {
            packages = it
            loadError = null
        }
        result.onFailure { loadError = it.message ?: it.javaClass.simpleName }
        loading = false
    }

    fun runAction(label: String, refreshAfter: Boolean = false, action: () -> String) {
        busy = label
        scope.launch {
            try {
                val msg = withContext(Dispatchers.IO) { action() }
                toast.success(msg.lines().first().take(140))
            } catch (e: Exception) {
                toast.error(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = null
                if (refreshAfter) refreshTick++
            }
        }
    }

    fun save(pkg: DevicePackage) {
        busy = "Сохранение ${pkg.name}…"
        saving = true
        cancelFlag.set(false)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AdbPackages.saveApk(serial, pkg, saveDir, isCancelled = { cancelFlag.get() }) { step ->
                        scope.launch(Dispatchers.Main) { if (saving) busy = step }
                    }
                }
            }
            saving = false
            busy = null
            result.onSuccess { saved ->
                if (saved == null) {
                    toast.info("Сохранение прервано")
                } else {
                    toast.success("Сохранено: ${saved.fileName}")
                    onSaved(saved)
                }
            }
            result.onFailure { toast.error(it.message ?: it.javaClass.simpleName) }
        }
    }

    val needle = query.trim()
    val shown = packages.filter { (showSystem || !it.system) && (needle.isEmpty() || it.name.contains(needle, ignoreCase = true)) }
    val userCount = packages.count { !it.system }

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
                                SymbolIcon(SymbolRef("apps"), null, tint = NoctaliaPrimary, modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Приложения на телефоне",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = NoctaliaPrimary
                            )
                            Text(
                                if (packages.isEmpty()) deviceName
                                else "$deviceName  ·  своих $userCount, всего ${packages.size}",
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

                // --- Поиск и отбор ---
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GrailCompactField(
                        value = query,
                        onChange = { query = it },
                        placeholder = "Поиск по имени пакета",
                        mono = true,
                        modifier = Modifier.weight(1f),
                        leadingIcon = {
                            SymbolIcon(SymbolRef("search"), null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                        }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showSystem = !showSystem }.padding(end = 8.dp)
                    ) {
                        Checkbox(
                            checked = showSystem,
                            onCheckedChange = { showSystem = it },
                            colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary)
                        )
                        Text("Системные", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    ToolButton("refresh", "Обновить", enabled = !loading && busy == null) { refreshTick++ }
                }
                Text(
                    "APK сохраняются в $saveDir",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                )

                busy?.let {
                    WexLinearSpinner(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = NoctaliaPrimary)
                    Row(modifier = Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                        if (saving) {
                            TextButton(onClick = { cancelFlag.set(true) }) { Text("Прервать", fontSize = 12.sp) }
                        }
                    }
                }

                // --- Список ---
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    when {
                        loading && packages.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            WexSpinner(color = NoctaliaPrimary, modifier = Modifier.size(26.dp))
                        }
                        loadError != null && packages.isEmpty() -> Box(modifier = Modifier.padding(6.dp)) {
                            HintCard("error", NoctaliaError, "Список приложений не получен", loadError!!, action = "Повторить" to { refreshTick += 1 })
                        }
                        shown.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                when {
                                    needle.isNotEmpty() -> "Ничего не найдено"
                                    else -> "Своих приложений нет — включите «Системные»"
                                },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(shown, key = { it.name }) { pkg ->
                                AppRow(
                                    pkg = pkg,
                                    enabled = busy == null,
                                    onSave = { save(pkg) },
                                    onLaunch = { runAction("Запуск ${pkg.name}…") { AdbManager.launch(serial, pkg.name) } },
                                    onStop = { runAction("Остановка ${pkg.name}…") { AdbPackages.forceStop(serial, pkg.name) } },
                                    onDataFolder = { onOpenDataFolder("/sdcard/Android/data/${pkg.name}") },
                                    onClear = { confirm = AppConfirm.CLEAR to pkg },
                                    // Встроенное в прошивку adb не удаляет — только сторонние приложения
                                    onUninstall = if (pkg.system) null else ({ confirm = AppConfirm.UNINSTALL to pkg })
                                )
                            }
                        }
                    }
                }
            }
            ToastHost(toast)
        }
    }

    confirm?.let { (kind, pkg) ->
        when (kind) {
            AppConfirm.CLEAR -> ConfirmDialog(
                title = "Очистить данные?",
                message = "У ${pkg.name} сотрутся все данные: настройки, вход в аккаунт, кэш — как сразу после установки. Отменить это нельзя.",
                confirmText = "Очистить",
                isDestructive = true,
                focusCancel = true,
                onConfirm = {
                    confirm = null
                    runAction("Очистка данных ${pkg.name}…") { AdbPackages.clearData(serial, pkg.name) }
                },
                onDismiss = { confirm = null }
            )
            AppConfirm.UNINSTALL -> ConfirmDialog(
                title = "Удалить с телефона?",
                message = "${pkg.name} будет удалено с $deviceName вместе с данными приложения. Отменить это нельзя.",
                confirmText = "Удалить",
                isDestructive = true,
                focusCancel = true,
                onConfirm = {
                    confirm = null
                    runAction("Удаление ${pkg.name}…", refreshAfter = true) { AdbManager.uninstall(serial, pkg.name) }
                },
                onDismiss = { confirm = null }
            )
        }
    }
}

@Composable
private fun AppRow(
    pkg: DevicePackage,
    enabled: Boolean,
    onSave: () -> Unit,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onDataFolder: () -> Unit,
    onClear: () -> Unit,
    onUninstall: (() -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolIcon(
                SymbolRef("android"),
                contentDescription = null,
                tint = if (pkg.system) MaterialTheme.colorScheme.outline else NoctaliaPrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pkg.name,
                    fontSize = 13.sp,
                    fontFamily = AppFonts.JetBrainsMono,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val details = listOfNotNull(pkg.versionLabel.ifEmpty { null }, if (pkg.system) "системное" else null).joinToString("  ·  ")
                if (details.isNotEmpty()) {
                    Text(details, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            AppAction("download", "Сохранить APK на компьютер", NoctaliaPrimary, enabled, onSave)
            AppAction("play_arrow", "Запустить", NoctaliaSecondary, enabled, onLaunch)
            AppAction("stop_circle", "Остановить", NoctaliaSecondary, enabled, onStop)
            AppAction("folder_open", "Открыть папку Android/data в панели", NoctaliaSecondary, enabled, onDataFolder)
            AppAction("cleaning_services", "Очистить данные…", NoctaliaError, enabled, onClear)
            if (onUninstall != null) {
                AppAction("delete", "Удалить с телефона…", NoctaliaError, enabled, onUninstall)
            } else {
                // Место под кнопку — чтобы значки в строках стояли столбцами
                Spacer(modifier = Modifier.width(30.dp))
            }
        }
    }
}

/** Кнопка-значок с подсказкой: без неё значки строки не понять. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppAction(icon: String, hint: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp) {
                Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        },
        delayMillis = 450
    ) {
        SymbolIconButton(icon, hint, tint, onClick, size = 30.dp, iconSize = 18.dp, enabled = enabled)
    }
}
