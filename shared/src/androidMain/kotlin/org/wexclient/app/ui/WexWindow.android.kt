package org.wexclient.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.DpSize
import java.util.concurrent.atomic.AtomicLong

/**
 * Окна-инструменты на Android. Каждый [WexWindow] — слой во весь экран; открытые лежат стопкой,
 * видно верхний. Нижние остаются в композиции (но не рисуются): окно, открытое из другого окна,
 * живёт, пока жив его хозяин, — как на десктопе.
 */
class WindowLayers {
    internal class Layer(
        val id: Long,
        val title: State<String>,
        val close: State<() -> Unit>,
        val preview: State<(KeyEvent) -> Boolean>,
        val key: State<(KeyEvent) -> Boolean>,
        val content: State<@Composable () -> Unit>
    ) {
        /** Появление слоя (уходит он сразу: окно убирает хозяин, перестав его вызывать). */
        val visibility = MutableTransitionState(false).apply { targetState = true }
    }

    internal val layers = mutableStateListOf<Layer>()

    /** Заголовок верхнего окна (для полосы состояния) или null — окон нет. */
    val topTitle: String? get() = layers.lastOrNull()?.title?.value

    val isEmpty: Boolean get() = layers.isEmpty()
}

val LocalWindowLayers = staticCompositionLocalOf<WindowLayers?> { null }

private val nextId = AtomicLong()

@Composable
actual fun WexWindow(
    onCloseRequest: () -> Unit,
    title: String,
    size: DpSize,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable () -> Unit
) {
    val host = LocalWindowLayers.current ?: error("WexWindow без WindowLayersHost")
    val titleState = rememberUpdatedState(title)
    val closeState = rememberUpdatedState(onCloseRequest)
    val previewState = rememberUpdatedState(onPreviewKeyEvent)
    val keyState = rememberUpdatedState(onKeyEvent)
    val contentState = rememberUpdatedState(content)
    DisposableEffect(host) {
        val layer = WindowLayers.Layer(nextId.incrementAndGet(), titleState, closeState, previewState, keyState, contentState)
        host.layers += layer
        onDispose { host.layers.remove(layer) }
    }
}

/**
 * Место, где рисуются окна: поверх [content] (файлового менеджера). Ставится один раз в корне Activity.
 */
@Composable
fun WindowLayersHost(layers: WindowLayers, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWindowLayers provides layers) {
        Box(Modifier.fillMaxSize()) {
            content()
            val all = layers.layers.toList()
            all.forEachIndexed { index, layer ->
                val top = index == all.lastIndex
                androidx.compose.runtime.key(layer.id) {
                    WindowLayer(layer, top)
                }
            }
        }
    }
}

@Composable
private fun WindowLayer(layer: WindowLayers.Layer, top: Boolean) {
    val focus = remember { FocusRequester() }
    // Нижние слои живут (их окна-дети на них держатся), но не измеряются и не рисуются
    val hidden = Modifier.layout { measurable, constraints ->
        if (top) {
            val p = measurable.measure(constraints)
            layout(p.width, p.height) { p.place(0, 0) }
        } else {
            layout(0, 0) {}
        }
    }
    Box(hidden.fillMaxSize()) {
        AnimatedVisibility(
            visibleState = layer.visibility,
            enter = fadeIn(tween(Motion.ENTER)) + slideInVertically(tween(Motion.ENTER)) { it / 12 },
            exit = fadeOut(tween(Motion.EXIT)) + slideOutVertically(tween(Motion.EXIT)) { it / 12 }
        ) {
            // Окно — во весь экран, но содержимое не под строкой состояния и полосой жестов
            Box(
                Modifier.fillMaxSize()
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
                    .safeDrawingPadding()
                    .onPreviewKeyEvent { layer.preview.value(it) }
                    .onKeyEvent { layer.key.value(it) }
                    .focusRequester(focus)
                    .focusable()
            ) {
                layer.content.value()
            }
        }
    }
    if (top) {
        BackHandler { layer.close.value() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}
