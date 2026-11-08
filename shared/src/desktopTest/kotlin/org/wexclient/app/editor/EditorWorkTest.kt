package org.wexclient.app.editor

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class EditorWorkTest {
    private fun checkLimit(dispatcher: CoroutineDispatcher, limit: Int) = runBlocking {
        val current = AtomicInteger()
        val peak = AtomicInteger()
        (1..12).map { async(dispatcher) {
            val count = current.incrementAndGet()
            peak.accumulateAndGet(count, ::maxOf)
            try { Thread.sleep(20) } finally { current.decrementAndGet() }
        } }.awaitAll()
        assertTrue(peak.get() in 1..limit, "peak=${peak.get()} limit=$limit")
        assertEquals(0, current.get())
    }
    @Test fun `file preparation is limited across all editor windows`() = checkLimit(EditorWork.loading, 2)
    @Test fun `analysis and external tools cannot expand per window`() {
        checkLimit(EditorWork.analysis, 2)
        checkLimit(EditorWork.externalChecks, 1)
    }
}
