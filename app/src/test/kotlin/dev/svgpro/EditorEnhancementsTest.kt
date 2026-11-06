package dev.svgpro

import dev.svgpro.core.geom.Vec
import dev.svgpro.core.model.Color
import dev.svgpro.core.model.Paint
import dev.svgpro.core.model.StyleUtil
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import dev.svgpro.editor.Editor
import dev.svgpro.editor.ToolType
import dev.svgpro.editor.swapFillStrokeSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 今回の拡張（塗り/線入替・最近使った色・ビュー中央移動・円弧/螺旋作図）の回帰テスト */
class EditorEnhancementsTest {

    private fun newEditor(): Editor {
        val e = Editor()
        e.newDocument(200.0, 200.0)
        return e
    }

    private fun rect(e: Editor, x: Double, y: Double, w: Double, h: Double) {
        e.createShape(ToolType.RECT, Vec(x, y), Vec(x + w, y + h))
    }

    private fun css(argb: String?): Int = Color.parse(argb ?: error("color missing")) ?: error("unparseable: $argb")

    // ―――― 塗りと線の入れ替え ――――

    @Test
    fun swapFillStroke_swaps_attributes() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0)
        val el = e.selection[0]
        e.applyFillToSelection(Paint.Solid(0xFFFF0000.toInt()))
        e.applyStrokeToSelection(Paint.Solid(0xFF0000FF.toInt()))
        e.applyStrokeWidthToSelection(3.0)

        assertTrue(e.swapFillStrokeSelection())
        assertEquals(0xFF0000FF.toInt(), css(StyleUtil.getProperty(el, "fill")))
        assertEquals(0xFFFF0000.toInt(), css(StyleUtil.getProperty(el, "stroke")))
        // 線幅は入れ替え後も維持される
        assertEquals("3", StyleUtil.getProperty(el, "stroke-width"))

        // undo で元に戻る
        e.undo()
        assertEquals(0xFFFF0000.toInt(), css(StyleUtil.getProperty(el, "fill")))
        assertEquals(0xFF0000FF.toInt(), css(StyleUtil.getProperty(el, "stroke")))
    }

    @Test
    fun swapFillStroke_whenOnlyFill_exists_keepsStrokeEmpty() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0)
        val el = e.selection[0]
        e.applyFillToSelection(Paint.Solid(0xFF00FF00.toInt()))
        assertTrue(e.swapFillStrokeSelection())
        assertEquals(0xFF00FF00.toInt(), css(StyleUtil.getProperty(el, "stroke")))
        assertNull(StyleUtil.getProperty(el, "fill"))
    }

    // ―――― 最近使った色 ――――

    @Test
    fun recentColors_movesToFront_andCapsAt12() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0)
        e.applyFillToSelection(Paint.Solid(0xFF111111.toInt()))
        e.applyFillToSelection(Paint.Solid(0xFF222222.toInt()))
        e.applyFillToSelection(Paint.Solid(0xFF111111.toInt()))
        assertEquals(0xFF111111.toInt(), e.recentColors.first())
        assertEquals(2, e.recentColors.size)
        for (i in 0 until 15) {
            e.applyFillToSelection(Paint.Solid(0xFF000000.toInt() + i))
        }
        assertTrue(e.recentColors.size <= 12)
        assertEquals(0xFF000000.toInt() + 14, e.recentColors.first())
    }

    @Test
    fun addColorSwatch_alsoRemembersRecent() {
        val e = newEditor()
        e.addColorSwatch(0xFF334455.toInt())
        assertTrue(e.colorSwatches.contains(0xFF334455.toInt()))
        assertTrue(e.recentColors.contains(0xFF334455.toInt()))
    }

    // ―――― ビュー中央移動（ナビゲーター） ――――

    @Test
    fun centerOnUser_placesUserAtViewportCenter() {
        val e = newEditor()
        e.setViewport(400.0, 300.0)
        val zoomBefore = e.zoom
        e.centerOnUser(Vec(100.0, 50.0))
        val center = e.userToScreen(Vec(100.0, 50.0))
        assertEquals(200.0, center.x, 0.5)
        assertEquals(150.0, center.y, 0.5)
        // 拡大率は不変
        assertEquals(zoomBefore, e.zoom, 1e-6)
    }

    // ―――― 円弧・扇形・螺旋ツールの作図（以前は onDown 未対応で描けなかった） ――――

    @Test
    fun createShape_arc_sector_spiral_producePaths() {
        val e = newEditor()

        e.createShape(ToolType.ARC, Vec(10.0, 10.0), Vec(60.0, 10.0))
        val arc = e.selection[0]
        assertEquals("path", arc.tag)
        val arcD = PathData.parse(arc.attr("d"))
        assertNotNull(arcD)
        assertTrue(arcD.segs.any { it is Seg.Arc || it is Seg.Cubic }, "arc should contain curved segs")

        e.createShape(ToolType.SECTOR, Vec(10.0, 40.0), Vec(60.0, 40.0))
        val sector = e.selection[0]
        val sectorD = PathData.parse(sector.attr("d"))
        assertNotNull(sectorD)
        assertTrue(sectorD.segs.last() is Seg.Close, "sector should be closed")

        e.createShape(ToolType.SPIRAL, Vec(80.0, 10.0), Vec(140.0, 70.0))
        val spiral = e.selection[0]
        assertEquals("path", spiral.tag)
        val spiralD = PathData.parse(spiral.attr("d"))
        assertNotNull(spiralD)
        assertTrue(spiralD.segs.size > 20, "spiral should be densely sampled")

        // 角丸矩形のrxは以前どおり生成される
        e.createShape(ToolType.RRECT, Vec(0.0, 100.0), Vec(50.0, 150.0))
        val rrect = e.selection[0]
        assertEquals("10", rrect.attr("rx"))
    }

    // ―――― スタイル既定値（stroke属性は未設定=None扱い） ――――

    @Test
    fun defaultStroke_isNone() {
        val e = newEditor()
        assertTrue(e.curStroke is Paint.None)
        rect(e, 0.0, 0.0, 5.0, 5.0)
        assertTrue(e.inheritedPaint(e.selection[0], "stroke") is Paint.None)
    }
}
