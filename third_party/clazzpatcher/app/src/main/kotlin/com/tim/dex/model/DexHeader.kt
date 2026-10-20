package com.tim.dex.model

/**
 * Заголовок DEX файла.
 * 
 * Содержит информацию о всём DEX файле: магическое число, контрольную сумму,
 * подпись и смещения ко всем основным секциям.
 * 
 * @property magic Магическое число, идентифицирующее формат DEX ("dex\n035\0" и т.д.)
 * @property checksum Контрольная сумма Adler-32 (исключая первые 12 байт)
 * @property signature Подпись SHA-1 (исключая первые 32 байта)
 * @property fileSize Общий размер файла в байтах
 * @property headerSize Размер заголовка (0x70 для версии 035, 0x78 для версии 041)
 * @property endianTag Тег порядка байтов (0x12345678 для little-endian, 0x78563412 для big-endian)
 * @property linkSize Размер секции связывания (обычно 0)
 * @property linkOff Смещение к секции связывания (обычно 0)
 * @property mapOff Смещение к секции карты
 * @property stringIdsSize Количество строк в таблице строк
 * @property stringIdsOff Смещение к таблице идентификаторов строк
 * @property typeIdsSize Количество типов в таблице типов
 * @property typeIdsOff Смещение к таблице идентификаторов типов
 * @property protoIdsSize Количество прототипов в таблице прототипов
 * @property protoIdsOff Смещение к таблице идентификаторов прототипов
 * @property fieldIdsSize Количество полей в таблице полей
 * @property fieldIdsOff Смещение к таблице идентификаторов полей
 * @property methodIdsSize Количество методов в таблице методов
 * @property methodIdsOff Смещение к таблице идентификаторов методов
 * @property classDefsSize Количество определений классов
 * @property classDefsOff Смещение к таблице определений классов
 * @property dataSize Размер секции данных в байтах
 * @property dataOff Смещение к секции данных
 * @property containerSize Размер контейнера (только для версии 041+)
 * @property headerOffset Смещение заголовка (только для версии 041+)
 */
data class DexHeader(
    var magic: ByteArray = ByteArray(8),
    var checksum: Int = 0,
    var signature: ByteArray = ByteArray(20),
    var fileSize: Int = 0,
    var headerSize: Int = 0x70,
    var endianTag: Int = 0x12345678,
    var linkSize: Int = 0,
    var linkOff: Int = 0,
    var mapOff: Int = 0,
    var stringIdsSize: Int = 0,
    var stringIdsOff: Int = 0,
    var typeIdsSize: Int = 0,
    var typeIdsOff: Int = 0,
    var protoIdsSize: Int = 0,
    var protoIdsOff: Int = 0,
    var fieldIdsSize: Int = 0,
    var fieldIdsOff: Int = 0,
    var methodIdsSize: Int = 0,
    var methodIdsOff: Int = 0,
    var classDefsSize: Int = 0,
    var classDefsOff: Int = 0,
    var dataSize: Int = 0,
    var dataOff: Int = 0,
    var containerSize: Int = 0,
    var headerOffset: Int = 0
)
