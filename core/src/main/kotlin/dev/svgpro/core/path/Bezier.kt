package dev.svgpro.core.path

import kotlin.math.sqrt
import dev.svgpro.core.geom.Vec

/** ベジェ曲線の数値ユーティリティ（評価・分割・フラット化） */
object Bezier {

    /** 3次ベジェ評価 */
    fun cubicEval(p0: Vec, p1: Vec, p2: Vec, p3: Vec, t: Double): Vec {
        val u = 1.0 - t
        val a = u * u * u
        val b = 3.0 * u * u * t
        val c = 3.0 * u * t * t
        val d = t * t * t
        return Vec(
            a * p0.x + b * p1.x + c * p2.x + d * p3.x,
            a * p0.y + b * p1.y + c * p2.y + d * p3.y,
        )
    }

    /** 2次ベジェ評価 */
    fun quadEval(p0: Vec, c: Vec, p1: Vec, t: Double): Vec {
        val u = 1.0 - t
        return Vec(
            u * u * p0.x + 2.0 * u * t * c.x + t * t * p1.x,
            u * u * p0.y + 2.0 * u * t * c.y + t * t * p1.y,
        )
    }

    /**
     * 3次ベジェを t で分割する。
     * 返値: (左: p0..SP, 右: SP..p3) 各配列は [p0, c1, c2, p1] 型の制御点
     */
    fun cubicSplit(p0: Vec, p1: Vec, p2: Vec, p3: Vec, t: Double): Pair<Array<Vec>, Array<Vec>> {
        val clampedT = t.coerceIn(1e-6, 1.0 - 1e-6)
        val u = 1.0 - clampedT
        val p01 = p0 * u + p1 * clampedT
        val p12 = p1 * u + p2 * clampedT
        val p23 = p2 * u + p3 * clampedT
        val p012 = p01 * u + p12 * clampedT
        val p123 = p12 * u + p23 * clampedT
        val split = p012 * u + p123 * clampedT
        val left = arrayOf(p0, p01, p012, split)
        val right = arrayOf(split, p123, p23, p3)
        return left to right
    }

    /** 2次ベジェを t で分割。配列は [p0, c, p1] */
    fun quadSplit(p0: Vec, c: Vec, p1: Vec, t: Double): Pair<Array<Vec>, Array<Vec>> {
        val u = 1.0 - t
        val p01 = p0 * u + c * t
        val p12 = c * u + p1 * t
        val split = p01 * u + p12 * t
        val left = arrayOf(p0, p01, split)
        val right = arrayOf(split, p12, p1)
        return left to right
    }

    /** 3次ベジェを tol 誤差でフラット化（終点を含む点列）。p0 は含まれない。 */
    fun cubicFlatten(p0: Vec, p1: Vec, p2: Vec, p3: Vec, tol: Double): List<Vec> {
        val out = ArrayList<Vec>()
        forEachFlat(p0, p1, p2, p3, tol, out, 0)
        return out
    }

    private fun forEachFlat(p0: Vec, p1: Vec, p2: Vec, p3: Vec, tol: Double, out: MutableList<Vec>, depth: Int) {
        // コントロールポリゴン長と端点間距離の差を平坦性判定に用いる
        val polyLen = p0.distanceTo(p1) + p1.distanceTo(p2) + p2.distanceTo(p3)
        val chord = p0.distanceTo(p3)
        if (polyLen - chord < tol || depth >= 18) {
            out.add(p3)
            return
        }
        val (left, right) = cubicSplit(p0, p1, p2, p3, 0.5)
        forEachFlat(left[0], left[1], left[2], left[3], tol, out, depth + 1)
        forEachFlat(right[0], right[1], right[2], right[3], tol, out, depth + 1)
    }

    /** 2次ベジェをフラット化（3次に変換して再利用） */
    fun quadFlatten(p0: Vec, c: Vec, p1: Vec, tol: Double): List<Vec> {
        val c1 = Vec(p0.x + (c.x - p0.x) * 2.0 / 3.0, p0.y + (c.y - p0.y) * 2.0 / 3.0)
        val c2 = Vec(p1.x + (c.x - p1.x) * 2.0 / 3.0, p1.y + (c.y - p1.y) * 2.0 / 3.0)
        return cubicFlatten(p0, c1, c2, p1, tol)
    }

    /** 3次ベジェを2次に近似できない場合も考慮し、いずれも「制御点2つ」へ正規化するヘルパ */
    fun asCubicCtrls(p0: Vec, c: Vec, p1: Vec): Pair<Vec, Vec> {
        val c1 = Vec(p0.x + (c.x - p0.x) * 2.0 / 3.0, p0.y + (c.y - p0.y) * 2.0 / 3.0)
        val c2 = Vec(p1.x + (c.x - p1.x) * 2.0 / 3.0, p1.y + (c.y - p1.y) * 2.0 / 3.0)
        return c1 to c2
    }

    /** sqrt helper for readers */
    fun dist(a: Vec, b: Vec): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
}
