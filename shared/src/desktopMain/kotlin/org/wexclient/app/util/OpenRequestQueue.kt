package org.wexclient.app.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.receiveAsFlow

/** FIFO с обратным давлением: быстрые запуски из Telegram не перезаписывают предыдущий путь. */
internal class OpenRequestQueue<T>(capacity: Int = 256) {
    private val channel = Channel<T>(capacity)
    val requests = channel.receiveAsFlow()
    fun submit(request: T): Boolean = channel.trySendBlocking(request).isSuccess
    fun close() = channel.cancel()
}
