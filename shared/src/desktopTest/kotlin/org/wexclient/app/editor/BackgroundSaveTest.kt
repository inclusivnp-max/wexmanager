package org.wexclient.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.wexclient.app.ui.BackgroundSave
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundSaveTest {
    @Test
    fun `edits made while saving stay dirty`() = runBlocking {
        val ctrl = CodeEditorController("one\n")
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        var written: String? = null
        var writerThread: Thread? = null
        ctrl.saver = { text ->
            writerThread = Thread.currentThread()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            written = text
        }
        val runner = BackgroundSave(this)
        ctrl.applyEdit(TextFieldValue("two\n", TextRange(3)))
        assertTrue(ctrl.isDirty)

        val result = CompletableDeferred<String?>()
        assertTrue(ctrl.saveInBackground(runner) { result.complete(it) })
        assertTrue(runner.busy)
        // Второе сохранение, пока идёт первое, не начинается
        assertFalse(ctrl.saveInBackground(runner) { error("не должно вызываться") })
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) }
        // Правка во время записи
        ctrl.applyEdit(TextFieldValue("three\n", TextRange(5)))
        release.countDown()
        assertNull(result.await())
        assertFalse(runner.busy)
        assertEquals("two\n", written)
        assertTrue(writerThread !== Thread.currentThread(), "запись — не в вызывающем потоке")
        assertTrue(ctrl.isDirty, "правка после снимка не сохранена")

        // Сохранили и последнюю версию — «изменено» снимается
        val second = CompletableDeferred<String?>()
        ctrl.saveInBackground(runner) { second.complete(it) }
        assertNull(second.await())
        assertEquals("three\n", written)
        assertFalse(ctrl.isDirty)
    }

    @Test
    fun `failed save keeps the text dirty and reports the error`() = runBlocking {
        val ctrl = CodeEditorController("a")
        ctrl.saver = { throw java.io.IOException("Диск только для чтения") }
        ctrl.applyEdit(TextFieldValue("ab", TextRange(2)))
        val runner = BackgroundSave(this)
        val result = CompletableDeferred<String?>()
        ctrl.saveInBackground(runner) { result.complete(it) }
        assertEquals("Диск только для чтения", result.await())
        assertEquals("Диск только для чтения", ctrl.saveError)
        assertTrue(ctrl.isDirty)
        assertFalse(runner.busy)
    }

    @Test
    fun `exit waits for a running save`() = runBlocking {
        val release = CountDownLatch(1)
        val runner = BackgroundSave(this)
        val started = CountDownLatch(1)
        runner.run({ started.countDown(); release.await(5, TimeUnit.SECONDS) }) {}
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) }
        assertFalse(BackgroundSave.awaitIdle(100), "запись ещё идёт")
        release.countDown()
        assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BackgroundSave.awaitIdle(5000) })
    }
}
