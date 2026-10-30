package dev.svgpro.core.geom

import dev.svgpro.core.util.Numbers
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 2Dベクトル（編集単位系） */
data class Vec(val x: Double, val y: Double) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec(x * s, y * s)
    operator fun div(s: Double) = Vec(x / s, y / s)
    operator fun unaryMinus() = Vec(-x, -y)
    fun dot(o: Vec) = x * o.x + y * o.y
    fun cross(o: Vec) = x * o.y - y * o.x
    fun length() = hypot(x, y)
    fun distanceTo(o: Vec) = hypot(x - o.x, y - o.y)
    fun normalized(): Vec {
        val l = length()
        return if (l == 0.0) Vec(0.0, 0.0) else Vec(x / l, y / l)
    }

    fun angleTo(o: Vec) = atan2(o.y - y, o.x - x)
    companion object {
        val ZERO = Vec(0.0, 0.0)
    }
}

/** x/y幅高さの矩形 */
data class Bounds(val x: Double, val y: Double, val width: Double, val height: Double) {
    val left: Double get() = x
    val top: Double get() = y
    val right: Double get() = x + width
    val bottom: Double get() = y + height
    val centerX: Double get() = x + width / 2
    val centerY: Double get() = y + height / 2
    val isEmpty: Boolean get() = width <= 0.0 && height <= 0.0

    fun contains(p: Vec) = p.x >= x && p.y >= y && p.x <= right && p.y <= bottom
    fun inflate(dx: Double, dy: Double) = Bounds(x - dx, y - dy, width + 2 * dx, height + 2 * dy)
    fun union(other: Bounds?): Bounds {
        if (other == null) return this
        val nx = minOf(x, other.x)
        val ny = minOf(y, other.y)
        val nr = maxOf(right, other.right)
        val nb = maxOf(bottom, other.bottom)
        return Bounds(nx, ny, nr - nx, nb - ny)
    }

    companion object {
        val EMPTY = Bounds(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * SVG transform (matrix) 相当の3x2アフィン変換。
 * 右順: this * other（thisを外側に適用してからotherを適用）とは逆の書式に注意:
 *   p' = this.apply(p) で this.m による変換。
 *   concat(a, b) = aを先に適用しbを後に適用した合成。
 */
data class Matrix(
    val a: Double = 1.0, val b: Double = 0.0,
    val c: Double = 0.0, val d: Double = 1.0,
    val e: Double = 0.0, val f: Double = 0.0,
) {
    fun isIdentity() = a == 1.0 && b == 0.0 && c == 0.0 && d == 1.0 && e == 0.0 && f == 0.0

    fun apply(p: Vec): Vec = Vec(
        a * p.x + c * p.y + e,
        b * p.x + d * p.y + f,
    )

    /** 合成: 結果 = other(this(p)) 、つまり this を先に適用して other を後に適用する */
    fun then(other: Matrix): Matrix = Matrix(
        a = other.a * a + other.c * b,
        b = other.b * a + other.d * b,
        c = other.a * c + other.c * d,
        d = other.b * c + other.d * d,
        e = other.a * e + other.c * f + other.e,
        f = other.b * e + other.d * f + other.f,
    )

    /** 合成: 結果 = this(other(p)) 、つまり other を先に適用して this を後に適用する */
    fun preMultiply(other: Matrix): Matrix = other.then(this)

    fun translate(tx: Double, ty: Double): Matrix = Matrix(1.0, 0.0, 0.0, 1.0, tx, ty).then(this)
    fun scale(sx: Double, sy: Double = sx, px: Double, py: Double): Matrix =
        translate(-px, -py).preMultiply(Matrix(sx, 0.0, 0.0, sy, 0.0, 0.0)).preMultiply(translate(px, py))

    fun rotateDeg(deg: Double): Matrix {
        val r = Math.toRadians(deg)
        val ca = cos(r)
        val sa = sin(r)
        return Matrix(a = ca, b = sa, c = -sa, d = ca)
    }

    fun invert(): Matrix {
        val det = a * d - b * c
        require(det != 0.0) { "Singular matrix cannot be inverted" }
        return Matrix(
            a = d / det, b = -b / det,
            c = -c / det, d = a / det,
            e = (c * f - d * e) / det,
            f = (b * e - a * f) / det,
        )
    }

    fun transformBounds(bounds: Bounds?): Bounds? {
        if (bounds == null) return null
        val pts = listOf(
            Vec(bounds.x, bounds.y), Vec(bounds.x + bounds.width, bounds.y),
            Vec(bounds.x, bounds.y + bounds.height), Vec(bounds.x + bounds.width, bounds.y + bounds.height),
        ).map { apply(it) }
        val nx = pts.minOf { it.x }
        val vmaxY = pts.minOf { it.y }
        val maxX = pts.maxOf { it.x }
        val maxY = pts.maxOf { it.y }
        return Bounds(nx, vmaxY, maxX - nx, maxY - vmaxY)
    }

    companion object {
        val IDENTITY = Matrix()

        /** SVG transform文字列を解析する（translate/scale/rotate/skewX/skewY/matrix、複数連結対応） */
        fun fromSvgTransform(value: String): Matrix {
            var m = IDENTITY
            val fnPattern = Regex("""([A-Za-z]+)\s*\(([^)]*)\)""")
            for (match in fnPattern.findAll(value)) {
                val name = match.groupValues[1].trim().lowercase()
                val args = match.groupValues[2].split(Regex("[,\\s]+"))
                    .filter { it.isNotEmpty() }
                    .mapNotNull { dev.svgpro.core.util.Numbers.parse(it) }
                // SVG仕様: transform="A B C" は C→B→A の順に点へ適用（右から左）
                m = applyFn(name, args).then(m)
            }
            return m
        }

        private fun applyFn(name: String, args: List<Double>): Matrix = when (name) {
            "translate" -> Matrix(e = args.getOrElse(0) { 0.0 }, f = args.getOrElse(1) { 0.0 })
            "scale" -> {
                val sx = args.getOrElse(0) { 1.0 }
                Matrix(a = sx, d = args.getOrElse(1) { sx })
            }
            "rotate" -> {
                val r = IDENTITY.rotateDeg(args.getOrElse(0) { 0.0 })
                if (args.size >= 3) {
                    // rotate(a, cx, cy) = translate(-cx,-cy) → rotate → translate(+cx,+cy) の順に適用
                    val cx = args[1]
                    val cy = args[2]
                    Matrix(e = -cx, f = -cy).then(r).then(Matrix(e = cx, f = cy))
                } else r
            }
            "skewx" -> Matrix(c = Math.tan(Math.toRadians(args.getOrElse(0) { 0.0 })))
            "skewy" -> Matrix(b = Math.tan(Math.toRadians(args.getOrElse(0) { 0.0 })))
            "matrix" -> Matrix(
                a = args.getOrElse(0) { 1.0 }, b = args.getOrElse(1) { 0.0 },
                c = args.getOrElse(2) { 0.0 }, d = args.getOrElse(3) { 1.0 },
                e = args.getOrElse(4) { 0.0 }, f = args.getOrElse(5) { 0.0 },
            )
            else -> IDENTITY
        }

        /** toSvg属性形式（SVG "matrix(a,b,c,d,e,f)"） */
        fun toSvgString(m: Matrix): String =
            "matrix(${Numbers.format(m.a)},${Numbers.format(m.b)},${Numbers.format(m.c)}," +
                "${Numbers.format(m.d)},${Numbers.format(m.e)},${Numbers.format(m.f)})"
    }
}
