package dev.svgpro.core

import dev.svgpro.core.geom.Bounds
import dev.svgpro.core.geom.Matrix
import dev.svgpro.core.geom.Vec
import dev.svgpro.core.ops.GeometryOps
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MatrixTest {

    @Test
    fun translate_then_apply() {
        val m = Matrix(e = 10.0, f = 5.0)
        val p = m.apply(Vec(1.0, 2.0))
        assertEquals(11.0, p.x)
        assertEquals(7.0, p.y)
    }

    @Test
    fun scale_concat() {
        val m = Matrix(a = 2.0, d = 3.0)
        val p = m.apply(Vec(4.0, 5.0))
        assertEquals(8.0, p.x)
        assertEquals(15.0, p.y)
    }

    @Test
    fun transform_string_parse() {
        val m = Matrix.fromSvgTransform("translate(10,20) scale(2) rotate(90)")
        val p = m.apply(Vec(0.0, 0.0))
        // (0,0)→scale→(0,0)→rotate→(0,0)→translate(10,20)
        assertEquals(10.0, p.x, 0.0001)
        assertEquals(20.0, p.y, 0.0001)
        // (1,0) → scale(2) → (2,0) → rotate90 → (0,2) → translate → (10,22)
        val q = m.apply(Vec(1.0, 0.0))
        assertEquals(10.0, q.x, 0.0001)
        assertEquals(22.0, q.y, 0.0001)
    }

    @Test
    fun invert_roundtrip() {
        val m = Matrix.fromSvgTransform("translate(3,4) rotate(30) scale(2)")
        val inv = m.invert()
        val p = Vec(7.0, -2.0)
        val round = inv.apply(m.apply(p))
        assertTrue(round.distanceTo(p) < 1e-9)
    }

    @Test
    fun rotate_pivot() {
        val m = Matrix.fromSvgTransform("rotate(90 10 10)")
        val p = m.apply(Vec(10.0, 0.0))
        assertEquals(20.0, p.x, 1e-9)
        assertEquals(10.0, p.y, 1e-9)
    }
}

class GeometryOpsTest {

    @Test
    fun union_two_rects() {
        val a = PathData.rect(0.0, 0.0, 10.0, 10.0)
        val b = PathData.rect(5.0, 5.0, 10.0, 10.0)
        val result = GeometryOps.boolean(a, b, GeometryOps.BoolOp.UNION)!!
        val area = abs(estimateArea(result))
        assertTrue(area > 174.0 && area < 176.0, "area=$area")
    }

    @Test
    fun difference_cuts_hole() {
        val a = PathData.rect(0.0, 0.0, 10.0, 10.0)
        val b = PathData.rect(2.0, 2.0, 4.0, 4.0)
        val result = GeometryOps.boolean(a, b, GeometryOps.BoolOp.DIFFERENCE)!!
        val area = abs(estimateArea(result))
        assertTrue(area > 83.0 && area < 85.0, "area=$area")
    }

    @Test
    fun intersection_common_area() {
        val a = PathData.rect(0.0, 0.0, 10.0, 10.0)
        val b = PathData.rect(5.0, 5.0, 10.0, 10.0)
        val result = GeometryOps.boolean(a, b, GeometryOps.BoolOp.INTERSECTION)!!
        val area = abs(estimateArea(result))
        assertTrue(abs(area - 25.0) < 0.2, "area=$area")
    }

    @Test
    fun xor_disjoint_ring() {
        val a = PathData.rect(0.0, 0.0, 10.0, 10.0)
        val b = PathData.rect(2.0, 2.0, 6.0, 6.0)
        val result = GeometryOps.boolean(a, b, GeometryOps.BoolOp.XOR)!!
        val area = abs(estimateArea(result))
        // 100 - 36 + ... (穴扱いなら 100-36=64)
        assertTrue(area > 60.0 && area < 70.0, "area=$area")
    }

    @Test
    fun offset_expand_shrink() {
        val a = PathData.rect(0.0, 0.0, 10.0, 10.0)
        val grown = GeometryOps.offset(a, 2.0)!!
        val gArea = abs(estimateArea(grown))
        assertTrue(gArea > 190.0, "area=$gArea")
        val shrunk = GeometryOps.offset(a, -2.0)
        assertNotNull(shrunk)
        val sArea = abs(estimateArea(shrunk))
        assertTrue(sArea < 45.0 && sArea > 25.0, "area=$sArea")
    }

    @Test
    fun simplify_reduces_points() {
        // 円をフラット化した点列に微小な凸凹を加えた多角形を単純化する
        val base = PathData.ellipse(50.0, 50.0, 30.0, 30.0)
        val pts = base.flatten(0.5).toMutableList()
        // 末尾の閉点（先頭と同座標）を除去してからジッターを付与
        while (pts.size > 2 && pts.last().distanceTo(pts.first()) < 1e-9) pts.removeAt(pts.size - 1)
        val segs = ArrayList<Seg>()
        pts.forEachIndexed { i, p ->
            val jitter = if (i % 2 == 0) 0.4 else -0.4
            val np = Vec(p.x, p.y + jitter)
            if (i == 0) segs.add(Seg.Move(np.x, np.y)) else segs.add(Seg.Line(np.x, np.y))
        }
        segs.add(Seg.Close)
        val pd = PathData(segs)
        val simplified = GeometryOps.simplify(pd, 4.0)
        val out = simplified.flatten(0.01)
        val before = pd.flatten(0.01)
        assertTrue(out.size < before.size, "size=${out.size} before=${before.size}")
    }

    private fun estimateArea(pd: PathData): Double {
        // shoe-lace（フラット化点列）
        val pts = pd.flatten(0.01)
        var area = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            area += a.x * b.y - b.x * a.y
        }
        return area / 2.0
    }
}

class BoundsTest {
    @Test
    fun circle_bounds_approx() {
        val c = PathData.ellipse(50.0, 50.0, 20.0, 10.0)
        val b = c.bounds(0.05)!!
        assertTrue(abs(b.width - 40.0) < 0.5, "w=${b.width}")
        assertTrue(abs(b.height - 20.0) < 0.5, "h=${b.height}")
        assertEquals(30.0, b.x, 0.5)
        assertEquals(40.0, b.y, 0.5)
    }

    @Test
    fun bounds_union_inflate() {
        val b = Bounds(0.0, 0.0, 10.0, 10.0)
        val b2 = b.inflate(2.0, 3.0)
        assertEquals(-2.0, b2.x)
        assertEquals(14.0, b2.width)
        val u = b.union(Bounds(5.0, 5.0, 10.0, 10.0))
        assertEquals(15.0, u.width)
    }
}
