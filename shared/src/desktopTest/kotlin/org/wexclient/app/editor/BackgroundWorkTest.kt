package org.wexclient.app.editor

import org.wexclient.app.editor.lang.Languages
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Фоновые разборы бросают работу, когда их поток прервали (задачу отменили), и работают как раньше без этого. */
class BackgroundWorkTest {
    private val snap = HighlightModel(Languages.detect("a.kt")).also { model ->
        model.reset((0 until 20_000).joinToString("\n") { "fun f$it() {\n    val x = $it\n}" })
    }.snapshot

    @Test
    fun `свёртки и структура останавливаются при прерывании потока`() {
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { FoldingEngine.compute(snap) }
            assertFailsWith<CancellationException> { OutlineEngine.compute(snap, emptyList()) }
            assertFailsWith<CancellationException> { WordIndexer.build(snap.text, snap.language, snap.version) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `без прерывания разбор доходит до конца`() {
        val folds = FoldingEngine.compute(snap)
        assertTrue(folds.size >= 20_000, "${folds.size}")
        assertTrue(OutlineEngine.compute(snap, folds).isNotEmpty())
    }
}
