package org.wexclient.app.doc

import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamReader

private val factory: XMLInputFactory = XMLInputFactory.newInstance().apply {
    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    setProperty(XMLInputFactory.SUPPORT_DTD, false)
}

actual fun openXmlReader(input: InputStream): XmlPullReader = StaxReader(factory.createXMLStreamReader(input))

private class StaxReader(private val r: XMLStreamReader) : XmlPullReader {
    override fun hasNext() = r.hasNext()
    override fun next() = r.next()
    override val localName: String get() = r.localName
    override val text: String get() = r.text
    override val attributeCount: Int get() = r.attributeCount
    override fun getAttributeLocalName(index: Int): String = r.getAttributeLocalName(index)
    override fun getAttributeValue(index: Int): String = r.getAttributeValue(index)
    override fun getAttributeValue(namespace: String?, name: String): String? = r.getAttributeValue(namespace, name)
    override fun close() = r.close()
}
