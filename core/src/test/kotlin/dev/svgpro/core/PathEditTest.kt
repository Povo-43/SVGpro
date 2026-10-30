package dev.svgpro.core

import dev.svgpro.core.geom.Vec
import dev.svgpro.core.path.NodeRef
import dev.svgpro.core.path.NodePt
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import dev.svgpro.core.path.SegType
import dev.svgpro.core.path.SubpathNodes
import dev.svgpro.core.path.distributeNodePositions
import dev.svgpro.core.path.isSegmentCurve
import dev.svgpro.core.path.joinOwnEnds
import dev.svgpro.core.path.joinSubpathEndNodes
import dev.svgpro.core.path.nodeBounds
import dev.svgpro.core.path.reduceNodes
import dev.svgpro.core.path.removeSegment
import dev.svgpro.core.path.removeSegments
import dev.svgpro.core.path.setSegmentCurve
import dev.svgpro.core.path.setSegmentLine
import dev.svgpro.core.path.splitAtNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 高度なノード編集プリミティブのテスト */
class PathEditTest {

    private fun openSquare(): SubpathNodes {
        val nodes = ArrayList<NodePt>()
        nodes.add(NodePt(Vec(0.0, 0.0), null, null))
        nodes.add(NodePt(Vec(10.0, 0.0), null, null))
        nodes.add(NodePt(Vec(10.0, 10.0), null, null))
        nodes.add(NodePt(Vec(0.0, 10.0), null, null))
        val types = ArrayList(listOf(SegType.LINE, SegType.LINE, SegType.LINE))
        return SubpathNodes(nodes, types, closed = false)
    }

    private fun closedSquare(): SubpathNodes {
        val m = openSquare()
        m.closeSubpath()
        return m
    }

    @Test
    fun segment_line_curve_conversion() {
        val m = closedSquare()
        assertFalse(m.isSegmentCurve(0))
        m.setSegmentCurve(0)
        assertTrue(m.isSegmentCurve(0))
        // ハンドルが直線上に配置され、形状が保存される
        val a = m.nodes[0]
        val b = m.nodes[1]
        val cOut = a.cout!!
        val cIn = b.cin!!
        // cOut は (0,0)→(10,0) 上、cIn も同様（y≈0）
        assertTrue(kotlin.math.abs(cOut.y) < 1e-9 && cOut.x > 0.0 && cOut.x < 10.0, "cOut=$cOut")
        assertTrue(kotlin.math.abs(cIn.y) < 1e-9 && cIn.x > 0.0 && cIn.x < 10.0, "cIn=$cIn")
        m.setSegmentLine(0)
        assertFalse(m.isSegmentCurve(0))
        val d = PathData.fromSegs(SubpathNodes.toSegs(m)).write()
        assertTrue(d.contains("L"), d)
    }

    @Test
    fun remove_segment_closes_path_to_open_chain() {
        val m = closedSquare()
        val parts = m.removeSegment(1) // セグメント node1→node2 を削除
        assertNotNull(parts)
        assertEquals(1, parts.size)
        val open = parts[0]
        assertFalse(open.closed)
        assertEquals(4, open.nodeCount)
        assertEquals(3, open.segmentCount)
        // 開始点は node2 (10,10)
        assertEquals(Vec(10.0, 10.0), open.nodes[0].p)
        // 鎖の終点は node1 (10,0)
        assertEquals(Vec(10.0, 0.0), open.nodes.last().p)
        // 書き戻し整合
        val segs = SubpathNodes.toSegs(open)
        assertEquals(4, segs.size) // M + L×3 + (Z無し)
        assertFalse(segs.last() is Seg.Close)
    }

    @Test
    fun remove_segment_splits_open_path() {
        val m = openSquare()
        val parts = m.removeSegment(1)
        assertNotNull(parts)
        assertEquals(2, parts.size)
        assertEquals(2, parts[0].nodeCount) // (0,0)-(10,0)
        assertEquals(2, parts[1].nodeCount) // (10,10)-(0,10)
    }

    @Test
    fun remove_multiple_segments_from_ring() {
        val m = closedSquare() // 4ノード4セグメント
        // セグメント1と3を削除 → 2つのchain
        val parts = m.removeSegments(setOf(1, 3))
        assertEquals(2, parts.size)
        for (p in parts) {
            assertFalse(p.closed)
            assertEquals(2, p.nodeCount)
            assertEquals(1, p.segmentCount)
        }
        // 全セグメント削除 → 空
        assertTrue(closedSquare().removeSegments(setOf(0, 1, 2, 3)).isEmpty())
        // 連続削除 → 1chain
        val m2 = closedSquare()
        val parts2 = m2.removeSegments(setOf(0, 1))
        assertEquals(1, parts2.size)
        assertEquals(3, parts2[0].nodeCount)
    }

    @Test
    fun join_own_ends_closes_path() {
        val m = openSquare()
        assertTrue(m.joinOwnEnds())
        assertTrue(m.closed)
        assertEquals(4, m.segmentCount)
        val segs = SubpathNodes.toSegs(m)
        assertTrue(segs.last() is Seg.Close)
    }

    @Test
    fun split_at_node_duplicates_node() {
        val m = closedSquare()
        val parts = m.splitAtNode(2)
        assertEquals(1, parts.size)
        val open = parts[0]
        assertFalse(open.closed)
        assertEquals(5, open.nodeCount) // 4 + 複製1
        assertEquals(4, open.segmentCount)
        // 始点と終点が同じ位置
        assertEquals(open.nodes.first().p, open.nodes.last().p)
        // 全セグメントの形状が保存されている（flatten長さがほぼ同じ）
        val before = PathData.fromSegs(SubpathNodes.toSegs(closedSquare())).length()
        val after = PathData.fromSegs(SubpathNodes.toSegs(open)).length()
        assertTrue(kotlin.math.abs(before - after) < 0.5, "before=$before after=$after")

        // 開パスの中央で分割 → 2サブパス
        val open2 = openSquare()
        val parts2 = open2.splitAtNode(2)
        assertEquals(2, parts2.size)
        assertEquals(3, parts2[0].nodeCount) // (0,0)-(10,0)-(10,10)
        assertEquals(2, parts2[1].nodeCount) // (10,10)-(0,10)
    }

    @Test
    fun reduce_nodes_keeps_shape_within_tolerance() {
        // 折れ線に微小な中間点を挟んだパス → tol大きめで削減される
        val nodes = ArrayList<NodePt>()
        val pts = listOf(Vec(0.0, 0.0), Vec(5.0, 0.01), Vec(10.0, 0.0), Vec(15.0, 0.02), Vec(20.0, 0.0))
        for (p in pts) nodes.add(NodePt(p, null, null))
        val types = ArrayList<SegType>()
        types.addAll(listOf(SegType.LINE, SegType.LINE, SegType.LINE, SegType.LINE))
        val m = SubpathNodes(nodes, types, closed = false)
        val removed = m.reduceNodes(0.5)
        assertTrue(removed >= 3, "removed=$removed")
        assertEquals(2, m.nodeCount)
    }

    @Test
    fun reduce_nodes_preserves_strong_corners() {
        val m = openSquare() // しっかり曲がる角 → 削られない
        val removed = m.reduceNodes(0.5)
        assertEquals(0, removed)
        assertEquals(4, m.nodeCount)
    }

    @Test
    fun join_subpath_end_nodes_merges_two_open_subpaths() {
        val a = openSquare() // (0,0)→(10,0)→(10,10)→(0,10)
        val bNodes = ArrayList<NodePt>()
        bNodes.add(NodePt(Vec(0.0, 10.0), null, null)) // aの終点と同位置（退化接続でもデータ整合を保つ）
        bNodes.add(NodePt(Vec(-5.0, 15.0), null, null))
        bNodes.add(NodePt(Vec(-10.0, 10.0), null, null))
        val b = SubpathNodes(bNodes, ArrayList(listOf(SegType.LINE, SegType.LINE)), closed = false)
        val models = ArrayList(listOf(a, b))
        // aの末尾(node3) と bの先頭(node0) を接続 → 両ノード保持で1本に
        val joined = joinSubpathEndNodes(models, NodeRef(0, 3), NodeRef(1, 0))
        assertNotNull(joined)
        assertEquals(1, models.size)
        val merged = models[0]
        assertEquals(7, merged.nodeCount) // 4 + 3（両端点保持）
        assertEquals(6, merged.segmentCount) // 3 + 接続1 + 2
        assertFalse(merged.closed)
        val segs = SubpathNodes.toSegs(merged)
        assertTrue(segs.first() is Seg.Move)
        // 統合後も全ノード位置が保存されている
        val positions = merged.nodes.map { it.p }
        assertTrue(positions.contains(Vec(-10.0, 10.0)))
        assertTrue(positions.contains(Vec(0.0, 0.0)))
    }

    @Test
    fun join_same_subpath_endpoints_closes() {
        val m = openSquare()
        val models = ArrayList(listOf(m))
        val joined = joinSubpathEndNodes(models, NodeRef(0, 0), NodeRef(0, 3))
        assertNotNull(joined)
        assertEquals(1, models.size)
        assertTrue(models[0].closed)
    }

    @Test
    fun distribute_node_positions_even_spacing() {
        val positions = listOf(Vec(0.0, 0.0), Vec(3.0, 1.0), Vec(4.0, 2.0), Vec(10.0, 5.0))
        val out = distributeNodePositions(positions, horizontal = true)
        assertNotNull(out)
        // x = 0, 3.33, 6.67, 10 の等間隔。yは不変
        assertTrue(kotlin.math.abs(out[1].x - 10.0 / 3) < 1e-9)
        assertTrue(kotlin.math.abs(out[2].x - 20.0 / 3) < 1e-9)
        assertEquals(1.0, out[1].y)
        assertEquals(10.0, out[3].x)
        assertNull(distributeNodePositions(positions.take(2), true))
    }

    @Test
    fun node_bounds_helper() {
        val b = nodeBounds(listOf(Vec(1.0, 2.0), Vec(4.0, -1.0), Vec(2.0, 3.0)))
        assertNotNull(b)
        assertEquals(1.0, b.x)
        assertEquals(-1.0, b.y)
        assertEquals(3.0, b.width)
        assertEquals(4.0, b.height)
    }
}
