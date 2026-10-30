package dev.svgpro.core

import dev.svgpro.core.svg.SaxTokenSource
import dev.svgpro.core.svg.SvgLoader
import dev.svgpro.core.svg.SvgWriter
import dev.svgpro.core.svg.Token
import dev.svgpro.core.svg.TokenSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** TokenSourceヘルパ */
fun parseSvg(doc: String): TokenSource = object : TokenSource {
    private val queue = SaxTokenSource.tokenQueue(doc)
    override fun next(): Token = queue.removeFirstOrNull() ?: Token.EndDocument
}

class SvgRoundtripTest {

    private val sample = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="200" height="120" viewBox="0 0 200 120" version="1.1">
  <defs>
    <linearGradient id="g1" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#ff0000"/>
      <stop offset="1" stop-color="#0000ff" stop-opacity="0.5"/>
    </linearGradient>
  </defs>
  <!-- hello comment -->
  <metadata>meta info</metadata>
  <rect x="10" y="20" width="100" height="50" fill="url(#g1)" stroke="#000" stroke-width="2"/>
  <filter id="blur"><feGaussianBlur stdDeviation="2"/></filter>
  <path d="M10 80 C40 60 60 100 90 80" fill="none" stroke="red"/>
  <style>.cls{fill:#00ff00;}</style>
</svg>""".trimIndent()

    @Test
    fun parse_and_structure() {
        val doc = SvgLoader.load(::parseSvg, sample)
        assertNotNull(doc)
        val root = doc.root
        assertEquals("svg", root.tag)
        assertEquals("200", root.attr("width"))
        val rect = root.firstByTag("rect")!!
        assertEquals("10", rect.attr("x"))
        assertEquals("url(#g1)", rect.attr("fill"))
        // 未知要素・コメント・text保持
        val comment = root.children.any { dev.svgpro.core.svg.Comment::class.isInstance(it) }
        assertTrue(comment)
        assertNotNull(root.firstByTag("filter"))
        assertNotNull(root.firstByTag("style"))
        assertEquals(2, root.firstByTag("linearGradient")!!.childElements().size)
    }

    @Test
    fun roundtrip_preserves_semantics() {
        val doc = SvgLoader.load(::parseSvg, sample)!!
        val out = SvgWriter.write(doc.root)
        val doc2 = SvgLoader.load(::parseSvg, out)!!
        val rect1 = doc.root.firstByTag("rect")!!
        val rect2 = doc2.root.firstByTag("rect")!!
        assertEquals("url(#g1)", rect2.attr("fill"))
        assertEquals("2", rect2.attr("stroke-width"))
        val p1 = doc.root.firstByTag("path")!!.attr("d")
        val p2 = doc2.root.firstByTag("path")!!.attr("d")
        assertEquals(p1, p2)
        // viewBox保存
        assertEquals("0 0 200 120", doc2.root.attr("viewBox"))
        // gradient stops保存
        val g2 = doc2.root.firstByTag("linearGradient")!!
        assertEquals(2, g2.childElements().size)
        assertEquals("#0000ff", g2.childElements()[1].attr("stop-color"))
        assertEquals("0.5", g2.childElements()[1].attr("stop-opacity"))
    }

    @Test
    fun legacy_svg_with_9_version() {
        val legacy = """<svg width="10" height="10" xmlns="http://www.w3.org/2000/svg"><circle cx="5" cy="5" r="4"/></svg>"""
        val doc = SvgLoader.load(::parseSvg, legacy)
        assertNotNull(doc)
        val c = doc.root.firstByTag("circle")
        assertEquals("5", c!!.attr("cx"))
    }
}
