package dev.svgpro.core.path

import dev.svgpro.core.geom.Vec
import dev.svgpro.core.util.Numbers

/**
 * 正規化セグメント列 → SVG path "d" 文字列。
 * パーサが保持した記法（H/V/S/T）は可能な限り復元し、編集された座標は通常のL/C/Qで書き出す。
 */
object PathWriter {

    fun write(segs: List<Seg>): String {
        val sb = StringBuilder()
        for (s in segs) {
            when (s) {
                is Seg.Move -> {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append("M").append(Numbers.format(s.x)).append(' ').append(Numbers.format(s.y))
                }
                is Seg.Line -> {
                    if (sb.isNotEmpty()) sb.append(' ')
                    when (s.form) {
                        Seg.Line.FORM_H -> sb.append("H").append(Numbers.format(s.x))
                        Seg.Line.FORM_V -> sb.append("V").append(Numbers.format(s.y))
                        else -> sb.append("L").append(Numbers.format(s.x)).append(' ').append(Numbers.format(s.y))
                    }
                }
                is Seg.Cubic -> {
                    if (sb.isNotEmpty()) sb.append(' ')
                    if (s.smooth) {
                        //S: c1は書かない（ 前セグメントのc2反射として解釈される）
                        sb.append("S").append(Numbers.format(s.c2x)).append(' ').append(Numbers.format(s.c2y))
                            .append(' ').append(Numbers.format(s.x)).append(' ').append(Numbers.format(s.y))
                    } else {
                        sb.append("C")
                        sb.append(Numbers.format(s.c1x)).append(' ').append(Numbers.format(s.c1y)).append(' ')
                        sb.append(Numbers.format(s.c2x)).append(' ').append(Numbers.format(s.c2y)).append(' ')
                        sb.append(Numbers.format(s.x)).append(' ').append(Numbers.format(s.y))
                    }
                }
                is Seg.Quad -> {
                    if (sb.isNotEmpty()) sb.append(' ')
                    if (s.smooth) {
                        sb.append("T").append(Numbers.format(s.x)).append(' ').append(Numbers.format(s.y))
                    } else {
                        sb.append("Q")
                        sb.append(Numbers.format(s.cx)).append(' ').append(Numbers.format(s.cy)).append(' ')
                        sb.append(Numbers.format(s.x)).append(' ').append(Numbers.format(s.y))
                    }
                }
                is Seg.Arc -> {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append("A")
                    sb.append(Numbers.format(s.rx)).append(' ').append(Numbers.format(s.ry)).append(' ')
                    sb.append(Numbers.format(s.rot)).append(' ')
                    sb.append(if (s.largeArc) 1 else 0).append(' ').append(if (s.sweep) 1 else 0).append(' ')
                    sb.append(Numbers.format(s.x)).append(' ').append(Numbers.format(s.y))
                }
                Seg.Close -> {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append("Z")
                }
            }
        }
        return sb.toString()
    }
}
