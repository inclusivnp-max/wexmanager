package com.tim.clazz.util

/**
 * Ошибка формата class-файла (JVMS §4) при чтении или записи.
 * Сообщение содержит место ошибки: класс, член, атрибут и смещение.
 */
class ClassFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
