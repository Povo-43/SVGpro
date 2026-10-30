package dev.svgpro.core.model

import dev.svgpro.core.geom.Matrix
import dev.svgpro.core.path.PathData
import dev.svgpro.core.svg.Element

/**
 * グラデーション定義（linearGradient / radialGradient）の読み書きモデル。
 * stop の追加・削除・移動・色変更を Element ツリーへ反映する。
 */
class GradientModel(
    val def: Element,
    val radial: Boolean,
) {
    class Stop(var offset: Double, var argb: Int)

    val stops = ArrayList<Stop>()

    /** linear: x1,y1,x2,y2 / radial: cx,cy,r （objectBoundingBox単位の数値） */
    var x1 = 0.0; var y1 = 0.0; var x2 = 1.0; var y2 = 0.0
    var cx = 0.5; var cy = 0.5; var r = 0.5
    /** gradientUnits == userSpaceOnUse */
    var userSpace: Boolean = false

    init {
        read()
    }

    val id: String get() = def.attr("id") ?: ""

    /** Element → モデル */
    fun read() {
        userSpace = StyleUtil.getProperty(def, "gradientUnits") == "userSpaceOnUse"
        val n = dev.svgpro.core.util.Numbers
        if (radial) {
            cx = n.parse(StyleUtil.getProperty(def, "cx")) ?: 0.5
            cy = n.parse(StyleUtil.getProperty(def, "cy")) ?: 0.5
            r = n.parse(StyleUtil.getProperty(def, "r")) ?: 0.5
        } else {
            x1 = n.parse(StyleUtil.getProperty(def, "x1")) ?: 0.0
            y1 = n.parse(StyleUtil.getProperty(def, "y1")) ?: 0.0
            x2 = n.parse(StyleUtil.getProperty(def, "x2")) ?: 1.0
            y2 = n.parse(StyleUtil.getProperty(def, "y2")) ?: 0.0
        }
        stops.clear()
        for (child in def.childElements()) {
            if (child.tag != "stop") continue
            val offset = n.parse(StyleUtil.getProperty(child, "offset")) ?: 0.0
            val color = dev.svgpro.core.model.Color.parse(StyleUtil.getProperty(child, "stop-color")) ?: 0xFF000000.toInt()
            val so = n.parse(StyleUtil.getProperty(child, "stop-opacity")) ?: 1.0
            stops.add(Stop(offset.coerceIn(0.0, 1.0), Color.withAlpha(color and 0x00FFFFFF, so)))
        }
        if (stops.isEmpty()) {
            stops.add(Stop(0.0, 0xFF000000.toInt()))
            stops.add(Stop(1.0, 0xFFFFFFFF.toInt()))
        }
        stops.sortBy { it.offset }
    }

    /** モデル → Element（stop子要素を再構築。他の子は保持） */
    fun write() {
        val n = dev.svgpro.core.util.Numbers
        if (radial) {
            def.setAttr("cx", n.format(cx))
            def.setAttr("cy", n.format(cy))
            def.setAttr("r", n.format(r.coerceAtLeast(0.0)))
        } else {
            def.setAttr("x1", n.format(x1))
            def.setAttr("y1", n.format(y1))
            def.setAttr("x2", n.format(x2))
            def.setAttr("y2", n.format(y2))
        }
        // 既存stopを削除し、新しいstop列を先頭へ挿入（他の子要素は保持）
        val removed = ArrayList<Element>()
        for (c in def.children.filterIsInstance<Element>()) {
            if (c.tag == "stop") removed.add(c)
        }
        for (c in removed) def.children.remove(c)
        var insertAt = 0
        for (s in stops) {
            val st = Element("stop")
            st.setAttr("offset", n.format(s.offset.coerceIn(0.0, 1.0)))
            st.setAttr("stop-color", Color.css(s.argb))
            val a = (s.argb ushr 24) and 0xFF
            if (a != 0xFF) st.setAttr("stop-opacity", n.format(a / 255.0, 6))
            def.children.add(insertAt++, st)
        }
    }

    // ―――― 編集操作 ――――

    /** 停止点を追加（offset位置）。argbは近傍ストップから補間 */
    fun addStop(offset: Double): Int {
        val o = offset.coerceIn(0.0, 1.0)
        val color = colorAt(o)
        val stop = Stop(o, color)
        val idx = stops.indexOfFirst { it.offset > o }.let { if (it < 0) stops.size else it }
        stops.add(idx, stop)
        return idx
    }

    /** offset位置の補間色 */
    fun colorAt(offset: Double): Int {
        if (stops.isEmpty()) return 0xFF000000.toInt()
        if (offset <= stops.first().offset) return stops.first().argb
        if (offset >= stops.last().offset) return stops.last().argb
        for (i in 0 until stops.size - 1) {
            val a = stops[i]
            val b = stops[i + 1]
            if (offset in a.offset..b.offset) {
                val span = b.offset - a.offset
                val t = if (span < 1e-9) 0.0 else (offset - a.offset) / span
                val ar = (a.argb shr 16) and 0xFF; val ag = (a.argb shr 8) and 0xFF; val ab = a.argb and 0xFF; val aa = (a.argb ushr 24) and 0xFF
                val br = (b.argb shr 16) and 0xFF; val bg = (b.argb shr 8) and 0xFF; val bb = b.argb and 0xFF; val ba = (b.argb ushr 24) and 0xFF
                val r = (ar + (br - ar) * t).toInt()
                val g = (ag + (bg - ag) * t).toInt()
                val bl = (ab + (bb - ab) * t).toInt()
                val al = (aa + (ba - aa) * t).toInt()
                return (al shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        return stops.last().argb
    }

    fun removeStop(index: Int): Boolean {
        if (stops.size <= 2) return false // 最低2ストップは保持
        if (index !in stops.indices) return false
        stops.removeAt(index)
        return true
    }

    /**
     * グラデーションのハンドル座標を targetのbbox（objectBoundingBox単位→user変換）で計算する。
     * @return 線形: (start, end) / 放射: (center, edge)。user空間座標
     */
    fun handlePoints(targetBoundsUser: dev.svgpro.core.geom.Bounds): Pair<dev.svgpro.core.geom.Vec, dev.svgpro.core.geom.Vec> {
        val b = targetBoundsUser
        fun px(t: Double) = b.x + b.width * t
        fun py(t: Double) = b.y + b.height * t
        return if (radial) {
            val c = dev.svgpro.core.geom.Vec(px(cx), py(cy))
            // r は「最大辺の半分」基準（レンダラの近似と一致させる）
            val rr = r * maxOf(b.width, b.height)
            c to dev.svgpro.core.geom.Vec(c.x + rr, c.y)
        } else {
            dev.svgpro.core.geom.Vec(px(x1), py(y1)) to dev.svgpro.core.geom.Vec(px(x2), py(y2))
        }
    }

    /** ハンドル座標（user空間）から座標属性を更新（objectBoundingBox単位へ逆変換） */
    fun setFromHandles(targetBoundsUser: dev.svgpro.core.geom.Bounds, start: dev.svgpro.core.geom.Vec, end: dev.svgpro.core.geom.Vec) {
        val b = targetBoundsUser
        fun tx(x: Double) = if (b.width < 1e-9) 0.0 else (x - b.x) / b.width
        fun ty(y: Double) = if (b.height < 1e-9) 0.0 else (y - b.y) / b.height
        if (radial) {
            cx = tx(start.x).coerceIn(-1.0, 2.0)
            cy = ty(start.y).coerceIn(-1.0, 2.0)
            r = (end.distanceTo(start) / maxOf(b.width, b.height).coerceAtLeast(1e-9)).coerceAtLeast(0.0)
        } else {
            x1 = tx(start.x).coerceIn(-1.0, 2.0)
            y1 = ty(start.y).coerceIn(-1.0, 2.0)
            x2 = tx(end.x).coerceIn(-1.0, 2.0)
            y2 = ty(end.y).coerceIn(-1.0, 2.0)
        }
    }

    companion object {
        /** defs中のグラデーション定義をモデル化。見つからなければnull */
        fun of(def: Element): GradientModel? = when (def.tag) {
            "linearGradient" -> GradientModel(def, radial = false)
            "radialGradient" -> GradientModel(def, radial = true)
            else -> null
        }
    }
}

/**
 * パターン塗りの生成ヘルパ。
 * 選択オブジェクトから <pattern> タイルを作り、他のオブジェクトの fill に適用する。
 */
object PatternUtil {

    /**
     * sources のコピーを pattern 定義へ格納する。
     * @param tile パターン1枚の矩形（user空間・絶対座標）
     * @param bake 変換ヘルパ: 要素の幾何を絶対座標へ焼き込むためのCTM
     * @return pattern要素（defsへは未追加。呼び出し側で追加する）
     */
    fun createPattern(
        id: String,
        tile: dev.svgpro.core.geom.Bounds,
        sources: List<Element>,
        bake: (Element) -> Matrix,
    ): Element {
        val p = Element("pattern")
        p.setAttr("id", id)
        p.setAttr("patternUnits", "userSpaceOnUse")
        val n = dev.svgpro.core.util.Numbers
        p.setAttr("x", n.format(tile.x))
        p.setAttr("y", n.format(tile.y))
        p.setAttr("width", n.format(tile.width.coerceAtLeast(0.01)))
        p.setAttr("height", n.format(tile.height.coerceAtLeast(0.01)))
        for (src in sources) {
            val copy = src.deepCopy()
            // 幾何を絶対座標へ焼き込み、transformを解消
            val geo = copy.attr("d")?.let { PathData.parse(it) }
            if (geo != null) {
                geo.transform(bake(copy))
                copy.setAttr("d", geo.write())
                copy.setAttr("transform", null)
            }
            p.children.add(copy)
        }
        return p
    }

    /** 要素の塗りをパターン参照へ変更 */
    fun applyPattern(el: Element, patternId: String) {
        StyleUtil.setProperty(el, "fill", "url(#$patternId)")
    }

    /** patternTransform を設定（拡大縮小・回転・オフセット） */
    fun setTransform(pattern: Element, scale: Double, rotationDeg: Double, offsetX: Double, offsetY: Double) {
        val parts = ArrayList<String>()
        if (offsetX != 0.0 || offsetY != 0.0) {
            parts.add("translate(${dev.svgpro.core.util.Numbers.format(offsetX)},${dev.svgpro.core.util.Numbers.format(offsetY)})")
        }
        if (rotationDeg != 0.0) {
            parts.add("rotate(${dev.svgpro.core.util.Numbers.format(rotationDeg)})")
        }
        if (scale != 1.0) {
            parts.add("scale(${dev.svgpro.core.util.Numbers.format(scale)})")
        }
        if (parts.isEmpty()) pattern.setAttr("patternTransform", null)
        else pattern.setAttr("patternTransform", parts.joinToString(" "))
    }
}
