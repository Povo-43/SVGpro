package dev.svgpro.core.path

import dev.svgpro.core.geom.Bounds
import dev.svgpro.core.geom.Matrix
import dev.svgpro.core.geom.Vec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 単一サブパス区間情報。segs[segStart ..< segEnd]。closed=true の場合 segEnd はCloseのindex。 */
class SubPath(val segStart: Int, val segEnd: Int, val closed: Boolean) {
    override fun toString(): String = "SubPath($segStart..$segEnd, closed=$closed)"
}

/**
 * path "d" を表す可変コンテナ。正規化セグメント（M/L/C/Q/A/Z）で保持する。
 * ノード編集・図形生成・ブーリアンの結果生成など、すべてJSON的値ではなくこの構造を共有する。
 */
class PathData(val segs: MutableList<Seg>) {

    companion object {
        fun parse(d: String?): PathData? {
            if (d.isNullOrBlank()) return null
            val segs = PathParser(d).parse()
            if (segs.isEmpty()) return null
            return PathData(segs)
        }

        fun of(vararg pts: Vec): PathData {
            val segs = ArrayList<Seg>()
            pts.forEachIndexed { i, p ->
                if (i == 0) segs.add(Seg.Move(p.x, p.y)) else segs.add(Seg.Line(p.x, p.y))
            }
            return PathData(segs)
        }

        /** 閉じた矩形パス（時計回り） */
        fun rect(x: Double, y: Double, w: Double, h: Double): PathData {
            val segs = ArrayList<Seg>()
            segs.add(Seg.Move(x, y))
            segs.add(Seg.Line(x + w, y))
            segs.add(Seg.Line(x + w, y + h))
            segs.add(Seg.Line(x, y + h))
            segs.add(Seg.Close)
            return PathData(segs)
        }

        /** 楕円／円パス（4つのcubicで近似、K=A*0.5523） */
        fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): PathData {
            val k = 0.5522847498307936
            val kx = k * rx
            val ky = k * ry
            val segs = ArrayList<Seg>()
            segs.add(Seg.Move(cx - rx, cy))
            segs.add(Seg.Cubic(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry))
            segs.add(Seg.Cubic(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy))
            segs.add(Seg.Cubic(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry))
            segs.add(Seg.Cubic(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy))
            segs.add(Seg.Close)
            return PathData(segs)
        }

        /** 閉じた多角形パス */
        fun polygon(pts: List<Vec>): PathData {
            val segs = ArrayList<Seg>()
            pts.forEachIndexed { i, p ->
                if (i == 0) segs.add(Seg.Move(p.x, p.y)) else segs.add(Seg.Line(p.x, p.y))
            }
            segs.add(Seg.Close)
            return PathData(segs)
        }

        fun fromSegs(list: List<Seg>): PathData = PathData(ArrayList(list))
    }

    fun clone(): PathData = PathData(
        segs.map {
            when (it) {
                is Seg.Move -> it.copy()
                is Seg.Line -> it.copy()
                is Seg.Cubic -> it.copy()
                is Seg.Quad -> it.copy()
                is Seg.Arc -> it.copy()
                Seg.Close -> Seg.Close
            }
        }.toMutableList(),
    )

    fun write(): String = PathWriter.write(segs)

    fun isEmpty(): Boolean = segs.isEmpty()

    // ―――― 座標構造走査 ――――

    fun subpaths(): List<SubPath> {
        val out = ArrayList<SubPath>()
        var start = -1
        var i = 0
        while (i < segs.size) {
            when (val s = segs[i]) {
                is Seg.Move -> {
                    if (start >= 0) {
                        out.add(SubPath(start, i, closed = false))
                    }
                    start = i
                }
                Seg.Close -> {
                    if (start >= 0) {
                        out.add(SubPath(start, i, closed = true))
                        start = -1
                    }
                }
                else -> {
                    if (start < 0) {
                        // 仕様外（Mなし開始）はLineとみなして始点0,0とする
                        out.add(SubPath(i, segs.size, closed = false))
                    }
                }
            }
            i++
        }
        if (start >= 0) out.add(SubPath(start, segs.size, closed = false))
        return out
    }

    /** segIndex が属するサブパスの始点（M座標） */
    fun startPointOfSubpath(segIndex: Int): Vec {
        var k = segIndex
        while (k >= 0) {
            val s = segs[k]
            if (s is Seg.Move) return Vec(s.x, s.y)
            k--
        }
        return Vec.ZERO
    }

    /** segIndex の終点座標 */
    fun endPoint(i: Int): Vec = when (val s = segs[i]) {
        is Seg.Move -> Vec(s.x, s.y)
        is Seg.Line -> Vec(s.x, s.y)
        is Seg.Cubic -> Vec(s.x, s.y)
        is Seg.Quad -> Vec(s.x, s.y)
        is Seg.Arc -> Vec(s.x, s.y)
        Seg.Close -> startPointOfSubpath(i)
    }

    /** segIndex の始点座標。Closeのときはサブパス始点。 */
    fun startPointOf(i: Int): Vec {
        if (i <= 0) return Vec.ZERO
        return endPoint(i - 1)
    }

    // ―――― 幾何変換 ――――

    /** アフィン変換をインプレースで適用。H/V form は一般変換で崩れるため解除する。 */
    fun transform(m: Matrix): PathData {
        val needsFormReset = !(m.a != 0.0 && m.b == 0.0 && m.c == 0.0 && m.d != 0.0)
        segs.forEach { s ->
            when (s) {
                is Seg.Move -> { val p = m.apply(Vec(s.x, s.y)); s.x = p.x; s.y = p.y }
                is Seg.Line -> { val p = m.apply(Vec(s.x, s.y)); s.x = p.x; s.y = p.y; if (needsFormReset) s.form = Seg.Line.FORM_LINE }
                is Seg.Cubic -> {
                    val p1 = m.apply(Vec(s.c1x, s.c1y)); val p2 = m.apply(Vec(s.c2x, s.c2y)); val p3 = m.apply(Vec(s.x, s.y))
                    s.c1x = p1.x; s.c1y = p1.y
                    s.c2x = p2.x; s.c2y = p2.y
                    s.x = p3.x; s.y = p3.y
                }
                is Seg.Quad -> {
                    val pc = m.apply(Vec(s.cx, s.cy)); val p3 = m.apply(Vec(s.x, s.y))
                    s.cx = pc.x; s.cy = pc.y
                    s.x = p3.x; s.y = p3.y
                }
                is Seg.Arc -> {
                    // アークは一般変換で楕円の傾きが変わる → rotを更新する近似（rx,ryはそのまま）
                    // 正確性重視で cubics に展開する場合は rootNode時 caller で対応とする
                    val p = m.apply(Vec(s.x, s.y)); s.x = p.x; s.y = p.y
                    if (needsFormReset) {
                        // 傾き更新: 回転成分だけ取り出してrotへ合成するのは近似。⇒ cubics変換はアプリは自動で 使わない
                    }
                }
                Seg.Close -> {}
            }
        }
        return this
    }

    // ―――― フラット化 / 長さ / 範囲 ――――

    /** polyline点列（tol誤差）。サブパスの接続点は重複点として現れる。p0（Move座標）を含む */
    fun flatten(tol: Double = 0.01): List<Vec> {
        val out = ArrayList<Vec>()
        if (segs.isEmpty()) return out
        var startIdx = 0
        if (segs.first() is Seg.Move) {
            val mv = segs.first() as Seg.Move
            out.add(Vec(mv.x, mv.y))
            startIdx = 1
        }
        var cur = out.lastOrNull() ?: Vec.ZERO
        for (i in startIdx until segs.size) {
            val s = segs[i]
            when (s) {
                is Seg.Move -> {
                    out.add(Vec(s.x, s.y))
                    cur = Vec(s.x, s.y)
                }
                is Seg.Line -> {
                    out.add(Vec(s.x, s.y)); cur = Vec(s.x, s.y)
                }
                is Seg.Cubic -> {
                    val pts = Bezier.cubicFlatten(cur, Vec(s.c1x, s.c1y), Vec(s.c2x, s.c2y), Vec(s.x, s.y), tol)
                    out.addAll(pts); cur = Vec(s.x, s.y)
                }
                is Seg.Quad -> {
                    val pts = Bezier.quadFlatten(cur, Vec(s.cx, s.cy), Vec(s.x, s.y), tol)
                    out.addAll(pts); cur = Vec(s.x, s.y)
                }
                is Seg.Arc -> {
                    val start = cur
                    val cubics = ArcConverter.arcToCubics(start, s)
                    if (cubics.isEmpty()) {
                        out.add(Vec(s.x, s.y))
                    } else {
                        var c = start
                        for ((c1, c2, e) in cubics) {
                            val pts = Bezier.cubicFlatten(c, c1, c2, e, tol)
                            out.addAll(pts)
                            c = e
                        }
                    }
                    cur = Vec(s.x, s.y)
                }
                Seg.Close -> {
                    val sp0 = startPointOfSubpath(i)
                    out.add(sp0)
                    cur = sp0
                }
            }
        }
        return out
    }

    private fun Vec.equalsEps(o: Vec, eps: Double) = abs(x - o.x) <= eps && abs(y - o.y) <= eps

    /** 全体長の近似 */
    fun length(): Double {
        var l = 0.0
        val pts = flatten(0.02)
        for (i in 1 until pts.size) l += pts[i].distanceTo(pts[i - 1])
        return l
    }

    /** ノード数の概算（スナップ等のコスト制御用。解析を伴わない） */
    fun nodeCountEstimate(): Int {
        var n = 0
        for (s in segs) {
            when (s) {
                is Seg.Move, is Seg.Line, is Seg.Cubic, is Seg.Quad, is Seg.Arc -> n++
                Seg.Close -> {}
            }
        }
        return n
    }

    /**
     * フラットな点集合に基づいたbounds（選択ボックス/表示用）。
     * 曲線の外れは flatten復元性が常であり、tol誤差で十分
     */
    fun bounds(tol: Double = 0.01): Bounds? {
        val pts = flatten(tol)
        if (pts.isEmpty()) return null
        var minX = pts[0].x; var maxX = minX
        var minY = pts[0].y; var maxY = minY
        for (p in pts) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        return Bounds(minX, minY, maxX - minX, maxY - minY)
    }

    // ―――― 反転 ――――

    /** サブパス方向を反転した新しいパス（サブパス自体の順序は保持） */
    fun reversed(): PathData {
        val out = ArrayList<Seg>()
        for (sp in subpaths()) {
            val sub = segs.subList(sp.segStart, sp.segEnd)
            out.addAll(reverseSubPath(sub, sp.closed))
        }
        return PathData(out)
    }

    private fun reverseSubPath(sub: List<Seg>, closed: Boolean): List<Seg> {
        val body = if (closed) sub.subList(0, sub.size - 1) else sub
        if (body.isEmpty()) return sub.toList()

        val move = body[0] as Seg.Move
        val startP = Vec(move.x, move.y)

        // 各segを {始点, 中間制御..., 終点} の座標列に正規化
        var cur = startP
        data class PolySeg(val pts: List<Vec>, val original: Seg)
        val poly = ArrayList<PolySeg>()
        for (i in 1 until body.size) {
            val s = body[i]
            when (s) {
                is Seg.Line -> {
                    val e = Vec(s.x, s.y); poly.add(PolySeg(listOf(e), s)); cur = e
                }
                is Seg.Cubic -> {
                    val e = Vec(s.x, s.y); poly.add(PolySeg(listOf(Vec(s.c1x, s.c1y), Vec(s.c2x, s.c2y), e), s)); cur = e
                }
                is Seg.Quad -> {
                    val e = Vec(s.x, s.y); poly.add(PolySeg(listOf(Vec(s.cx, s.cy), e), s)); cur = e
                }
                is Seg.Arc -> {
                    val cubics = ArcConverter.arcToCubics(cur, s)
                    if (cubics.isEmpty()) {
                        val e = Vec(s.x, s.y)
                        poly.add(PolySeg(listOf(e), Seg.Line(e.x, e.y)))
                    } else {
                        for ((c1, c2, e) in cubics) {
                            poly.add(PolySeg(listOf(c1, c2, e), Seg.Cubic(c1.x, c1.y, c2.x, c2.y, e.x, e.y)))
                        }
                    }
                    cur = Vec(s.x, s.y)
                }
                Seg.Close -> {}
                else -> {}
            }
        }

        val out = ArrayList<Seg>()
        // 新しい起点は旧終点
        val lastEnd = poly.lastOrNull()?.pts?.lastOrNull() ?: startP
        out.add(Seg.Move(lastEnd.x, lastEnd.y))
        for (j in poly.indices.reversed()) {
            val ps = poly[j]
            val prevP = when (j) {
                0 -> startP
                else -> poly[j - 1].pts.lastOrNull() ?: startP
            }
            // ps.pts: [c1, c2, end] (cubic) / [c, end] (quad) / [end] (line)
            when {
                ps.pts.size == 3 -> {
                    val (c1, c2, _) = ps.pts
                    out.add(Seg.Cubic(c2.x, c2.y, c1.x, c1.y, prevP.x, prevP.y))
                }
                ps.pts.size == 2 -> {
                    val (c, _) = ps.pts
                    out.add(Seg.Quad(c.x, c.y, prevP.x, prevP.y))
                }
                else -> {
                    out.add(Seg.Line(prevP.x, prevP.y))
                }
            }
        }
        if (closed) out.add(Seg.Close)
        return out
    }

    // ―――― 最近点 / 挟み撃ち分割 ――――

    data class Projection(val segIndex: Int, val t: Double, val point: Vec, val distance: Double)

    /** 点 p への最近投影（segIndexは「部分セグメント族」） */
    fun project(p: Vec, tol: Double = 0.02): Projection? {
        var best: Projection? = null
        var cur: Vec = if (segs.isNotEmpty() && segs[0] is Seg.Move) Vec((segs[0] as Seg.Move).x, (segs[0] as Seg.Move).y) else Vec.ZERO
        for (i in segs.indices) {
            val s = segs[i]
            val nSamples: Int
            val eval: (Double) -> Vec
            when (s) {
                is Seg.Line -> {
                    val e = Vec(s.x, s.y)
                    nSamples = 1
                    eval = { t -> cur + ((e - cur) * t) }
                }
                is Seg.Cubic -> {
                    val c1 = Vec(s.c1x, s.c1y); val c2 = Vec(s.c2x, s.c2y); val e = Vec(s.x, s.y)
                    nSamples = 24
                    eval = { t -> Bezier.cubicEval(cur, c1, c2, e, t) }
                }
                is Seg.Quad -> {
                    val c = Vec(s.cx, s.cy); val e = Vec(s.x, s.y)
                    nSamples = 16
                    eval = { t -> Bezier.quadEval(cur, c, e, t) }
                }
                is Seg.Arc -> {
                    val cubics = ArcConverter.arcToCubics(cur, s)
                    // アークはcubicsに展開して評価
                    var acc = cur
                    for ((c1, c2, e) in cubics) {
                        val pr = projectOnCubic(acc, c1, c2, e, p)
                        val candidate = Projection(i, pr.second, pr.first, pr.third)
                        if (best == null || candidate.distance < best!!.distance) best = candidate
                        acc = e
                    }
                    if (segs[i] is Seg.Arc) cur = Vec(s.x, s.y)
                    continue
                }
                is Seg.Move -> {
                    cur = Vec(s.x, s.y); continue
                }
                Seg.Close -> {
                    cur = startPointOfSubpath(i); continue
                }
            }
            var nearest = 0
            var nearestDist = Double.MAX_VALUE
            var nearestPt = eval(0.0)
            for (k in 0..nSamples) {
                val t = k.toDouble() / nSamples
                val pt = eval(t)
                val d = pt.distanceTo(p)
                if (d < nearestDist) {
                    nearestDist = d; nearest = k; nearestPt = pt
                }
            }
            // refine（tを狭めて再評価）
            var tNearest = nearest.toDouble() / nSamples
            val step = 1.0 / nSamples
            for (refine in 0 until 6) {
                val dt = step / Math.pow(2.0, (refine + 1).toDouble())
                val cands = listOf(tNearest - dt, tNearest + dt)
                for (t in cands) {
                    val tt = t.coerceIn(0.0, 1.0)
                    val pt = eval(tt)
                    val d = pt.distanceTo(p)
                    if (d < nearestDist) {
                        nearestDist = d; tNearest = tt; nearestPt = pt
                    }
                }
            }
            val proj = Projection(i, tNearest, nearestPt, nearestDist)
            if (best == null || proj.distance < best!!.distance) best = proj
            when (s) {
                is Seg.Line -> cur = eval(1.0)
                is Seg.Cubic -> cur = eval(1.0)
                is Seg.Quad -> cur = eval(1.0)
                else -> {}
            }
        }
        return best
    }

    /** cubic p0..p3 の点 p への最近距離・tを返す（先粗い探索 + refine） */
    fun projectOnCubic(p0: Vec, c1: Vec, c2: Vec, p3: Vec, p: Vec): Triple<Vec, Double, Double> {
        val n = 24
        var bestT = 0.0
        var bestD = Double.MAX_VALUE
        var bestPt = p0
        for (k in 0..n) {
            val t = k.toDouble() / n
            val pt = Bezier.cubicEval(p0, c1, c2, p3, t)
            val d = pt.distanceTo(p)
            if (d < bestD) {
                bestD = d; bestT = t; bestPt = pt
            }
        }
        var t = bestT
        var step = 1.0 / n
        for (j in 0 until 8) {
            val a = (t - step).coerceIn(0.0, 1.0)
            val b = (t + step).coerceIn(0.0, 1.0)
            for (tt in listOf(a, b)) {
                val pt = Bezier.cubicEval(p0, c1, c2, p3, tt)
                val d = pt.distanceTo(p)
                if (d < bestD) {
                    bestD = d; t = tt; bestPt = pt
                }
            }
            step /= 2.0
        }
        return Triple(bestPt, t, bestD)
    }
}
