package org.wexclient.app.ui

/** Прямоугольник плитки. */
data class TmBox(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float): Boolean = px >= x && px < x + w && py >= y && py < y + h
}

/**
 * Раскладка «плитками» (squarified treemap, Bruls и др.): площадь плитки пропорциональна
 * значению, а сами плитки получаются близкими к квадрату — их легко сравнивать на глаз.
 * [values] — по убыванию; нулевые получают пустую плитку. Результат — в том же порядке.
 */
object Treemap {

    fun layout(values: List<Double>, x: Float, y: Float, w: Float, h: Float): List<TmBox> {
        val result = ArrayList<TmBox>(values.size)
        if (values.isEmpty()) return result
        val total = values.sumOf { if (it > 0) it else 0.0 }
        if (total <= 0.0 || w <= 0f || h <= 0f) {
            repeat(values.size) { result.add(TmBox(x, y, 0f, 0f)) }
            return result
        }
        val scale = w.toDouble() * h / total
        val areas = values.map { if (it > 0) it * scale else 0.0 }

        var rx = x.toDouble()
        var ry = y.toDouble()
        var rw = w.toDouble()
        var rh = h.toDouble()
        var i = 0
        while (i < areas.size) {
            if (areas[i] <= 0.0) {
                result.add(TmBox(rx.toFloat(), ry.toFloat(), 0f, 0f))
                i++
                continue
            }
            val side = minOf(rw, rh)
            // Набираем ряд, пока самая вытянутая плитка в нём становится лучше
            var end = i
            var sum = 0.0
            var best = Double.MAX_VALUE
            while (end < areas.size && areas[end] > 0.0) {
                val s = sum + areas[end]
                val worst = worst(areas, i, end, s, side)
                if (worst > best) break
                best = worst
                sum = s
                end++
            }
            if (rw >= rh) {
                // Ряд — столбец слева
                val stripW = if (rh > 0) sum / rh else 0.0
                var cy = ry
                for (k in i until end) {
                    val hk = if (stripW > 0) areas[k] / stripW else 0.0
                    result.add(TmBox(rx.toFloat(), cy.toFloat(), stripW.toFloat(), hk.toFloat()))
                    cy += hk
                }
                rx += stripW
                rw -= stripW
            } else {
                // Ряд — полоса сверху
                val stripH = if (rw > 0) sum / rw else 0.0
                var cx = rx
                for (k in i until end) {
                    val wk = if (stripH > 0) areas[k] / stripH else 0.0
                    result.add(TmBox(cx.toFloat(), ry.toFloat(), wk.toFloat(), stripH.toFloat()))
                    cx += wk
                }
                ry += stripH
                rh -= stripH
            }
            i = end
        }
        return result
    }

    /** Худшее соотношение сторон в ряду areas[from..to] с суммой [sum] вдоль стороны [side]. */
    private fun worst(areas: List<Double>, from: Int, to: Int, sum: Double, side: Double): Double {
        val s2 = side * side
        val sum2 = sum * sum
        var worst = 0.0
        for (k in from..to) {
            val r = areas[k]
            val ratio = maxOf(s2 * r / sum2, sum2 / (s2 * r))
            if (ratio > worst) worst = ratio
        }
        return worst
    }
}
