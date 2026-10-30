package dev.svgpro.core.svg

/**
 * Element ツリー → SVG文字列（XML）。
 * 属性順・子要素の並びをそのまま出力し、可逆性を保つ。
 */
object SvgWriter {

    const val XML_DECL = """<?xml version="1.0" encoding="UTF-8"?>"""
    const val INDENT = "  "

    /** XML宣言つきで出力 */
    fun write(root: Element): String {
        val sb = StringBuilder()
        sb.append(XML_DECL)
        writeElement(sb, root, 0)
        return sb.toString()
    }

    private fun writeElement(sb: StringBuilder, e: Element, depth: Int) {
        sb.append('<').append(e.tag)
        for ((k, v) in e.attrs) {
            sb.append(' ').append(k).append("=\"").append(escape(v)).append('"')
        }
        if (e.children.isEmpty()) {
            sb.append("/>")
            return
        }
        sb.append('>')
        // 1つだけの短いテキストなら行内に納める（style/metadata等の省スペース化）
        val inline = e.children.size == 1 &&
            e.children.first() is Text &&
            (e.children.first() as Text).text.length < 300 &&
            !(e.children.first() as Text).text.contains('\n')

        if (!inline) sb.append('\n')
        for (c in e.children) {
            when (c) {
                is Element -> {
                    if (!inline) for (i in 0 until depth + 1) sb.append(INDENT)
                    writeElement(sb, c, depth + 1)
                    if (!inline) sb.append('\n')
                }
                is Text -> {
                    if (!inline) for (i in 0 until depth + 1) sb.append(INDENT)
                    sb.append(escape(c.text))
                    if (!inline) sb.append('\n')
                }
                is Comment -> {
                    if (!inline) for (i in 0 until depth + 1) sb.append(INDENT)
                    sb.append("<!--").append(c.text).append("-->")
                    if (!inline) sb.append('\n')
                }
            }
        }
        if (depth > 0) for (i in 0 until depth) sb.append(INDENT)
        sb.append("</").append(e.tag).append('>')
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\'' -> sb.append("&#39;")
                c.code < 0x20 && c != '\n' && c != '\t' -> sb.append("&#").append(c.code).append(';')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
