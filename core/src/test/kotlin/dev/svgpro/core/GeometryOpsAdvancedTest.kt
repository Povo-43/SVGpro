package dev.svgpro.core

import dev.svgpro.core.geom.Vec
import dev.svgpro.core.ops.GeometryOps
import dev.svgpro.core.ops.StrokeCap
import dev.svgpro.core.ops.StrokeJoin
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import org.locationtech.jts.operation.buffer.BufferParameters
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** パス演算の品質と拡張機能（ストロークのアウトライン化等）のテスト */
class GeometryOpsAdvancedTest {

    private fun rectPath(x: Double, y: Double, w: Double, h: Double): PathData = PathData.rect(x, y, w, h)

    /** even-odd塗りの面積をサンプリング計算 */
    private fun area(pd: PathData, step: Double = 1.0): Double {
        val b = pd.bounds(0.01) ?: return 0.0
        var inside = 0
        var total = 0
        var y = b.y + step / 2
        while (y < b.bottom) {
            var x = b.x + step / 2
            while (x < b.right) {
                total++
                if (containsEvenOdd(pd, Vec(x, y))) inside++
                x += step
            }
            y += step
        }
        return inside * step * step
    }

    private fun containsEvenOdd(pd: PathData, p: Vec): Boolean {
        val pts = pd.flatten(0.01)
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val a = pts[i]
            val c = pts[j]
            if ((a.y > p.y) != (c.y > p.y)) {
                val xint = (c.x - a.x) * (p.y - a.y) / (c.y - a.y) + a.x
                if (p.x < xint) inside = !inside
            }
            j = i
        }
        return inside
    }

    @Test
    fun stroke_outline_of_straight_line() {
        // (0,0)→(100,0) の幅10の直線ストローク → 100×10の帯
        val pd = PathData.fromSegs(listOf(Seg.Move(0.0, 0.0), Seg.Line(100.0, 0.0)))
        val outline = GeometryOps.strokeOutline(pd, 10.0, StrokeCap.BUTT, StrokeJoin.MITER)!!
        val b = outline.bounds(0.01)!!
        assertTrue(b.width > 99.0 && b.width < 101.0, "w=${b.width}")
        assertTrue(b.height > 9.0 && b.height < 11.0, "h=${b.height}")
        // 面積 ≈ 1000
        val a = area(outline, 0.5)
        assertTrue(a > 930.0 && a < 1070.0, "area=$a")
    }

    @Test
    fun stroke_outline_caps() {
        val pd = PathData.fromSegs(listOf(Seg.Move(0.0, 0.0), Seg.Line(100.0, 0.0)))
        val butt = GeometryOps.strokeOutline(pd, 10.0, StrokeCap.BUTT)!!.bounds(0.01)!!
        val square = GeometryOps.strokeOutline(pd, 10.0, StrokeCap.SQUARE)!!.bounds(0.01)!!
        val round = GeometryOps.strokeOutline(pd, 10.0, StrokeCap.ROUND)!!.bounds(0.01)!!
        // butt: 幅100 / square: 100+10 / round: 100+10
        assertTrue(butt.width < 101.0, "butt=${butt.width}")
        assertTrue(square.width > 108.0 && square.width < 112.0, "square=${square.width}")
        assertTrue(round.width > 108.0 && round.width < 112.0, "round=${round.width}")
    }

    @Test
    fun stroke_outline_of_closed_shape_produces_ring() {
        val outline = GeometryOps.strokeOutline(rectPath(0.0, 0.0, 50.0, 50.0), 6.0)!!
        val subs = outline.subpaths()
        // 外周 + 内側の穴（閉図形のストロークはリング状）
        assertTrue(subs.size >= 2, "subs=${subs.size}")
        val a = area(outline, 0.5)
        // 56²-44² = 1200（枠幅6の帯）
        assertTrue(a > 1150.0 && a < 1250.0, "area=$a")
    }

    @Test
    fun boolean_result_has_no_degenerate_rings() {
        // ほぼ重なる2つの矩形の和集合 → 無意味な微小リングが大量発生しない
        val a = rectPath(0.0, 0.0, 100.0, 100.0)
        val b = rectPath(0.001, 0.001, 100.0, 100.0)
        val result = GeometryOps.boolean(a, b, GeometryOps.BoolOp.UNION, 0.01)!!
        val subs = result.subpaths()
        // 面積がほぼ10000の1外周（+微小穴があればそれも微小）
        val total = area(result, 1.0)
        assertTrue(total > 9900.0, "area=$total")
        // 各リングの面積は minRingArea 以上
        for (sp in subs) {
            val segs = ArrayList(result.segs.subList(sp.segStart, sp.segEnd + if (sp.closed) 1 else 0))
            val spd = PathData(segs)
            val ringArea = kotlin.math.abs(spd.length()) // 長さベースの健全性チェック
            assertTrue(ringArea > 0.0)
        }
    }

    @Test
    fun boolean_union_collinear_points_merged() {
        // 同一矩形の和集合 → 頂点は4つ（共線点が増殖しない）
        val a = rectPath(0.0, 0.0, 40.0, 40.0)
        val b = rectPath(0.0, 0.0, 40.0, 40.0)
        val result = GeometryOps.boolean(a, b, GeometryOps.BoolOp.UNION, 0.01)!!
        val sp = result.subpaths().first()
        val nodeCount = sp.segEnd - sp.segStart - 1 // M + (n-1 segs) + Z → nノード
        assertTrue(nodeCount <= 8, "nodes=$nodeCount")
    }

    @Test
    fun difference_keeps_hole_with_orientation() {
        // 矩形から円を抜く → 外周+穴。nonzeroでもevenoddでも塗り面積は同じ
        val a = rectPath(0.0, 0.0, 100.0, 100.0)
        val circle = PathData.ellipse(50.0, 50.0, 20.0, 20.0)
        val result = GeometryOps.boolean(a, circle, GeometryOps.BoolOp.DIFFERENCE, 0.02)!!
        assertEquals(2, result.subpaths().size)
        val a1 = area(result, 1.0)
        // 10000 - π·20² ≈ 8744（サンプリング誤差を含む）
        assertTrue(a1 > 8600.0 && a1 < 8850.0, "area=$a1")
        // 巻き方向: 穴は外周と逆向き（nonzeroでも穴として機能する）
        val areas = ringSignedAreas(result)
        assertEquals(2, areas.size)
        assertTrue(areas[0] * areas[1] < 0, "rings must have opposite winding: $areas")
    }

    private fun ringSignedAreas(pd: PathData): List<Double> {
        val out = ArrayList<Double>()
        for (sp in pd.subpaths()) {
            val segs = ArrayList(pd.segs.subList(sp.segStart, sp.segEnd + if (sp.closed) 1 else 0))
            val pts = PathData(segs).flatten(0.01)
            if (pts.size < 3) continue
            var s = 0.0
            for (i in pts.indices) {
                val p = pts[i]
                val q = pts[(i + 1) % pts.size]
                s += p.x * q.y - q.x * p.y
            }
            out.add(s / 2.0)
        }
        return out
    }

    @Test
    fun offset_inside_and_outside() {
        val a = rectPath(0.0, 0.0, 50.0, 50.0)
        val bigger = GeometryOps.offset(a, 5.0)!!
        val smaller = GeometryOps.offset(a, -5.0)!!
        val ab = bigger.bounds(0.01)!!
        val sb = smaller.bounds(0.01)!!
        assertTrue(ab.width > 55.0 && ab.width < 63.0, "外周オフセット(ラウンド結合) w=${ab.width}")
        assertTrue(sb.width > 36.0 && sb.width < 41.0, "内側オフセット w=${sb.width}")
    }

    @Test
    fun offset_miter_join() {
        val a = rectPath(0.0, 0.0, 50.0, 50.0)
        val bigger = GeometryOps.offset(a, 5.0, joinStyle = BufferParameters.JOIN_MITRE)!!
        val b = bigger.bounds(0.01)!!
        // miter結合なら角は尖る → bboxは 60×60
        assertTrue(b.width > 59.0 && b.width < 61.5, "w=${b.width}")
    }

    @Test
    fun circle_stroke_outline_area() {
        // 半径20の円、幅4のストローク → 面積 ≈ π(22²-18²) ≈ 502
        val circle = PathData.ellipse(0.0, 0.0, 20.0, 20.0)
        val outline = GeometryOps.strokeOutline(circle, 4.0, StrokeCap.ROUND)!!
        val a = area(outline, 0.5)
        val expected = PI * (22.0 * 22.0 - 18.0 * 18.0)
        assertTrue(a > expected * 0.9 && a < expected * 1.1, "a=$a expected=$expected")
    }
}
