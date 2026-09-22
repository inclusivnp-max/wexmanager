package org.wexclient.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import org.wexclient.app.ui.WexDropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.apk.ApkAnalyzer
import org.wexclient.app.apk.ApkDetails
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.icons.SymbolStyle
import org.wexclient.app.signing.ApkSignerEngine
import org.wexclient.app.signing.KeyInfo
import org.wexclient.app.signing.SignResult
import org.wexclient.app.signing.SignSchemes
import org.wexclient.app.signing.SigningKey
import org.wexclient.app.signing.SigningKeys
import org.wexclient.app.signing.SigningPreferences
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaSecondary
import org.wexclient.app.vfs.VfsEntry
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Подпись APK как в MT Manager: список ключей, схемы V1–V4, замена исходника или копия. */
@Composable
fun SignApkDialog(
    apkEntry: VfsEntry,
    targetDirectory: String,
    onDismiss: () -> Unit,
    onSignCompleted: (SignResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    val onDisk = apkEntry.path.fileSystem === FileSystems.getDefault()
    // Набор split-APK: подписываются все части одним ключом
    val isBundle = remember(apkEntry.name) { org.wexclient.app.apk.SplitBundle.isBundle(apkEntry.name) }

    var selectedKeyId by remember { mutableStateOf(SigningPreferences.defaultKey.id) }
    var schemes by remember { mutableStateOf(SigningPreferences.schemes) }
    var replaceOriginal by remember { mutableStateOf(SigningPreferences.replaceOriginal && onDisk) }
    var outputName by remember {
        mutableStateOf(
            if (isBundle) apkEntry.name.substringBeforeLast('.') + "_signed." + apkEntry.name.substringAfterLast('.')
            else apkEntry.name.removeSuffix(".apk") + "_signed.apk"
        )
    }
    var rememberChoice by remember { mutableStateOf(true) }

    var current by remember { mutableStateOf<ApkDetails?>(null) }
    var infos by remember { mutableStateOf<Map<String, KeyInfo>>(emptyMap()) }
    var isSigning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val dialogs = rememberKeyDialogsState()

    val keys = SigningKeys.keys.toList()
    // Сведения о ключах (сертификаты, отпечатки) читаем в фоне
    LaunchedEffect(keys) {
        infos = withContext(Dispatchers.IO) {
            keys.mapNotNull { k -> SigningKeys.info(k)?.let { k.id to it } }.toMap()
        }
    }
    LaunchedEffect(apkEntry.path) {
        current = withContext(Dispatchers.IO) {
            runCatching {
                if (isBundle && onDisk) org.wexclient.app.apk.SplitBundle.baseDetails(apkEntry.path)
                else ApkAnalyzer.analyzePath(apkEntry.path)
            }.getOrNull()
        }
    }

    val selectedKey = SigningKeys.byId(selectedKeyId) ?: SigningKeys.testKey
    val outputFile = File(targetDirectory, outputName)

    fun startSign() {
        if (isSigning) return
        isSigning = true
        error = null
        val key = selectedKey
        // У частей набора V4 не бывает: файл .idsig негде положить
        val chosen = if (isBundle) schemes.copy(v4 = false) else schemes
        val replace = replaceOriginal
        // Запоминаем выбор как есть: V4, отключённый только для набора, обычным APK остаётся
        if (rememberChoice) SigningPreferences.updateDefaults(key.id, schemes, replace)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (isBundle) {
                        val source = if (onDisk) apkEntry.path else {
                            val temp = org.wexclient.app.util.ProcessScratch.work.newFile("bundle-", "." + apkEntry.name.substringAfterLast('.'))
                            Files.copy(apkEntry.path, temp, StandardCopyOption.REPLACE_EXISTING)
                            temp
                        }
                        try {
                            org.wexclient.app.apk.SplitBundle.sign(source, if (replace) source else outputFile.toPath(), key, chosen)
                        } finally {
                            if (!onDisk) Files.deleteIfExists(source)
                        }
                    } else if (replace) {
                        ApkSignerEngine.signInPlace(apkEntry.path.toFile(), key, chosen)
                    } else if (onDisk) {
                        ApkSignerEngine.sign(apkEntry.path.toFile(), outputFile, key, chosen)
                    } else {
                        // APK внутри архива: подписываем временную копию
                        val temp = org.wexclient.app.util.ProcessScratch.work.newFile("sign-", ".apk").toFile()
                        try {
                            Files.copy(apkEntry.path, temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            ApkSignerEngine.sign(temp, outputFile, key, chosen)
                        } finally {
                            temp.delete()
                        }
                    }
                }
            }
            isSigning = false
            result.onSuccess(onSignCompleted).onFailure { e ->
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    val canSign = !isSigning && schemes.isValid && (replaceOriginal || outputName.isNotBlank())

    ModalDialog(onDismissRequest = { if (!isSigning) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false), dismissible = !isSigning) {
        // Клавиши окну: Enter подписывает, Esc закрывает — без щелчка по окну
        val keysFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { org.wexclient.app.editor.requestFocusSafely(keysFocus) }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 8.dp,
            modifier = Modifier.width(720.dp).dialogGlass(RoundedCornerShape(16.dp))
                .onPreviewKeyEvent { dialogKeys(it, { if (canSign) startSign() }, { if (!isSigning) onDismiss() }) }
                .focusRequester(keysFocus)
                .focusable()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // --- Заголовок и текущая подпись ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(10.dp), color = NoctaliaPrimary.copy(alpha = 0.16f), modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            SymbolIcon(SymbolRef("key"), null, tint = NoctaliaPrimary, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (isBundle) "Подпись набора APK" else "Подпись APK",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = NoctaliaPrimary
                        )
                        Text(
                            apkEntry.name,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    SymbolIconButton("close", "Закрыть", MaterialTheme.colorScheme.outline, onDismiss, size = 34.dp, iconSize = 20.dp, enabled = !isSigning)
                }

                Spacer(modifier = Modifier.height(10.dp))
                CurrentSignature(current, keys.firstOrNull { k -> infos[k.id]?.sha256.equals(current?.certSha256, true) })

                // --- Ключи ---
                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Ключ подписи")
                    Spacer(modifier = Modifier.weight(1f))
                    SmallAction("add", "Создать") { dialogs.showCreate = true }
                    Spacer(modifier = Modifier.width(6.dp))
                    SmallAction("upload_file", "Импорт…") { dialogs.showImport = true }
                    Spacer(modifier = Modifier.width(6.dp))
                    SmallAction("download", "Экспорт…") { dialogs.exportKey = selectedKey }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyColumn(modifier = Modifier.heightIn(max = 250.dp).padding(4.dp)) {
                        items(keys, key = { it.id }) { key ->
                            KeyRow(
                                key = key,
                                info = infos[key.id],
                                selected = key.id == selectedKey.id,
                                isDefault = key.id == SigningPreferences.defaultKey.id,
                                signedByThis = current?.certSha256?.let { sha -> infos[key.id]?.sha256.equals(sha, true) } == true,
                                onSelect = { selectedKeyId = key.id },
                                onMakeDefault = { SigningPreferences.updateDefaultKey(key.id) },
                                onInfo = { dialogs.infoKey = key },
                                onExport = { dialogs.exportKey = key },
                                onRename = { dialogs.renameKey = key },
                                onDelete = { dialogs.deleteKey = key }
                            )
                        }
                    }
                }

                // --- Схемы ---
                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Схемы подписи")
                    Spacer(modifier = Modifier.width(14.dp))
                    SchemeChip("V1", schemes.v1) { schemes = schemes.copy(v1 = it) }
                    Spacer(modifier = Modifier.width(6.dp))
                    SchemeChip("V2", schemes.v2) { schemes = schemes.copy(v2 = it) }
                    Spacer(modifier = Modifier.width(6.dp))
                    SchemeChip("V3", schemes.v3) { schemes = schemes.copy(v3 = it) }
                    if (!isBundle) {
                        Spacer(modifier = Modifier.width(6.dp))
                        SchemeChip("V4", schemes.v4) { schemes = schemes.copy(v4 = it) }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        isBundle -> "Все части набора подписываются одним ключом — иначе Android не поставит его целиком"
                        !schemes.isValid && schemes.v4 -> "V4 работает только вместе с V2 или V3"
                        !schemes.isValid -> "Выберите хотя бы одну схему"
                        else -> "V1 — Android 1–6 · V2 — 7+ · V3 — 9+ (ротация ключа) · V4 — ADB-установка частями (файл .idsig)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (schemes.isValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )

                // --- Куда ---
                Spacer(modifier = Modifier.height(14.dp))
                SectionTitle("Результат")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(enabled = onDisk && !isSigning) { replaceOriginal = true }
                ) {
                    RadioButton(
                        selected = replaceOriginal,
                        onClick = { replaceOriginal = true },
                        enabled = onDisk && !isSigning,
                        colors = RadioButtonDefaults.colors(selectedColor = NoctaliaPrimary)
                    )
                    Text(
                        if (onDisk) "Заменить исходный файл" else "Заменить исходный файл (недоступно: APK внутри архива)",
                        fontSize = 13.sp,
                        color = if (onDisk) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = !replaceOriginal,
                        onClick = { replaceOriginal = false },
                        enabled = !isSigning,
                        colors = RadioButtonDefaults.colors(selectedColor = NoctaliaPrimary)
                    )
                    Text("Сохранить копию:", fontSize = 13.sp, modifier = Modifier.clickable { replaceOriginal = false })
                    Spacer(modifier = Modifier.width(8.dp))
                    GrailCompactField(
                        value = outputName,
                        onChange = { outputName = it },
                        placeholder = "app_signed.apk",
                        mono = true,
                        height = 36.dp,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!replaceOriginal) {
                    Text(
                        text = "→ ${outputFile.path}" + if (outputFile.exists()) "  (файл будет перезаписан)" else "",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                        color = if (outputFile.exists()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 48.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp).clickable { rememberChoice = !rememberChoice }
                ) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it },
                        colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary)
                    )
                    Text("Запомнить для быстрой подписи (кнопка «Подписать» и автоподпись)", fontSize = 12.sp)
                }

                error?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onDismiss, enabled = !isSigning) { Text("Отмена") }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = { startSign() },
                        enabled = canSign,
                        colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary),
                        modifier = Modifier.height(42.dp)
                    ) {
                        if (isSigning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            SymbolIcon(SymbolRef("key"), null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (isSigning) "Подпись…" else "Подписать · ${selectedKey.title} · ${schemes.label}",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    KeyDialogs(
        state = dialogs,
        exportDir = if (onDisk) apkEntry.path.toAbsolutePath().parent?.toFile() else null,
        onAdded = { selectedKeyId = it.id },
        onDeleted = { if (selectedKeyId == it.id) selectedKeyId = SigningKeys.TESTKEY_ID }
    )
}

@Composable
private fun CurrentSignature(details: ApkDetails?, signedBy: SigningKey?) {
    val (icon, color, text) = when {
        details == null -> Triple("hourglass_empty", MaterialTheme.colorScheme.outline, "Проверка текущей подписи…")
        details.signatureProblem == null -> {
            val by = signedBy?.let { "ключ «${it.title}»" }
                ?: details.certIssuer.removePrefix("CN=").let { "издатель $it" }
            Triple("verified_user", NoctaliaOk, "Сейчас подписан: ${details.signatureScheme} · $by")
        }
        else -> Triple("gpp_bad", MaterialTheme.colorScheme.error, "Сейчас: ${details.signatureProblem}")
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolIcon(SymbolRef(icon), null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun KeyRow(
    key: SigningKey,
    info: KeyInfo?,
    selected: Boolean,
    isDefault: Boolean,
    signedByThis: Boolean,
    onSelect: () -> Unit,
    onMakeDefault: () -> Unit,
    onInfo: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) NoctaliaPrimary.copy(alpha = 0.14f) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, NoctaliaPrimary.copy(alpha = 0.55f)) else null,
        modifier = Modifier.fillMaxWidth().padding(2.dp).clickable(onClick = onSelect)
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect, colors = RadioButtonDefaults.colors(selectedColor = NoctaliaPrimary))
            SymbolIcon(
                SymbolRef(
                    when {
                        key.builtIn -> "shield"
                        else -> "key"
                    },
                    if (selected) SymbolStyle.FILLED else SymbolStyle.OUTLINED
                ),
                null,
                tint = if (selected) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(key.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isDefault) {
                        Spacer(modifier = Modifier.width(6.dp))
                        KeyBadge("по умолчанию", NoctaliaPrimary)
                    }
                    if (signedByThis) {
                        Spacer(modifier = Modifier.width(6.dp))
                        KeyBadge("подписан этим", NoctaliaOk)
                    }
                }
                val details = buildList {
                    key.alias?.let { add(it) }
                    info?.let { add(it.shortLabel) }
                    info?.let { add(it.sha256.take(14) + "…") }
                    if (info == null) add("…")
                }.joinToString("  ·  ")
                Text(
                    details,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                SymbolIconButton("more_vert", "Действия", MaterialTheme.colorScheme.onSurfaceVariant, { menu = true }, size = 30.dp, iconSize = 18.dp)
                WexDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuEntry("star", "Ключ по умолчанию", enabled = !isDefault, checked = if (isDefault) true else null) {
                        menu = false
                        onMakeDefault()
                    }
                    MenuEntry("info", "Сведения и сертификат…") {
                        menu = false
                        onInfo()
                    }
                    MenuEntry("download", "Экспорт…", trailing = "JKS · P12 · PK8") {
                        menu = false
                        onExport()
                    }
                    if (!key.builtIn) {
                        MenuEntry("edit", "Переименовать…") {
                            menu = false
                            onRename()
                        }
                        HorizontalDivider()
                        MenuEntry("delete", "Удалить ключ…", destructive = true) {
                            menu = false
                            onDelete()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SchemeChip(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (checked) NoctaliaPrimary.copy(alpha = 0.22f) else Color.Transparent,
        border = BorderStroke(1.dp, if (checked) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clickable { onChange(!checked) }
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolIcon(
                SymbolRef(if (checked) "check_box" else "check_box_outline_blank"),
                null,
                tint = if (checked) NoctaliaPrimary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = AppFonts.JetBrainsMono, color = if (checked) NoctaliaPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SmallAction(icon: String, text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(30.dp)
    ) {
        SymbolIcon(SymbolRef(icon), null, tint = NoctaliaSecondary, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text, fontSize = 12.sp)
    }
}
