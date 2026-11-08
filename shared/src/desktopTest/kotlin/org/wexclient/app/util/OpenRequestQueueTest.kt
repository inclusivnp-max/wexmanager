package org.wexclient.app.util

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class OpenRequestQueueTest {
    @Test fun `burst requests are delivered once and in order`() = runBlocking {
        val inbox = OpenRequestQueue<Int>()
        repeat(200) { assertTrue(inbox.submit(it)) }
        assertEquals((0 until 200).toList(), withTimeout(3000) { inbox.requests.take(200).toList() })
        inbox.close()
    }
    @Test fun `bounded queue applies backpressure without dropping requests`() = runBlocking {
        val inbox = OpenRequestQueue<Int>(2)
        assertTrue(inbox.submit(1)); assertTrue(inbox.submit(2))
        val started = CountDownLatch(1)
        val pending = async(Dispatchers.IO) { started.countDown(); inbox.submit(3) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        delay(50)
        assertFalse(pending.isCompleted)
        assertEquals(listOf(1, 2, 3), withTimeout(3000) { inbox.requests.take(3).toList() })
        assertTrue(pending.await())
        inbox.close()
    }
    @Test fun `shutdown releases a blocked socket producer`() = runBlocking {
        val inbox = OpenRequestQueue<Int>(1)
        assertTrue(inbox.submit(1))
        val pending = async(Dispatchers.IO) { inbox.submit(2) }
        delay(50)
        assertFalse(pending.isCompleted)
        inbox.close()
        assertFalse(withTimeout(3000) { pending.await() })
        assertFalse(inbox.submit(3))
    }
}
