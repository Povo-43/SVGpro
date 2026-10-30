package dev.svgpro.core.path

import kotlin.math.abs

/**
 * SVG path "d" 属性 → 正規化seg列のパーサ。
 * 対応: M m L l H h V v C c S s Q q T t A a Z z（省略記法・スペース/カンマ区切り tolerances）
 * エラー時は解析できた部分までのセグメントを返す（描画可能な限り保持方針）。
 */
class PathParser(private val src: String) {
    private var i = 0
    private var curX = 0.0
    private var curY = 0.0

    private val numRegex = Regex("""[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?""")

    fun parse(): MutableList<Seg> {
        val segs = ArrayList<Seg>()
        var lastCmd = ' '
        var explicit = false
        var subStartX = 0.0
        var subStartY = 0.0

        fun number(): Double? {
            skipSep()
            val m = numRegex.matchAt(src, i) ?: return null
            i = m.range.last + 1
            return m.value.toDouble()
        }

        fun flag(): Boolean? {
            skipSep()
            if (i >= src.length) return null
            return when (src[i]) {
                '1' -> { i++; true }
                '0' -> { i++; false }
                else -> null
            }
        }

        while (i < src.length) {
            skipSep()
            if (i >= src.length) break
            val c = src[i]
            var cmd: Char
            if (c in COMMANDS) {
                cmd = c
                i++
                explicit = true
            } else {
                if (lastCmd == ' ') break
                cmd = lastCmd
            }
            lastCmd = cmd
            val relative = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = number() ?: break
                    val y = number() ?: break
                    val ax = if (relative) curX + x else x
                    val ay = if (relative) curY + y else y
                    if (explicit) {
                        subStartX = ax
                        subStartY = ay
                        segs.add(Seg.Move(ax, ay))
                    } else {
                        // "M1 2 3 4" の後続ペアは暗黙のL
                        segs.add(Seg.Line(ax, ay))
                    }
                    curX = ax; curY = ay
                }
                'L' -> {
                    val x = number() ?: break
                    val y = number() ?: break
                    curX = if (relative) curX + x else x
                    curY = if (relative) curY + y else y
                    segs.add(Seg.Line(curX, curY))
                }
                'H' -> {
                    val x = number() ?: break
                    curX = if (relative) curX + x else x
                    segs.add(Seg.Line(curX, curY, Seg.Line.FORM_H))
                }
                'V' -> {
                    val y = number() ?: break
                    curY = if (relative) curY + y else y
                    segs.add(Seg.Line(curX, curY, Seg.Line.FORM_V))
                }
                'C' -> {
                    val c1x = number() ?: break
                    val c1y = number() ?: break
                    val c2x = number() ?: break
                    val c2y = number() ?: break
                    val x = number() ?: break
                    val y = number() ?: break
                    val a1x = if (relative) curX + c1x else c1x
                    val a1y = if (relative) curY + c1y else c1y
                    val a2x = if (relative) curX + c2x else c2x
                    val a2y = if (relative) curY + c2y else c2y
                    curX = if (relative) curX + x else x
                    curY = if (relative) curY + y else y
                    segs.add(Seg.Cubic(a1x, a1y, a2x, a2y, curX, curY))
                }
                'S' -> {
                    val c2x = number() ?: break
                    val c2y = number() ?: break
                    val x = number() ?: break
                    val y = number() ?: break
                    val a2x = if (relative) curX + c2x else c2x
                    val a2y = if (relative) curY + c2y else c2y
                    val (rx, ry) = reflectedControl(segs)
                    curX = if (relative) curX + x else x
                    curY = if (relative) curY + y else y
                    segs.add(Seg.Cubic(rx, ry, a2x, a2y, curX, curY, smooth = true))
                }
                'Q' -> {
                    val cx = number() ?: break
                    val cy = number() ?: break
                    val x = number() ?: break
                    val y = number() ?: break
                    val acx = if (relative) curX + cx else cx
                    val acy = if (relative) curY + cy else cy
                    curX = if (relative) curX + x else x
                    curY = if (relative) curY + y else y
                    segs.add(Seg.Quad(acx, acy, curX, curY))
                }
                'T' -> {
                    val x = number() ?: break
                    val y = number() ?: break
                    val (rx, ry) = reflectedQuadControl(segs)
                    curX = if (relative) curX + x else x
                    curY = if (relative) curY + y else y
                    segs.add(Seg.Quad(rx, ry, curX, curY, smooth = true))
                }
                'A' -> {
                    val rx = number() ?: break
                    val ry = number() ?: break
                    val rot = number() ?: break
                    val la = flag() ?: break
                    val sw = flag() ?: break
                    val x = number() ?: break
                    val y = number() ?: break
                    curX = if (relative) curX + x else x
                    curY = if (relative) curY + y else y
                    segs.add(
                        Seg.Arc(
                            abs(rx).takeIf { it > 0.0 } ?: 1.0,
                            abs(ry).takeIf { it > 0.0 } ?: 1.0,
                            rot, la, sw, curX, curY,
                        ),
                    )
                }
                'Z' -> {
                    segs.add(Seg.Close)
                    curX = subStartX
                    curY = subStartY
                }
            }
            explicit = false
        }
        // 末尾の単独Moveは実体がないので除去
        if (segs.isNotEmpty() && segs.last() is Seg.Move) segs.removeAt(segs.size - 1)
        return segs
    }

    /** 数値欠損（数値読み取り失敗）はその時点で終了。解析済み部分は保持される */

    private fun skipSep() {
        while (i < src.length && (src[i].isWhitespace() || src[i] == ',')) i++
    }

    /** 直前のCubicのc2を現在点で反射（S用） */
    private fun reflectedControl(segs: List<Seg>): Pair<Double, Double> {
        var k = segs.size - 1
        while (k >= 0 && segs[k] != Seg.Close) {
            val s = segs[k]
            if (s is Seg.Cubic) return (2.0 * curX - s.c2x) to (2.0 * curY - s.c2y)
            if (s is Seg.Quad) return (2.0 * curX - s.cx) to (2.0 * curY - s.cy)
            k--
        }
        return curX to curY
    }

    /** 直前のQuadの制御点を現在点で反射（T用） */
    private fun reflectedQuadControl(segs: List<Seg>): Pair<Double, Double> {
        var k = segs.size - 1
        while (k >= 0 && segs[k] != Seg.Close) {
            val s = segs[k]
            if (s is Seg.Quad) return (2.0 * curX - s.cx) to (2.0 * curY - s.cy)
            k--
        }
        return curX to curY
    }

    companion object {
        private const val COMMANDS = "MmLlHhVvCcSsQqTtAaZz"
    }
}
