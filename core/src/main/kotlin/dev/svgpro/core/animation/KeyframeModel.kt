package dev.svgpro.core.animation

import dev.svgpro.core.model.Color
import dev.svgpro.core.util.Numbers

/**
 * キーフレームアニメーションのデータモデル（After Effects 風）。
 *
 * オブジェクト（targetId）に対して、時刻ごとの属性値スナップショット（キーフレーム）を
 * 持つ。キーフレーム間は線形補間（イージング対応）で滑らかに変化する。
 *
 * 対応プロパティ: x, y, transform, fill, stroke, opacity, stroke-width, など
 * （SVG要素の任意の属性をアニメーション化可能）
 */

/** イージング種別（After Effects 風） */
enum class EaseType {
    LINEAR,       // 一定速度
    EASE_IN,      // 緩やかに加速
    EASE_OUT,     // 緩やかに減速
    EASE_IN_OUT,  // 緩やかに加速して緩やかに減速
    BEZIER,       // カスタム3次ベジェ（x1,y1,x2,y2）
}

/** 3次ベジェイージング制御点 */
data class BezierEase(
    val x1: Double = 0.25,
    val y1: Double = 0.1,
    val x2: Double = 0.25,
    val y2: Double = 1.0,
) {
    fun toSvgKeySplines(): String =
        "${Numbers.format(x1)} ${Numbers.format(y1)} ${Numbers.format(x2)} ${Numbers.format(y2)}"

    companion object {
        fun fromSvgKeySplines(s: String?): BezierEase? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
                .mapNotNull { Numbers.parse(it) }
            if (parts.size < 4) return null
            return BezierEase(parts[0], parts[1], parts[2], parts[3])
        }

        // After Effects 既定のイージング
        val defaultEaseInOut = BezierEase(0.25, 0.1, 0.25, 1.0)
        val easeIn = BezierEase(0.42, 0.0, 1.0, 1.0)
        val easeOut = BezierEase(0.0, 0.0, 0.58, 1.0)
        val linear = BezierEase(0.0, 0.0, 1.0, 1.0)
    }
}

/** 1つのキーフレーム：特定時刻における属性値のスナップショット */
data class Keyframe(
    /** ミリ秒（トラック開始からの相対時刻） */
    val timeMs: Long = 0,
    /** 属性名 -> 文字列値（"100 200", "#FF0000", "1.5" 等） */
    val properties: LinkedHashMap<String, String> = LinkedHashMap(),
    /** イージング種別 */
    val easeType: EaseType = EaseType.LINEAR,
    /** カスタムベジェ制御点（easeType=BEZIER のみ使用） */
    val bezier: BezierEase = BezierEase(),
) {
    /** このキーフレームが持つプロパティキー一覧 */
    val keys: List<String> get() = properties.keys.toList()

    /** イージングを文字列でシリアライズ（保存用） */
    fun easeToString(): String = when (easeType) {
        EaseType.LINEAR -> "linear"
        EaseType.EASE_IN -> "easeIn"
        EaseType.EASE_OUT -> "easeOut"
        EaseType.EASE_IN_OUT -> "easeInOut"
        EaseType.BEZIER -> "bezier:${bezier.toSvgKeySplines()}"
    }

    /** イージングを文字列から復元 */
    companion object {
        fun easeFromString(s: String?): Pair<EaseType, BezierEase> {
            if (s == null || s.isBlank()) return EaseType.LINEAR to BezierEase()
            return when {
                s == "linear" -> EaseType.LINEAR to BezierEase()
                s == "easeIn" -> EaseType.EASE_IN to BezierEase()
                s == "easeOut" -> EaseType.EASE_OUT to BezierEase()
                s == "easeInOut" -> EaseType.EASE_IN_OUT to BezierEase()
                s.startsWith("bezier:") -> {
                    val splines = s.removePrefix("bezier:")
                    EaseType.BEZIER to (BezierEase.fromSvgKeySplines(splines) ?: BezierEase())
                }
                else -> EaseType.LINEAR to BezierEase()
            }
        }
    }
}

/** レイヤーマーカー（タイムライン上の目印） */
data class Marker(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timeMs: Long = 0,
    val label: String = "",
    val color: String = "#FF0000",
)

/** モーションパス（オブジェクトをパスに沿って移動させる） */
data class MotionPath(
    val pathData: String = "",   // SVG path data (M..., L..., C...)
    val durationMs: Long = 2000, // パスを一周する時間
    val loop: Boolean = false,
    val offset: Double = 0.0,    // パス上の開始オフセット（0〜1）
)

/** 1オブジェクトのアニメーショントラック */
data class AnimationTrack(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** 対象要素のid（doc.root.findById で探索） */
    val targetId: String = "",
    /** 親トラックのid（親子関係。親の変換が子に継承される） */
    val parentId: String? = null,
    /** 表示用オブジェクト名（UI用、保存時は使わない） */
    val targetName: String = "",
    val keyframes: MutableList<Keyframe> = mutableListOf(),
    /** トータル再生時間（ミリ秒、ループ判定に使用） */
    val durationMs: Long = 2000,
    /** ループ再生するか */
    val loop: Boolean = false,
    /** トラック有効/無効 */
    val enabled: Boolean = true,
    /** モーションパス（position がパスに沿って動く） */
    val motionPath: MotionPath? = null,
    /** モーションブラー（動きに応じたぼかし） */
    val motionBlur: Boolean = false,
    /** シャッター角度（度。大きいほどぼかしが強い） */
    val shutterAngle: Double = 180.0,
    /** タイムリマッピング（再生速度倍率。1.0=通常、2.0=2倍速、-1.0=逆再生） */
    val timeRemap: Double = 1.0,
    /** レイヤーマーカー一覧 */
    val markers: MutableList<Marker> = mutableListOf(),
) {
    /** キーフレームを時刻順にソートして取得 */
    val sortedKeyframes: List<Keyframe>
        get() = keyframes.sortedBy { it.timeMs }

    /** 時刻に最も近い前後のキーフレームペアを返す（1つしかない場合は null, null） */
    fun surrounding(timeMs: Long): Pair<Keyframe, Keyframe>? {
        val sorted = sortedKeyframes
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return null
        if (timeMs <= sorted.first().timeMs) return null
        if (timeMs >= sorted.last().timeMs) return null

        var prev = sorted[0]
        for (next in sorted.drop(1)) {
            if (timeMs <= next.timeMs) {
                return prev to next
            }
            prev = next
        }
        return null
    }
}

/** アニメーションプロジェクト全体（ドキュメントに紐付け） */
data class AnimationProject(
    val tracks: MutableList<AnimationTrack> = mutableListOf(),
    /** 全トラック最大の durationMs（Editor 側で再計算して設定する） */
    val totalDurationMs: Long = 2000
)

/**
 * キーフレームアニメーション補間エンジン（After Effects 風）。
 *
 * 指定時刻における各トラックの属性値を計算し、Map<String,String> として返す。
 * 実際のElementへの適用は Editor 側で行う。
 */
class KeyframeEngine {

    /** 3次ベジェ曲線の y 値を x=t に対応する値で計算（ニュートン反復法） */
    private fun cubicBezierY(x1: Double, y1: Double, x2: Double, y2: Double, x: Double): Double {
        // x(t) = 3(1-t)^2*t*x1 + 3(1-t)*t^2*x2 + t^3
        // y(t) = 3(1-t)^2*t*y1 + 3(1-t)*t^2*y2 + t^3
        // x から t を求めて y を返す
        var t = x
        for (i in 0..10) {
            val t2 = t * t
            val t3 = t2 * t
            val xt = 3.0 * (1.0 - t) * (1.0 - t) * t * x1 +
                3.0 * (1.0 - t) * t2 * x2 + t3
            val dx = 3.0 * (1.0 - t) * (1.0 - t) * x1 +
                6.0 * (1.0 - t) * t * x2 - 2.0 * x1 * t - 3.0 * x2 * t + 3.0 * t2
            val diff = xt - x
            if (Math.abs(diff) < 1e-6) break
            t -= diff / (dx.coerceIn(0.001, 1000.0))
            t = t.coerceIn(0.0, 1.0)
        }
        val t2 = t * t
        val t3 = t2 * t
        return 3.0 * (1.0 - t) * (1.0 - t) * t * y1 +
            3.0 * (1.0 - t) * t2 * y2 + t3
    }

    /** イージング関数（t: 0.0〜1.0 -> 補間係数） */
    private fun ease(t: Double, easeType: EaseType, bezier: BezierEase): Double {
        val tt = t.coerceIn(0.0, 1.0)
        return when (easeType) {
            EaseType.LINEAR -> tt
            EaseType.EASE_IN -> tt * tt
            EaseType.EASE_OUT -> 1.0 - (1.0 - tt) * (1.0 - tt)
            EaseType.EASE_IN_OUT -> {
                if (tt < 0.5) 2.0 * tt * tt else 1.0 - ((-2.0 * tt + 2.0) * (-2.0 * tt + 2.0)) / 2.0
            }
            EaseType.BEZIER -> cubicBezierY(bezier.x1, bezier.y1, bezier.x2, bezier.y2, tt)
        }
    }

    /** カラー文字列をARGB Intに変換（失敗時はnull） */
    private fun parseColor(s: String?): Int? {
        if (s == null || s.isBlank()) return null
        return Color.parse(s.trim())
    }

    /** 数値リストをパース（スペース/カンマ区切り） */
    private fun parseNumbers(s: String?): List<Double>? {
        if (s == null || s.isBlank()) return null
        return s.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            .mapNotNull { Numbers.parse(it) }
            .takeIf { it.isNotEmpty() }
    }

    /** 2つの数値リストをtで線形補間 */
    private fun interpolateNumbers(from: List<Double>, to: List<Double>, t: Double): List<Double> {
        val size = maxOf(from.size, to.size)
        return List(size) { i ->
            val f = from.getOrNull(i) ?: 0.0
            val tVal = to.getOrNull(i) ?: 0.0
            f + (tVal - f) * t
        }
    }

    /** 2つのカラーをtで線形補間（ARGB） */
    private fun interpolateColor(from: Int, to: Int, t: Double): Int {
        val fa = (from shr 24) and 0xFF
        val fr = (from shr 16) and 0xFF
        val fg = (from shr 8) and 0xFF
        val fb = from and 0xFF
        val ta = (to shr 24) and 0xFF
        val tr = (to shr 16) and 0xFF
        val tg = (to shr 8) and 0xFF
        val tb = to and 0xFF
        val a = (fa + (ta - fa) * t).toInt()
        val r = (fr + (tr - fr) * t).toInt()
        val g = (fg + (tg - fg) * t).toInt()
        val b = (fb + (tb - fb) * t).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 2つの文字列値をtで補間。同種（数値/カラー/その他）を判定して適切に補間。 */
    private fun interpolateValue(from: String?, to: String?, t: Double): String? {
        if (from == null && to == null) return null
        if (from == null) return to
        if (to == null) return from

        val cFrom = parseColor(from)
        val cTo = parseColor(to)
        if (cFrom != null && cTo != null) {
            val result = interpolateColor(cFrom, cTo, t)
            return Color.css(result)
        }

        val nFrom = parseNumbers(from)
        val nTo = parseNumbers(to)
        if (nFrom != null && nTo != null) {
            val result = interpolateNumbers(nFrom, nTo, t)
            return result.map { Numbers.format(it) }.joinToString(" ")
        }

        // 数値・カラー以外は離散（t>=1ならto、それ以外はfrom）
        return if (t >= 1.0) to else from
    }

    /**
     * SVGパスデータをフラット化して点列を取得。
     * モーションパス用（簡易実装：path のコマンドを解析してサンプリング）。
     */
    private fun samplePathPoints(pathData: String, samples: Int): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        var curX = 0.0
        var curY = 0.0
        val cmdRegex = Regex("""([MLCQZSmlcqzs])([^MLCQZSmlcqzs]*)""")
        for (match in cmdRegex.findAll(pathData)) {
            val cmd = match.groups[1]!!.value.uppercase()
            val args = match.groups[2]!!.value.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
                .mapNotNull { Numbers.parse(it) }
            when (cmd) {
                "M" -> {
                    if (args.size >= 2) {
                        curX = args[0]
                        curY = args[1]
                        points.add(curX to curY)
                    }
                }
                "L" -> {
                    if (args.size >= 2) {
                        curX = args[0]
                        curY = args[1]
                        points.add(curX to curY)
                    }
                }
                "C" -> {
                    // 3次ベジェを簡易サンプリング
                    var i = 0
                    while (i + 5 < args.size) {
                        val cp1x = args[i]
                        val cp1y = args[i + 1]
                        val cp2x = args[i + 2]
                        val cp2y = args[i + 3]
                        val endX = args[i + 4]
                        val endY = args[i + 5]
                        for (s in 0..10) {
                            val tt = s / 10.0
                            val x = Math.pow(1.0 - tt, 3.0) * curX +
                                3.0 * Math.pow(1.0 - tt, 2.0) * tt * cp1x +
                                3.0 * (1.0 - tt) * tt * tt * cp2x +
                                tt * tt * tt * endX
                            val y = Math.pow(1.0 - tt, 3.0) * curY +
                                3.0 * Math.pow(1.0 - tt, 2.0) * tt * cp1y +
                                3.0 * (1.0 - tt) * tt * tt * cp2y +
                                tt * tt * tt * endY
                            points.add(x to y)
                        }
                        curX = endX
                        curY = endY
                        i += 6
                    }
                }
                "Z" -> {
                    // 閉じパス（簡易: 開始点へ戻る）
                }
            }
        }
        return points
    }

    /** モーションパス上の指定時刻（0〜1の正規化時刻）における位置を取得 */
    private fun motionPathPosition(path: MotionPath, normalizedT: Double): Pair<Double, Double>? {
        if (path.pathData.isBlank()) return null
        val points = samplePathPoints(path.pathData, 100)
        if (points.isEmpty()) return null
        val t = normalizedT.coerceIn(0.0, 1.0)
        val idx = t * (points.size - 1)
        val i = idx.toInt().coerceIn(0, points.size - 2)
        val frac = idx - i
        val (x1, y1) = points[i]
        val (x2, y2) = points[i + 1]
        return (x1 + (x2 - x1) * frac) to (y1 + (y2 - y1) * frac)
    }

    /**
     * 指定トラックにおいて、timeMs における属性値マップを計算する。
     * 戻り値: 属性名 -> 補間後の文字列値
     */
    fun getValuesAt(track: AnimationTrack, timeMs: Long): Map<String, String> {
        if (!track.enabled || track.keyframes.isEmpty()) return emptyMap()

        // タイムリマッピング適用（逆再生・速度変更）
        val effectiveTime = if (track.timeRemap != 0.0) {
            val remapped = timeMs.toDouble() / track.timeRemap.coerceAtLeast(0.001)
            remapped.toLong().coerceIn(0, track.durationMs.coerceAtLeast(1))
        } else {
            timeMs
        }

        val t = if (track.loop && track.durationMs > 0) {
            effectiveTime % track.durationMs
        } else {
            effectiveTime.coerceIn(0, track.durationMs)
        }

        val sorted = track.sortedKeyframes
        if (sorted.size == 1) return sorted.first().properties

        // 範囲外クランプ
        if (t <= sorted.first().timeMs) return sorted.first().properties
        if (t >= sorted.last().timeMs) return sorted.last().properties

        // 前後のキーフレームを探す
        var prev: Keyframe? = null
        var next: Keyframe? = null
        for (kf in sorted) {
            if (kf.timeMs <= t) prev = kf
            else { next = kf; break }
        }

        if (prev == null || next == null) return emptyMap()

        val span = (next.timeMs - prev.timeMs).toDouble().coerceAtLeast(1.0)
        val rawT = (t - prev.timeMs).toDouble() / span
        val easedT = ease(rawT.coerceIn(0.0, 1.0), prev.easeType, prev.bezier)

        val result = LinkedHashMap<String, String>()
        val allKeys = (prev.keys + next.keys).distinct()
        for (key in allKeys) {
            val fromVal = prev.properties[key]
            val toVal = next.properties[key]
            val interpolated = interpolateValue(fromVal, toVal, easedT)
            if (interpolated != null) {
                result[key] = interpolated
            } else if (fromVal != null) {
                result[key] = fromVal
            } else if (toVal != null) {
                result[key] = toVal
            }
        }

        // モーションパス適用：position (x, y) をパス上の位置で上書き
        track.motionPath?.let { path ->
            val normT = if (path.durationMs > 0) (t % path.durationMs).toDouble() / path.durationMs else 0.0
            val pos = motionPathPosition(path, normT)
            if (pos != null) {
                val offsetX = pos.first + path.offset * 100 // オフセット適用（簡易）
                val offsetY = pos.second
                result["x"] = Numbers.format(offsetX)
                result["y"] = Numbers.format(offsetY)
            }
        }

        return result
    }

    /**
     * モーションブラー用：指定トラックの速度ベクトルを取得（ピクセル/秒換算）。
     * 描画側でぼかし量を計算するために使用。
     */
    fun getVelocity(track: AnimationTrack, timeMs: Long): Pair<Double, Double>? {
        if (!track.enabled || track.keyframes.size < 2) return null
        val dt = 5L // 5ms 前後で速度を近似
        val t1 = (timeMs - dt).coerceAtLeast(0)
        val t2 = (timeMs + dt).coerceAtMost(track.durationMs)
        if (t1 >= t2) return null
        val v1 = getValuesAt(track, t1)
        val v2 = getValuesAt(track, t2)
        val x1 = Numbers.parse(v1["x"]) ?: 0.0
        val y1 = Numbers.parse(v1["y"]) ?: 0.0
        val x2 = Numbers.parse(v2["x"]) ?: 0.0
        val y2 = Numbers.parse(v2["y"]) ?: 0.0
        val dx = (x2 - x1) / (dt.toDouble() / 1000.0) // ピクセル/秒
        val dy = (y2 - y1) / (dt.toDouble() / 1000.0)
        return dx to dy
    }
}
