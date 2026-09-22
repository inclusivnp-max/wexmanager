package org.wexclient.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import org.wexclient.app.theme.NoctaliaError
import org.wexclient.app.theme.NoctaliaPrimary

/** Enter — подтвердить, Esc — закрыть (окно без этого не отвечало на клавиатуру). */
internal fun dialogKeys(e: KeyEvent, onConfirm: () -> Unit, onDismiss: () -> Unit): Boolean {
    // Отпускание Enter тоже забираем: иначе оно доходило до кнопки в фокусе и нажимало её
    // второй раз, пока окно ещё закрывалось, — копирование делалось дважды
    if (e.type == KeyEventType.KeyUp && (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Escape)) return true
    if (e.type != KeyEventType.KeyDown) return false
    return when (e.key) {
        Key.Enter, Key.NumPadEnter -> {
            onConfirm()
            true
        }
        Key.Escape -> {
            onDismiss()
            true
        }
        else -> false
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Да",
    cancelText: String = "Отмена",
    isDestructive: Boolean = false,
    /** Фокус на «Отмена»: для неожиданных вопросов, где привычный Enter не должен подтверждать. */
    focusCancel: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // Ответ принимается один раз: двойной щелчок или клавиша плюс кнопка не повторят действие
    var answered by remember { mutableStateOf(false) }
    val confirmOnce = { if (!answered) { answered = true; onConfirm() } }
    val dismissOnce = { if (!answered) { answered = true; onDismiss() } }
    ModalDialog(onDismissRequest = dismissOnce) {
        // Фокус — на кнопке подтверждения: клавиши доходят до окна, Tab переключает кнопки
        val confirmFocus = remember { FocusRequester() }
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            runCatching { if (focusCancel && cancelText.isNotBlank()) cancelFocus.requestFocus() else confirmFocus.requestFocus() }
        }
        // Enter нажимает ту кнопку, на которой фокус: Tab на «Отмена» + Enter не должен удалять
        var cancelFocused by remember { mutableStateOf(false) }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 6.dp,
            modifier = Modifier.width(400.dp).dialogGlass(RoundedCornerShape(16.dp)).onPreviewKeyEvent {
                dialogKeys(it, { if (cancelFocused) dismissOnce() else confirmOnce() }, dismissOnce)
            }
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Icon(
                        imageVector = if (isDestructive) Icons.Default.WarningAmber else Icons.Default.HelpOutline,
                        contentDescription = null,
                        tint = if (isDestructive) NoctaliaError else NoctaliaPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (isDestructive) NoctaliaError else NoctaliaPrimary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    // Сообщение без выбора — одна кнопка
                    if (cancelText.isNotBlank()) {
                        OutlinedButton(onClick = dismissOnce, modifier = Modifier.focusRequester(cancelFocus).onFocusChanged { cancelFocused = it.isFocused }) {
                            Text(cancelText)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Button(
                        onClick = confirmOnce,
                        modifier = Modifier.focusRequester(confirmFocus),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDestructive) NoctaliaError else NoctaliaPrimary
                        )
                    ) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}

@Composable
fun InputDialog(
    title: String,
    initialValue: String = "",
    label: String = "Имя",
    confirmText: String = "OK",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Сообщение о недопустимом значении (кнопка недоступна), null — всё в порядке */
    validate: (String) -> String? = { null }
) {
    var text by remember { mutableStateOf(initialValue) }
    val problem = remember(text) { if (text.isBlank()) null else validate(text) }
    val confirm = { if (text.isNotBlank() && validate(text) == null) onConfirm(text) }

    ModalDialog(onDismissRequest = onDismiss) {
        val fieldFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 6.dp,
            modifier = Modifier.width(400.dp).dialogGlass(RoundedCornerShape(16.dp)).onPreviewKeyEvent { dialogKeys(it, confirm, onDismiss) }
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = NoctaliaPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { msg -> { Text(msg) } },
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Отмена")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = confirm,
                        enabled = text.isNotBlank() && problem == null,
                        colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary)
                    ) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}
