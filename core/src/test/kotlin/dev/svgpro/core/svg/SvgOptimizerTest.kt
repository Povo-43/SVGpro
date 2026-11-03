package dev.svgpro.core.svg

import dev.svgpro.core.svg.SaxTokenSource
import dev.svgpro.core.svg.SvgLoader
import dev.svgpro.core.svg.Token
import dev.svgpro.core.svg.TokenSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun parseSvg(doc: String): TokenSource = object : TokenSource {
    private val queue = SaxTokenSource.tokenQueue(doc)
    override fun next(): Token = queue.removeFirstOrNull() ?: Token.EndDocument
}

class SvgOptimizerTest {

    private val sample = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 120">
  <defs>
    <linearGradient id="usedGrad" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#ff0000"/>
      <stop offset="1" stop-color="#0000ff"/>
    </linearGradient>
    <linearGradient id="unusedGrad" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#ff0000"/>
    </linearGradient>
    <clipPath id="usedClip">
      <rect x="0" y="0" width="50" height="50"/>
    </clipPath>
    <clipPath id="unusedClip">
      <circle cx="10" cy="10" r="5"/>
    </clipPath>
  </defs>
  <rect x="10" y="20" width="100" height="50" fill="url(#usedGrad)" clip-path="url(#usedClip)"/>
  <rect x="10" y="90" width="40" height="40" fill="red" id="dup"/>
  <rect x="60" y="90" width="40" height="40" fill="blue" id="dup"/>
</svg>""".trimIndent()

    @Test
    fun validate_detects_duplicate_and_dangling_and_unused() {
        val doc = SvgLoader.load(::parseSvg, sample)!!
        val result = SvgOptimizer.validate(doc.root)
        assertEquals(listOf("dup"), result.duplicateIds)
        assertTrue(result.unusedDefIds.contains("unusedGrad"))
        assertTrue(result.unusedDefIds.contains("unusedClip"))
        assertFalse(result.isValid)
    }

    @Test
    fun dedupeIds_makes_ids_unique_and_updates_refs() {
        val doc = SvgLoader.load(::parseSvg, sample)!!
        val changed = SvgOptimizer.dedupeIds(doc.root)
        assertTrue(changed)
        val after = SvgOptimizer.validate(doc.root)
        assertTrue(after.duplicateIds.isEmpty())
        // 未使用defはそのまま（dedupeは重複のみ）
        assertNotNull(doc.root.findById("unusedGrad"))
        // 参照先は更新済み：dangling参照は無いのでURLは保持
        val fills = doc.root.childElements().mapNotNull { it.attr("fill") }
        assertTrue(fills.any { it.startsWith("url(#usedGrad") })
    }

    @Test
    fun removeUnusedDefs_removes_only_unused() {
        val doc = SvgLoader.load(::parseSvg, sample)!!
        val changed = SvgOptimizer.removeUnusedDefs(doc.root)
        assertTrue(changed)
        assertNotNull(doc.root.findById("usedGrad"))
        assertNotNull(doc.root.findById("usedClip"))
        assertNull(doc.root.findById("unusedGrad"))
        assertNull(doc.root.findById("unusedClip"))
    }

    @Test
    fun optimize_changes_when_duplicate_or_unused() {
        val doc = SvgLoader.load(::parseSvg, sample)!!
        val res = SvgOptimizer.optimize(doc.root)
        assertTrue(res.changed)
        // 重複ID付け直しと未使用def削除により、最適化後はバリデーションを通過する
        assertTrue(res.validation.isValid)
        assertTrue(res.validation.duplicateIds.isEmpty())
        assertTrue(res.validation.danglingRefs.isEmpty())
    }

    @Test
    fun optimize_noop_when_already_clean() {
        val clean = """<svg viewBox="0 0 100 100"><rect x="1" y="1" width="10" height="10" fill="red" id="a"/></svg>"""
        val doc = SvgLoader.load(::parseSvg, clean)!!
        val res = SvgOptimizer.optimize(doc.root)
        assertFalse(res.changed)
        assertTrue(res.validation.isValid)
    }
}
