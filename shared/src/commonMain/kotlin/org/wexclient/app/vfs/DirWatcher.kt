package org.wexclient.app.vfs

import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Слежение за папками на диске (inotify): панель обновляется сама, когда файлы меняют
 * другие программы или встроенная консоль. Частые изменения склеиваются: уведомление
 * приходит после паузы [QUIET_MS], а при непрерывном потоке — не реже раза в [MAX_DELAY_MS].
 */
object DirWatcher {
    const val QUIET_MS = 250L
    const val MAX_DELAY_MS = 1500L

    private class Watch(val dir: Path, val key: WatchKey) {
        val listeners = CopyOnWriteArrayList<() -> Unit>()
        var pending = false
        var firstEventAt = 0L
        var lastEventAt = 0L
    }

    private val lock = Any()
    private var service: WatchService? = null
    private val byDir = HashMap<Path, Watch>()
    private val byKey = HashMap<WatchKey, Watch>()

    private val timer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wex-dir-watch-timer").apply { isDaemon = true } }
    }

    /**
     * Следить за [dir] (только обычный диск). [onChange] вызывается в фоновом потоке.
     * Закрытие возвращённого объекта снимает слежение.
     */
    fun watch(dir: Path, onChange: () -> Unit): AutoCloseable {
        if (dir.fileSystem !== FileSystems.getDefault()) return NOOP
        val abs = dir.toAbsolutePath().normalize()
        synchronized(lock) {
            val ws = service ?: runCatching { FileSystems.getDefault().newWatchService() }.getOrNull()
                ?.also {
                    service = it
                    startLoop(it)
                } ?: return NOOP
            val w = byDir[abs] ?: run {
                val key = try {
                    abs.register(
                        ws,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY
                    )
                } catch (_: Exception) {
                    // Нет прав, папки уже нет, кончились inotify-наблюдатели — просто без автообновления
                    return NOOP
                }
                Watch(abs, key).also {
                    byDir[abs] = it
                    byKey[key] = it
                }
            }
            w.listeners.add(onChange)
        }
        return AutoCloseable { unwatch(abs, onChange) }
    }

    /** Сколько папок сейчас под наблюдением (для тестов). */
    internal fun watchedCount(): Int = synchronized(lock) { byDir.size }

    private fun unwatch(dir: Path, onChange: () -> Unit) {
        synchronized(lock) {
            val w = byDir[dir] ?: return
            w.listeners.remove(onChange)
            if (w.listeners.isEmpty()) {
                w.key.cancel()
                byDir.remove(dir)
                byKey.remove(w.key)
            }
        }
    }

    private fun startLoop(ws: WatchService) {
        Thread({
            while (true) {
                val key = try {
                    ws.take()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: ClosedWatchServiceException) {
                    return@Thread
                }
                key.pollEvents()
                val valid = key.reset()
                val w = synchronized(lock) {
                    byKey[key]?.also {
                        // Папку удалили или отмонтировали — сообщаем сразу и забываем её
                        if (!valid) {
                            byDir.remove(it.dir)
                            byKey.remove(key)
                        }
                    }
                } ?: continue
                if (valid) schedule(w) else fire(w)
            }
        }, "wex-dir-watch").apply {
            isDaemon = true
            start()
        }
    }

    private fun schedule(w: Watch) {
        val now = System.nanoTime()
        synchronized(w) {
            w.lastEventAt = now
            if (w.pending) return
            w.pending = true
            w.firstEventAt = now
        }
        timer.schedule({ check(w) }, QUIET_MS, TimeUnit.MILLISECONDS)
    }

    private fun check(w: Watch) {
        val now = System.nanoTime()
        val wait: Long
        synchronized(w) {
            val sinceLast = (now - w.lastEventAt) / 1_000_000
            val sinceFirst = (now - w.firstEventAt) / 1_000_000
            if (sinceLast >= QUIET_MS || sinceFirst >= MAX_DELAY_MS) {
                w.pending = false
                wait = 0
            } else {
                wait = minOf(QUIET_MS - sinceLast, MAX_DELAY_MS - sinceFirst).coerceAtLeast(10)
            }
        }
        if (wait > 0) timer.schedule({ check(w) }, wait, TimeUnit.MILLISECONDS) else fire(w)
    }

    private fun fire(w: Watch) {
        for (l in w.listeners) {
            try {
                l()
            } catch (_: Exception) {
                // слушатель сам отвечает за свои ошибки
            }
        }
    }

    private val NOOP = AutoCloseable {}
}
