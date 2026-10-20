package org.wexclient.app.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class PasteSafetyTest {
    private val esc = Char(27)

    @Test
    fun `pasted text cannot end bracketed mode or send control keys`() {
        val hostile = "echo hi${esc}[201~\nrm -rf ~\n${Char(3)}${Char(21)}done"
        val out = TerminalSession.preparePaste(hostile, bracketed = true)
        assertEquals("${esc}[200~echo hi[201~\rrm -rf ~\rdone${esc}[201~", out)
        // Единственные ESC — рамка вставки
        assertEquals(2, out.count { it == esc })
    }

    @Test
    fun `line breaks become carriage returns and tabs stay`() {
        assertEquals("a\rb\rc\td", TerminalSession.preparePaste("a\r\nb\nc\td", bracketed = false))
        assertEquals("привет", TerminalSession.preparePaste("при${Char(0x9B)}вет${Char(0x7F)}", bracketed = false))
    }
}
