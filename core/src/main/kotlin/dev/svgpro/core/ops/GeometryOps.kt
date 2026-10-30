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
 * JTSを使ったパスの幾何演算（ブーリアン、オフセット、単純化）。
 * ベジェ/アークをあらかじめtolでフラット化してから多角形として扱う。
 * fill-rule は even-odd 相当（Polygonizerでネスト検出）として扱う。
 */
object GeometryOps {

    private val GF = GeometryFactory()

    enum class BoolOp { UNION, DIFFERENCE, INTERSECTION, XOR }

    /**
     * PathData → JTS Geometry。
     * 開いたサブパスは暗黙に閉じて処理する（SVGの塗り仕様に倣う）。
     */
    fun pathToGeometry(pd: PathData, tol: Double = 0.01): Geometry? {
        val lineStrings = ArrayList<LineString>()
        for (sp in pd.subpaths()) {
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

    /** 結果ジオメトリをパスへ。すべてのリングをサブパス化する（_fill-rule=evenodd 想定） */
    fun geometryToPath(g: Geometry): PathData {
        val segs = ArrayList<Seg>()

        fun addClosedRing(coordsList: Array<Coordinate>) {
            if (coordsList.size < 3) return
            // MultiPolygon等のリングは閉じている（先頭==末尾）
            val pts = if (coordsList.first() == coordsList.last()) coordsList.dropLast(1) else coordsList.toList()
            if (pts.size < 3) return
            segs.add(Seg.Move(pts[0].x, pts[0].y))
            for (i in 1 until pts.size) {
                segs.add(Seg.Line(pts[i].x, pts[i].y))
            }
            segs.add(Seg.Close)
        }

        fun addPolygon(poly: Polygon) {
            addClosedRing(poly.exteriorRing.coordinates)
            for (i in 0 until poly.numInteriorRing) {
                addClosedRing(poly.getInteriorRingN(i).coordinates)
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
                    segs.add(startFrom(ls))
                }
                else -> {}
            }
        }
        walk(g)
        return PathData(segs)
    }

    // LineStringの先頭座標（ヘルパ）
    private fun startFrom(ls: LineString): Seg.Move {
        val p = ls.coordinates.firstOrNull() ?: Coordinate(0.0, 0.0)
        return Seg.Move(p.x, p.y)
    }

    // ―――― ブーリアン演算 ――――

    fun boolean(a: PathData, b: PathData, op: BoolOp, tol: Double = 0.01): PathData? {
        val ga = pathToGeometry(a, tol) ?: return null
        val gb = pathToGeometry(b, tol) ?: return null
        val result = when (op) {
            BoolOp.UNION -> ga.union(gb)
            BoolOp.DIFFERENCE -> ga.difference(gb)
            BoolOp.INTERSECTION -> ga.intersection(gb)
            BoolOp.XOR -> ga.symDifference(gb)
        }
        if (result.isEmpty) return null
        return geometryToPath(result)
    }

    /** dist>0で拡張、dist<0で縮小 */
    fun offset(pd: PathData, dist: Double, tol: Double = 0.01): PathData? {
        val g = pathToGeometry(pd, tol) ?: return null
        val params = BufferParameters().apply {
            joinStyle = BufferParameters.JOIN_ROUND
            endCapStyle = BufferParameters.CAP_ROUND
        }
        val buffered = BufferOp.bufferOp(g, dist, params)
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

/** PathData 生成ヘルパの橋渡し（ジオメトリ用） */
internal object ArcBridge {
    fun cubicsFor(start: Vec, arc: Seg.Arc): List<Triple<Vec, Vec, Vec>> =
        dev.svgpro.core.path.ArcConverter.arcToCubics(start, arc)
}
