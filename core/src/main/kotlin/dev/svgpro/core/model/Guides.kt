package dev.svgpro.core.model

import dev.svgpro.core.geom.Vec
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * エディタガイド（整列補助線）。
 * ドキュメント属性 `data-svgpro-guides` に可逆保存し、SVGの構造には影響しない。
 *
 * - 水平/垂直ガイド: `pos`（水平ならy値、垂直ならx値）
 * - 斜めガイド: `angle`（度・user空間）＋ `point`（線上の1点）。任意方向の基準線にスナップできる
 */
data class Guide(
    val horizontal: Boolean,
    val pos: Double,
    val locked: Boolean = false,
    val visible: Boolean = true,
    /** 斜めガイドの角度（度。0=右向き水平、90=下向き垂直）。nullは水平/垂直ガイド */
    val angle: Double? = null,
    /** 斜めガイドが通る点（user座標）。angle != null のとき有効 */
    val point: Vec? = null,
) {
    /** 斜めガイドか */
    val isAngled: Boolean get() = angle != null && point != null

    /** この直線が通る点 */
    fun anchor(): Vec = point ?: if (horizontal) Vec(0.0, pos) else Vec(pos, 0.0)

    /** 線の方向単位ベクトル */
    fun direction(): Vec {
        val r = Math.toRadians(angle ?: if (horizontal) 0.0 else 90.0)
        return Vec(cos(r), sin(r))
    }

    /** 点からこの直線までの垂線距離 */
    fun distanceTo(p: Vec): Double {
        val d = direction()
        val v = p - anchor()
        val t = v.x * d.x + v.y * d.y
        val px = v.x - t * d.x
        val py = v.y - t * d.y
        return sqrt(px * px + py * py)
    }

    /** 点をこの直線上へ投影 */
    fun project(p: Vec): Vec {
        val d = direction()
        val a = anchor()
        val v = p - a
        val t = v.x * d.x + v.y * d.y
        return a + d * t
    }

    fun encode(): String {
        val sb = StringBuilder()
        if (isAngled) {
            // a<angle>@<x>,<y>
            sb.append('a')
            sb.append(dev.svgpro.core.util.Numbers.format(angle!!))
            sb.append('@')
            sb.append(dev.svgpro.core.util.Numbers.format(point!!.x))
            sb.append(',')
            sb.append(dev.svgpro.core.util.Numbers.format(point!!.y))
        } else {
            sb.append(if (horizontal) 'h' else 'v')
            sb.append(dev.svgpro.core.util.Numbers.format(pos))
        }
        if (locked) sb.append('L')
        if (!visible) sb.append('H')
        return sb.toString()
    }

    companion object {
        const val ATTR = "data-svgpro-guides"

        /** 任意角度のガイドを作成する */
        fun angled(angleDeg: Double, through: Vec, locked: Boolean = false, visible: Boolean = true): Guide =
            Guide(horizontal = false, pos = 0.0, locked = locked, visible = visible, angle = angleDeg, point = through)

        /** `45@10,20` 形式の斜めガイド本体をパース */
        private fun parseAngled(body: String): Pair<Double, Vec>? {
            val at = body.indexOf('@')
            if (at <= 0) return null
            val ang = dev.svgpro.core.util.Numbers.parse(body.substring(0, at)) ?: return null
            val parts = body.substring(at + 1).split(',')
            if (parts.size != 2) return null
            val x = dev.svgpro.core.util.Numbers.parse(parts[0]) ?: return null
            val y = dev.svgpro.core.util.Numbers.parse(parts[1]) ?: return null
            return ang to Vec(x, y)
        }

        fun decode(s: String?): Guide? {
            if (s.isNullOrEmpty()) return null
            val t = s.trim()
            if (t.length < 2) return null
            var locked = false
            var visible = true
            var body = t
            // 末尾フラグ（L=ロック, H=非表示）。エンコード順 L→H に対して逆順に剥がす
            while (body.length > 1) {
                when (body.last()) {
                    'L' -> { locked = true; body = body.dropLast(1) }
                    'H' -> { visible = false; body = body.dropLast(1) }
                    else -> break
                }
            }
            return when (body[0]) {
                'h', 'H' -> {
                    val pos = dev.svgpro.core.util.Numbers.parse(body.substring(1)) ?: return null
                    Guide(true, pos, locked, visible)
                }
                'v', 'V' -> {
                    val pos = dev.svgpro.core.util.Numbers.parse(body.substring(1)) ?: return null
                    Guide(false, pos, locked, visible)
                }
                'a', 'A' -> {
                    val (ang, pt) = parseAngled(body.substring(1)) ?: return null
                    Guide(false, 0.0, locked, visible, angle = ang, point = pt)
                }
                else -> null
            }
        }
    }

    object Codec {
        /** ガイド列 → 属性値 */
        fun encode(guides: List<Guide>): String? =
            if (guides.isEmpty()) null else guides.joinToString("|") { it.encode() }

        /** 属性値 → ガイド列（不正エントリは無視） */
        fun decode(value: String?): List<Guide> {
            if (value.isNullOrBlank()) return emptyList()
            return value.split('|').mapNotNull { Guide.decode(it) }
        }
    }
}
