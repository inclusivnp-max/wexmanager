package org.wexclient.app.editor

/**
 * Фоновые разборы редактора (свёртки, структура, индекс слов, быстрые проверки) идут через
 * runInterruptible: отменённая задача — пользователь снова печатает — прерывает поток. Длинный проход
 * по строкам сверяется с этим раз в 1024 шага и бросает работу. Раньше она доделывалась до конца,
 * и на большом файле при наборе копились параллельные разборы одного и того же текста.
 */
internal fun stopIfInterrupted(step: Int) {
    if (step and 1023 == 0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
}
