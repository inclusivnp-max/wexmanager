package org.wexclient.app.doc

import java.io.InputStream

/**
 * Потоковое чтение XML — то немногое, что нужно разбору DOCX, XLSX и PPTX. Десктоп — StAX (javax.xml.stream),
 * Android — встроенный XmlPullParser (StAX в Android нет). Внешние сущности и DTD не читаются.
 */
interface XmlPullReader : AutoCloseable {
    fun hasNext(): Boolean

    /** Следующее событие: [XmlEvents.START_ELEMENT], [XmlEvents.END_ELEMENT], [XmlEvents.CHARACTERS] или прочее. */
    fun next(): Int

    /** Имя элемента без префикса пространства имён. */
    val localName: String
    val text: String
    val attributeCount: Int
    fun getAttributeLocalName(index: Int): String
    fun getAttributeValue(index: Int): String

    /** Атрибут по имени; [namespace] null — в любом пространстве имён. */
    fun getAttributeValue(namespace: String?, name: String): String?
}

/** Номера событий — те же, что у StAX (XMLStreamConstants). */
object XmlEvents {
    const val START_ELEMENT = 1
    const val END_ELEMENT = 2
    const val CHARACTERS = 4
    const val END_DOCUMENT = 8
}

expect fun openXmlReader(input: InputStream): XmlPullReader
