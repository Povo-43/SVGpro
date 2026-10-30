package dev.svgpro.core.model

import dev.svgpro.core.svg.Element
import dev.svgpro.core.util.Numbers
import kotlin.math.min

/**
 * 色/ペイントの解析ユーティリティ。
 * SVGの間接参照（url(#)やcurrentColor）、名前色、rgb()/rgba()、#hex を扱う。
 */
object Color {

    // ―――― 名前色（よく使うもの）――――
    private val NAMES: Map<String, Int> = buildMap {
        put("black", 0xFF000000.toInt())
        put("white", 0xFFFFFFFF.toInt())
        put("red", 0xFFFF0000.toInt())
        put("lime", 0xFF00FF00.toInt())
        put("green", 0xFF008000.toInt())
        put("blue", 0xFF0000FF.toInt())
        put("yellow", 0xFFFFFF00.toInt())
        put("cyan", 0xFF00FFFF.toInt())
        put("aqua", 0xFF00FFFF.toInt())
        put("magenta", 0xFFFF00FF.toInt())
        put("fuchsia", 0xFFFF00FF.toInt())
        put("gray", 0xFF808080.toInt())
        put("grey", 0xFF808080.toInt())
        put("orange", 0xFFFFA500.toInt())
        put("purple", 0xFF800080.toInt())
        put("brown", 0xFFA52A2A.toInt())
        put("pink", 0xFFFFC0CB.toInt())
        put("gold", 0xFFFFD700.toInt())
        put("silver", 0xFFC0C0C0.toInt())
        put("navy", 0xFF000080.toInt())
        put("teal", 0xFF008080.toInt())
        put("olive", 0xFF808000.toInt())
        put("maroon", 0xFF800000.toInt())
        put("violet", 0xFFEE82EE.toInt())
        put("indigo", 0xFF4B0082.toInt())
        put("coral", 0xFFFF7F50.toInt())
        put("crimson", 0xFFDC143C.toInt())
        put("salmon", 0xFFFA8072.toInt())
        put("turquoise", 0xFF40E0D0.toInt())
        put("beige", 0xFFF5F5DC.toInt())
        put("khaki", 0xFFF0E68C.toInt())
        put("lavender", 0xFFE6E6FA.toInt())
        put("tomato", 0xFFFF6347.toInt())
        put("forestgreen", 0xFF228B22.toInt())
        put("skyblue", 0xFF87CEEB.toInt())
        put("steelblue", 0xFF4682B4.toInt())
        put("darkgray", 0xFFA9A9A9.toInt())
        put("darkgrey", 0xFFA9A9A9.toInt())
        put("lightgray", 0xFFD3D3D3.toInt())
        put("lightgrey", 0xFFD3D3D3.toInt())
        put("dimgray", 0xFF696969.toInt())
        put("whitesmoke", 0xFFF5F5F5.toInt())
        put("ivory", 0xFFFFFFF0.toInt())
        put("transparent", 0x00000000)
        put("none", 0x00000000)
    }

    /** 色+alphaに正規化。nullは解析不能（またはnone）。 */
    fun parse(src: String?): Int? {
        if (src.isNullOrEmpty()) return null
        var s = src.trim().lowercase()
        if (s.startsWith("url")) return null
        if (s == "currentcolor" || s == "current-color") return 0xFF000000.toInt()
        if (s.startsWith("#")) {
            val hex = s.substring(1)
            return when (hex.length) {
                3 -> {
                    val r = hex[0].hexValue() * 17
                    val g = hex[1].hexValue() * 17
                    val b = hex[2].hexValue() * 17
                    0xFF shl 24 or (r shl 16) or (g shl 8) or b
                }
                4 -> {
                    val r = hex[0].hexValue() * 17
                    val g = hex[1].hexValue() * 17
                    val b = hex[2].hexValue() * 17
                    val a = hex[3].hexValue() * 17
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                6 -> (0xFF shl 24) or (hex.substring(0, 2).toInt(16) shl 16) or
                    (hex.substring(2, 4).toInt(16) shl 8) or hex.substring(4, 6).toInt(16)
                8 -> (hex.substring(0, 2).toInt(16) shl 24) or
                    (hex.substring(2, 4).toInt(16) shl 16) or
                    (hex.substring(4, 6).toInt(16) shl 8) or
                    hex.substring(6, 8).toInt(16)
                else -> null
            }
        }
        if (s.startsWith("rgb")) {
            val inner = s.substringAfter('(').substringBefore(')')
            val parts = inner.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            if (parts.size >= 3) {
                val r = parseChannel(parts[0]) ?: return null
                val g = parseChannel(parts[1]) ?: return null
                val b = parseChannel(parts[2]) ?: return null
                val a = if (parts.size >= 4) (parseAlpha(parts[3]) ?: 1.0) else 1.0
                return (a * 255).toInt() shl 24 or (r shl 16) or (g shl 8) or b
            }
            return null
        }
        NAMES[s]?.let { return it }
        return null
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> 0
    }

    private fun parseChannel(v: String): Int? = if (v.endsWith("%")) {
        val q = Numbers.parse(v.dropLast(1)) ?: return null
        (q.coerceIn(0.0, 100.0) * 2.55).toInt()
    } else {
        Numbers.parse(v)?.coerceIn(0.0, 255.0)?.toInt()
    }

    private fun parseAlpha(v: String): Double? = if (v.endsWith("%")) {
        Numbers.parse(v.dropLast(1))?.div(100.0)
    } else {
        Numbers.parse(v)
    }

    /** #rrggbb 形式へ */
    fun toHex(color: Int): String {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val a = (color ushr 24) and 0xFF
        val body = "%02x%02x%02x".format(r, g, b)
        return if (a == 0xFF) "#$body" else "#${a.toString(16).padStart(2, '0')}$body"
    }

    /** css色を ARGB にする。url/current は除外してnull */
    fun css(argb: Int): String {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val a = (argb ushr 24) and 0xFF
        return if (a == 0xFF) "rgb($r,$g,$b)" else "rgba($r,$g,$b,${(a / 255.0).coerceIn(0.0, 1.0)})"
    }

    fun withAlpha(color: Int, alpha: Double): Int {
        val a = Math.round(alpha.coerceIn(0.0, 1.0) * 255).toInt()
        return (color and 0xFFFFFF) or (a shl 24)
    }
}

/** 塗り・線の「値」を解析する仕組み */
sealed class Paint {
    data object None : Paint()

    /** 単色（ARGB。不透明度はSeparateで持ちstyle属性化する） */
    data class Solid(val argb: Int) : Paint()

    /** url(#id) */
    data class Server(val id: String) : Paint()

    companion object {
        fun parse(value: String?, elementOpacityValue: Double = 1.0): Paint? {
            if (value.isNullOrEmpty()) return null
            val v = value.trim()
            if (v.equals("none", true)) return None
            if (v.equals("inherit", true)) return null
            val urlRef = Regex("""url\(['"]?#([^)'"\s]+)?['"]?\)""").find(v)
            if (urlRef != null) return Server(urlRef.groupValues[1])
            val c = Color.parse(v) ?: return null
            return Solid(c)
        }
    }
}

/**
 * style属性・presentation属性の読み書き。
 * SVGでは style属性が個別属性より優先されるため、編集は style属性へ書き込むのが基本。
 */
object StyleUtil {

    val PRESENTATION_PROPS = listOf(
        "fill", "fill-opacity", "fill-rule",
        "stroke", "stroke-opacity", "stroke-width", "stroke-linecap", "stroke-linejoin",
        "stroke-dasharray", "stroke-dashoffset", "stroke-miterlimit",
        "opacity", "display", "font-family", "font-size", "font-weight", "font-style",
        "text-anchor", "letter-spacing", "line-height",
    )

    /** style属性をMap化 */
    fun styleAttrs(el: Element): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val raw = el.attr("style") ?: return out
        for (decl in raw.split(';')) {
            val kv = decl.split(':')
            if (kv.size < 2) continue
            val k = kv[0].trim()
            val v = kv.drop(1).joinToString(":").trim()
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    fun writeStyleAttrs(el: Element, values: Map<String, String?>) {
        val cur = styleAttrs(el)
        for ((k, v) in values) {
            if (v == null) cur.remove(k) else cur[k] = v
        }
        if (cur.isEmpty()) {
            el.removeAttr("style")
            return
        }
        el.setAttr("style", cur.entries.joinToString(";") { "${it.key}:${it.value}" })
    }

    fun setProperty(el: Element, prop: String, value: String?) {
        writeStyleAttrs(el, mapOf(prop to value))
    }

    fun removeProperty(el: Element, prop: String) {
        writeStyleAttrs(el, mapOf<String, String?>(prop to null))
    }
    fun getProperty(el: Element, prop: String): String? =
        styleAttrs(el)[prop] ?: el.attr(prop)

    fun opacity(el: Element): Double =
        Numbers.parse(getProperty(el, "opacity"))?.coerceIn(0.0, 1.0) ?: 1.0

    /** paint値をPaintへ。none → Paint.None */
    fun paint(el: Element, prop: String): Paint {
        val v = getProperty(el, prop) ?: return PropDefault.paintDefault(prop)
        val p = Paint.parse(v)
        return if (p == null && v.trim().equals("url(#", false)) {
            PropDefault.paintDefault(prop)
        } else if (p == null) {
            // 未知の値（名前外の色等）はそのまま Solid black扱い
            if (v.startsWith("url")) Server.defaultFor(prop)
            else Paint.Solid(0xFF000000.toInt())
        } else p
    }

    /** fill/stroke それぞれに初期値あり: fill=black, stroke=none */
    private object PropDefault {
        fun paintDefault(prop: String): Paint = when (prop) {
            "fill" -> Paint.Solid(0xFF000000.toInt())
            else -> Paint.None
        }
    }

    private object Server {
        fun defaultFor(prop: String): Paint = when (prop) {
            "fill" -> Paint.Solid(0xFF000000.toInt())
            else -> Paint.None
        }
    }

    fun dashArray(el: Element): List<Double>? {
        val v = getProperty(el, "stroke-dasharray") ?: return null
        if (v.trim().equals("none", true)) return null
        return v.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.mapNotNull { Numbers.parse(it) }
    }

    /** 塗り規則（nonzero / evenodd） */
    fun fillRule(el: Element): String = getProperty(el, "fill-rule")?.trim()?.lowercase().takeIf { it == "evenodd" } ?: "nonzero"

    /** 塗り規則を設定（nonzeroは属性削除=デフォルト） */
    fun setFillRule(el: Element, rule: String) {
        if (rule.equals("nonzero", true)) {
            removeProperty(el, "fill-rule")
            el.setAttr("fill-rule", null)
        } else {
            setProperty(el, "fill-rule", "evenodd")
        }
    }

    /** 線幅拡大縮小への追従はSVGではtransformに含まれる（スケールで線幅も拡縮）。
     *  追従させない場合は必要に応じて stroke-width を逆補正するためのヘルパ。 */
    fun compensationForScale(el: Element, scaleX: Double, scaleY: Double): Double = kotlin.math.sqrt(kotlin.math.abs(scaleX * scaleY).coerceAtLeast(1e-12))

    /** Xlink:href と href の両対応 */
    fun href(el: Element): String? = el.attr("href") ?: el.attr("xlink:href")

    /** 要素とその祖先の display 判定 */
    fun inheritedDisplay(el: Element, parentLookup: (Element) -> Element?): Boolean {
        if (getProperty(el, "display") == "none") return false
        val p = parentLookup(el) ?: return true
        return inheritedDisplay(p, parentLookup)
    }
}

/**
 * ドキュメント全体の色操作（一括置換・使用色の収集）。
 * style属性・presentation属性・stop-color・gradient stops を対象とする。
 */
object ColorOps {

    /** ドキュメント内で使用されている色（ARGB）を収集 */
    fun collectColors(root: Element): Set<Int> {
        val out = HashSet<Int>()
        val props = listOf("fill", "stroke", "stop-color", "flood-color", "lighting-color")
        root.walk { el ->
            for (prop in props) {
                val v = StyleUtil.getProperty(el, prop) ?: continue
                if (v.startsWith("url", true)) continue
                Color.parse(v)?.let { out.add(it) }
            }
            // style属性の生文字列にも対応
            val raw = el.attr("style")
            if (raw != null) {
                for (decl in raw.split(';')) {
                    val i = decl.indexOf(':')
                    if (i < 0) continue
                    val key = decl.substring(0, i).trim()
                    if (key in props) {
                        Color.parse(decl.substring(i + 1).trim())?.let { out.add(it) }
                    }
                }
            }
        }
        return out
    }

    /**
     * 色の一括置換。対象: fill / stroke / stop-color（style属性・presentation属性両方）。
     * @return 置換が発生した要素数
     */
    fun replaceColor(root: Element, from: Int, to: Int, alsoStops: Boolean = true): Int {
        val fromHex = Color.toHex(from).lowercase()
        val fromCss = Color.css(from).lowercase()
        var count = 0
        fun matches(v: String): Boolean {
            val t = v.trim().lowercase()
            return t == fromHex || t == fromCss || (Color.parse(t) == from && !t.startsWith("url"))
        }

        root.walk { el ->
            var touched = false
            for (prop in listOf("fill", "stroke")) {
                val v = StyleUtil.getProperty(el, prop) ?: continue
                if (matches(v)) {
                    StyleUtil.setProperty(el, prop, Color.css(to))
                    touched = true
                }
            }
            if (alsoStops) {
                for (stop in el.childElements()) {
                    if (stop.tag != "stop") continue
                    val v = StyleUtil.getProperty(stop, "stop-color") ?: continue
                    if (matches(v)) {
                        // 元stopの不透明度を保ったままRGBを置換
                        val old = Color.parse(v) ?: continue
                        val newArgb = (old and 0xFF000000.toInt()) or (to and 0x00FFFFFF)
                        StyleUtil.setProperty(stop, "stop-color", Color.css(newArgb))
                        touched = true
                    }
                }
            }
            if (touched) count++
        }
        return count
    }

    /** 指定色を使用している要素を選択対象として収集 */
    fun elementsUsingColor(root: Element, color: Int): List<Element> {
        val out = ArrayList<Element>()
        fun matches(v: String): Boolean {
            val t = v.trim().lowercase()
            return Color.parse(t) == color && !t.startsWith("url")
        }
        root.walk { el ->
            for (prop in listOf("fill", "stroke")) {
                val v = StyleUtil.getProperty(el, prop)
                if (v != null && matches(v)) {
                    out.add(el)
                    return@walk
                }
            }
        }
        return out
    }
}
