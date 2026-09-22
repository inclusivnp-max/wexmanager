package org.wexclient.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.signing.SigningPreferences
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaError
import org.wexclient.app.theme.NoctaliaPrimary
import org.wexclient.app.theme.NoctaliaTertiary
import java.io.File

/** Как в MT Manager: после правок внутри APK — предложить подпись при выходе из него. */
@Composable
fun ModifiedApkDialog(
    apks: List<String>,
    onSign: () -> Unit,
    onSignAs: (() -> Unit)?,
    onSkip: () -> Unit,
    /** Esc и щелчок мимо окна — «спросить позже»: APK остаётся в списке неподписанных до выхода. */
    onLater: () -> Unit = onSkip
) {
    ModalDialog(onDismissRequest = onLater, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent, tonalElevation = 6.dp, modifier = Modifier.width(480.dp).dialogGlass(RoundedCornerShape(16.dp))) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(SymbolRef("verified_user"), null, tint = NoctaliaTertiary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        if (apks.size == 1) "APK изменён" else "Изменено APK: ${apks.size}",
                        style = MaterialTheme.typography.titleLarge,
                        color = NoctaliaTertiary
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Файлы внутри были изменены, поэтому подпись больше не действительна — " +
                        "без новой подписи Android не установит приложение. Подписать сейчас " +
                        "(ключ «${SigningPreferences.defaultKey.title}», ${SigningPreferences.schemes.label})?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        apks.take(6).forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                SymbolIcon(SymbolRef("android"), null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(File(p).name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    File(p).parent ?: "",
                                    fontFamily = AppFonts.JetBrainsMono,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (apks.size > 6) Text("… и ещё ${apks.size - 6}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { SigningPreferences.updateAutoSignModified(!SigningPreferences.autoSignModified) }
                ) {
                    Checkbox(
                        checked = SigningPreferences.autoSignModified,
                        onCheckedChange = { SigningPreferences.updateAutoSignModified(it) },
                        colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary)
                    )
                    Text("Больше не спрашивать — подписывать автоматически", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSkip) { Text("Не подписывать") }
                    if (onSignAs != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedButton(onClick = onSignAs) { Text("С параметрами…") }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onSign, colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary)) {
                        SymbolIcon(SymbolRef("key"), null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Подписать")
                    }
                }
            }
        }
    }
}

/** Выход из программы при несохранённых правках и неподписанных APK. */
@Composable
fun ExitConfirmDialog(
    unsavedEditors: List<String>,
    unsignedApks: List<String>,
    onSignAndExit: (() -> Unit)?,
    onExit: () -> Unit,
    onCancel: () -> Unit,
    /** Идущая операция с файлами (копирование, распаковка…) — выход её прервёт */
    runningOperation: String? = null,
    /** Окно открыто из-за операции: кнопка «после завершения» остаётся, даже если та уже закончилась */
    onExitWhenDone: (() -> Unit)? = null
) {
    ModalDialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent, tonalElevation = 6.dp, modifier = Modifier.width(560.dp).dialogGlass(RoundedCornerShape(16.dp))) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(SymbolRef("warning"), null, tint = NoctaliaError, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Выйти из WEX Desktop?", style = MaterialTheme.typography.titleLarge, color = NoctaliaError)
                }
                if (runningOperation == null && onExitWhenDone != null) {
                    // Окно открыли из-за операции, а она уже закончилась
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Операция завершена — можно выходить.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (unsavedEditors.isNotEmpty()) {
                    Text("Несохранённые изменения будут потеряны:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    unsavedEditors.take(6).forEach { Text("•  $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (unsignedApks.isNotEmpty()) {
                    Text("Изменённые и не подписанные APK:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    unsignedApks.take(6).forEach { Text("•  ${File(it).name}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (runningOperation != null) {
                    Text("Ещё идёт: ${runningOperation.trimEnd('.', '…')}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        "Выход прервёт её — копируемые файлы могут остаться недописанными.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Отмена") }
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onExit,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NoctaliaError),
                        border = BorderStroke(1.dp, NoctaliaError.copy(alpha = 0.6f))
                    ) { Text(if (unsignedApks.isNotEmpty()) "Выйти без подписи" else if (runningOperation != null) "Прервать и выйти" else "Выйти") }
                    if (onExitWhenDone != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onExitWhenDone, colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary)) {
                            Text("Выйти после завершения", maxLines = 1, softWrap = false)
                        }
                    }
                    if (onSignAndExit != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onSignAndExit, colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary)) {
                            Text("Подписать и выйти")
                        }
                    }
                }
            }
        }
    }
}
