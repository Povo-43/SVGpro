package dev.svgpro

import android.util.Xml
import dev.svgpro.core.svg.Token
import dev.svgpro.core.svg.TokenSource
import org.xmlpull.v1.XmlPullParser

/**
 * Android XmlPullParser → TokenSource。
 * nextToken()ベースでコメント/CDATAも保持し、ラウンドトリップの可逆性を確保する。
 * セキュリティ: DOCTYPE(DocDecl)は拒否する。
 */
class AndroidTokenSource(xml: String) : TokenSource {

    companion object {
        fun tokenQueue(xml: String): ArrayDeque<Token> {
            if (xml.contains("<!DOCTYPE", ignoreCase = true)) {
                throw IllegalArgumentException("DOCTYPE declarations are not allowed")
            }
            val parser = Xml.newPullParser()
            parser.setInput(xml.byteInputStream(), null)
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)

            val queue = ArrayDeque<Token>()
            val openTags = ArrayDeque<String>()
            var text = StringBuilder()

            fun flushText() {
                if (text.isNotEmpty()) {
                    queue.add(Token.Text(text.toString()))
                    text = StringBuilder()
                }
            }

            loop@ while (true) {
                val ev = parser.nextToken()
                when (ev) {
                    XmlPullParser.START_DOCUMENT -> {}
                    XmlPullParser.START_TAG -> {
                        flushText()
                        val attrs = LinkedHashMap<String, String>()
                        for (i in 0 until parser.attributeCount) {
                            val name = parser.getAttributeName(i)
                            val prefix = parser.getAttributePrefix(i)
                            val qname = if (prefix.isNullOrEmpty()) name else "$prefix:$name"
                            attrs[qname] = parser.getAttributeValue(i)
                        }
                        val qname = if (parser.prefix.isNullOrEmpty()) parser.name else "${parser.prefix}:${parser.name}"
                        queue.add(Token.StartTag(qname, attrs, false))
                        openTags.addLast(qname)
                    }
                    XmlPullParser.END_TAG -> {
                        flushText()
                        queue.add(Token.EndTag)
                        openTags.removeLastOrNull()
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> text.append(parser.text ?: "")
                    XmlPullParser.ENTITY_REF -> text.append(parser.text ?: "")
                    XmlPullParser.COMMENT -> {
                        flushText()
                        queue.add(Token.Comment(parser.text ?: ""))
                    }
                    XmlPullParser.DOCDECL -> throw IllegalArgumentException("DTD is not allowed")
                    XmlPullParser.PROCESSING_INSTRUCTION -> {}
                    XmlPullParser.END_DOCUMENT -> break@loop
                    else -> {}
                }
            }
            // 未閉鎖タグへの耐性
            while (openTags.isNotEmpty()) {
                openTags.removeLastOrNull()
                queue.add(Token.EndTag)
            }
            queue.add(Token.EndDocument)
            return queue
        }
    }

    private val queue: ArrayDeque<Token> = try {
        tokenQueue(xml)
    } catch (e: Exception) {
        ArrayDeque()
    }

    override fun next(): Token = queue.removeFirstOrNull() ?: Token.EndDocument
}
