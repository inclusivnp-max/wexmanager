package org.wexclient.app.ui

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import org.wexclient.app.ui.WexDropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.signing.KeyExportFormat
import org.wexclient.app.signing.KeyExportRequest
import org.wexclient.app.signing.KeyExporter
import org.wexclient.app.signing.KeyInfo
import org.wexclient.app.signing.KeyKind
import org.wexclient.app.signing.SigningKey
import org.wexclient.app.signing.SigningKeys
import org.wexclient.app.signing.SigningPreferences
import org.wexclient.app.theme.AppFonts
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaPrimary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
private fun KeyDialogFrame(
    icon: String,
    title: String,
    subtitle: String,
    width: Int = 520,
    onDismiss: () -> Unit,
    /** false — идёт работа, Esc и щелчок мимо окно не закрывают. */
    dismissible: Boolean = true,
    content: @Composable () -> Unit
) {
    ModalDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false), dismissible = dismissible) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent, tonalElevation = 8.dp, modifier = Modifier.width(width.dp).dialogGlass(RoundedCornerShape(16.dp))) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(SymbolRef(icon), null, tint = NoctaliaPrimary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = NoctaliaPrimary)
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DialogClose(onDismiss)
                }
                Spacer(modifier = Modifier.height(14.dp))
                content()
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    password: Boolean = false,
    mono: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Column(modifier = modifier.padding(bottom = 8.dp)) {
        FieldLabel(label)
        Spacer(modifier = Modifier.height(3.dp))
        if (password) {
            PasswordField(value, onChange, placeholder)
        } else {
            GrailCompactField(value = value, onChange = onChange, placeholder = placeholder, mono = mono, height = 36.dp, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Поле пароля с кнопкой «показать». */
@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester? = null,
    onEnter: (() -> Unit)? = null
) {
    var reveal by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().height(36.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = if (reveal) AppFonts.JetBrainsMono else null
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(NoctaliaPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
                        .onPreviewKeyEvent { e ->
                            if (onEnter != null && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                                onEnter()
                                true
                            } else false
                        }
                )
            }
            SymbolIconButton(
                if (reveal) "visibility_off" else "visibility",
                if (reveal) "Скрыть пароль" else "Показать пароль",
                MaterialTheme.colorScheme.onSurfaceVariant,
                { reveal = !reveal },
                size = 28.dp,
                iconSize = 16.dp
            )
        }
    }
}

@Composable
private fun DialogButtons(
    confirmText: String,
    enabled: Boolean,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "Отмена"
) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = onDismiss, enabled = !busy) { Text(dismissText) }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onConfirm, enabled = enabled && !busy, colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary)) {
            if (busy) {
                WexSpinner(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(confirmText)
        }
    }
}

/** Новый ключ (RSA, PKCS12) через keytool. */
@Composable
fun CreateKeyDialog(onCreated: (SigningKey) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("key0") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var cn by remember { mutableStateOf(System.getProperty("user.name") ?: "") }
    var org by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var years by remember { mutableStateOf("30") }
    var keySize by remember { mutableStateOf(2048) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val passwordProblem = when {
        password.isNotEmpty() && password.length < 6 -> "Пароль — не короче 6 символов"
        password != password2 -> "Пароли не совпадают"
        else -> null
    }

    KeyDialogFrame("key", "Новый ключ подписи", "RSA, хранилище PKCS12 в ~/.config/wexdesktop/keys", onDismiss = { if (!busy) onDismiss() }, dismissible = !busy) {
        Row {
            LabeledField("Название в списке", title, { title = it }, "Мой ключ", modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(10.dp))
            LabeledField("Псевдоним (alias)", alias, { alias = it.filter { c -> !c.isWhitespace() } }, "key0", mono = true, modifier = Modifier.weight(1f))
        }
        Row {
            LabeledField("Пароль (можно пустой)", password, { password = it }, "не короче 6 символов", password = true, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(10.dp))
            LabeledField("Повтор пароля", password2, { password2 = it }, "", password = true, modifier = Modifier.weight(1f))
        }
        Text(
            "Пустой пароль — WEX сгенерирует случайный и сохранит его для быстрой подписи (при экспорте можно задать свой).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        LabeledField("Владелец (CN)", cn, { cn = it }, "Имя или ник")
        Row {
            LabeledField("Организация (O)", org, { org = it }, "необязательно", modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(10.dp))
            LabeledField("Страна (C)", country, { country = it.filter(Char::isLetter).take(2).uppercase() }, "RU", modifier = Modifier.width(90.dp))
            Spacer(modifier = Modifier.width(10.dp))
            LabeledField("Срок, лет", years, { years = it.filter(Char::isDigit).take(3) }, "30", modifier = Modifier.width(90.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Размер ключа:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(10.dp))
            GrailTabs(listOf("2048", "4096"), if (keySize == 2048) 0 else 1) { keySize = if (it == 0) 2048 else 4096 }
        }
        (error ?: passwordProblem)?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        DialogButtons(
            confirmText = "Создать",
            enabled = alias.isNotBlank() && passwordProblem == null && (years.toIntOrNull() ?: 0) in 1..100,
            busy = busy,
            onConfirm = {
                busy = true
                error = null
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        runCatching {
                            SigningKeys.create(
                                SigningKeys.NewKeyRequest(
                                    title = title, alias = alias, password = password, commonName = cn,
                                    organization = org, country = country,
                                    validityYears = years.toIntOrNull() ?: 30, keySize = keySize
                                )
                            )
                        }
                    }
                    busy = false
                    res.onSuccess(onCreated).onFailure { error = it.message ?: it.javaClass.simpleName }
                }
            },
            onDismiss = onDismiss
        )
    }
}

/** Файл ключа — пара PKCS#8 (или .pem с ключом), а не хранилище. */
internal fun isPk8KeyFile(f: File): Boolean {
    val name = f.name.lowercase()
    return f.extension.lowercase() in setOf("pk8", "key", "pem") && !name.endsWith(".x509.pem")
}

/** Сертификат для .pk8: в том же .pem или рядом (testkey.pk8 → testkey.x509.pem). */
internal fun guessCertificateFor(key: File): File? {
    if (SigningKeys.pemHasCertificate(key)) return key
    return listOf(".x509.pem", ".pem", ".crt", ".cer")
        .map { File(key.parentFile, key.nameWithoutExtension + it) }
        .firstOrNull { it.isFile && it != key }
}

private sealed class StoreCheck {
    object Idle : StoreCheck()
    object Checking : StoreCheck()
    class Ok(val count: Int) : StoreCheck()
    class Failed(val message: String) : StoreCheck()
}

/**
 * Импорт ключа подписи: хранилище JKS/PKCS12 (пароль, псевдоним, пароль ключа)
 * или пара .pk8 + .x509.pem. Пароль проверяется по мере ввода, сертификат показывается до импорта.
 */
@Composable
fun ImportKeyDialog(
    onImported: (SigningKey) -> Unit,
    onDismiss: () -> Unit,
    /** Файл, по которому щёлкнули в панели (null — выбрать вручную). */
    initialFile: File? = null,
    /** Как показывать файл (путь внутри архива вместо временной копии). */
    displayPath: String? = null
) {
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(initialFile) }
    var shownPath by remember { mutableStateOf(displayPath) }
    var certFile by remember { mutableStateOf(initialFile?.takeIf { isPk8KeyFile(it) }?.let { guessCertificateFor(it) }) }
    var title by remember { mutableStateOf(initialFile?.nameWithoutExtension?.removeSuffix(".x509") ?: "") }
    var storePassword by remember { mutableStateOf("") }
    var keyPassword by remember { mutableStateOf("") }
    var separateKeyPassword by remember { mutableStateOf(false) }
    var aliases by remember { mutableStateOf<List<String>>(emptyList()) }
    var alias by remember { mutableStateOf("") }
    var storeCheck by remember { mutableStateOf<StoreCheck>(StoreCheck.Idle) }
    var forceCheck by remember { mutableStateOf(0) }
    var keyInfo by remember { mutableStateOf<KeyInfo?>(null) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var checkingKey by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf<SigningKey?>(null) }
    var makeDefault by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val passwordFocus = remember { FocusRequester() }

    val current = file
    val isPk8 = current != null && isPk8KeyFile(current)
    val isPepk = current?.extension?.lowercase() == "pepk"

    fun chooseKeyFile(f: File) {
        file = f
        shownPath = null
        aliases = emptyList()
        alias = ""
        storeCheck = StoreCheck.Idle
        keyInfo = null
        keyError = null
        error = null
        if (title.isBlank()) title = f.nameWithoutExtension.removeSuffix(".x509")
        certFile = if (isPk8KeyFile(f)) guessCertificateFor(f) else null
    }

    // Пароль хранилища — сразу в фокус, если файл уже выбран
    LaunchedEffect(Unit) {
        if (initialFile != null && !isPk8KeyFile(initialFile)) org.wexclient.app.editor.requestFocusSafely(passwordFocus)
    }

    // Проверка пароля хранилища по мере ввода: список ключей
    LaunchedEffect(current, storePassword, forceCheck) {
        val f = current ?: return@LaunchedEffect
        if (isPk8KeyFile(f) || f.extension.lowercase() == "pepk") return@LaunchedEffect
        if (storePassword.isEmpty() && forceCheck == 0) {
            storeCheck = StoreCheck.Idle
            aliases = emptyList()
            return@LaunchedEffect
        }
        delay(if (forceCheck > 0 && storePassword.isEmpty()) 0 else 350)
        storeCheck = StoreCheck.Checking
        val res = withContext(Dispatchers.IO) { runCatching { SigningKeys.aliases(f, storePassword) } }
        res.onSuccess { list ->
            aliases = list
            storeCheck = if (list.isEmpty()) StoreCheck.Failed("В хранилище нет закрытых ключей") else StoreCheck.Ok(list.size)
            if (alias !in list) alias = list.firstOrNull() ?: ""
        }.onFailure {
            aliases = emptyList()
            storeCheck = StoreCheck.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    // Проверка выбранного ключа: пароль ключа, сертификат, нет ли его уже в списке
    LaunchedEffect(current, certFile, aliases, alias, keyPassword, separateKeyPassword) {
        keyInfo = null
        keyError = null
        duplicate = null
        val f = current ?: return@LaunchedEffect
        val cert = certFile
        if (isPk8KeyFile(f)) {
            if (cert == null) return@LaunchedEffect
        } else if (alias.isEmpty() || alias !in aliases) {
            return@LaunchedEffect
        }
        delay(200)
        checkingKey = true
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val info = if (isPk8KeyFile(f)) SigningKeys.inspectPk8(f, cert!!)
                else SigningKeys.inspectKeystore(f, storePassword, alias, if (separateKeyPassword) keyPassword else "")
                info to SigningKeys.findBySha256(info.sha256)
            }
        }
        checkingKey = false
        res.onSuccess { (info, dup) ->
            keyInfo = info
            duplicate = dup
        }.onFailure {
            val msg = it.message ?: it.javaClass.simpleName
            keyError = msg
            // У ключа свой пароль — показываем поле для него
            if (!separateKeyPassword && msg.contains("пароль ключа", ignoreCase = true)) separateKeyPassword = true
        }
    }

    fun import() {
        val f = file ?: return
        if (keyInfo == null || busy) return
        busy = true
        error = null
        val cert = certFile
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    if (isPk8KeyFile(f)) SigningKeys.importPk8(f, cert!!, title)
                    else SigningKeys.importKeystore(f, title, storePassword, alias, if (separateKeyPassword) keyPassword else "")
                }
            }
            busy = false
            res.onSuccess { key ->
                if (makeDefault) SigningPreferences.updateDefaultKey(key.id)
                onImported(key)
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }

    KeyDialogFrame(
        "upload_file",
        "Импорт ключа подписи",
        "JKS / .keystore / PKCS12 (.p12, .pfx) или .pk8 + .x509.pem",
        width = 560,
        onDismiss = { if (!busy) onDismiss() },
        dismissible = !busy
    ) {
        FieldLabel("Файл ключа")
        Spacer(modifier = Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
                    SymbolIcon(SymbolRef("key"), null, tint = if (current != null) NoctaliaPrimary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    if (current == null) {
                        Text("не выбран", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    } else {
                        Text(current.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            shownPath ?: current.parent.orEmpty(),
                            fontSize = 11.sp,
                            fontFamily = AppFonts.JetBrainsMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = { org.wexclient.app.util.FileChooser.openFile("Файл ключа", null) { it?.let { f -> chooseKeyFile(f) } } },
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(40.dp)
            ) { Text("Выбрать…", fontSize = 12.sp) }
        }
        Spacer(modifier = Modifier.height(12.dp))

        when {
            current == null -> Unit
            isPepk -> HintLine("warning", MaterialTheme.colorScheme.error, "PEPK — ключ, зашифрованный для Google Play; импортировать его нельзя. Нужен исходный .jks/.keystore.")
            isPk8 -> {
                FieldLabel("Сертификат (.x509.pem)")
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (val c = certFile) {
                            null -> "не найден рядом — выберите"
                            current -> "в том же файле"
                            else -> c.name
                        },
                        fontFamily = AppFonts.JetBrainsMono,
                        fontSize = 12.sp,
                        color = if (certFile == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { org.wexclient.app.util.FileChooser.openFile("Сертификат X.509", null) { it?.let { f -> certFile = f } } },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(32.dp)
                    ) { Text("Выбрать…", fontSize = 12.sp) }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
            else -> {
                FieldLabel("Пароль хранилища")
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        PasswordField(
                            storePassword,
                            { storePassword = it; error = null },
                            "пароль от файла ключа",
                            focusRequester = passwordFocus,
                            onEnter = { if (keyInfo != null) import() else forceCheck++ }
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { forceCheck++ },
                        enabled = storeCheck !is StoreCheck.Checking,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(36.dp)
                    ) { Text("Проверить", fontSize = 12.sp) }
                }
                Spacer(modifier = Modifier.height(4.dp))
                when (val c = storeCheck) {
                    StoreCheck.Idle -> Text(
                        "Введите пароль — ключи из хранилища появятся ниже",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    StoreCheck.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        WexSpinner(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = NoctaliaPrimary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Проверка пароля…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is StoreCheck.Ok -> StatusLine("check_circle", NoctaliaOk, "Пароль верный · ключей в хранилище: ${c.count}")
                    is StoreCheck.Failed -> StatusLine("error", MaterialTheme.colorScheme.error, c.message)
                }

                if (aliases.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    FieldLabel("Псевдоним ключа (alias — «логин» ключа)")
                    Spacer(modifier = Modifier.height(3.dp))
                    AliasPicker(aliases, alias) { alias = it }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.offset(x = (-7).dp).padding(top = 4.dp).clickable { separateKeyPassword = !separateKeyPassword }
                    ) {
                        Checkbox(
                            checked = separateKeyPassword,
                            onCheckedChange = { separateKeyPassword = it },
                            colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("У ключа свой пароль (не как у хранилища)", fontSize = 12.sp)
                    }
                    if (separateKeyPassword) {
                        PasswordField(
                            keyPassword,
                            { keyPassword = it },
                            "пароль ключа «$alias»",
                            onEnter = { if (keyInfo != null) import() }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        // Сертификат выбранного ключа — до импорта
        val info = keyInfo
        when {
            info != null -> KeyPreview(info, duplicate)
            checkingKey -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                WexSpinner(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = NoctaliaPrimary)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Проверка ключа…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            keyError != null -> StatusLine("error", MaterialTheme.colorScheme.error, keyError!!, bottom = 8.dp)
        }

        if (current != null && !isPepk) {
            LabeledField("Название в списке ключей", title, { title = it }, "Релизный ключ")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = (-7).dp).clickable { makeDefault = !makeDefault }
            ) {
                Checkbox(
                    checked = makeDefault,
                    onCheckedChange = { makeDefault = it },
                    colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Сразу выбрать для подписи (кнопка «Подписать»)", fontSize = 12.sp)
            }
        }
        Text(
            "После импорта ключ появится в меню «Подписать». Пароли хранятся в ~/.config/wexdesktop/keys (доступ только у вашего пользователя).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        error?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        DialogButtons(
            confirmText = "Импортировать",
            enabled = current != null && keyInfo != null && !isPepk,
            busy = busy,
            onConfirm = { import() },
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun StatusLine(icon: String, color: Color, text: String, bottom: androidx.compose.ui.unit.Dp = 0.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = bottom)) {
        SymbolIcon(SymbolRef(icon), null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, fontSize = 11.sp, lineHeight = 14.sp, color = color)
    }
}

/** Сертификат ключа перед импортом: владелец, алгоритм, срок, отпечаток, дубликат. */
@Composable
private fun KeyPreview(info: KeyInfo, duplicate: SigningKey?) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val green = NoctaliaOk
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = green.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, green.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SymbolIcon(SymbolRef("verified_user"), null, tint = green, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(info.commonName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "${info.algorithm} · ${fmt.format(info.notBefore)} — ${fmt.format(info.notAfter)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                info.subject,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                "SHA-256 " + info.sha256,
                fontSize = 10.sp,
                fontFamily = AppFonts.JetBrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (duplicate != null) {
                Spacer(modifier = Modifier.height(4.dp))
                StatusLine("info", NoctaliaPrimary, "Этот ключ уже есть в списке: «${duplicate.title}»")
            }
        }
    }
}

/** Выбор псевдонима из ключей хранилища. */
@Composable
private fun AliasPicker(aliases: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, if (open) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clickable(enabled = aliases.size > 1) { open = true }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 6.dp)) {
                SymbolIcon(SymbolRef("badge"), null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(selected, fontSize = 13.sp, fontFamily = AppFonts.JetBrainsMono, modifier = Modifier.weight(1f), maxLines = 1)
                if (aliases.size > 1) {
                    Text("${aliases.size} ключа(ей)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SymbolIcon(SymbolRef("arrow_drop_down"), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
        WexDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            aliases.forEach { a ->
                MenuEntry("key", a, checked = if (a == selected) true else null) {
                    open = false
                    onSelect(a)
                }
            }
        }
    }
}

/** Сведения о сертификате ключа: отпечатки и переход к экспорту. */
@Composable
fun KeyInfoDialog(
    key: SigningKey,
    info: KeyInfo?,
    onDismiss: () -> Unit,
    loading: Boolean = false,
    onExport: (() -> Unit)? = null
) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    var status by remember { mutableStateOf<String?>(null) }
    KeyDialogFrame("verified_user", key.title, key.alias?.let { "Псевдоним: $it" } ?: "", width = 620, onDismiss = onDismiss) {
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                WexSpinner(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NoctaliaPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Чтение сертификата…", fontSize = 13.sp)
            }
            info == null -> Text(
                if (key.id == SigningKeys.DEBUG_ID) "~/.android/debug.keystore ещё не создан — появится при первой подписи или экспорте этого ключа."
                else "Не удалось прочитать сертификат ключа.",
                fontSize = 13.sp
            )
            else -> {
                SelectionContainer {
                    Column {
                        InfoLine("Владелец", info.subject)
                        InfoLine("Алгоритм", info.algorithm)
                        InfoLine("Действует", "${fmt.format(info.notBefore)} — ${fmt.format(info.notAfter)}")
                        InfoLine("SHA-256", info.sha256, mono = true)
                        InfoLine("SHA-1", info.sha1, mono = true)
                        InfoLine("MD5", info.md5, mono = true)
                    }
                }
                status?.let { Text(it, fontSize = 12.sp, color = NoctaliaPrimary, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (info != null) {
                OutlinedButton(onClick = {
                    putClipboardText(info.sha256)
                    status = "SHA-256 скопирован"
                }) { Text("Копировать SHA-256") }
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (onExport != null) {
                OutlinedButton(onClick = onExport) {
                    SymbolIcon(SymbolRef("download"), null, tint = NoctaliaPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Экспорт…")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary)) { Text("Готово") }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(90.dp))
        Text(
            value,
            fontSize = if (mono) 11.sp else 12.sp,
            fontFamily = if (mono) AppFonts.JetBrainsMono else null,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

// Последняя папка экспорта — в пределах запуска
private var lastExportDir: File? = null

private data class ExportDefaults(val alias: String, val password: String, val keyPassword: String)

// Для своих хранилищ подставляем сохранённые пароли — иначе ключ со сгенерированным паролем не выгрузить
private fun exportDefaults(key: SigningKey): ExportDefaults = when (key.kind) {
    KeyKind.ANDROID_DEBUG -> ExportDefaults("androiddebugkey", "android", "android")
    KeyKind.KEYSTORE -> ExportDefaults(
        key.alias ?: "key0",
        key.storePassword.orEmpty(),
        key.keyPassword ?: key.storePassword.orEmpty()
    )
    else -> ExportDefaults(key.alias?.filterNot { it.isWhitespace() }?.ifEmpty { null } ?: "key0", "", "")
}

private fun expandHome(path: String): String =
    if (path == "~" || path.startsWith("~/")) org.wexclient.app.util.AppDirs.userHome.toString() + path.drop(1) else path

// Меняет известное расширение формата на расширение нового (release.jks → release.p12)
private fun withFormatExtension(path: String, format: KeyExportFormat): String {
    val f = File(path)
    val known = KeyExportFormat.entries.flatMap { it.extensions }.distinct().sortedByDescending { it.length }
    val base = known.firstOrNull { f.name.length > it.length + 1 && f.name.endsWith(".$it", ignoreCase = true) }
        ?.let { f.name.dropLast(it.length + 1) } ?: f.name
    return File(f.parentFile, "$base.${format.extension}").path
}

/** Экспорт ключа: JKS, PKCS12, PK8 + X509.PEM, PEM или только сертификат. */
@Composable
fun ExportKeyDialog(initialKey: SigningKey, initialDir: File? = null, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(initialKey) }
    var format by remember { mutableStateOf(KeyExportFormat.JKS) }
    val defaults = remember(key) { exportDefaults(key) }
    var alias by remember(key) { mutableStateOf(defaults.alias) }
    var storePassword by remember(key) { mutableStateOf(defaults.password) }
    var storePassword2 by remember(key) { mutableStateOf(defaults.password) }
    var separateKeyPassword by remember(key) { mutableStateOf(defaults.keyPassword != defaults.password) }
    var keyPassword by remember(key) { mutableStateOf(defaults.keyPassword) }
    val startDir = remember {
        initialDir?.takeIf { it.isDirectory } ?: lastExportDir?.takeIf { it.isDirectory } ?: File(org.wexclient.app.util.AppDirs.userHome.toString())
    }
    var path by remember { mutableStateOf(File(startDir, KeyExporter.defaultFileName(initialKey, KeyExportFormat.JKS)).path) }
    var pathEdited by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<List<File>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var info by remember(key) { mutableStateOf<KeyInfo?>(null) }
    LaunchedEffect(key) { info = withContext(Dispatchers.IO) { SigningKeys.info(key) } }

    val targetFile = File(expandHome(path.trim()))
    val request = KeyExportRequest(
        format = format,
        target = KeyExporter.normalizeTarget(format, targetFile),
        alias = alias,
        storePassword = storePassword,
        keyPassword = if (separateKeyPassword && format == KeyExportFormat.JKS) keyPassword else storePassword
    )
    val outputs = if (path.isBlank()) emptyList() else KeyExporter.outputs(format, request.target)
    val problem = when {
        path.isBlank() -> "Укажите файл"
        !targetFile.isAbsolute -> "Укажите полный путь к файлу"
        format.isKeystore && storePassword != storePassword2 -> "Пароли не совпадают"
        else -> KeyExporter.validate(request)
    }
    val passwordPrefilled = format.isKeystore && defaults.password.isNotEmpty() && storePassword == defaults.password

    fun changed() {
        done = null
        status = null
        error = null
    }

    KeyDialogFrame("download", "Экспорт ключа", "Сохранить ключ подписи в файл для Android Studio, MT Manager, apksigner", width = 600, onDismiss = { if (!busy) onDismiss() }, dismissible = !busy) {
        FieldLabel("Ключ")
        Spacer(modifier = Modifier.height(3.dp))
        KeyPicker(key, info) { k ->
            if (k.id != key.id) {
                key = k
                if (!pathEdited) path = File(File(path).parentFile ?: startDir, KeyExporter.defaultFileName(k, format)).path
                changed()
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        FieldLabel("Формат")
        Spacer(modifier = Modifier.height(4.dp))
        GrailTabs(KeyExportFormat.entries.map { it.title }, format.ordinal) { i ->
            val next = KeyExportFormat.entries[i]
            if (next != format) {
                path = withFormatExtension(path, next)
                format = next
                changed()
            }
        }
        Text(
            format.hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )

        if (format.isKeystore) {
            LabeledField("Псевдоним (alias)", alias, { alias = it.filterNot(Char::isWhitespace); changed() }, "key0", mono = true)
            Row {
                LabeledField("Пароль хранилища", storePassword, { storePassword = it; changed() }, "не короче 6 символов", password = true, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(10.dp))
                LabeledField("Повтор пароля", storePassword2, { storePassword2 = it; changed() }, "", password = true, modifier = Modifier.weight(1f))
            }
            if (passwordPrefilled) {
                Text(
                    "Подставлен сохранённый пароль ключа — значок глаза покажет его, или задайте новый.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            if (format == KeyExportFormat.JKS) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // Квадрат чекбокса — вровень с полями
                    modifier = Modifier.offset(x = (-7).dp).padding(bottom = 6.dp).clickable { separateKeyPassword = !separateKeyPassword; changed() }
                ) {
                    Checkbox(
                        checked = separateKeyPassword,
                        onCheckedChange = { separateKeyPassword = it; changed() },
                        colors = CheckboxDefaults.colors(checkedColor = NoctaliaPrimary),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Отдельный пароль ключа", fontSize = 12.sp)
                }
                if (separateKeyPassword) {
                    LabeledField("Пароль ключа", keyPassword, { keyPassword = it; changed() }, "не короче 6 символов", password = true)
                }
            }
        } else if (format.containsPrivateKey) {
            HintLine("warning", MaterialTheme.colorScheme.error, "Закрытый ключ записывается без шифрования (как testkey.pk8 в AOSP) — не публикуйте этот файл.")
        }

        FieldLabel("Файл")
        Spacer(modifier = Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GrailCompactField(
                value = path,
                onChange = { path = it; pathEdited = true; changed() },
                placeholder = "/home/user/release.jks",
                mono = true,
                height = 36.dp,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = {
                org.wexclient.app.util.FileChooser.saveFile("Экспорт ключа — ${format.title}", targetFile) { picked ->
                    picked?.let {
                        path = KeyExporter.normalizeTarget(format, it).path
                        pathEdited = true
                        changed()
                    }
                }
            }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(36.dp)) { Text("Обзор…", fontSize = 12.sp) }
        }
        outputs.forEach { f ->
            val exists = f.exists() && done == null
            Text(
                "→ ${f.name}" + if (exists) "  (файл будет перезаписан)" else "",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppFonts.JetBrainsMono),
                color = if (exists) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        val result = done
        when {
            result != null -> {
                HintLine("check_circle", NoctaliaOk, "Сохранено: " + result.joinToString(", ") { it.name })
                Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        putClipboardText(result.joinToString("\n") { it.path })
                        status = "Путь скопирован"
                    }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(32.dp)) { Text("Копировать путь", fontSize = 12.sp) }
                    if (format.isKeystore) {
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            putClipboardText(KeyExporter.gradleSigningConfig(request))
                            status = "signingConfigs для build.gradle.kts скопирован (с паролями)"
                        }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(32.dp)) { Text("Копировать signingConfig", fontSize = 12.sp) }
                    }
                }
                status?.let { Text(it, fontSize = 11.sp, color = NoctaliaPrimary, modifier = Modifier.padding(top = 4.dp)) }
            }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            problem != null -> Text(
                problem,
                // Пустой пароль — ещё не ввели, а не ошибка
                color = if (format.isKeystore && storePassword.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        DialogButtons(
            confirmText = if (result != null) "Экспортировать ещё раз" else "Экспортировать",
            enabled = problem == null,
            busy = busy,
            dismissText = if (result != null) "Готово" else "Отмена",
            onConfirm = {
                val req = request
                val exportKey = key
                busy = true
                changed()
                scope.launch {
                    val res = withContext(Dispatchers.IO) { runCatching { KeyExporter.export(exportKey, req) } }
                    busy = false
                    res.onSuccess { files ->
                        done = files
                        lastExportDir = files.firstOrNull()?.parentFile
                    }.onFailure { error = it.message ?: it.javaClass.simpleName }
                }
            },
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun HintLine(icon: String, color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        SymbolIcon(SymbolRef(icon), null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Выбор ключа из списка (встроенные и свои). */
@Composable
private fun KeyPicker(selected: SigningKey, info: KeyInfo?, onSelect: (SigningKey) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, if (open) NoctaliaPrimary else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().height(40.dp).clickable { open = true }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, end = 6.dp)) {
                SymbolIcon(SymbolRef(if (selected.builtIn) "shield" else "key"), null, tint = NoctaliaPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(selected.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    listOfNotNull(selected.alias, info?.shortLabel).joinToString(" · "),
                    fontSize = 11.sp,
                    fontFamily = AppFonts.JetBrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SymbolIcon(SymbolRef("arrow_drop_down"), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        WexDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SigningKeys.keys.forEach { k ->
                MenuEntry(
                    if (k.builtIn) "shield" else "key",
                    k.title,
                    checked = if (k.id == selected.id) true else null,
                    trailing = k.alias
                ) {
                    open = false
                    onSelect(k)
                }
            }
        }
    }
}

/** Какие окна работы с ключами открыты — общее для меню «Подписать» и окна подписи. */
@Stable
class KeyDialogsState {
    var showCreate by mutableStateOf(false)
    var showImport by mutableStateOf(false)
    var exportKey by mutableStateOf<SigningKey?>(null)
    var infoKey by mutableStateOf<SigningKey?>(null)
    var renameKey by mutableStateOf<SigningKey?>(null)
    var deleteKey by mutableStateOf<SigningKey?>(null)
}

@Composable
fun rememberKeyDialogsState(): KeyDialogsState = remember { KeyDialogsState() }

/** Окна «Создать», «Импорт», «Экспорт», «Сведения», «Переименовать», «Удалить». */
@Composable
fun KeyDialogs(
    state: KeyDialogsState,
    exportDir: File? = null,
    onAdded: (SigningKey) -> Unit = {},
    onDeleted: (SigningKey) -> Unit = {}
) {
    if (state.showCreate) {
        CreateKeyDialog(
            onCreated = { state.showCreate = false; onAdded(it) },
            onDismiss = { state.showCreate = false }
        )
    }
    if (state.showImport) {
        ImportKeyDialog(
            onImported = { state.showImport = false; onAdded(it) },
            onDismiss = { state.showImport = false }
        )
    }
    state.exportKey?.let { key ->
        ExportKeyDialog(initialKey = key, initialDir = exportDir, onDismiss = { state.exportKey = null })
    }
    state.infoKey?.let { key ->
        var info by remember(key) { mutableStateOf<KeyInfo?>(null) }
        var loading by remember(key) { mutableStateOf(true) }
        LaunchedEffect(key) {
            info = withContext(Dispatchers.IO) { SigningKeys.info(key) }
            loading = false
        }
        KeyInfoDialog(
            key = key,
            info = info,
            loading = loading,
            onDismiss = { state.infoKey = null },
            onExport = {
                state.infoKey = null
                state.exportKey = key
            }
        )
    }
    state.renameKey?.let { key ->
        InputDialog(
            title = "Переименовать ключ",
            initialValue = key.title,
            label = "Название",
            confirmText = "Сохранить",
            onConfirm = {
                SigningKeys.rename(key, it)
                state.renameKey = null
            },
            onDismiss = { state.renameKey = null }
        )
    }
    state.deleteKey?.let { key ->
        ConfirmDialog(
            title = "Удалить ключ",
            message = "Удалить «${key.title}»? Файл хранилища будет удалён из настроек WEX. " +
                "APK, подписанные этим ключом, без него нельзя будет обновить поверх установленных — " +
                "при необходимости сначала сделайте экспорт.",
            confirmText = "Удалить",
            isDestructive = true,
            onConfirm = {
                if (SigningPreferences.defaultKeyId == key.id) SigningPreferences.updateDefaultKey(SigningKeys.TESTKEY_ID)
                SigningKeys.delete(key)
                state.deleteKey = null
                onDeleted(key)
            },
            onDismiss = { state.deleteKey = null }
        )
    }
}

internal fun putClipboardText(text: String) {
    runCatching {
        org.wexclient.app.util.TextClipboard.copy(text)
    }
}

// Владелец — активное окно (где нажали кнопку): выбор файла откроется поверх него, а не отдельным окном
