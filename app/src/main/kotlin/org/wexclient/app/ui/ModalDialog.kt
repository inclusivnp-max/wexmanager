package org.wexclient.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Куда вернуть клавиатуру после закрытия окна-слоя. Слой забирает фокус у главного окна
 * и при закрытии его не отдаёт: Delete, стрелки и F-клавиши переставали работать.
 */
object FocusReturn {
    @Volatile
    var target: (() -> Unit)? = null

    /** Открытые окна-слои: пока хоть одно открыто, клавиатуру у него не забираем. */
    var openLayers by mutableStateOf(0)
        internal set
}

/**
 * Вызвать в содержимом меню главного окна: всплывающее меню забирает клавиатуру и, закрывшись,
 * её не отдаёт — после выбора пункта не работали Delete, Ctrl+Z и стрелки.
 */
@Composable
fun ReturnFocusOnClose() {
    DisposableEffect(Unit) {
        onDispose { FocusReturn.target?.invoke() }
    }
}

/** [Dialog] поверх главного окна, после закрытия которого файловый менеджер снова слышит клавиши. */
@Composable
fun ModalDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    /**
     * false — идёт работа: Esc и щелчок мимо окно не прячут, а только передают просьбу хозяину
     * (он может, например, отменить работу).
     */
    dismissible: Boolean = true,
    content: @Composable () -> Unit
) {
    DisposableEffect(Unit) {
        FocusReturn.openLayers++
        onDispose {
            FocusReturn.openLayers--
            FocusReturn.target?.invoke()
        }
    }
    // Окно не выскакивает рывком и не пропадает мгновенно: закрытие ждёт конца движения
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val canHide by rememberUpdatedState(dismissible)
    val request by rememberUpdatedState(onDismissRequest)
    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(closing) {
        if (closing) {
            kotlinx.coroutines.delay(Motion.EXIT.toLong())
            request()
            // Хозяин мог окно и не закрыть (идёт работа) — тогда вернуть его на место.
            // Иначе спрятанный слой так и держал бы клавиатуру и мышь главного окна.
            kotlinx.coroutines.delay(RESTORE_AFTER_MS)
            closing = false
            visible = true
        }
    }
    Dialog(
        onDismissRequest = {
            when {
                closing -> Unit
                canHide -> {
                    closing = true
                    visible = false
                }
                else -> request()
            }
        },
        properties = properties
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = Motion.dialogEnter,
            exit = Motion.dialogExit
        ) {
            content()
        }
    }
}

/** Сколько ждать, что хозяин уберёт закрытое окно, прежде чем вернуть его. */
private const val RESTORE_AFTER_MS = 400L

/** Для onKeyEvent отдельного окна: Esc, которую не забрало ничего внутри (поиск, подсказки), закрывает окно. */
fun closeOnEscape(event: KeyEvent, close: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return false
    close()
    return true
}

/**
 * Для onPreviewKeyEvent/onKeyEvent отдельного окна (DialogWindow): Compose берёт эти лямбды один раз,
 * при создании окна, и дальше вызывает их со значениями первой композиции. Обёртка всегда
 * вызывает последнюю версию обработчика — иначе, например, Ctrl+S видел «не изменено» навсегда.
 */
@Composable
fun latestKeyHandler(handler: (KeyEvent) -> Boolean): (KeyEvent) -> Boolean {
    val latest = rememberUpdatedState(handler)
    return remember { { event: KeyEvent -> latest.value(event) } }
}
