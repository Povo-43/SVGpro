package dev.svgpro.core.svg

import dev.svgpro.core.util.Numbers
import kotlin.math.roundToInt

/**
 * SVGアニメーション（SMIL）のデータモデル。
 *
 * 編集中はこのデータクラスを保持し、保存時に <animate>/<animateTransform>/<set> 等の
 * 要素としてSVGに書き出す。読み込み時はElementツリーから復元する。
 * 編集データ（Elementツリー）とは別レイヤーとして保持し、可逆に相互変換する。
 */

/** アニメーション要素の種類 */
enum class AnimationKind {
    ANIMATE,           // <animate>  - 属性を時間で補間
    ANIMATE_TRANSFORM, // <animateTransform> - 変形を補間
    ANIMATE_MOTION,    // <animateMotion> - パスに沿って移動
    SET,               // <set> - 離散的に値を設定
    DISCARD,           // <discard> - 指定時刻で要素を削除
}

/** 補間モード */
enum class CalcMode {
    DISCRETE, LINEAR, PACED, SPLINE,
}

/** 属性の参照種別 */
enum class AttributeType {
    CSS, XML, AUTO,
}

/** 塗りつぶしモード（アニメーション終了後の動作） */
enum class FillMode {
    REMOVE, FREEZE,
}

/**
 * 1本のアニメーション。
 *
 * targetRef: アニメーションの対象要素のid。nullの場合は親要素（子要素として配置される）。
 * attributeName: アニメーション対象の属性名（例: "x", "fill", "transform", "opacity"）。
 */
data class Animation(
    val id: String = newAnimId(),
    val kind: AnimationKind = AnimationKind.ANIMATE,
    val targetRef: String? = null,
    val attributeName: String = "",
    val attributeType: AttributeType = AttributeType.AUTO,

    // タイミング
    val begin: String = "0s",
    val dur: String = "1s",
    val end: String? = null,
    val min: String? = null,
    val max: String? = null,
    val repeatCount: String? = null,
    val repeatDur: String? = null,
    val fill: FillMode = FillMode.REMOVE,

    // 値
    val calcMode: CalcMode = CalcMode.LINEAR,
    val from: String? = null,
    val to: String? = null,
    val by: String? = null,
    val values: String? = null,
    val keyTimes: String? = null,
    val keySplines: String? = null,

    // 追加・累積
    val additive: String = "replace",
    val accumulate: String = "none",

    // animateTransform専用
    val transformType: String? = null, // translate/scale/rotate/skewX/skewY
    // animateMotion専用
    val motionPath: String? = null,
) {
    /** SVG要素名 */
    fun tagName(): String = when (kind) {
        AnimationKind.ANIMATE -> "animate"
        AnimationKind.ANIMATE_TRANSFORM -> "animateTransform"
        AnimationKind.ANIMATE_MOTION -> "animateMotion"
        AnimationKind.SET -> "set"
        AnimationKind.DISCARD -> "discard"
    }

    /** SVG用の属性マップ（出力順を保持） */
    fun toAttrs(): LinkedHashMap<String, String> {
        val attrs = LinkedHashMap<String, String>()
        attrs["attributeName"] = attributeName
        if (attributeType != AttributeType.AUTO) {
            attrs["attributeType"] = when (attributeType) {
                AttributeType.CSS -> "css"
                AttributeType.XML -> "xml"
                AttributeType.AUTO -> "auto"
            }
        }
        attrs["begin"] = begin
        attrs["dur"] = dur
        end?.let { attrs["end"] = it }
        min?.let { attrs["min"] = it }
        max?.let { attrs["max"] = it }
        repeatCount?.let { attrs["repeatCount"] = it }
        repeatDur?.let { attrs["repeatDur"] = it }
        attrs["fill"] = when (fill) {
            FillMode.REMOVE -> "remove"
            FillMode.FREEZE -> "freeze"
        }

        if (kind != AnimationKind.SET && kind != AnimationKind.DISCARD) {
            attrs["calcMode"] = when (calcMode) {
                CalcMode.DISCRETE -> "discrete"
                CalcMode.LINEAR -> "linear"
                CalcMode.PACED -> "paced"
                CalcMode.SPLINE -> "spline"
            }
            from?.let { attrs["from"] = it }
            to?.let { attrs["to"] = it }
            by?.let { attrs["by"] = it }
            values?.let { attrs["values"] = it }
            keyTimes?.let { attrs["keyTimes"] = it }
            keySplines?.let { attrs["keySplines"] = it }
        }

        if (additive != "replace") attrs["additive"] = additive
        if (accumulate != "none") attrs["accumulate"] = accumulate

        if (kind == AnimationKind.ANIMATE_TRANSFORM) {
            transformType?.let { attrs["type"] = it }
        }
        if (kind == AnimationKind.ANIMATE_MOTION) {
            motionPath?.let { attrs["path"] = it }
        }

        return attrs
    }

    /** このアニメーションが対象要素に有効か（targetRefがnullでも親子関係なら有効） */
    fun appliesTo(el: Element): Boolean {
        if (targetRef == null) return true
        return el.id == targetRef
    }

    /** アニメーションのコピー・編集用ビルダー */
    class Builder private constructor(
        private var kind: AnimationKind,
        private var targetRef: String?,
        private var attributeName: String,
        private var attributeType: AttributeType,
        private var begin: String,
        private var dur: String,
        private var end: String?,
        private var min: String?,
        private var max: String?,
        private var repeatCount: String?,
        private var repeatDur: String?,
        private var fill: FillMode,
        private var calcMode: CalcMode,
        private var from: String?,
        private var to: String?,
        private var by: String?,
        private var values: String?,
        private var keyTimes: String?,
        private var keySplines: String?,
        private var additive: String,
        private var accumulate: String,
        private var transformType: String?,
        private var motionPath: String?,
    ) {
        constructor(old: Animation) : this(
            old.kind, old.targetRef, old.attributeName, old.attributeType, old.begin, old.dur,
            old.end, old.min, old.max, old.repeatCount, old.repeatDur, old.fill, old.calcMode,
            old.from, old.to, old.by, old.values, old.keyTimes, old.keySplines,
            old.additive, old.accumulate, old.transformType, old.motionPath,
        )

        fun kind(v: AnimationKind) { kind = v }
        fun targetRef(v: String?) { targetRef = v }
        fun attributeName(v: String) { attributeName = v }
        fun attributeType(v: AttributeType) { attributeType = v }
        fun begin(v: String) { begin = v }
        fun dur(v: String) { dur = v }
        fun end(v: String?) { end = v }
        fun min(v: String?) { min = v }
        fun max(v: String?) { max = v }
        fun repeatCount(v: String?) { repeatCount = v }
        fun repeatDur(v: String?) { repeatDur = v }
        fun fill(v: FillMode) { fill = v }
        fun calcMode(v: CalcMode) { calcMode = v }
        fun from(v: String?) { from = v }
        fun to(v: String?) { to = v }
        fun by(v: String?) { by = v }
        fun values(v: String?) { values = v }
        fun keyTimes(v: String?) { keyTimes = v }
        fun keySplines(v: String?) { keySplines = v }
        fun additive(v: String) { additive = v }
        fun accumulate(v: String) { accumulate = v }
        fun transformType(v: String?) { transformType = v }
        fun motionPath(v: String?) { motionPath = v }

        fun build() = Animation(
            kind = kind, targetRef = targetRef, attributeName = attributeName,
            attributeType = attributeType, begin = begin, dur = dur, end = end,
            min = min, max = max, repeatCount = repeatCount, repeatDur = repeatDur,
            fill = fill, calcMode = calcMode, from = from, to = to, by = by,
            values = values, keyTimes = keyTimes, keySplines = keySplines,
            additive = additive, accumulate = accumulate,
            transformType = transformType, motionPath = motionPath,
        )
    }
}

/** アニメーション全体の実行状態（ドキュメントレベル） */
data class AnimationState(
    val animations: List<Animation> = emptyList(),
    val currentTime: Double = 0.0,   // 秒
    val isPlaying: Boolean = false,
    val duration: Double = 0.0,      // 秒（全アニメーションの最大終了時刻）
)

/** アニメーションID採番（一時的、編集中のみ一意であれば十分） */
private var _animCounter = 0
fun newAnimId(): String {
    _animCounter++
    return "svgpro-anim-${System.currentTimeMillis()}-${_animCounter}"
}

/** タイム値（"1s", "500ms", "0.5" 等）を秒にパースする。失敗時は0。 */
fun parseTimeSeconds(value: String?): Double {
    if (value.isNullOrEmpty()) return 0.0
    val s = value.trim()
    return when {
        s.endsWith("ms") -> s.dropLast(2).toDoubleOrNull() ?: 0.0
        s.endsWith("s") -> s.dropLast(1).toDoubleOrNull() ?: 0.0
        s.endsWith("h") -> (s.dropLast(1).toDoubleOrNull() ?: 0.0) * 3600.0
        s.endsWith("min") -> s.dropLast(3).toDoubleOrNull() ?: 0.0 * 60.0
        else -> s.toDoubleOrNull() ?: 0.0
    }
}

/** セミコロン区切りの値リストをパース（values, keyTimes, keySplines用） */
fun parseSemicolonList(value: String?): List<String> {
    if (value.isNullOrEmpty()) return emptyList()
    return value.split(';').map { it.trim() }.filter { it.isNotEmpty() }
}

/** アニメーションをElementツリーから読み出す（SMIL子要素またはhref参照） */
fun animationsFromElement(root: Element): List<Animation> {
    val out = ArrayList<Animation>()
    root.walk { el ->
        when (el.tag) {
            "animate", "animateTransform", "animateMotion", "set", "discard" -> {
                val kind = when (el.tag) {
                    "animate" -> AnimationKind.ANIMATE
                    "animateTransform" -> AnimationKind.ANIMATE_TRANSFORM
                    "animateMotion" -> AnimationKind.ANIMATE_MOTION
                    "set" -> AnimationKind.SET
                    "discard" -> AnimationKind.DISCARD
                    else -> AnimationKind.ANIMATE
                }
                // 対象要素: xlink:href / href 参照 or 親要素
                val targetRef = el.attr("xlink:href")?.removePrefix("#")
                    ?: el.attr("href")?.removePrefix("#")
                val attributeName = el.attr("attributeName") ?: ""
                val attributeType = when (el.attr("attributeType")) {
                    "css" -> AttributeType.CSS
                    "xml" -> AttributeType.XML
                    else -> AttributeType.AUTO
                }
                val fill = when (el.attr("fill")) {
                    "freeze" -> FillMode.FREEZE
                    else -> FillMode.REMOVE
                }
                val calcMode = when (el.attr("calcMode")) {
                    "discrete" -> CalcMode.DISCRETE
                    "linear" -> CalcMode.LINEAR
                    "paced" -> CalcMode.PACED
                    "spline" -> CalcMode.SPLINE
                    else -> CalcMode.LINEAR
                }
                out.add(Animation(
                    id = el.attr("id") ?: newAnimId(),
                    kind = kind,
                    targetRef = targetRef,
                    attributeName = attributeName,
                    attributeType = attributeType,
                    begin = el.attr("begin") ?: "0s",
                    dur = el.attr("dur") ?: "1s",
                    end = el.attr("end"),
                    min = el.attr("min"),
                    max = el.attr("max"),
                    repeatCount = el.attr("repeatCount"),
                    repeatDur = el.attr("repeatDur"),
                    fill = fill,
                    calcMode = calcMode,
                    from = el.attr("from"),
                    to = el.attr("to"),
                    by = el.attr("by"),
                    values = el.attr("values"),
                    keyTimes = el.attr("keyTimes"),
                    keySplines = el.attr("keySplines"),
                    additive = el.attr("additive") ?: "replace",
                    accumulate = el.attr("accumulate") ?: "none",
                    transformType = el.attr("type"),
                    motionPath = el.attr("path"),
                ))
            }
        }
    }
    return out
}

/** アニメーションをElementとしてparentの子に追加（保存時に使用） */
fun animationToElement(anim: Animation, parent: Element) {
    val el = Element(anim.tagName())
    el.attrs.putAll(anim.toAttrs())
    if (anim.id.isNotEmpty()) el.setAttr("id", anim.id)
    // 対象要素への参照: targetRefがあればhref参照、なければ子要素として配置
    if (anim.targetRef != null) {
        el.setAttr("xlink:href", "#${anim.targetRef}")
        el.setAttr("href", "#${anim.targetRef}")
    }
    parent.children.add(el)
}

/** ルート以下のすべてのアニメーション要素をElementツリーに書き出す */
fun writeAnimationsToTree(animations: List<Animation>, root: Element) {
    // 既存のアニメーション要素を削除
    root.walk { el ->
        val toRemove = el.children.filterIsInstance<Element>().filter { it.tag in
            setOf("animate", "animateTransform", "animateMotion", "set", "discard")
        }
        for (c in toRemove) el.detachChild(c)
    }
    // targetRef指定のアニメーションは対象要素の子に、nullのものはルート直下に配置
    for (anim in animations) {
        val target: Element? = if (anim.targetRef != null) root.findById(anim.targetRef) else null
        val parent = target ?: root
        animationToElement(anim, parent)
    }
}

/** 単一数値をパース（単位付きも可） */
fun parseNumber(value: String?): Double? {
    if (value.isNullOrEmpty()) return null
    return Numbers.parse(value.trim())
}

/** カラーコード(#rrggbb等)をARGB Intに変換。失敗時はnull */
fun parseColorArgb(value: String?): Int? {
    if (value.isNullOrEmpty()) return null
    return dev.svgpro.core.model.Color.parse(value.trim())
}

/** ARGB IntをSVGカラー文字列に */
fun colorToSvg(argb: Int): String {
    return dev.svgpro.core.model.Color.css(argb)
}

/** アニメーションの有効期間（アクティブ期間）の終了時刻（秒）を概算する */
fun activeEndSeconds(anim: Animation): Double {
    val begin = parseTimeSeconds(anim.begin)
    val dur = parseTimeSeconds(anim.dur)
    val repeatCount = anim.repeatCount?.toDoubleOrNull()
    val repeatDur = parseTimeSeconds(anim.repeatDur)
    val end = parseTimeSeconds(anim.end)
    var activeDur = dur
    if (repeatCount != null && repeatCount > 0) {
        activeDur = dur * repeatCount
    }
    if (repeatDur != null && repeatDur > 0) {
        activeDur = kotlin.math.min(activeDur, repeatDur)
    }
    var endTime = begin + activeDur
    if (end > 0) endTime = kotlin.math.max(endTime, begin + end)
    return endTime
}
