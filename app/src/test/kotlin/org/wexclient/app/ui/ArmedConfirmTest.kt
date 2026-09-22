package org.wexclient.app.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Подтверждение повторным нажатием: двойной щелчок не проходит обе ступени сразу. */
class ArmedConfirmTest {

    @Test
    fun `двойной щелчок только взводит`() {
        val c = ArmedConfirm()
        assertFalse(c.press(now = 1_000))
        assertTrue(c.armed)
        assertFalse(c.press(now = 1_150), "второе нажатие внутри двойного щелчка")
        assertTrue(c.armed)
        assertTrue(c.press(now = 1_000 + ArmedConfirm.MIN_GAP_MS + 50), "осознанное второе нажатие")
        assertFalse(c.armed)
    }

    @Test
    fun `взвод со временем снимается`() {
        val c = ArmedConfirm()
        c.press(now = 0)
        assertFalse(c.press(now = ArmedConfirm.TIMEOUT_MS + 1), "слишком поздно — снова только взвод")
        assertTrue(c.armed)
        assertTrue(c.press(now = ArmedConfirm.TIMEOUT_MS + 1 + ArmedConfirm.MIN_GAP_MS))
    }

    @Test
    fun `сброс снимает взвод`() {
        val c = ArmedConfirm()
        c.press(now = 0)
        c.reset()
        assertFalse(c.press(now = ArmedConfirm.MIN_GAP_MS + 10))
    }
}
