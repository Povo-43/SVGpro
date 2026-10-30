package dev.svgpro.core.model

import dev.svgpro.core.path.PathData
import dev.svgpro.core.svg.Element

/**
 * ドキュメントを表す。ルートは <svg> 要素を前提とする。
 * width/height/viewBox のデフォルト解決: width指定なし→viewBox幅→100。
 */
class SvgDocument(val root: Element) {

    companion object {
        val SHAPE_TAGS = setOf("path", "rect", "circle", "ellipse", "line", "polyline", "polygon")
        const val DEFAULT_W = 100.0
        const val DEFAULT_H = 100.0

        /** 新規ドキュメントを作る */
        fun create(width: Double = 512.0, height: Double = 512.0): SvgDocument {
            val root = Element("svg")
            root.setAttr("xmlns", "http://www.w3.org/2000/svg")
            root.setAttr("xmlns:xlink", "http://www.w3.org/1999/xlink")
            root.setAttr("width", fmt(width))
            root.setAttr("height", fmt(height))
            root.setAttr("viewBox", "0 0 ${fmt(width)} ${fmt(height)}")
            root.setAttr("version", "1.1")
            val defs = Element("defs")
            root.children.add(defs)
            return SvgDocument(root)
        }

        private fun fmt(v: Double) = dev.svgpro.core.util.Numbers.format(v)
    }

    // ―――― ビューポート ――――

    data class ViewBox(val minX: Double, val minY: Double, val width: Double, val height: Double)

    val viewBox: ViewBox
        get() {
            val vb = root.attr("viewBox") ?: return ViewBox(0.0, 0.0, width, height)
            val parts = vb.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.mapNotNull { dev.svgpro.core.util.Numbers.parse(it) }
            if (parts.size >= 4) return ViewBox(parts[0], parts[1], parts[2], parts[3])
            return ViewBox(0.0, 0.0, width, height)
        }

    val width: Double
        get() = dev.svgpro.core.util.Numbers.parse(root.attr("width")) ?: viewBoxWidthFallback()

    val height: Double
        get() = dev.svgpro.core.util.Numbers.parse(root.attr("height")) ?: viewBoxHeightFallback()

    private fun viewBoxWidthFallback() = viewBox.width.takeIf { it > 0.0 } ?: DEFAULT_W
    private fun viewBoxHeightFallback() = viewBox.height.takeIf { it > 0.0 } ?: DEFAULT_H

    fun setViewBox(minX: Double, minY: Double, w: Double, h: Double) {
        root.setAttr(
            "viewBox",
            "${dev.svgpro.core.util.Numbers.format(minX)} ${dev.svgpro.core.util.Numbers.format(minY)} " +
                "${dev.svgpro.core.util.Numbers.format(w)} ${dev.svgpro.core.util.Numbers.format(h)}",
        )
    }

    fun setPageSize(w: Double, h: Double) {
        root.setAttr("width", dev.svgpro.core.util.Numbers.format(w))
        root.setAttr("height", dev.svgpro.core.util.Numbers.format(h))
        if (root.attr("viewBox") == null) {
            setViewBox(0.0, 0.0, w, h)
        }
    }

    // ―――― 編集可能要素の取得 ――――

    fun defsElement(): Element {
        var defs = root.children.filterIsInstance<Element>().firstOrNull { it.tag == "defs" }
        if (defs == null) {
            defs = Element("defs")
            root.children.add(defs)
        }
        return defs
    }

    fun isRenderable(el: Element): Boolean = when (el.tag) {
        in SHAPE_TAGS -> true
        "text" -> true
        "g" -> true
        "a" -> true
        "use" -> true
        "image" -> true
        else -> false
    }

    /** 編集対象になり得る要素（選択ヒットの対象） */
    fun isSelectableShape(el: Element): Boolean = when (el.tag) {
        in SHAPE_TAGS -> true
        "text", "image" -> true
        else -> false
    }

    /** ルート直下の「レイヤー」相当（子要素） */
    fun layerGroup(): List<Element> = root.children.filterIsInstance<Element>()

    fun serialize(): String {
        // xmlns がなければ補う
        if (root.attr("xmlns") == null) {
            val out = root.deepCopy()
            out.setAttr("xmlns", "http://www.w3.org/2000/svg")
            out.setAttr("xmlns:xlink", "http://www.w3.org/1999/xlink")
            return dev.svgpro.core.svg.SvgWriter.write(out)
        }
        return dev.svgpro.core.svg.SvgWriter.write(root)
    }
}
