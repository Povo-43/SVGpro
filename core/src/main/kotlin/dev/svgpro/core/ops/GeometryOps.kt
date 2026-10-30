package dev.svgpro.core.ops

import dev.svgpro.core.geom.Vec
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.polygonize.Polygonizer
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier

/**
 * JTSを使ったパスの幾何演算（ブーリアン、オフセット、単純化、ストロークのアウトライン化）。
 * ベジェ/アークをあらかじめtolでフラット化してから多角形として扱う。
 *
 * 出力品質:
 *  - 微小リング（面積がほぼ0）の除去
 *  - 重複点・共線点の統合 → 細分化された無意味な線分を減らす
 *  - 外周と穴の巻き方向を統一（nonzeroでも穴が保たれる）
 */
object GeometryOps {

    private val GF = GeometryFactory()

    enum class BoolOp { UNION, DIFFERENCE, INTERSECTION, XOR }

    /** クリーンアップ設定 */
    data class Quality(
        /** 座標の丸め（user単位）。0で無効 */
        val quantize: Double = 0.0,
        /** この面積未満のリングを捨てる */
        val minRingArea: Double = 1e-4,
        /** 共線点の統合を行うか */
        val mergeCollinear: Boolean = true,
    ) {
        companion object {
            val DEFAULT = Quality()
        }
    }

    // ―――― PathData → JTS ――――

    /**
     * PathData → JTS Geometry（塗りセマンティクス）。
     * 開いたサブパスは暗黙に閉じて処理する（SVGの塗り仕様に倣う）。
     */
    fun pathToGeometry(pd: PathData, tol: Double = 0.01): Geometry? {
        val lineStrings = ArrayList<LineString>()
        for (sp in pd.subpaths()) {
            val pts = flattenSubpath(pd, sp, tol)
            if (pts.size < 3) continue
            val coords = ArrayList<Coordinate>(pts.size + 1)
            for (p in pts) coords.add(Coordinate(p.x, p.y))
            // 暗黙閉鎖（塗りセマンティクス）
            val first = pts.first()
            val last = pts.last()
            if (first.distanceTo(last) > 1e-9) {
                coords.add(Coordinate(first.x, first.y))
            }
            if (coords.size < 4) continue
            lineStrings.add(GF.createLineString(coords.toTypedArray()))
        }
        if (lineStrings.isEmpty()) return null

        val grand = GF.createGeometryCollection(lineStrings.toTypedArray())
        val polygonizer = Polygonizer()
        polygonizer.add(grand)
        val ps = polygonizer.getPolygons().filterIsInstance<Polygon>()
        if (ps.isEmpty()) return null
        return if (ps.size == 1) {
            ps.first()
        } else {
            GF.createMultiPolygon(ps.toTypedArray())
        }
    }

    /**
     * PathData → JTS LineString集合（ストローク/開パスのオフセット用）。
     * 開いたサブパスは閉じずに線として扱う。閉じたサブパスは始点で閉じる。
     */
    fun pathToLines(pd: PathData, tol: Double = 0.01): List<LineString> {
        val out = ArrayList<LineString>()
        for (sp in pd.subpaths()) {
            val pts = flattenSubpath(pd, sp, tol)
            if (pts.size < 2) continue
            if (sp.closed && pts.first().distanceTo(pts.last()) > 1e-9) {
                pts.add(pts.first())
            }
            val coords = ArrayList<Coordinate>(pts.size)
            for (p in pts) coords.add(Coordinate(p.x, p.y))
            out.add(GF.createLineString(coords.toTypedArray()))
        }
        return out
    }

    private fun flattenSubpath(pd: PathData, sp: dev.svgpro.core.path.SubPath, tol: Double): MutableList<Vec> {
        val pts = ArrayList<Vec>()
        var cur = pd.startPointOfSubpath(sp.segStart)
        pts.add(cur)
        for (i in sp.segStart until sp.segEnd) {
            when (val s = pd.segs[i]) {
                is Seg.Move -> { pts.add(Vec(s.x, s.y)); cur = Vec(s.x, s.y) }
                is Seg.Line -> { pts.add(Vec(s.x, s.y)); cur = Vec(s.x, s.y) }
                is Seg.Cubic -> {
                    pts.addAll(dev.svgpro.core.path.Bezier.cubicFlatten(cur, Vec(s.c1x, s.c1y), Vec(s.c2x, s.c2y), Vec(s.x, s.y), tol))
                    cur = Vec(s.x, s.y)
                }
                is Seg.Quad -> {
                    pts.addAll(dev.svgpro.core.path.Bezier.quadFlatten(cur, Vec(s.cx, s.cy), Vec(s.x, s.y), tol))
                    cur = Vec(s.x, s.y)
                }
                is Seg.Arc -> {
                    val cubics = ArcBridge.cubicsFor(cur, s)
                    if (cubics.isEmpty()) {
                        pts.add(Vec(s.x, s.y))
                    } else {
                        for ((c1, c2, e) in cubics) {
                            pts.addAll(dev.svgpro.core.path.Bezier.cubicFlatten(cur, c1, c2, e, tol))
                            cur = e
                        }
                    }
                    cur = Vec(s.x, s.y)
                }
                Seg.Close -> { pts.add(pd.startPointOfSubpath(i)); cur = pd.startPointOfSubpath(i) }
            }
        }
        return pts
    }

    // ―――― JTS → PathData ――――

    /**
     * 結果ジオメトリをパスへ。外周と穴の巻き方向を揃え、微小リング・共線点を除去する。
     * （fill-rule=evenodd を付けて使う想定だが、nonzeroでも穴が保たれる巻き方向にする）
     */
    fun geometryToPath(g: Geometry, quality: Quality = Quality.DEFAULT): PathData {
        val segs = ArrayList<Seg>()

        fun signedArea(pts: List<Vec>): Double {
            var a = 0.0
            for (i in pts.indices) {
                val p = pts[i]
                val q = pts[(i + 1) % pts.size]
                a += p.x * q.y - q.x * p.y
            }
            return a / 2.0
        }

        fun cleanRing(coords: Array<Coordinate>): List<Vec> {
            // 閉じたリング → 開いた点列へ
            val raw = ArrayList<Vec>(coords.size)
            for (c in coords) raw.add(Vec(c.x, c.y))
            if (raw.size >= 2 && raw.first().distanceTo(raw.last()) < 1e-9) raw.removeAt(raw.size - 1)
            // 重複点除去
            val dedup = ArrayList<Vec>(raw.size)
            for (p in raw) {
                if (dedup.isEmpty() || dedup.last().distanceTo(p) > 1e-9) dedup.add(p)
            }
            if (dedup.size >= 2 && dedup.first().distanceTo(dedup.last()) < 1e-9) dedup.removeAt(dedup.size - 1)
            if (quality.quantize > 0.0) {
                val q = quality.quantize
                dedup.replaceAll { p -> Vec(kotlin.math.round(p.x / q) * q, kotlin.math.round(p.y / q) * q) }
            }
            if (!quality.mergeCollinear || dedup.size < 3) return dedup
            // 共線点除去（角度がほぼ180°の頂点を落とす）
            val out = ArrayList<Vec>(dedup.size)
            val n = dedup.size
            for (i in 0 until n) {
                val prev = out.lastOrNull() ?: dedup[(i - 1 + n) % n]
                val cur = dedup[i]
                val next = dedup[(i + 1) % n]
                val v1 = (cur - prev).normalized()
                val v2 = (next - cur).normalized()
                val cross = kotlin.math.abs(v1.cross(v2))
                val dot = v1.dot(v2)
                if (cross < 1e-7 && dot > 0.0) continue // 直進 → 除去
                out.add(cur)
            }
            return out
        }

        fun addRing(coords: Array<Coordinate>, hole: Boolean) {
            val pts = cleanRing(coords)
            if (pts.size < 3) return
            val area = signedArea(pts)
            if (kotlin.math.abs(area) < quality.minRingArea) return
            // 巻き方向を統一: 外周=正 (CCW in y-downなら時計回り)、穴=逆
            val wantPositive = !hole
            val ordered = if ((area > 0) != wantPositive) pts.asReversed() else pts
            segs.add(Seg.Move(ordered[0].x, ordered[0].y))
            for (i in 1 until ordered.size) {
                segs.add(Seg.Line(ordered[i].x, ordered[i].y))
            }
            segs.add(Seg.Close)
        }

        fun addPolygon(poly: Polygon) {
            addRing(poly.exteriorRing.coordinates, hole = false)
            for (i in 0 until poly.numInteriorRing) {
                addRing(poly.getInteriorRingN(i).coordinates, hole = true)
            }
        }

        fun walk(geo: Geometry) {
            when (geo.geometryType) {
                "Polygon" -> addPolygon(geo as Polygon)
                "MultiPolygon" -> {
                    val mp = geo as MultiPolygon
                    for (i in 0 until mp.numGeometries) {
                        val gN = mp.getGeometryN(i)
                        if (gN is Polygon) addPolygon(gN)
                    }
                }
                "GeometryCollection" -> {
                    val gc = geo as GeometryCollection
                    for (i in 0 until gc.numGeometries) walk(gc.getGeometryN(i))
                }
                "LineString" -> {
                    val ls = geo as LineString
                    val coords = ls.coordinates
                    if (coords.isNotEmpty()) segs.add(Seg.Move(coords[0].x, coords[0].y))
                    for (i in 1 until coords.size) segs.add(Seg.Line(coords[i].x, coords[i].y))
                }
                else -> {}
            }
        }
        walk(g)
        return PathData(segs)
    }

    // ―――― ブーリアン演算 ――――

    fun boolean(a: PathData, b: PathData, op: BoolOp, tol: Double = 0.01, quality: Quality = Quality.DEFAULT): PathData? {
        val ga = pathToGeometry(a, tol) ?: return null
        val gb = pathToGeometry(b, tol) ?: return null
        val result = when (op) {
            BoolOp.UNION -> ga.union(gb)
            BoolOp.DIFFERENCE -> ga.difference(gb)
            BoolOp.INTERSECTION -> ga.intersection(gb)
            BoolOp.XOR -> ga.symDifference(gb)
        }
        if (result.isEmpty) return null
        return geometryToPath(result, quality)
    }

    /** dist>0で拡張、dist<0で縮小（閉パスの塗りセマンティクス） */
    fun offset(pd: PathData, dist: Double, tol: Double = 0.01, joinStyle: Int = BufferParameters.JOIN_ROUND): PathData? {
        val g = pathToGeometry(pd, tol) ?: return null
        val params = BufferParameters().apply {
            this.joinStyle = joinStyle
            endCapStyle = BufferParameters.CAP_ROUND
        }
        val buffered = BufferOp.bufferOp(g, dist, params)
        if (buffered.isEmpty) return null
        return geometryToPath(buffered)
    }

    /**
     * 開いたパスをストロークとみなして帯状のアウトラインを作る（ストロークのパス化）。
     * cap/join は SVG stroke-linecap / stroke-linejoin に対応。
     * 閉じたサブパスはリングとして処理し、内側に穴を持つ帯を正しく生成する。
     */
    fun strokeOutline(
        pd: PathData,
        width: Double,
        cap: StrokeCap = StrokeCap.BUTT,
        join: StrokeJoin = StrokeJoin.MITER,
        miterLimit: Double = 4.0,
        tol: Double = 0.01,
    ): PathData? {
        if (width <= 0.0) return null
        val lines = ArrayList<Geometry>()
        for (ls in pathToLines(pd, tol)) {
            val coords = ls.coordinates
            val isClosed = coords.size >= 4 &&
                coords.first().distance(coords.last()) < 1e-9
            if (isClosed) {
                // 閉じた輪郭はリングとして扱い、帯に穴が開くようにする
                lines.add(GF.createLinearRing(coords))
            } else {
                lines.add(ls)
            }
        }
        if (lines.isEmpty()) return null
        val params = BufferParameters().apply {
            endCapStyle = when (cap) {
                StrokeCap.BUTT -> BufferParameters.CAP_FLAT
                StrokeCap.ROUND -> BufferParameters.CAP_ROUND
                StrokeCap.SQUARE -> BufferParameters.CAP_SQUARE
            }
            this.joinStyle = when (join) {
                StrokeJoin.MITER -> BufferParameters.JOIN_MITRE
                StrokeJoin.ROUND -> BufferParameters.JOIN_ROUND
                StrokeJoin.BEVEL -> BufferParameters.JOIN_BEVEL
            }
            mitreLimit = miterLimit
        }
        val collection = GF.createGeometryCollection(lines.toTypedArray())
        val buffered = BufferOp.bufferOp(collection, width / 2.0, params)
        if (buffered.isEmpty) return null
        return geometryToPath(buffered)
    }

    /** DouglasPeuckerによる単純化（tol = 許容誤差） */
    fun simplify(pd: PathData, tol: Double): PathData {
        val g = pathToGeometry(pd) ?: return pd
        val simplified = DouglasPeuckerSimplifier.simplify(g, tol)
        if (simplified.isEmpty) return pd
        return geometryToPath(simplified)
    }
}

enum class StrokeCap { BUTT, ROUND, SQUARE }
enum class StrokeJoin { MITER, ROUND, BEVEL }

/** PathData 生成ヘルパの橋渡し（ジオメトリ用） */
internal object ArcBridge {
    fun cubicsFor(start: Vec, arc: Seg.Arc): List<Triple<Vec, Vec, Vec>> =
        dev.svgpro.core.path.ArcConverter.arcToCubics(start, arc)
}
