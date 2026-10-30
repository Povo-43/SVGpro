package dev.svgpro.core.util

import kotlin.math.abs

/** SVG数値の丸め・整形ユーティリティ。不要な桁を除去し、ファイルサイズを抑える。 */
object Numbers {
    /** 既定の精度（有効桁数） */
    const val MAX_DECIMALS = 4

    fun format(value: Double, maxDecimals: Int = MAX_DECIMALS): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        var v = value
        //丸め誤差吸収
        val scale = Math.pow(10.0, maxDecimals.toDouble())
        v = Math.round(v * scale) / scale
        if (v == 0.0) return "0"
        val absV = abs(v)
        if (absV >= 1e10 || (absV < 1e-6 && absV != 0.0)) {
            //指数表記はSVGでは利用可能だが、可読性のため通常表記に置換
            return plainString(v)
        }
        var s = v.toString()
        if (s.contains('E') || s.contains('e')) {
            s = plainString(v)
        }
        if (s.contains('.')) {
            s = s.trimEnd('0').trimEnd('.')
        }
        return if (s == "-0") "0" else s
    }

    private fun plainString(v: Double): String {
        val s = java.math.BigDecimal(v).toPlainString()
        var out = s
        if (out.contains('.')) out = out.trimEnd('0').trimEnd('.')
        return if (out == "-0") "0" else out
    }

    fun parse(s: String?): Double? {
        if (s.isNullOrEmpty()) return null
        val v = s.trim().toDoubleOrNull()
        if (v != null) return v
        // "10px" "1.5em" 等の単位付き数値から数値部を取り出す
        return Regex("""^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?""").find(s.trim())?.value?.toDoubleOrNull()
    }
}
