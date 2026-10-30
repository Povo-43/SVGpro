package dev.svgpro.core.svg

import org.xml.sax.Attributes
import org.xml.sax.SAXParseException
import org.xml.sax.ext.LexicalHandler
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/**
 * JVMテスト用の TokenSource 実装（javax XMLReader / SAX）。
 * 外部DTD/エンティティは無効化（XXE対策）。
 */
class SaxTokenSource(xml: String) : TokenSource {

    companion object {
        /** xml → Token列 */
        fun tokenQueue(xml: String): ArrayDeque<Token> {
            val factory = SAXParserFactory.newInstance()
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.isNamespaceAware = false
            val reader = factory.newSAXParser().xmlReader

            val queue = ArrayDeque<Token>()
            val handler = object : DefaultHandler() {
                private val textBuf = StringBuilder()

                private fun flushText() {
                    if (textBuf.isNotEmpty()) {
                        queue.add(Token.Text(textBuf.toString()))
                        textBuf.setLength(0)
                    }
                }

                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                    flushText()
                    val attrs = LinkedHashMap<String, String>()
                    for (i in 0 until attributes.length) {
                        attrs[attributes.getQName(i)] = attributes.getValue(i)
                    }
                    queue.add(Token.StartTag(qName, attrs, false))
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    flushText()
                    queue.add(Token.EndTag)
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    textBuf.appendRange(ch, start, start + length)
                }
            }
            val lexical = object : LexicalHandler {
                override fun comment(ch: CharArray, start: Int, length: Int) {
                    queue.add(Token.Comment(String(ch, start, length)))
                }
                override fun startCDATA() {}
                override fun endCDATA() {}
                override fun startEntity(name: String?) {}
                override fun endEntity(name: String?) {}
                override fun startDTD(name: String?, publicId: String?, systemId: String?) {}
                override fun endDTD() {}
            }
            reader.contentHandler = handler
            reader.errorHandler = object : DefaultHandler() {
                override fun error(e: SAXParseException) {}
                override fun warning(e: SAXParseException) {}
                override fun fatalError(e: SAXParseException) { throw e }
            }
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", lexical)
            reader.parse(org.xml.sax.InputSource(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))))
            queue.add(Token.EndDocument)
            return queue
        }
    }

    private val queue: ArrayDeque<Token> = try {
        tokenQueue(xml)
    } catch (e: Exception) {
        // 壊れたXML: 空トークン
        ArrayDeque()
    }

    override fun next(): Token = queue.removeFirstOrNull() ?: Token.EndDocument
}
