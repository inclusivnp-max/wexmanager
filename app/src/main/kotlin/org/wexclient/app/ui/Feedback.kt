package org.wexclient.app.ui

import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.withLock
import org.wexclient.app.icons.SymbolIcon
import org.wexclient.app.icons.SymbolRef
import org.wexclient.app.theme.NoctaliaError
import org.wexclient.app.theme.NoctaliaOk
import org.wexclient.app.theme.NoctaliaPrimary

enum class ToastKind { SUCCESS, INFO, ERROR }

/** Кнопка в уведомлении («Отменить»). */
class ToastAction(val label: String, val onClick: () -> Unit)

class ToastState {
    internal data class Message(val id: Long, val text: String, val kind: ToastKind, val action: ToastAction? = null)

    internal var current by mutableStateOf<Message?>(null)
    private var counter = 0L

    fun show(text: String, kind: ToastKind = ToastKind.SUCCESS, action: ToastAction? = null) {
        current = Message(++counter, text, kind, action)
    }

    internal fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }

    fun success(text: String) = show(text, ToastKind.SUCCESS)
    fun error(text: String) = show(text, ToastKind.ERROR)
    fun info(text: String) = show(text, ToastKind.INFO)
}

@Composable
fun rememberToastState(): ToastState = remember { ToastState() }

/** Всплывающее уведомление внизу окна (кладётся последним в Box поверх содержимого). */
@Composable
fun BoxScope.ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf<ToastState.Message?>(null) }
    val msg = state.current
    LaunchedEffect(msg?.id) {
        if (msg != null) {
            visible = msg
            // С кнопкой — дольше: успеть нажать «Отменить»
            delay(if (msg.action != null) 6500 else if (msg.kind == ToastKind.ERROR) 4500 else 1800)
            visible = null
        } else {
            visible = null
        }
    }
    var last by remember { mutableStateOf<ToastState.Message?>(null) }
    if (visible != null) last = visible
    AnimatedVisibility(
        visible = visible != null,
        enter = slideInVertically(tween(180)) { it / 2 } + fadeIn(tween(160)),
        exit = slideOutVertically(tween(160)) { it / 2 } + fadeOut(tween(140)),
        modifier = modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
    ) {
        val m = last ?: return@AnimatedVisibility
        val (icon, accent) = when (m.kind) {
            ToastKind.SUCCESS -> "check_circle" to NoctaliaOk
            ToastKind.INFO -> "info" to NoctaliaPrimary
            ToastKind.ERROR -> "error" to NoctaliaError
        }
        val toastShape = RoundedCornerShape(12.dp)
        Surface(
            shape = toastShape,
            // Тёмная плашка в тон окну: inverseSurface в тёмной теме светлый — подсказка слепила.
            // Стекло — чтобы сквозь неё не читался список файлов
            color = Color.Transparent,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
            modifier = Modifier.glass(toastShape, MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 560.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SymbolIcon(SymbolRef(icon), contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = m.text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    // Строка обрезается по самим буквам и центрируется: иначе запас шрифта
                    // сверху и снизу разный и текст выглядит сдвинутым вверх
                    lineHeight = 16.sp,
                    style = LocalTextStyle.current.copy(
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both
                        )
                    ),
                    modifier = Modifier.weight(1f, fill = false)
                )
                m.action?.let { action ->
                    Spacer(modifier = Modifier.width(12.dp))
                    androidx.compose.material3.TextButton(
                        onClick = {
                            state.dismiss(m.id)
                            action.onClick()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(action.label, color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * «Сохранить изменения?» перед закрытием редактора.
 * canSave=false — только «Не сохранять»/«Отмена» (сохранить нечем).
 */
@Composable
fun UnsavedChangesDialog(
    fileName: String,
    message: String = "В «$fileName» есть несохранённые изменения. Сохранить их перед закрытием?",
    saveText: String = "Сохранить",
    discardText: String = "Не сохранять",
    busy: Boolean = false,
    error: String? = null,
    canSave: Boolean = true,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    ModalDialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false), dismissible = !busy) {
        // Фокус на «Сохранить» (без него — на «Отмена»): Enter нажимает кнопку в фокусе, Tab их переключает
        val mainFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { org.wexclient.app.editor.requestFocusSafely(mainFocus) }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            tonalElevation = 6.dp,
            modifier = Modifier.width(440.dp).dialogGlass(RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(SymbolRef("save"), contentDescription = null, tint = NoctaliaPrimary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Сохранить изменения?", style = MaterialTheme.typography.titleLarge, color = NoctaliaPrimary)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onCancel,
                        enabled = !busy,
                        modifier = if (canSave) Modifier else Modifier.focusRequester(mainFocus)
                    ) { Text("Отмена") }
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onDiscard,
                        enabled = !busy,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NoctaliaError),
                        border = BorderStroke(1.dp, NoctaliaError.copy(alpha = 0.6f))
                    ) { Text(discardText) }
                    if (canSave) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onSave,
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = NoctaliaPrimary),
                            modifier = Modifier.focusRequester(mainFocus)
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(saveText)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Запуск записи, которая может идти секунды: [done] получает null при успехе или текст ошибки.
 * Возвращает false, если запись не началась (уже идёт другая) — тогда [done] не вызывается.
 */
typealias AsyncSave = (done: (String?) -> Unit) -> Boolean

/**
 * Запись в фоне: файл внутри архива переписывает весь архив, и окно замирало на секунды.
 * Пока идёт одна запись, вторая не начинается.
 */
class BackgroundSave(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set

    /** [write] — в фоновом потоке; [done] — в потоке окна: null или текст ошибки. */
    fun run(write: () -> Unit, done: (String?) -> Unit): Boolean {
        if (busy) return false
        busy = true
        scope.launch {
            val error = try {
                withContext(Dispatchers.IO) { tracked(write) }
                null
            } catch (e: CancellationException) {
                busy = false
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } catch (e: OutOfMemoryError) {
                "Недостаточно памяти для сохранения"
            }
            busy = false
            done(error)
        }
        return true
    }

    companion object {
        private val lock = java.util.concurrent.locks.ReentrantLock()
        private val idle = lock.newCondition()
        private var active = 0

        /** Запись, которую выход из программы подождёт (см. [awaitIdle]). */
        fun <T> tracked(write: () -> T): T {
            lock.withLock { active++ }
            try {
                return write()
            } finally {
                lock.withLock {
                    active--
                    if (active == 0) idle.signalAll()
                }
            }
        }

        /**
         * При выходе: дождаться начатых записей (не дольше [timeoutMs]). Потоки записи — фоновые,
         * и без ожидания JVM обрывала бы запись файла на середине.
         */
        fun awaitIdle(timeoutMs: Long): Boolean = lock.withLock {
            var left = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (active > 0) {
                if (left <= 0) return@withLock false
                left = idle.awaitNanos(left)
            }
            true
        }
    }
}

@Composable
fun rememberBackgroundSave(): BackgroundSave {
    val scope = rememberCoroutineScope()
    return remember(scope) { BackgroundSave(scope) }
}

/** Закрытие редактора с проверкой несохранённого: requestClose() вместо onDismiss(). */
class CloseGuard(private val isDirty: () -> Boolean, private val onClose: () -> Unit) {
    var asking by mutableStateOf(false)
        internal set
    var saveError by mutableStateOf<String?>(null)

    /** Идёт сохранение перед закрытием: окно закроется, когда оно кончится. */
    var saving by mutableStateOf(false)
        private set

    fun requestClose() {
        if (saving) return
        if (isDirty()) {
            saveError = null
            asking = true
        } else {
            onClose()
        }
    }

    fun cancel() {
        asking = false
    }

    fun discard() {
        asking = false
        onClose()
    }

    /** save() -> null при успехе или текст ошибки. */
    fun saveAndClose(save: () -> String?) {
        val err = save()
        if (err == null) {
            asking = false
            onClose()
        } else {
            saveError = err
        }
    }

    /** То же для записи в фоне: окно закрывается, только когда запись удалась. */
    fun saveAndCloseAsync(save: AsyncSave) {
        if (saving) return
        saveError = null
        saving = true
        val started = save { err ->
            saving = false
            if (err == null) {
                asking = false
                onClose()
            } else {
                saveError = err
            }
        }
        if (!started) {
            saving = false
            saveError = "Сохранение уже идёт — подождите"
        }
    }
}

@Composable
fun rememberCloseGuard(isDirty: () -> Boolean, onClose: () -> Unit): CloseGuard {
    val dirty = rememberUpdatedState(isDirty)
    val close = rememberUpdatedState(onClose)
    return remember { CloseGuard({ dirty.value() }, { close.value() }) }
}

/** Открытые редакторы с несохранёнными правками — чтобы не потерять их при выходе из программы. */
object UnsavedRegistry {
    val entries = mutableStateMapOf<Any, String>()
}

@Composable
fun TrackUnsaved(name: String, dirty: Boolean) {
    val key = remember { Any() }
    LaunchedEffect(dirty, name) {
        if (dirty) UnsavedRegistry.entries[key] = name else UnsavedRegistry.entries.remove(key)
    }
    DisposableEffect(Unit) {
        onDispose { UnsavedRegistry.entries.remove(key) }
    }
}
