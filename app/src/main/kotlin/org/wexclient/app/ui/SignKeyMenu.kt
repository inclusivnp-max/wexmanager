package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import org.wexclient.app.ui.WexDropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wexclient.app.apk.ApkDetails
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.icons.SymbolStyle
import org.wexclient.app.signing.KeyInfo
import org.wexclient.app.signing.SignSchemes
import org.wexclient.app.signing.SigningKey
import org.wexclient.app.signing.SigningKeys
import org.wexclient.app.signing.SigningPreferences
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaTertiary

private val SignedGreen = NoctaliaOk

/**
 * Меню кнопки «Подписать»: выбор ключа (встроенные и свои), схемы V1–V4,
 * создание, импорт и экспорт ключей. Выбранный ключ запоминается для быстрой подписи.
 */
@Composable
fun SignKeyMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    /** SHA-256 сертификата действующей подписи APK (AA:BB:…) — отметка «текущая подпись». */
    currentSha256: String?,
    /** Почему подписать нельзя (null — можно). */
    blockedReason: String?,
    /** Что произойдёт при выборе ключа: «поверх файла» или «откроется окно подписи». */
    modeHint: String,
    onSign: (SigningKey) -> Unit,
    onAdvanced: () -> Unit,
    dialogs: KeyDialogsState,
    offset: DpOffset = DpOffset(0.dp, 6.dp)
) {
    val keys = SigningKeys.keys.toList()
    var infos by remember { mutableStateOf<Map<String, KeyInfo>>(emptyMap()) }
    // Сертификаты (PKCS12 читается заметное время) — в фоне, из кэша мгновенно
    LaunchedEffect(expanded, keys) {
        if (expanded) {
            infos = withContext(Dispatchers.IO) { keys.mapNotNull { k -> SigningKeys.info(k)?.let { k.id to it } }.toMap() }
        }
    }
    WexDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        modifier = Modifier.width(420.dp)
    ) {
        SignKeyMenuContent(
            keys = keys,
            infos = infos,
            defaultKeyId = SigningPreferences.defaultKey.id,
            schemes = SigningPreferences.schemes,
            currentSha256 = currentSha256,
            blockedReason = blockedReason,
            modeHint = modeHint,
            onSchemes = { SigningPreferences.updateSchemes(it) },
            onSign = { key ->
                onDismiss()
                SigningPreferences.updateDefaultKey(key.id)
                onSign(key)
            },
            onMakeDefault = { SigningPreferences.updateDefaultKey(it.id) },
            onAdvanced = {
                onDismiss()
                onAdvanced()
            },
            dialogs = dialogs,
            onDismiss = onDismiss
        )
    }
}

/**
 * Кнопка «Подписать» окна APK: показывает ключ быстрой подписи и открывает [SignKeyMenu].
 * [onSignWithKey] == null — APK не на диске: выбранный ключ запоминается и открывается окно подписи.
 */
@Composable
fun SignKeyButton(
    signing: Boolean,
    details: ApkDetails?,
    isBundle: Boolean,
    onSignWithKey: ((SigningKey) -> Unit)?,
    onOpenSignDialog: () -> Unit,
    dialogs: KeyDialogsState,
    initiallyExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Box {
        Button(
            onClick = { if (!signing) expanded = true },
            enabled = !signing,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NoctaliaTertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary
            ),
            contentPadding = PaddingValues(start = 14.dp, end = 8.dp),
            modifier = Modifier.height(42.dp)
        ) {
            if (signing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onTertiary)
            } else {
                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(if (signing) "Подпись…" else "Подписать", fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp)
                Text(
                    SigningPreferences.defaultKey.title,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = LocalContentColor.current.copy(alpha = 0.78f),
                    modifier = Modifier.widthIn(max = 150.dp)
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Выбор ключа", modifier = Modifier.size(20.dp))
        }
        SignKeyMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            // «Текущая подпись» — только у подписи, прошедшей проверку
            currentSha256 = details?.takeIf { it.signatureProblem == null }?.certSha256,
            blockedReason = if (isBundle) "Набор split-APK: подпишите APK внутри архива" else null,
            modeHint = when {
                isBundle -> "Только управление ключами"
                onSignWithKey != null -> "Поверх исходного файла · " + SigningPreferences.schemes.label
                else -> "APK в архиве — откроется окно подписи (копия)"
            },
            onSign = { key -> if (onSignWithKey != null) onSignWithKey(key) else onOpenSignDialog() },
            onAdvanced = onOpenSignDialog,
            dialogs = dialogs
        )
    }
}

@Composable
internal fun SignKeyMenuContent(
    keys: List<SigningKey>,
    infos: Map<String, KeyInfo>,
    defaultKeyId: String,
    schemes: SignSchemes,
    currentSha256: String?,
    blockedReason: String?,
    modeHint: String,
    onSchemes: (SignSchemes) -> Boolean,
    onSign: (SigningKey) -> Unit,
    onMakeDefault: (SigningKey) -> Unit,
    onAdvanced: () -> Unit,
    dialogs: KeyDialogsState,
    onDismiss: () -> Unit
) {
    var schemeHint by remember { mutableStateOf<String?>(null) }
    fun toggle(next: SignSchemes) {
        schemeHint = when {
            onSchemes(next) -> null
            next.v4 && !next.v2 && !next.v3 -> "V4 работает только вместе с V2 или V3"
            else -> "Нужна хотя бы одна из схем V1–V3"
        }
    }
    val dialog: (KeyDialogsState.() -> Unit) -> Unit = { open ->
        onDismiss()
        dialogs.open()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Подписать ключом", fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, color = NoctaliaPrimary)
                Text(
                    modeHint,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SchemeToggle("V1", schemes.v1) { toggle(schemes.copy(v1 = !schemes.v1)) }
                SchemeToggle("V2", schemes.v2) { toggle(schemes.copy(v2 = !schemes.v2)) }
                SchemeToggle("V3", schemes.v3) { toggle(schemes.copy(v3 = !schemes.v3)) }
                SchemeToggle("V4", schemes.v4) { toggle(schemes.copy(v4 = !schemes.v4)) }
            }
        }
        schemeHint?.let {
            Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 14.dp, end = 12.dp, bottom = 4.dp))
        }
        blockedReason?.let {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SymbolIcon(SymbolRef("info"), null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            keys.forEach { key ->
                val info = infos[key.id]
                KeyMenuRow(
                    key = key,
                    info = info,
                    isDefault = key.id == defaultKeyId,
                    isCurrent = currentSha256 != null && info?.sha256.equals(currentSha256, ignoreCase = true),
                    canSign = blockedReason == null,
                    onSign = { onSign(key) },
                    onMakeDefault = { onMakeDefault(key) },
                    onInfo = { dialog { infoKey = key } },
                    onExport = { dialog { exportKey = key } },
                    onRename = { dialog { renameKey = key } },
                    onDelete = { dialog { deleteKey = key } }
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            MenuEntry("tune", "Подписать с параметрами…", trailing = "схемы · копия") { onAdvanced() }
            MenuEntry("add", "Создать ключ…") { dialog { showCreate = true } }
            MenuEntry("upload_file", "Импорт ключа…", trailing = "JKS · P12 · PK8 · PEM") { dialog { showImport = true } }
            MenuEntry("download", "Экспорт ключа…", trailing = "JKS · P12 · PK8 · PEM") {
                val key = keys.firstOrNull { it.id == defaultKeyId } ?: SigningKeys.testKey
                dialog { exportKey = key }
            }
        }
    }
}

@Composable
private fun KeyMenuRow(
    key: SigningKey,
    info: KeyInfo?,
    isDefault: Boolean,
    isCurrent: Boolean,
    canSign: Boolean,
    onSign: () -> Unit,
    onMakeDefault: () -> Unit,
    onInfo: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var more by remember { mutableStateOf(false) }
    val accent = NoctaliaPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isDefault) accent.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = canSign, onClick = onSign)
            .padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (isDefault) accent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            SymbolIcon(
                SymbolRef(if (key.builtIn) "shield" else "key", if (isDefault) SymbolStyle.FILLED else SymbolStyle.OUTLINED),
                null,
                tint = if (isDefault) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    key.title,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = if (isDefault) FontWeight.Bold else FontWeight.SemiBold,
                    color = when {
                        !canSign -> MaterialTheme.colorScheme.outline
                        isDefault -> accent
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isCurrent) {
                    Spacer(modifier = Modifier.width(6.dp))
                    KeyBadge("текущая подпись", SignedGreen)
                }
            }
            Text(
                listOfNotNull(key.alias, info?.shortLabel ?: "…").joinToString(" · "),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontFamily = AppFonts.JetBrainsMono,
                color = if (canSign) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isDefault) {
            Spacer(modifier = Modifier.width(6.dp))
            SymbolIcon(SymbolRef("check", codepoint = 0xe5ca), "Выбран для быстрой подписи", tint = accent, modifier = Modifier.size(18.dp))
        }
        Box {
            SymbolIconButton("more_vert", "Действия с ключом", MaterialTheme.colorScheme.onSurfaceVariant, { more = true }, size = 30.dp, iconSize = 18.dp)
            WexDropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                MenuEntry("key", "Подписать этим ключом", enabled = canSign) {
                    more = false
                    onSign()
                }
                MenuEntry("star", "Ключ по умолчанию", enabled = !isDefault, checked = if (isDefault) true else null) {
                    more = false
                    onMakeDefault()
                }
                MenuEntry("info", "Сведения и сертификат…") {
                    more = false
                    onInfo()
                }
                MenuEntry("download", "Экспорт…", trailing = "JKS · P12 · PK8") {
                    more = false
                    onExport()
                }
                if (!key.builtIn) {
                    MenuEntry("edit", "Переименовать…") {
                        more = false
                        onRename()
                    }
                    HorizontalDivider()
                    MenuEntry("delete", "Удалить ключ…", destructive = true) {
                        more = false
                        onDelete()
                    }
                }
            }
        }
    }
}

@Composable
private fun SchemeToggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (checked) NoctaliaPrimary.copy(alpha = 0.2f) else Color.Transparent,
        border = BorderStroke(1.dp, if (checked) NoctaliaPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle)
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AppFonts.JetBrainsMono,
            color = if (checked) NoctaliaPrimary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
internal fun KeyBadge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(5.dp), color = color.copy(alpha = 0.15f), border = BorderStroke(1.dp, color.copy(alpha = 0.5f))) {
        Text(text, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}
