package dev.svgpro.core.path

/**
 * SVG path data の1セグメント（正規形）。
 *
 * 正規化:
 *  - S → Cubic（c1 = 前c2の現在点反射を解析時に展開）
 *  - T → Quad
 *  - H/V → Line（元記法を form で保持し、シリアライズ時に戻す）
 * そのため本モジュールを扱うセグメントは M/L/C/Q/A/Z (+formフラグ) のみ。
 */
sealed class Seg {
    data class Move(var x: Double, var y: Double) : Seg()

    data class Line(var x: Double, var y: Double, var form: Int = FORM_LINE) : Seg() {
        companion object {
            const val FORM_LINE = 0
            const val FORM_H = 1
            const val FORM_V = 2
        }
    }

    data class Cubic(
        var c1x: Double, var c1y: Double,
        var c2x: Double, var c2y: Double,
        var x: Double, var y: Double,
        /** S記法として書き戻すか */
        var smooth: Boolean = false,
    ) : Seg()

    data class Quad(var cx: Double, var cy: Double, var x: Double, var y: Double, /** T記法として書き戻すか */ var smooth: Boolean = false) : Seg()

    data class Arc(
        var rx: Double, var ry: Double, var rot: Double,
        val largeArc: Boolean, val sweep: Boolean,
        var x: Double, var y: Double,
    ) : Seg()

    data object Close : Seg() {
        private fun readResolve(): Any = Close
    }
}
