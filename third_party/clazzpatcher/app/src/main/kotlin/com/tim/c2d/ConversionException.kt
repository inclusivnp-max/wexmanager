package com.tim.c2d

/**
 * Ошибка преобразования .class → DEX. Сообщение всегда указывает место:
 * класс, метод и, если известно, адрес инструкции (pc).
 */
class ConversionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
