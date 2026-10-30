package dev.svgpro.core.model

import dev.svgpro.core.geom.Bounds
import dev.svgpro.core.geom.Matrix
import dev.svgpro.core.path.PathData
import dev.svgpro.core.svg.Element

/**
 * クリッピングパス / 不透明マスクの生成ヘルパ（非破壊編集）。
 * SVG標準の clipPath / mask 要素を生成し、対象要素の clip-path / mask 属性で参照する。
 *
 * 座標系: clipPath(mask) の内容は userSpaceOnUse であり、
 * 「対象要素のローカル座標系（targetのtransform適用後の空間）」へ焼き込む。
 */
object ClipMaskUtil {

    /**
     * sources（図形要素群）から clipPath 定義を作る。
     * @param absOf 要素の絶対CTM
     * @param targetAbs 対象要素の絶対CTM（クリップ内容はこの逆変換で焼き込む）
     */
    fun createClipPath(
        id: String,
        sources: List<Element>,
        absOf: (Element) -> Matrix,
        targetAbs: Matrix,
    ): Element {
        val clip = Element("clipPath")
        clip.setAttr("id", id)
        val toTargetLocal = targetAbs.invert()
        for (src in sources) {
            val geo = Shapes.geometryOf(src) ?: continue
            val baked = geo.clone()
            baked.transform(toTargetLocal.then(absOf(src)))
            val p = Element("path")
            p.setAttr("d", baked.write())
            clip.children.add(p)
        }
        return clip
    }

    /**
     * sources（図形要素群）から不透明マスク定義を作る。
     * 黒背景 + 白い図形 = 図形の内側だけが表示される（不透明度マスク）。
     * mask要素のリージョンは対象bbox（targetローカル）で指定する。
     */
    fun createMask(
        id: String,
        sources: List<Element>,
        absOf: (Element) -> Matrix,
        targetAbs: Matrix,
        targetGeometryBounds: Bounds?,
    ): Element? {
        val toTargetLocal = targetAbs.invert()
        val mask = Element("mask")
        mask.setAttr("id", id)
        mask.setAttr("maskUnits", "userSpaceOnUse")
        // リージョン: 対象bbox + マスク図形bbox の和（余裕をもって）
        var region: Bounds? = targetGeometryBounds?.let { toTargetLocal.transformBounds(it) }
        for (src in sources) {
            val geo = Shapes.geometryOf(src) ?: continue
            val b = toTargetLocal.then(absOf(src)).transformBounds(geo.bounds(0.05))
            region = if (region == null) b else region!!.union(b)
        }
        val r = region ?: return null
        val n = dev.svgpro.core.util.Numbers
        mask.setAttr("x", n.format(r.x - r.width * 0.1))
        mask.setAttr("y", n.format(r.y - r.height * 0.1))
        mask.setAttr("width", n.format(r.width * 1.2))
        mask.setAttr("height", n.format(r.height * 1.2))

        val bg = Element("rect")
        bg.setAttr("x", n.format(r.x - r.width * 0.1))
        bg.setAttr("y", n.format(r.y - r.height * 0.1))
        bg.setAttr("width", n.format(r.width * 1.2))
        bg.setAttr("height", n.format(r.height * 1.2))
        bg.setAttr("fill", "black")
        mask.children.add(bg)

        for (src in sources) {
            val geo = Shapes.geometryOf(src) ?: continue
            val baked = geo.clone()
            baked.transform(toTargetLocal.then(absOf(src)))
            val p = Element("path")
            p.setAttr("d", baked.write())
            p.setAttr("fill", "white")
            mask.children.add(p)
        }
        return mask
    }

    /** 対象要素へクリップを適用（既存のクリップは置換） */
    fun applyClip(target: Element, clipId: String) {
        target.setAttr("clip-path", "url(#$clipId)")
    }

    /** 対象要素へマスクを適用 */
    fun applyMask(target: Element, maskId: String) {
        target.setAttr("mask", "url(#$maskId)")
    }

    /** クリップ解除。使用されなくなった定義は defs からも除去する */
    fun releaseClip(target: Element, root: Element, clipId: String) {
        target.setAttr("clip-path", null)
        removeDefIfUnused(root, clipId)
    }

    /** マスク解除 */
    fun releaseMask(target: Element, root: Element, maskId: String) {
        target.setAttr("mask", null)
        removeDefIfUnused(root, maskId)
    }

    /** id参照が他に無ければ defs から定義を除去 */
    fun removeDefIfUnused(root: Element, id: String) {
        var used = false
        root.walk { el ->
            for ((k, v) in el.attrs) {
                if (v.contains("url(#$id)")) used = true
            }
        }
        if (used) return
        val defs = root.children.filterIsInstance<Element>().firstOrNull { it.tag == "defs" } ?: return
        val def = defs.childElements().firstOrNull { it.attr("id") == id } ?: return
        defs.children.remove(def)
    }

    /** 複合パス（1つのpath要素に複数サブパス + evenodd）をサブパス単位のpath要素群へ分解 */
    fun splitSubpaths(el: Element): List<Element>? {
        if (el.tag != "path") return null
        val pd = PathData.parse(el.attr("d")) ?: return null
        val subs = pd.subpaths()
        if (subs.size < 2) return null
        val out = ArrayList<Element>(subs.size)
        for (sp in subs) {
            val segs = ArrayList<dev.svgpro.core.path.Seg>(pd.segs.subList(sp.segStart, sp.segEnd + if (sp.closed) 1 else 0))
            val p = Element("path")
            for ((k, v) in el.attrs) {
                if (k == "d" || k == "fill-rule") p.attrs[k] = v
            }
            p.setAttr("d", PathData(segs).write())
            out.add(p)
        }
        return out
    }
}
