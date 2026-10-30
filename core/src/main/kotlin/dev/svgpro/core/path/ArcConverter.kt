package dev.svgpro.core.path

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt
import dev.svgpro.core.geom.Vec

/**
 * SVGアーク (A rx ry rot laf sf x y) を3次ベジェ列へ変換する（W3C F.6.5準拠）。
 */
object ArcConverter {

    /** start→終点に対する cubics 制御点列 [(c1, c2, end)] */
    fun arcToCubics(start: Vec, arc: Seg.Arc): List<Triple<Vec, Vec, Vec>> {
        val end = Vec(arc.x, arc.y)
        val rx0 = abs(arc.rx).coerceAtLeast(1e-9)
        val ry0 = abs(arc.ry).coerceAtLeast(1e-9)
        val phiRad = Math.toRadians(arc.rot)
        val cosPhi = cos(phiRad)
        val sinPhi = sin(phiRad)

        val dx2 = (start.x - end.x) / 2.0
        val dy2 = (start.y - end.y) / 2.0
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        var rx = rx0
        var ry = ry0
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1.0) {
            val s = sqrt(lambda)
            rx *= s
            ry *= s
        }

        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        if (den <= 0.0) return emptyList()
        var cCoef = sqrt(maxOf(0.0, num / den))
        if (arc.largeArc == arc.sweep) cCoef = -cCoef

        val cxp = cCoef * rx * y1p / ry
        val cyp = -cCoef * ry * x1p / rx

        val cx = cosPhi * cxp - sinPhi * cyp + (start.x + end.x) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (start.y + end.y) / 2.0

        val v1x = (x1p - cxp) / rx
        val v1y = (y1p - cyp) / ry
        val v2x = (-x1p - cxp) / rx
        val v2y = (-y1p - cyp) / ry

        var theta1 = angleBetween(Vec(1.0, 0.0), Vec(v1x, v1y))
        var thetaSpan = angleBetween(Vec(v1x, v1y), Vec(v2x, v2y))
        if (!arc.sweep && thetaSpan > 0.0) thetaSpan -= 2.0 * PI
        if (arc.sweep && thetaSpan < 0.0) thetaSpan += 2.0 * PI
        if (theta1 < 0.0) theta1 += 2.0 * PI

        val segCount = ceil(abs(thetaSpan) / (2.0 * PI / 3.0)).toInt().coerceAtLeast(1)
        val out = ArrayList<Triple<Vec, Vec, Vec>>(segCount)
        for (i in 0 until segCount) {
            val s = theta1 + thetaSpan * i / segCount
            val e = theta1 + thetaSpan * (i + 1) / segCount
            val (c1, c2, pt) = cubicForArc(cx, cy, rx, ry, phiRad, s, e)
            out.add(Triple(c1, c2, pt))
        }
        return out
    }

    private fun angleBetween(u0: Vec, u1: Vec): Double {
        val len = u0.length() * u1.length()
        if (len == 0.0) return 0.0
        val dot = (u0.x * u1.x + u0.y * u1.y) / len
        val cross = u0.x * u1.y - u0.y * u1.x
        return acos(dot.coerceIn(-1.0, 1.0)).let { if (cross < 0.0) -it else it }
    }

    /** 楕円中心(cx,cy)、半径(rx,ry)、回転φのもとで角度 s..e の円弧を cubic に変換 */
    private fun cubicForArc(
        cx: Double, cy: Double, rx: Double, ry: Double,
        phiRad: Double, s: Double, e: Double,
    ): Triple<Vec, Vec, Vec> {
        val cosPhi = cos(phiRad)
        val sinPhi = sin(phiRad)
        fun point(theta: Double): Vec = Vec(
            cx + (rx * cos(theta) * cosPhi - ry * sin(theta) * sinPhi),
            cy + (rx * cos(theta) * sinPhi + ry * sin(theta) * cosPhi),
        )

        fun deriv(theta: Double): Vec = Vec(
            -rx * sin(theta) * cosPhi - ry * cos(theta) * sinPhi,
            -rx * sin(theta) * sinPhi + ry * cos(theta) * cosPhi,
        )

        var dTheta = e - s
        // 2π/3ごとの分割のため dTheta の絶対値は π 未満
        if (dTheta > PI) dTheta -= 2.0 * PI
        if (dTheta < -PI) dTheta += 2.0 * PI
        val k = 4.0 / 3.0 * kotlin.math.tan(dTheta / 4.0)

        val p1 = point(s)
        val p2 = point(e)
        val d1 = deriv(s)
        val d2 = deriv(e)
        val c1 = Vec(p1.x + k * d1.x, p1.y + k * d1.y)
        val c2 = Vec(p2.x - k * d2.x, p2.y - k * d2.y)
        return Triple(c1, c2, p2)
    }

    fun maxOf(a: Double, b: Double): Double = if (a >= b) a else b
}
