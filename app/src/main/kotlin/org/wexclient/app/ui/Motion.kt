package org.wexclient.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

/**
 * Единые длительности движения. Раньше у похожих окон были разные значения (150/160/170/180 мс),
 * и одно и то же действие ощущалось по-разному. Здесь один набор на всю программу.
 */
object Motion {
    /**
     * Выключатель из настроек: с нулевой длительностью всё происходит мгновенно,
     * а сами вызовы анимаций остаются на месте — правок в двух сотнях мест не нужно.
     */
    private val on: Boolean get() = ViewPreferences.animations

    val ENTER: Int get() = if (on) 200 else 0
    val EXIT: Int get() = if (on) 160 else 0
    /** Быстрая смена цвета: подсветка строки, чипы вкладок. */
    val TINT: Int get() = if (on) 120 else 0

    /**
     * Переход между папками в панели. Содержимое готово за единицы миллисекунд, поэтому движение
     * короткое: длинный проезд делал файловый менеджер «медленным» на ровном месте.
     */
    val NAV: Int get() = if (on) 110 else 0
    val NAV_FADE: Int get() = if (on) 90 else 0

    /** Появление карточки или диалога: чуть пружинит, но не «кисельно». */
    val dialogEnter: EnterTransition
        get() = if (!on) fadeIn(tween(0)) else fadeIn(tween(ENTER)) + scaleIn(
            initialScale = 0.96f,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
        )

    val dialogExit: ExitTransition
        get() = if (!on) fadeOut(tween(0)) else
            fadeOut(tween(EXIT)) + scaleOut(targetScale = 0.97f, animationSpec = tween(EXIT))

    val panelEnter: EnterTransition get() = fadeIn(tween(ENTER, easing = FastOutSlowInEasing))
    val panelExit: ExitTransition get() = fadeOut(tween(EXIT, easing = FastOutSlowInEasing))

    /** Плотность затемнения под окнами-накладками. */
    const val SCRIM = 0.45f
}
