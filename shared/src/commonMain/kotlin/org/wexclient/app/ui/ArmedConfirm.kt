package org.wexclient.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Подтверждение повторным нажатием той же кнопки: первое «взводит» (кнопка спрашивает «Точно?»),
 * второе выполняет. Второе засчитывается не раньше чем через [MIN_GAP_MS] — двойной щелчок
 * не проходит обе ступени сразу. Через [TIMEOUT_MS] взвод снимается сам.
 */
@Stable
class ArmedConfirm {
    var armed by mutableStateOf(false)
        private set
    private var armedAt = 0L

    /** true — это подтверждение, можно выполнять. */
    fun press(now: Long = System.currentTimeMillis()): Boolean {
        val since = now - armedAt
        if (armed && since in MIN_GAP_MS..TIMEOUT_MS) {
            armed = false
            return true
        }
        // Повтор внутри двойного щелчка взвод не продлевает и не выполняет
        if (!armed || since > TIMEOUT_MS) {
            armed = true
            armedAt = now
        }
        return false
    }

    fun reset() {
        armed = false
    }

    companion object {
        const val MIN_GAP_MS = 600L
        const val TIMEOUT_MS = 8_000L
    }
}

@Composable
fun rememberArmedConfirm(): ArmedConfirm {
    val confirm = remember { ArmedConfirm() }
    LaunchedEffect(confirm.armed) {
        if (confirm.armed) {
            delay(ArmedConfirm.TIMEOUT_MS)
            confirm.reset()
        }
    }
    return confirm
}
