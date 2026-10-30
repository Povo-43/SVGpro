package dev.svgpro.core

import dev.svgpro.core.geom.Vec
import dev.svgpro.core.path.NodeKind
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import dev.svgpro.core.path.SegType
import dev.svgpro.core.path.SubpathNodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs

class PathParserTest {

    @Test
    fun parse_basic() {
        val pd = PathData.parse("M10 20 L30 40 C50 60 70 80 90 100")!!
        assertEquals(3, pd.segs.size)
        assertEquals(Seg.Move(10.0, 20.0), pd.segs[0])
        assertEquals(Seg.Line(30.0, 40.0), pd.segs[1])
        val c = pd.segs[2] as Seg.Cubic
        assertEquals(50.0, c.c1x)
        assertEquals(100.0, c.y)
    }

    @Test
    fun parse_relative_and_shorthands() {
        val pd = PathData.parse("M10 20 l10 10 l10 -5 H30 V50")!!
        assertEquals(
            listOf(
                Seg.Move(10.0, 20.0),
                Seg.Line(20.0, 30.0),
                Seg.Line(30.0, 25.0),
                Seg.Line(30.0, 25.0, Seg.Line.FORM_H),
                Seg.Line(30.0, 50.0, Seg.Line.FORM_V),
            ),
            pd.segs.toList(),
        )
    }

    @Test
    fun closed_path_subpaths() {
        val pd = PathData.parse("M0 0 H10 V10 L0 10 Z")!!
        assertEquals(1, pd.subpaths().size)
        assertTrue(pd.subpaths()[0].closed)
        val pd2 = PathData.parse("M0 0 L5 0 Z M10 10 L20 10")!!
        assertEquals(2, pd2.subpaths().size)
    }

    @Test
    fun arc_parsed_and_flattened() {
        val pd = PathData.parse("M0 0 A10 10 0 0 1 20 0")!!
        val pts = pd.flatten(0.005)
        assertTrue(pts.size > 4)
        // sweep=1で半円（SVG座標系はy下向き、sweep=1で時計回り）: x=10,y=-10あたりを通る
        val minY = pts.minOf { it.y }
        assertTrue(minY < -9.5, "minY=$minY")
        val last = pts.last()
        assertTrue(abs(last.x - 20.0) < 0.05 && abs(last.y) < 0.05)
    }

    @Test
    fun malformed_path_partial() {
        val pd = PathData.parse("M1 2 L3 4 C5 6")
        // 数値欠損でも// 解析済み部分は保持
        assertNotNull(pd)
        assertTrue(pd.segs.size >= 2)
    }

    @Test
    fun write_and_read_roundtrip() {
        val pd = PathData.parse("M 0,0 L 10,0 L 10,10 Z")!!
        val text = pd.write()
        val pd2 = PathData.parse(text)!!
        assertEquals(pd.segs.size, pd2.segs.size)
        val p1 = pd.flatten(0.001)
        val p2 = pd2.flatten(0.001)
        assertEquals(p1.map { it.x }, p2.map { it.x })
        assertEquals(p1.map { it.y }, p2.map { it.y })
    }

    @Test
    fun reversed_flattening_preserves的整体_length() {
        val pd = PathData.parse("M0 0 C10 20 20 40 30 10 L50 10")!!
        val l1 = pd.length()
        val rev = pd.reversed()
        val l2 = rev.length()
        assertTrue(abs(l1 - l2) < 0.5, "l1=$l1 l2=$l2")
        // 反転後の起点は元の終点
        val first = rev.segs[0] as Seg.Move
        assertEquals(50.0, first.x)
        assertEquals(10.0, first.y)
    }
}

class SubPathNodesTest {

    @Test
    fun model_derivation_and_edit() {
        val pd = PathData.parse("M0 0 L10 0 C20 -10 30 -10 40 0 Z")!!
        val model = SubpathNodes.fromSubpath(pd, pd.subpaths()[0])!!
        assertEquals(3, model.nodes.size)
        assertEquals(3, model.segTypes.size) // 閉: nodes数とsegTypes数同一
        assertEquals(SegType.LINE, model.segTypeOf(0))
        assertEquals(SegType.CUBIC, model.segTypeOf(1))
        assertEquals(SegType.CUBIC, model.segTypeOf(2))
        assertEquals(SegType.LINE, model.segTypeOf(3))

        // ノード0の移動はハンドルと接続セグメントに反映される
        model.moveNode(0, Vec(5.0, 5.0))
        val segs = SubpathNodes.toSegs(model)
        val pd2 = PathData.fromSegs(segs)
        val mv = pd2.segs[0] as Seg.Move
        assertEquals(5.0, mv.x)
        assertEquals(5.0, mv.y)
        // 閉の最終セグメント（ノード2→ノード0）はノード0へ結線（元の最終描画segがCubicのためCUBIC）
        val last = pd2.segs[pd2.segs.size - 2] as Seg.Cubic
        assertEquals(5.0, last.x)
        assertEquals(5.0, last.y)
    }

    @Test
    fun insert_and_delete_node() {
        val pd = PathData.parse("M0 0 L10 0 L10 10 Z")!!
        val model = SubpathNodes.fromSubpath(pd, pd.subpaths()[0])!!
        val idx = model.insertNodeAt(0, 0.5)
        assertEquals(1, idx)
        assertEquals(4, model.nodes.size)
        val segs = SubpathNodes.toSegs(model)
        // 新しいノードは (5,0)
        assertTrue(segs.any { it is Seg.Line && it.x == 5.0 && it.y == 0.0 })
        assertTrue(model.removeNode(idx))
        assertEquals(3, model.nodes.size)
        val segs2 = SubpathNodes.toSegs(model)
        val p2 = PathData.fromSegs(segs2)
        // 復元後も3点が残る: (0,0),(10,0),(10,10)
        val pts = listOf(Vec(0.0, 0.0), Vec(10.0, 0.0), Vec(10.0, 10.0))
        pts.forEach { v ->
            assertTrue(p2.flatten().any { it.distanceTo(v) < 1e-6 }, "$v not found in ${p2.flatten()}")
        }
    }

    @Test
    fun smooth_conversion() {
        val pd = PathData.parse("M0 0 L10 0 L20 10")!!
        val model = SubpathNodes.fromSubpath(pd, pd.subpaths()[0])!!
        assertEquals(NodeKind.CORNER, model.nodeKind(1))
        model.convertKind(1, NodeKind.SMOOTH)
        val n = model.nodes[1]
        val a = n.cin!! - n.p
        val b = n.cout!! - n.p
        assertTrue(abs(a.cross(b)) < 1e-6, "not collinear")
        val segs = SubpathNodes.toSegs(model)
        assertTrue(segs[1] is Seg.Cubic)
    }
}
