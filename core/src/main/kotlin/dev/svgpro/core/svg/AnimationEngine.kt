package dev.svgpro.core.svg

import dev.svgpro.core.model.Color
import dev.svgpro.core.geom.Matrix
import dev.svgpro.core.geom.Vec
import dev.svgpro.core.util.Numbers
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * SVGアニメーションエンジン。
 *
 * 指定時刻における各アニメーションの「現在値」を計算する。
 * 補間対象: 数値、カラー、変換（translate/scale/rotate/skewX/skewY）、汎用文字列。
 *
 * 実際の描画への適用は呼び出し側（Editor / SvgRenderer）が行う。
 */

/** アニメーション1本の現在値計算結果 */
sealed class AnimValue {
    data class Number(val v: Double) : AnimValue()
    data class Color(val argb: Int) : AnimValue()
    data class Transform(val matrix: Matrix) : AnimValue()
    data class String(val s: kotlin.String) : AnimValue()
}

/** アニメーションが現在アクティブかどうか */
enum class AnimPhase {
    BEFORE,    // begin前
    ACTIVE,    // 再生中
    AFTER,     // 終了後（fill=removeなら無効、freezeなら最終値を保持）
}

/** タイムパラメータ（0.0〜1.0）をcalcModeに従って補間係数に変換 */
private fun calcModeFactor(mode: CalcMode, t: Double, keyTimes: List<Double>?, keySplines: List<Pair<Double, Double>>?): Double {
    val tt = t.coerceIn(0.0, 1.0)
    return when (mode) {
        CalcMode.DISCRETE -> if (tt >= 1.0) 1.0 else 0.0
        CalcMode.LINEAR -> tt
        CalcMode.PACED -> tt // 簡易: 等分割
        CalcMode.SPLINE -> {
            if (keySplines == null || keySplines.isEmpty()) tt
            else splineAt(tt, keyTimes, keySplines)
        }
    }
}

/** スプライン補間（keySplinesは (x1,y1)のペア） */
private fun splineAt(t: Double, keyTimes: List<Double>?, keySplines: List<Pair<Double, Double>>): Double {
    val times = keyTimes ?: listOf(0.0, 1.0)
    var seg = 0
    for (i in times.indices) {
        if (t >= times[i]) seg = i else break
    }
    val segIndex = (seg - 1).coerceIn(0, keySplines.lastIndex)
    val (x1, y1) = keySplines[segIndex]
    val t0 = times.getOrNull(segIndex) ?: 0.0
    val t1 = times.getOrNull(segIndex + 1) ?: 1.0
    val segT = if (t1 == t0) 0.0 else ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
    return cubicBezierY(x1, y1, segT)
}

/** 3次ベジェ曲線のy値をx=tに対応する値で計算（簡易：x2=1, y2=0として近似） */
private fun cubicBezierY(x1: Double, y1: Double, t: Double): Double {
    val c = 3.0 * (1.0 - t) * (1.0 - t) * t
    val b = 3.0 * (1.0 - t) * t * t
    val a = t * t * t
    return c * x1 + b * y1 + a * 1.0
}

/** 数値リストをパース（"0; 50; 100"） */
fun parseNumberList(value: String?): List<Double> {
    if (value.isNullOrEmpty()) return emptyList()
    return value.split(Regex("[;,\\s]+")).filter { it.isNotEmpty() }
        .mapNotNull { Numbers.parse(it) }
}

/** カラーリストをパース */
fun parseColorList(value: String?): List<Int> {
    if (value.isNullOrEmpty()) return emptyList()
    return value.split(Regex("[;,\\s]+")).filter { it.isNotEmpty() }
        .mapNotNull { Color.parse(it) }
}

/** 2つの値をt（0〜1）で線形補間 */
private fun lerpNumber(a: Double, b: Double, t: Double): Double = a + (b - a) * t

private fun lerpColor(a: Int, b: Int, t: Double): Int {
    val ar = (a shr 24) and 0xFF
    val ag = (a shr 16) and 0xFF
    val ab = (a shr 8) and 0xFF
    val aa = a and 0xFF
    val br = (b shr 24) and 0xFF
    val bg = (b shr 16) and 0xFF
    val bb = (b shr 8) and 0xFF
    val ba = b and 0xFF
    return ((aa + lerpNumber(0.0, (ba - aa).toDouble(), t)).toInt() shl 24) or
        ((ar + lerpNumber(ar.toDouble(), br.toDouble(), t)).toInt() shl 16) or
        ((ag + lerpNumber(ag.toDouble(), bg.toDouble(), t)).toInt() shl 8) or
        (ab + lerpNumber(ab.toDouble(), bb.toDouble(), t)).toInt()
}

/**
 * アニメーションの「単純継続時間（simple duration）」内の相対時刻（0〜dur）を計算。
 * 戻り値: (フェーズ, 0.0〜1.0の正規化済み時刻)。アクティブでなければnull。
 */
fun animationProgress(anim: Animation, globalTime: Double): Pair<AnimPhase, Double>? {
    val begin = parseTimeSeconds(anim.begin)
    val dur = parseTimeSeconds(anim.dur)
    if (dur <= 0) return null
    val repeatCount = anim.repeatCount?.toDoubleOrNull()
    val repeatDur = parseTimeSeconds(anim.repeatDur)
    val end = parseTimeSeconds(anim.end)

    var activeDur = dur
    if (repeatCount != null && repeatCount > 0) activeDur = dur * repeatCount
    if (repeatDur != null && repeatDur > 0) activeDur = min(activeDur, repeatDur)

    val activeEnd = begin + activeDur
    val finalEnd = if (end > 0) max(activeEnd, begin + end) else activeEnd

    if (globalTime < begin) return null
    if (globalTime > finalEnd && anim.fill == FillMode.REMOVE) return null

    var elapsed = max(0.0, globalTime - begin)
    var phase: AnimPhase
    var simpleT: Double
    if (globalTime >= activeEnd) {
        phase = AnimPhase.AFTER
        simpleT = 1.0
    } else {
        phase = AnimPhase.ACTIVE
        simpleT = elapsed / dur
    }

    var normalized = simpleT
    if (phase == AnimPhase.ACTIVE) {
        if (repeatCount != null && repeatCount > 0) {
            val cycle = elapsed / dur
            normalized = cycle - floor(cycle)
        } else if (repeatDur != null && repeatDur > 0) {
            val cycle = elapsed / dur
            normalized = cycle - floor(cycle)
        }
    }

    return phase to normalized.coerceIn(0.0, 1.0)
}

/**
 * 指定時刻におけるアニメーションの現在値を計算する。
 * from/to/values/keyTimes/keySplinesに対応。
 */
fun computeValue(anim: Animation, globalTime: Double): Pair<AnimPhase, AnimValue>? {
    val progress = animationProgress(anim, globalTime) ?: return null
    val (phase, t) = progress

    val keyTimes = parseSemicolonList(anim.keyTimes).mapNotNull { Numbers.parse(it) }
    val keySplines = parseSemicolonList(anim.keySplines).chunked(4).mapNotNull { chunk ->
        if (chunk.size < 4) null
        else Pair(Numbers.parse(chunk[0]) ?: 0.0, Numbers.parse(chunk[1]) ?: 0.0)
    }

    val mode = calcModeFactor(anim.calcMode, t, keyTimes, keySplines)

    return when {
        // カラー補間
        anim.attributeName.equals("fill", ignoreCase = true) ||
        anim.attributeName.equals("stroke", ignoreCase = true) ||
        anim.attributeName.equals("stop-color", ignoreCase = true) -> {
            val fromColor = Color.parse(anim.from ?: "")
            val toColor = Color.parse(anim.to ?: "")
            val values = parseColorList(anim.values)
            val result = when {
                values.isNotEmpty() -> {
                    val idx = (mode * (values.size - 1)).toInt().coerceIn(0, values.lastIndex)
                    AnimValue.Color(values[idx])
                }
                fromColor != null && toColor != null -> AnimValue.Color(lerpColor(fromColor, toColor, mode))
                fromColor != null -> AnimValue.Color(fromColor)
                toColor != null -> AnimValue.Color(toColor)
                else -> null
            }
            result?.let { phase to it }
        }
        // 数値補間
        else -> {
            val fromNum = Numbers.parse(anim.from ?: "")
            val toNum = Numbers.parse(anim.to ?: "")
            val values = parseNumberList(anim.values)
            val result = when {
                values.isNotEmpty() -> {
                    val idx = (mode * (values.size - 1)).toInt().coerceIn(0, values.lastIndex)
                    AnimValue.Number(values[idx])
                }
                fromNum != null && toNum != null -> AnimValue.Number(lerpNumber(fromNum, toNum, mode))
                anim.by != null && fromNum != null -> {
                    // by: from からの相対値（from + by）へ補間
                    AnimValue.Number(lerpNumber(fromNum, fromNum + (Numbers.parse(anim.by ?: "") ?: 0.0), mode))
                }
                fromNum != null -> AnimValue.Number(fromNum)
                toNum != null -> AnimValue.Number(toNum)
                else -> null
            }
            result?.let { phase to it }
        }
    }
}

/**
 * animateTransform用の変換行列を計算。
 * type: translate/scale/rotate/skewX/skewY
 */
fun computeTransform(anim: Animation, globalTime: Double): Pair<AnimPhase, Matrix>? {
    val progress = animationProgress(anim, globalTime) ?: return null
    val (phase, t) = progress

    val type = anim.transformType ?: "translate"
    val keyTimes = parseSemicolonList(anim.keyTimes).mapNotNull { Numbers.parse(it) }
    val keySplines = parseSemicolonList(anim.keySplines).chunked(4).mapNotNull { chunk ->
        if (chunk.size < 4) null
        else Pair(Numbers.parse(chunk[0]) ?: 0.0, Numbers.parse(chunk[1]) ?: 0.0)
    }
    val mode = calcModeFactor(anim.calcMode, t, keyTimes, keySplines)

    val fromVals = parseTransformParams(anim.from, type)
    val toVals = parseTransformParams(anim.to, type)
    val valuesList = parseSemicolonList(anim.values).mapNotNull { parseTransformParams(it, type) }

    val matrix: Matrix
    when {
        valuesList.isNotEmpty() -> {
            val idx = (mode * (valuesList.size - 1)).toInt().coerceIn(0, valuesList.lastIndex)
            matrix = paramsToMatrix(type, valuesList[idx])
        }
        fromVals != null && toVals != null -> {
            matrix = interpolateMatrix(type, fromVals, toVals, mode)
        }
        fromVals != null -> matrix = paramsToMatrix(type, fromVals)
        toVals != null -> matrix = paramsToMatrix(type, toVals)
        else -> return null
    }

    return phase to matrix
}

/** transformパラメータ文字列（"50 100" や "45" 等）をDoubleリストに変換 */
private fun parseTransformParams(value: String?, type: String): List<Double>? {
    if (value.isNullOrEmpty()) return null
    return value.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.mapNotNull { Numbers.parse(it) }
}

/** パラメータリストから変換行列を生成 */
private fun paramsToMatrix(type: String, params: List<Double>): Matrix {
    return when (type) {
        "translate" -> {
            val tx = params.getOrElse(0) { 0.0 }
            val ty = params.getOrElse(1) { 0.0 }
            Matrix(e = tx, f = ty)
        }
        "scale" -> {
            val sx = params.getOrElse(0) { 1.0 }
            val sy = params.getOrElse(1) { sx }
            Matrix(a = sx, d = sy)
        }
        "rotate" -> {
            val angle = params.getOrElse(0) { 0.0 }
            if (params.size >= 3) {
                val cx = params[1]
                val cy = params[2]
                // rotate(a, cx, cy) = translate(-cx,-cy) → rotate → translate(+cx,+cy)
                Matrix(e = -cx, f = -cy).then(Matrix().rotateDeg(angle)).then(Matrix(e = cx, f = cy))
            } else {
                Matrix().rotateDeg(angle)
            }
        }
        "skewX" -> {
            val angle = params.getOrElse(0) { 0.0 }
            Matrix(c = tan(Math.toRadians(angle)))
        }
        "skewY" -> {
            val angle = params.getOrElse(0) { 0.0 }
            Matrix(b = tan(Math.toRadians(angle)))
        }
        else -> Matrix()
    }
}

/** 2つの変換行列をtで線形補間 */
private fun interpolateMatrix(type: String, from: List<Double>, to: List<Double>, t: Double): Matrix {
    val f = t.coerceIn(0.0, 1.0)
    return when (type) {
        "translate" -> {
            val tx = lerpNumber(from.getOrElse(0) { 0.0 }, to.getOrElse(0) { 0.0 }, f)
            val ty = lerpNumber(from.getOrElse(1) { 0.0 }, to.getOrElse(1) { 0.0 }, f)
            Matrix(e = tx, f = ty)
        }
        "scale" -> {
            val sx = lerpNumber(from.getOrElse(0) { 1.0 }, to.getOrElse(0) { 1.0 }, f)
            val sy = lerpNumber(from.getOrElse(1) { sx }, to.getOrElse(1) { sx }, f)
            Matrix(a = sx, d = sy)
        }
        "rotate" -> {
            val angle = lerpNumber(from.getOrElse(0) { 0.0 }, to.getOrElse(0) { 0.0 }, f)
            val cx = lerpNumber(from.getOrElse(1) { 0.0 }, to.getOrElse(1) { 0.0 }, f)
            val cy = lerpNumber(from.getOrElse(2) { 0.0 }, to.getOrNull(2) ?: 0.0, f)
            Matrix(e = -cx, f = -cy).then(Matrix().rotateDeg(angle)).then(Matrix(e = cx, f = cy))
        }
        else -> Matrix()
    }
}

private fun tan(v: Double): Double = kotlin.math.tan(v * kotlin.math.PI / 180.0)
