package dev.svgpro.core.model

import dev.svgpro.core.geom.Matrix
import dev.svgpro.core.geom.Vec
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import dev.svgpro.core.svg.Element

/**
 * 図形要素（path/rect/circle/…）の幾何アクセス。
 * プリミティブは属性を直接編集し、pathに変換されたときだけ d を保持する。
 */
object Shapes {

    val DRAWABLE_TAGS = setOf("path", "rect", "circle", "ellipse", "line", "polyline", "polygon")

    /** 要素をパスデータとして取得（path以外は等価パスを構築。編集はしない・表示やヒット用） */
    fun geometryOf(el: Element): PathData? = when (el.tag) {
        "path" -> PathData.parse(el.attr("d"))
        "rect" -> rectPath(el)?.let { PathData.fromSegs(it) }
        "ellipse" -> ellipsePath(el)?.let { PathData.fromSegs(it) }
        "circle" -> ellipsePath(el)?.let { PathData.fromSegs(it) }
        "line" -> linePath(el)?.let { PathData.fromSegs(it) }
        "polyline", "polygon" -> polylinePath(el)?.let { PathData.fromSegs(it) }
        else -> null
    }

    /** 自分selfのtransform（ composite は親は含まない） */
    fun localTransform(el: Element): Matrix =
        Matrix.fromSvgTransform(el.attr("transform") ?: "")

    fun boundsOf(el: Element): dev.svgpro.core.geom.Bounds? = geometryOf(el)?.bounds(0.02)

    // ―――― 型別ヘルパ ――――

    private fun num(el: Element, name: String, dflt: Double = 0.0): Double =
        dev.svgpro.core.util.Numbers.parse(el.attr(name)) ?: dflt

    fun rectPath(el: Element): List<Seg>? {
        val x = num(el, "x")
        val y = num(el, "y")
        val w = num(el, "width", -1.0)
        val h = num(el, "height", -1.0)
        if (w <= 0.0 || h <= 0.0) return null
        var rx = num(el, "rx", -1.0)
        var ry = num(el, "ry", -1.0)
        if (rx < 0.0 && ry < 0.0) {
            rx = 0.0; ry = 0.0
        } else if (rx < 0.0) {
            rx = ry
        } else if (ry < 0.0) {
            ry = rx
        }
        rx = rx.coerceAtMost(w / 2)
        ry = ry.coerceAtMost(h / 2)
        if (rx == 0.0 && ry == 0.0) {
            return PathData.rect(x, y, w, h).segs
        }
        val segs = ArrayList<Seg>()
        // https://www.w3.org/TR/SVG11/shapes.html での rounded rect
        val kx = rx * 0.5522847498307936
        val ky = ry * 0.5522847498307936
        segs.add(Seg.Move(x + rx, y))
        segs.add(Seg.Line(x + w - rx, y))
        if (rx > 0.0 && ry > 0.0) segs.add(Seg.Cubic(x + w - rx - kx, y, x + w, y + ry - ky, x + w, y + ry))
        segs.add(Seg.Line(x + w, y + h - ry))
        if (rx > 0.0 && ry > 0.0) segs.add(Seg.Cubic(x + w, y + h - ry + ky, x + w - rx + kx, y + h, x + w - rx, y + h))
        segs.add(Seg.Line(x + rx, y + h))
        if (rx > 0.0 && ry > 0.0) segs.add(Seg.Cubic(x + rx - kx, y + h, x, y + h - ry + ky, x, y + h - ry))
        segs.add(Seg.Line(x, y + ry))
        if (rx > 0.0 && ry > 0.0) segs.add(Seg.Cubic(x, y + ry - ky, x + rx - kx, y, x + rx, y))
        segs.add(Seg.Close)
        return segs
    }

    fun ellipsePath(el: Element): List<Seg>? {
        val cx = num(el, "cx")
        val cy = num(el, "cy")
        var rx = if (el.tag == "circle") num(el, "r") else num(el, "rx")
        var ry = if (el.tag == "circle") num(el, "r") else num(el, "ry")
        if (rx <= 0.0 || ry <= 0.0) return null
        return PathData.ellipse(cx, cy, rx, ry).segs
    }

    fun linePath(el: Element): List<Seg>? {
        val x1 = num(el, "x1")
        val y1 = num(el, "y1")
        val x2 = num(el, "x2")
        val y2 = num(el, "y2")
        return listOf(Seg.Move(x1, y1), Seg.Line(x2, y2))
    }

    fun polylinePath(el: Element): List<Seg>? {
        val raw = el.attr("points") ?: return null
        val vals = raw.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.mapNotNull { dev.svgpro.core.util.Numbers.parse(it) }
        if (vals.size < 4) return null
        val segs = ArrayList<Seg>()
        for (i in 0 until vals.size - 1 step 2) {
            if (i == 0) {
                segs.add(Seg.Move(vals[0], vals[1]))
            } else {
                segs.add(Seg.Line(vals[i], vals[i + 1]))
            }
        }
        if (el.tag == "polygon" && vals.size >= 2 && vals.size % 2 == 0) {
            segs.add(Seg.Close)
        }
        return segs
    }

    // ―――― 変換（操作系）――――

    /** rect/circle/ellipse等 を等価pathに変換する（要素はpathに置き換わる想定）。 */
    fun toPathEquivalent(el: Element): Element? {
        val pd = geometryOf(el) ?: return null
        val newEl = Element("path")
        for ((k, v) in el.attrs) {
            if (k in setOf("x", "y", "cx", "cy", "r", "rx", "ry", "width", "height", "x1", "x2", "y1", "y2", "points", "d")) continue
            newEl.attrs[k] = v
        }
        newEl.setAttr("d", pd.write())
        return newEl
    }

    /** 矩形に対応した要素か判別 */
    fun isPrimitive(el: Element): Boolean = DRAWABLE_TAGS.contains(el.tag) && el.tag != "path"

    /** 図形にdefaultのプレゼン属性を与える（新規作成時） */
    fun applyDefaultStyle(el: Element, fill: Paint, stroke: Paint, strokeWidth: Double) {
        when (fill) {
            Paint.None -> StyleUtil.setProperty(el, "fill", "none")
            is Paint.Solid -> StyleUtil.setProperty(el, "fill", Color.css(fill.argb))
            is Paint.Server -> {}
        }
        when (stroke) {
            Paint.None -> {}
            is Paint.Solid -> {
                StyleUtil.setProperty(el, "stroke", Color.css(stroke.argb))
                StyleUtil.setProperty(el, "stroke-width", dev.svgpro.core.util.Numbers.format(strokeWidth))
            }
            is Paint.Server -> {}
        }
    }

    /** polyline/polygonソース値の生成（セグメント→points） */
    fun pointsString(pd: PathData): String {
        val sb = StringBuilder()
        var first = true
        for (s in pd.segs) {
            when (s) {
                is Seg.Move -> {
                    if (!first) sb.append(' ')
                    sb.append(dev.svgpro.core.util.Numbers.format(s.x)).append(',').append(dev.svgpro.core.util.Numbers.format(s.y))
                    first = false
                }
                is Seg.Line -> {
                    sb.append(' ').append(dev.svgpro.core.util.Numbers.format(s.x)).append(',').append(dev.svgpro.core.util.Numbers.format(s.y))
                }
                else -> {}
            }
        }
        return sb.toString()
    }
}

/** テキスト要素の設定ユーティリティ */
object TextAttr {
    fun newElement(text: String, pos: Vec, size: Double = 16.0, family: String = "sans-serif"): Element {
        val el = Element("text")
        el.setAttr("x", dev.svgpro.core.util.Numbers.format(pos.x))
        el.setAttr("y", dev.svgpro.core.util.Numbers.format(pos.y))
        el.setAttr("font-size", dev.svgpro.core.util.Numbers.format(size))
        el.setAttr("font-family", family)
        el.setTextContent(text)
        return el
    }
}
