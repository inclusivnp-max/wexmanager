package org.wexclient.app.doc

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

actual fun openXmlReader(input: InputStream): XmlPullReader {
    val p = Xml.newPullParser()
    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    p.setInput(input, null)
    return PullReader(p)
}

private class PullReader(private val p: XmlPullParser) : XmlPullReader {
    override fun hasNext() = p.eventType != XmlPullParser.END_DOCUMENT

    override fun next(): Int = when (p.next()) {
        XmlPullParser.START_TAG -> XmlEvents.START_ELEMENT
        XmlPullParser.END_TAG -> XmlEvents.END_ELEMENT
        XmlPullParser.TEXT, XmlPullParser.CDSECT -> XmlEvents.CHARACTERS
        XmlPullParser.END_DOCUMENT -> XmlEvents.END_DOCUMENT
        else -> 0
    }

    override val localName: String get() = p.name.orEmpty()
    override val text: String get() = p.text.orEmpty()
    override val attributeCount: Int get() = p.attributeCount.coerceAtLeast(0)
    override fun getAttributeLocalName(index: Int): String = p.getAttributeName(index)
    override fun getAttributeValue(index: Int): String = p.getAttributeValue(index)
    override fun getAttributeValue(namespace: String?, name: String): String? {
        for (i in 0 until attributeCount) {
            if (p.getAttributeName(i) == name && (namespace == null || p.getAttributeNamespace(i) == namespace)) return p.getAttributeValue(i)
        }
        return null
    }

    override fun close() {}
}
