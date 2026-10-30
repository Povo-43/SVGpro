package dev.svgpro.editor

import dev.svgpro.core.geom.Bounds
import dev.svgpro.core.geom.Matrix
import dev.svgpro.core.model.Shapes
import dev.svgpro.core.model.SvgDocument
import dev.svgpro.core.model.StyleUtil
import dev.svgpro.core.path.PathData
import dev.svgpro.core.svg.Element

/**
 * テキスト測定のプラットフォーム橋渡し（appが登録する）。
 */
object TextMetrics {
    /** text要素の user空間bounds（アンカー x,y と font-size を考慮した近似） */
    var provider: ((Element) -> Bounds?)? = null
}

/**
 * ドキュメント走査の結果（ヒットテスト・描画・選択bbox用のキャッシュ）。
 * すべて user 単位系（viewBox空間）の絶対座標。
 *
 * 大規模ドキュメント対策:
 *  - 幾何（PathData）は「幾何シグネチャ」が変わらない限り前回のTraceから再利用する
 *    （移動・変形はtransform属性の変更なので、幾何の再解析を回避できる）
 *  - 親要素参照をキャッシュし、findParent相当の走査を廃止
 */
class Trace private constructor(
    val items: List<Item>,
    val elementIndex: Map<Element, Item>,
    /** 幾何キャッシュ: el → (シグネチャ, 幾何)。再構築時の再解析を避ける */
    val geoCache: Map<Element, Pair<String, PathData>> = emptyMap(),
) {
    data class Item(
        val el: Element,
        /** 絶対CTM（親 + 自身のtransform） */
        val abs: Matrix,
        /** 親のCTM（相対操作に使用） */
        val parentAbs: Matrix,
        /** 親要素（ルートの子ならdoc.root） */
        val parent: Element?,
        /** 絶対空間の幾何（ヒット/境界用）。g等はnull */
        val geometry: PathData?,
        /** 表示状態（display, visible） */
        val visible: Boolean,
        /** ロック状態 */
        val locked: Boolean,
        /** 所属レイヤー（ルート直下のg、または自身が直下なら自分） */
        val layer: Element?,
        /** 描画順のインデックス（document順） */
        val order: Int,
    )

    fun itemOf(el: Element): Item? = elementIndex[el]

    fun parentAbsOf(el: Element): Matrix = elementIndex[el]?.parentAbs ?: Matrix.IDENTITY
    fun absOf(el: Element): Matrix = elementIndex[el]?.abs ?: Matrix.IDENTITY

    /** 親要素（キャッシュ参照） */
    fun parentOf(el: Element): Element? = elementIndex[el]?.parent

    /** 選択要素群の絶対bbox */
    fun boundsOf(elems: List<Element>): Bounds? {
        var result: Bounds? = null
        for (el in elems) {
            val b = boundsOf(el) ?: continue
            result = if (result == null) b else result!!.union(b)
        }
        return result
    }

    fun boundsOf(el: Element): Bounds? {
        val item = elementIndex[el] ?: return null
        if (!item.visible) return null
        if (item.geometry != null) {
            // ローカル幾何を絶対CTMで変換したbounds
            return item.abs.transformBounds(item.geometry.bounds(0.05))
        }
        // g / text / image
        return when (el.tag) {
            "g" -> {
                var b: Bounds? = null
                for (child in el.childElements()) {
                    val cb = boundsOf(child) ?: continue
                    b = if (b == null) cb else b!!.union(cb)
                }
                b
            }
            "text" -> TextMetrics.provider?.invoke(el)
            "use" -> {
                // 参照先幾何はbuild時にgeometryへ解決される。ここはフォールバック
                val x = dev.svgpro.core.util.Numbers.parse(el.attr("x")) ?: 0.0
                val y = dev.svgpro.core.util.Numbers.parse(el.attr("y")) ?: 0.0
                val w = dev.svgpro.core.util.Numbers.parse(el.attr("width")) ?: 40.0
                val h = dev.svgpro.core.util.Numbers.parse(el.attr("height")) ?: 40.0
                Bounds(x, y, w, h)
            }
            "image" -> {
                val x = dev.svgpro.core.util.Numbers.parse(el.attr("x")) ?: 0.0
                val y = dev.svgpro.core.util.Numbers.parse(el.attr("y")) ?: 0.0
                val w = dev.svgpro.core.util.Numbers.parse(el.attr("width")) ?: 0.0
                val h = dev.svgpro.core.util.Numbers.parse(el.attr("height")) ?: 0.0
                Bounds(x, y, w, h)
            }
            else -> null
        }
    }

    /** ヒットテスト（user座標、許容誤差tol）。visibleかつlockedでない要素が対象。 */
    fun hitTest(p: dev.svgpro.core.geom.Vec, tol: Double, includeLocked: Boolean = false): Element? {
        // 手前（document後半）を優先
        for (item in items.reversed()) {
            if (!item.visible) continue
            if (item.locked && !includeLocked) continue
            if (hitItem(item, p, tol)) return item.el
        }
        return null
    }

    fun hitItem(item: Item, p: dev.svgpro.core.geom.Vec, tol: Double): Boolean {
        val geo = item.geometry
        if (geo != null) {
            // ローカル空間へ変換して判定（許容誤差もスケールで補正）
            val inv = item.abs.invert()
            val pLocal = inv.apply(p)
            val localTol = tol / item.abs.a.coerceAtLeast(1e-6)
            val proj = geo.project(pLocal, localTol)
            val nearEdge = proj != null && proj.distance <= localTol
            if (nearEdge) return true
            // 塗りヒット: 偶奇
            if (hasFill(item.el) && insidePolygon(geo, pLocal)) return true
            return false
        }
        val b = boundsOf(item.el) ?: return false
        return b.inflate(tol, tol).contains(p)
    }

    private fun hasFill(el: Element): Boolean {
        val paint = StyleUtil.paint(el, "fill")
        return paint !is dev.svgpro.core.model.Paint.None
    }

    private fun insidePolygon(geo: PathData, p: dev.svgpro.core.geom.Vec): Boolean {
        val pts = geo.flatten(0.02)
        if (pts.size < 3) return false
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[j]
            if ((a.y > p.y) != (b.y > p.y)) {
                val xint = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
                if (p.x < xint) inside = !inside
            }
            j = i
        }
        return inside
    }

    companion object {
        const val LOCK_ATTR = "data-svgpro-lock"

        /** これらの子は個別に走査しない（defs/clipPath等は参照時に解決） */
        private val SKIP_SUBTREE = setOf("defs", "clipPath", "mask", "pattern", "filter", "symbol", "marker")

        /** 幾何シグネチャ: 幾何を決める属性のハッシュ（transformは含まない） */
        private fun geometrySignature(el: Element): String = when (el.tag) {
            "path" -> el.attr("d") ?: ""
            "rect" -> "r${el.attr("x")},${el.attr("y")},${el.attr("width")},${el.attr("height")},${el.attr("rx")},${el.attr("ry")}"
            "circle" -> "c${el.attr("cx")},${el.attr("cy")},${el.attr("r")}"
            "ellipse" -> "e${el.attr("cx")},${el.attr("cy")},${el.attr("rx")},${el.attr("ry")}"
            "line" -> "l${el.attr("x1")},${el.attr("y1")},${el.attr("x2")},${el.attr("y2")}"
            "polyline", "polygon" -> "p${el.attr("points")}"
            else -> ""
        }

        /**
         * ドキュメントを走査してTraceを構築。
         * @param previous 前回のTrace（幾何キャッシュとして再利用。null可）
         */
        fun build(doc: SvgDocument, previous: Trace? = null): Trace {
            val items = ArrayList<Item>()
            val index = HashMap<Element, Item>()
            val newCache = HashMap<Element, Pair<String, PathData>>()
            if (previous != null) {
                newCache.putAll(previous.geoCache)
            }
            var order = 0

            fun isHidden(el: Element): Boolean = StyleUtil.getProperty(el, "display") == "none"

            fun isLocked(el: Element): Boolean = el.attr(LOCK_ATTR) == "1"

            fun walk(el: Element, parentAbs: Matrix, parentVisible: Boolean, parentLocked: Boolean, layer: Element?, parent: Element?) {
                val local = Shapes.localTransform(el)
                val abs = parentAbs.then(local)
                val visible = parentVisible && !isHidden(el)
                val locked = parentLocked || isLocked(el)
                val ownLayer = if (parentAbs == Matrix.IDENTITY && el.tag == "g") el else layer
                var geo: PathData? = null
                if (el.tag in Shapes.DRAWABLE_TAGS) {
                    val sig = geometrySignature(el)
                    val cached = newCache[el]
                    geo = if (cached != null && cached.first == sig) {
                        cached.second
                    } else {
                        val fresh = Shapes.geometryOf(el)
                        if (fresh != null) newCache[el] = sig to fresh
                        fresh
                    }
                } else if (el.tag == "use") {
                    // use要素: 参照先の幾何（+x/yオフセット）をヒット/境界用に解決
                    geo = resolveUseGeometry(doc, el)
                }
                val item = Item(el, abs, parentAbs, parent, geo, visible, locked, ownLayer, order++)
                items.add(item)
                index[el] = item
                if (el.tag in SKIP_SUBTREE) return
                for (child in el.childElements()) {
                    walk(child, abs, visible, locked, ownLayer, el)
                }
            }

            for (child in doc.root.childElements()) {
                walk(child, Matrix.IDENTITY, true, false, if (child.tag == "g") child else null, doc.root)
            }
            return Trace(items, index, newCache)
        }

        /** use要素の参照先幾何（ローカルtransform+x/yオフセットを焼き込み） */
        private fun resolveUseGeometry(doc: SvgDocument, el: Element): PathData? {
            val href = StyleUtil.href(el) ?: return null
            val target = doc.root.findById(href.removePrefix("#")) ?: return null
            val geo = Shapes.geometryOf(target) ?: return null
            val baked = geo.clone()
            baked.transform(Shapes.localTransform(target))
            val dx = dev.svgpro.core.util.Numbers.parse(el.attr("x")) ?: 0.0
            val dy = dev.svgpro.core.util.Numbers.parse(el.attr("y")) ?: 0.0
            if (dx != 0.0 || dy != 0.0) baked.transform(Matrix(e = dx, f = dy))
            return baked
        }
    }
}
