package dev.svgpro.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Base64
import dev.svgpro.core.geom.Matrix
import dev.svgpro.core.model.Paint as SvgPaint
import dev.svgpro.core.model.Shapes
import dev.svgpro.core.model.StyleUtil
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import dev.svgpro.core.svg.Element
import dev.svgpro.editor.Editor
import dev.svgpro.editor.Trace

/**
 * Document（Trace）→ android.graphics 描画。
 * 表示のみを担当し、編集データには関与しない。
 */
class SvgRenderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapCache = HashMap<String, Bitmap?>()

    fun draw(canvas: Canvas, editor: Editor, overrideMatrix: Matrix? = null) {
        val trace = editor.trace
        for (item in trace.items) {
            if (!item.visible) continue
            drawItem(canvas, editor, item, overrideMatrix)
        }
    }

    private fun drawItem(canvas: Canvas, editor: Editor, item: Trace.Item, overrideMatrix: Matrix? = null) {
        val el = item.el
        val save = canvas.save()
        try {
            // 合成順: ローカル→user空間のCTM(item.abs)を先に適用し、その後 user→画面
            // (viewMatrix / overrideMatrix)を適用する。逆順だとtransform属性が画面空間で
            // 解釈され、選択ボックス(=abs基準のbounds)と描画がずれる。
            val m = item.abs.then(overrideMatrix ?: editor.viewMatrix)
            canvas.concat(toAndroid(m))
            applyClip(canvas, editor, el, item)
            // mask属性: レイヤーを分けて内容を描画し、マスク内容（白図形）でDST_IN
            val maskProp = StyleUtil.getProperty(el, "mask")
            if (maskProp != null) {
                val layer = canvas.saveLayer(null, null, Canvas.ALL_SAVE_FLAG)
                drawItemContent(canvas, editor, item)
                applyMask(canvas, editor, maskProp)
                canvas.restoreToCount(layer)
            } else {
                drawItemContent(canvas, editor, item)
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun drawItemContent(canvas: Canvas, editor: Editor, item: Trace.Item) {
        val el = item.el
        when (el.tag) {
            "g", "a" -> return // 子は個別アイテムとして描画済み
            "use" -> drawUse(canvas, editor, el, item)
            "image" -> drawImage(canvas, el)
            "text" -> drawText(canvas, editor, el, item)
            else -> drawShape(canvas, editor, el, item)
        }
    }

    /**
     * 不透明マスクの簡易適用（輝度近似）:
     * マスク内の白い図形の領域だけを表示する（黒背景は領域外扱い）。
     * マスク内容の座標系は対象要素のローカル空間（生成時に焼き込み済み）。
     */
    private fun applyMask(canvas: Canvas, editor: Editor, maskProp: String) {
        val match = Regex("url\\([\'\"]?#([^)\'\"\\s]+)[\'\"]?\\)").find(maskProp) ?: return
        val maskEl = editor.doc.root.findById(match.groupValues[1]) ?: return
        if (maskEl.tag != "mask") return
        val xfer = Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            color = 0xFFFFFFFF.toInt()
        }
        for (child in maskEl.childElements()) {
            val fill = StyleUtil.getProperty(child, "fill") ?: continue
            val color = dev.svgpro.core.model.Color.parse(fill) ?: continue
            val alpha = (color ushr 24) and 0xFF
            // 白系のみ表示領域として扱う（輝度近似: 黒=隠す、白=表示）
            if (alpha == 0) continue
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            if (r + g + b < 384) continue // 暗い色は領域外扱い
            val geo = Shapes.geometryOf(child) ?: continue
            xfer.alpha = alpha
            canvas.drawPath(toAndroidPath(geo), xfer)
        }
    }

    /** clip-path属性の簡易適用（clipPath内のshapeの和をクリップ） */
    private fun applyClip(canvas: Canvas, editor: Editor, el: Element, item: Trace.Item) {
        val clipProp = StyleUtil.getProperty(el, "clip-path") ?: return
        val match = Regex("""url\(['"]?#([^)'"\s]+)['"]?\)""").find(clipProp) ?: return
        val clipEl = editor.doc.root.findById(match.groupValues[1]) ?: return
        if (clipEl.tag != "clipPath") return
        val clipPath = Path()
        for (child in clipEl.childElements()) {
            val geo = Shapes.geometryOf(child) ?: continue
            val local = Shapes.localTransform(child)
            val transformed = geo.clone()
            transformed.transform(local)
            clipPath.addPath(toAndroidPath(transformed))
        }
        if (!clipPath.isEmpty) {
            canvas.clipPath(clipPath)
        }
    }

    private fun drawShape(canvas: Canvas, editor: Editor, el: Element, item: Trace.Item) {
        val geo = item.geometry ?: return
        val path = toAndroidPath(geo)
        val fill = editor.inheritedPaint(el, "fill")
        val stroke = editor.inheritedPaint(el, "stroke")
        val elementOpacity = StyleUtil.opacity(el)

        if (fill !is SvgPaint.None) {
            configurePaint(fillPaint, fill, el, item, editor, true)
            fillPaint.alpha = (fillPaint.alpha * elementOpacity).toInt().coerceIn(0, 255)
            canvas.drawPath(path, fillPaint)
        }
        if (stroke !is SvgPaint.None) {
            configurePaint(strokePaint, stroke, el, item, editor, false)
            val width = (dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(el, "stroke-width")) ?: 1.0)
            strokePaint.strokeWidth = width.toFloat()
            strokePaint.style = Paint.Style.STROKE
            cap(el)?.let { strokePaint.strokeCap = it }
            join(el)?.let { strokePaint.strokeJoin = it }
            dash(el)?.let { strokePaint.pathEffect = it }
            strokePaint.alpha = (strokePaint.alpha * elementOpacity).toInt().coerceIn(0, 255)
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun configurePaint(
        paint: Paint,
        paintSpec: SvgPaint,
        el: Element,
        item: Trace.Item,
        editor: Editor,
        isFill: Boolean,
    ) {
        when (paintSpec) {
            is SvgPaint.Solid -> {
                paint.shader = null
                paint.color = paintSpec.argb
                paint.style = Paint.Style.FILL
            }
            is SvgPaint.Server -> {
                paint.shader = buildShader(paintSpec.id, el, item, editor)
                paint.color = Color.BLACK
                paint.style = Paint.Style.FILL
            }
            is SvgPaint.None -> {}
        }
    }

    private fun buildShader(id: String, el: Element, item: Trace.Item, editor: Editor): Shader? {
        val def = editor.doc.root.findById(id) ?: return null
        val bounds = item.geometry?.bounds(0.02) ?: return null
        val opacity = dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "gradient-opacity")) ?: 1.0
        val stops = gradientStops(def, editor, opacity)
        if (stops.isEmpty()) return null
        val userSpace = StyleUtil.getProperty(def, "gradientUnits") == "userSpaceOnUse"
        val colors = IntArray(stops.size)
        val positions = FloatArray(stops.size)
        for ((i, s) in stops.withIndex()) {
            colors[i] = s.first
            positions[i] = s.second.toFloat().coerceIn(0f, 1f)
        }
        // positionsの単調性を保障
        for (i in 1 until positions.size) {
            if (positions[i] <= positions[i - 1]) positions[i] = positions[i - 1] + 0.0001f
        }
        if (positions.last() > 1f) {
            val scale = 1f / positions.last()
            for (i in positions.indices) positions[i] *= scale
        }
        if (def.tag == "pattern") {
            return patternShader(def, item, editor)
        }
        return when (def.tag) {
            "linearGradient" -> {
                val (x1, y1, x2, y2) = if (userSpace) {
                    quadruple(
                        dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "x1")) ?: 0.0,
                        dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "y1")) ?: 0.0,
                        dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "x2")) ?: 1.0,
                        dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "y2")) ?: 0.0,
                    )
                } else {
                    quadruple(
                        bounds.x + bounds.width * (dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "x1")) ?: 0.0),
                        bounds.y + bounds.height * (dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "y1")) ?: 0.0),
                        bounds.x + bounds.width * (dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "x2")) ?: 1.0),
                        bounds.y + bounds.height * (dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "y2")) ?: 0.0),
                    )
                }
                LinearGradient(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), colors, positions, Shader.TileMode.CLAMP)
            }
            "radialGradient" -> {
                val cxv: Double
                val cyv: Double
                val rv: Double
                if (userSpace) {
                    cxv = dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "cx")) ?: bounds.centerX
                    cyv = dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "cy")) ?: bounds.centerY
                    rv = dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "r")) ?: (bounds.width / 2)
                } else {
                    cxv = bounds.x + bounds.width * (dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "cx")) ?: 0.5)
                    cyv = bounds.y + bounds.height * (dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "cy")) ?: 0.5)
                    rv = (dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(def, "r")) ?: 0.5) *
                        maxOf(bounds.width, bounds.height)
                }
                RadialGradient(cxv.toFloat(), cyv.toFloat(), rv.toFloat().coerceAtLeast(0.01f), colors, positions, Shader.TileMode.CLAMP)
            }
            else -> null
        }
    }

    /**
     * パターン塗り（userSpaceOnUse + patternTransform対応）。
     * タイルをビットマップへ描画し、Repeatシェーダとして塗る。
     */
    private fun patternShader(def: Element, item: Trace.Item, editor: Editor): Shader? {
        val n = dev.svgpro.core.util.Numbers
        val pw = n.parse(StyleUtil.getProperty(def, "width")) ?: return null
        val ph = n.parse(StyleUtil.getProperty(def, "height")) ?: return null
        if (pw <= 0.0 || ph <= 0.0) return null
        val px = n.parse(StyleUtil.getProperty(def, "x")) ?: 0.0
        val py = n.parse(StyleUtil.getProperty(def, "y")) ?: 0.0
        val contentUnits = StyleUtil.getProperty(def, "contentUnits")
        if (contentUnits == "objectBoundingBox") return null // 未対応（保持のみ）
        // 画面解像度に応じたタイルビットマップ
        val scale = editor.zoom.coerceIn(0.05, 8.0)
        val bmpW = (pw * scale).toInt().coerceIn(1, 2048)
        val bmpH = (ph * scale).toInt().coerceIn(1, 2048)
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(bmpW.toFloat() / pw.toFloat(), bmpH.toFloat() / ph.toFloat())
        c.translate((-px).toFloat(), (-py).toFloat())
        // タイル内容の描画（簡易: 図形のみ）
        val tileRenderer = Paint(Paint.ANTI_ALIAS_FLAG)
        for (child in def.childElements()) {
            val geo = Shapes.geometryOf(child) ?: continue
            val childGeo = geo.clone()
            childGeo.transform(Shapes.localTransform(child))
            val fill = StyleUtil.getProperty(child, "fill") ?: "#000000"
            val color = dev.svgpro.core.model.Color.parse(fill) ?: 0xFF000000.toInt()
            tileRenderer.color = color
            tileRenderer.style = Paint.Style.FILL
            c.drawPath(toAndroidPath(childGeo), tileRenderer)
        }
        val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        // user空間 → タイルピクセルの対応: p_tile = P⁻¹(p_user - origin) * (bmpW/pw)
        val sx = bmpW.toDouble() / pw
        val sy = bmpH.toDouble() / ph
        val pt = Matrix.fromSvgTransform(StyleUtil.getProperty(def, "patternTransform") ?: "")
        val inv = if (pt.isIdentity()) Matrix.IDENTITY else pt.invert()
        val m = Matrix(
            a = sx, b = 0.0, c = 0.0, d = sy,
            e = -px * sx, f = -py * sy,
        )
        // pixel = S · P⁻¹ · (p - origin): m = translate(-origin)·S を先に、P⁻¹を後に適用
        val combined = inv.then(m)
        shader.setLocalMatrix(
            android.graphics.Matrix().apply {
                setValues(
                    floatArrayOf(
                        combined.a.toFloat(), combined.c.toFloat(), combined.e.toFloat(),
                        combined.b.toFloat(), combined.d.toFloat(), combined.f.toFloat(),
                        0f, 0f, 1f,
                    ),
                )
            },
        )
        return shader
    }

    private fun gradientStops(def: Element, editor: Editor, opacity: Double): List<Pair<Int, Double>> {
        // href 継承を解決しつつ自分のstopsを優先
        val href = StyleUtil.href(def)
        val inherited = if (href != null) {
            val target = editor.doc.root.findById(href.removePrefix("#"))
            if (target != null && target.tag in setOf("linearGradient", "radialGradient")) gradientStops(target, editor, opacity) else emptyList()
        } else {
            emptyList()
        }
        val own = def.childElements().filter { it.tag == "stop" }.map { stop ->
            val color = dev.svgpro.core.model.Color.parse(StyleUtil.getProperty(stop, "stop-color")) ?: 0xFF000000.toInt()
            val so = dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(stop, "stop-opacity")) ?: 1.0
            val offset = dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(stop, "offset")) ?: 0.0
            val a = ((so * opacity).coerceIn(0.0, 1.0) * 255).toInt()
            (color and 0x00FFFFFF) or (a shl 24) to offset
        }
        return if (own.isEmpty()) inherited else own
    }

    private fun cap(el: Element): Paint.Cap? = when (StyleUtil.getProperty(el, "stroke-linecap")) {
        "round" -> Paint.Cap.ROUND
        "square" -> Paint.Cap.SQUARE
        "butt" -> Paint.Cap.BUTT
        else -> null
    }

    private fun join(el: Element): Paint.Join? = when (StyleUtil.getProperty(el, "stroke-linejoin")) {
        "round" -> Paint.Join.ROUND
        "bevel" -> Paint.Join.BEVEL
        "miter" -> Paint.Join.MITER
        else -> null
    }

    private fun dash(el: Element): DashPathEffect? {
        val arr = StyleUtil.dashArray(el) ?: return null
        if (arr.isEmpty()) return null
        val list = if (arr.size % 2 == 1) arr + arr else arr
        return DashPathEffect(list.map { it.toFloat().coerceAtLeast(0.1f) }.toFloatArray(), 0f)
    }

    private fun drawText(canvas: Canvas, editor: Editor, el: Element, item: Trace.Item) {
        // textPath（パスに沿ったテキスト）: 標準構造を解決して近似描画
        val tp = el.childElements().firstOrNull { it.tag == "textPath" }
        if (tp != null) {
            drawTextOnPath(canvas, editor, el, tp, item)
            return
        }
        val content = el.textContent()
        if (content.isEmpty()) return
        val x = (dev.svgpro.core.util.Numbers.parse(el.attr("x")) ?: 0.0).toFloat()
        val y = (dev.svgpro.core.util.Numbers.parse(el.attr("y")) ?: 0.0).toFloat()
        val fontSize = (dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(el, "font-size")) ?: 16.0).toFloat()
        textPaint.textSize = fontSize
        textPaint.typeface = typeface(el)
        val anchor = StyleUtil.getProperty(el, "text-anchor")
        when (anchor) {
            "middle" -> textPaint.textAlign = Paint.Align.CENTER
            "end" -> textPaint.textAlign = Paint.Align.RIGHT
            else -> textPaint.textAlign = Paint.Align.LEFT
        }
        val fill = StyleUtil.getProperty(el, "fill")
        val color = dev.svgpro.core.model.Color.parse(fill) ?: Color.BLACK
        textPaint.color = color
        val ls = dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(el, "letter-spacing")) ?: 0.0
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            textPaint.letterSpacing = (ls / fontSize.coerceAtLeast(1f)).toFloat()
        }
        // 複数行（tspanによる折返しは簡易対応: text全体を1行で描画）
        canvas.drawText(content, x, y, textPaint)
    }

    private fun typeface(el: Element): android.graphics.Typeface {
        val family = StyleUtil.getProperty(el, "font-family") ?: "sans-serif"
        val weight = StyleUtil.getProperty(el, "font-weight") ?: "normal"
        val style = StyleUtil.getProperty(el, "font-style") ?: "normal"
        val italic = style == "italic" || style == "oblique"
        val bold = weight == "bold" || weight == "700" || weight == "800" || weight == "900"
        val base = when {
            family.contains("serif", true) && family.contains("sans", true).not() -> android.graphics.Typeface.SERIF
            family.contains("mono", true) -> android.graphics.Typeface.MONOSPACE
            else -> android.graphics.Typeface.SANS_SERIF
        }
        return if (bold || italic) {
            android.graphics.Typeface.create(base, (if (bold) android.graphics.Typeface.BOLD else 0) or (if (italic) android.graphics.Typeface.ITALIC else 0))
        } else base
    }

    /**
     * textPathの近似描画: 参照パスをフラット化し、文字を接線方向に沿って配置する。
     * （外部ビューアではtextPath構造がそのまま使われるため、表示の近似は編集補助）
     */
    private fun drawTextOnPath(canvas: Canvas, editor: Editor, el: Element, tp: Element, item: Trace.Item) {
        val content = tp.textContent().ifEmpty { el.textContent() }
        if (content.isEmpty()) return
        val href = StyleUtil.href(tp) ?: return
        val pathEl = editor.doc.root.findById(href.removePrefix("#")) ?: return
        val geo = editor.trace.itemOf(pathEl)?.geometry ?: Shapes.geometryOf(pathEl) ?: return
        val pts = geo.flatten(0.1)
        if (pts.size < 2) return
        // 累積長
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + pts[i].distanceTo(pts[i - 1])
        val total = cum.last()
        if (total <= 0.0) return
        val fontSize = (dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(el, "font-size")) ?: 16.0).toFloat()
        textPaint.textSize = fontSize
        textPaint.typeface = typeface(el)
        val color = dev.svgpro.core.model.Color.parse(StyleUtil.getProperty(el, "fill")) ?: Color.BLACK
        textPaint.color = color
        textPaint.textAlign = Paint.Align.LEFT
        val startOffset = (dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(tp, "startOffset")) ?: 0.0)
        val startLen = if (StyleUtil.getProperty(tp, "startOffset")?.contains("%") == true) total * startOffset / 100.0 else startOffset
        var s = startLen
        for (ch in content) {
            val w = textPaint.measureText(ch.toString())
            val mid = s + w / 2
            if (mid > total) break
            // 位置と接線
            var i = cum.binarySearch(mid)
            if (i < 0) i = -(i + 1)
            i = i.coerceIn(1, pts.size - 1)
            val a = pts[i - 1]
            val b = pts[i]
            val span = cum[i] - cum[i - 1]
            val t = if (span <= 0.0) 0.0 else (mid - cum[i - 1]) / span
            val px = a.x + (b.x - a.x) * t
            val py = a.y + (b.y - a.y) * t
            val angle = Math.toDegrees(kotlin.math.atan2(b.y - a.y, b.x - a.x))
            canvas.save()
            canvas.translate(px.toFloat(), py.toFloat())
            canvas.rotate(angle.toFloat())
            canvas.drawText(ch.toString(), -w / 2, 0f, textPaint)
            canvas.restore()
            s += w
        }
    }

    private fun drawImage(canvas: Canvas, el: Element) {
        val href = StyleUtil.href(el) ?: return
        val bitmap = bitmapFor(href) ?: return
        val x = (dev.svgpro.core.util.Numbers.parse(el.attr("x")) ?: 0.0).toFloat()
        val y = (dev.svgpro.core.util.Numbers.parse(el.attr("y")) ?: 0.0).toFloat()
        val w = (dev.svgpro.core.util.Numbers.parse(el.attr("width")) ?: bitmap.width.toDouble()).toFloat()
        val h = (dev.svgpro.core.util.Numbers.parse(el.attr("height")) ?: bitmap.height.toDouble()).toFloat()
        val dst = android.graphics.RectF(x, y, x + w, y + h)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    private fun bitmapFor(href: String): Bitmap? {
        if (href in bitmapCache) return bitmapCache[href]
        val bmp: Bitmap? = if (href.startsWith("data:")) {
            val b64 = href.substringAfter("base64,", "")
            if (b64.isNotEmpty()) {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    null
                }
            } else null
        } else {
            // 外部参照はセキュリティポリシーにより読み込まない
            null
        }
        bitmapCache[href] = bmp
        return bmp
    }

    private fun drawUse(canvas: Canvas, editor: Editor, el: Element, item: Trace.Item) {
        val href = StyleUtil.href(el) ?: return
        val target = editor.doc.root.findById(href.removePrefix("#")) ?: return
        val dx = dev.svgpro.core.util.Numbers.parse(el.attr("x")) ?: 0.0
        val dy = dev.svgpro.core.util.Numbers.parse(el.attr("y")) ?: 0.0
        val save = canvas.save()
        try {
            canvas.translate(dx.toFloat(), dy.toFloat())
            if (target.tag == "symbol") {
                // シンボル: 子要素を順に描画
                drawSymbolContent(canvas, editor, target, item)
                return
            }
            val geo = Shapes.geometryOf(target)
            if (geo != null) {
                val pseudoItem = item.copy(el = target, geometry = geo)
                drawShape(canvas, editor, target, pseudoItem)
            } else if (target.tag == "text") {
                drawText(canvas, editor, target, item)
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    /** symbol内容の描画（ネストしたgにも対応） */
    private fun drawSymbolContent(canvas: Canvas, editor: Editor, container: Element, item: Trace.Item) {
        for (child in container.childElements()) {
            val save = canvas.save()
            try {
                canvas.concat(toAndroid(Shapes.localTransform(child)))
                val geo = Shapes.geometryOf(child)
                if (geo != null) {
                    val pseudoItem = item.copy(el = child, geometry = geo)
                    drawShape(canvas, editor, child, pseudoItem)
                } else when (child.tag) {
                    "text" -> drawText(canvas, editor, child, item)
                    "g", "a" -> drawSymbolContent(canvas, editor, child, item)
                    "use" -> drawUse(canvas, editor, child, item)
                    else -> {}
                }
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }

    companion object {
        fun toAndroid(m: Matrix): android.graphics.Matrix = android.graphics.Matrix().apply {
            setValues(
                floatArrayOf(
                    m.a.toFloat(), m.c.toFloat(), m.e.toFloat(),
                    m.b.toFloat(), m.d.toFloat(), m.f.toFloat(),
                    0f, 0f, 1f,
                ),
            )
        }

        /** PathData → android.graphics.Path */
        fun toAndroidPath(pd: PathData): Path {
            val path = Path()
            var cur = dev.svgpro.core.geom.Vec.ZERO
            for (s in pd.segs) {
                when (s) {
                    is Seg.Move -> {
                        path.moveTo(s.x.toFloat(), s.y.toFloat())
                        cur = dev.svgpro.core.geom.Vec(s.x, s.y)
                    }
                    is Seg.Line -> {
                        path.lineTo(s.x.toFloat(), s.y.toFloat())
                        cur = dev.svgpro.core.geom.Vec(s.x, s.y)
                    }
                    is Seg.Cubic -> {
                        path.cubicTo(s.c1x.toFloat(), s.c1y.toFloat(), s.c2x.toFloat(), s.c2y.toFloat(), s.x.toFloat(), s.y.toFloat())
                        cur = dev.svgpro.core.geom.Vec(s.x, s.y)
                    }
                    is Seg.Quad -> {
                        path.quadTo(s.cx.toFloat(), s.cy.toFloat(), s.x.toFloat(), s.y.toFloat())
                        cur = dev.svgpro.core.geom.Vec(s.x, s.y)
                    }
                    is Seg.Arc -> {
                        // android.Path.arcToは楕円回転を直接サポートしないためcubic変換で描く
                        for ((c1, c2, e) in dev.svgpro.core.path.ArcConverter.arcToCubics(cur, s)) {
                            path.cubicTo(c1.x.toFloat(), c1.y.toFloat(), c2.x.toFloat(), c2.y.toFloat(), e.x.toFloat(), e.y.toFloat())
                        }
                        cur = dev.svgpro.core.geom.Vec(s.x, s.y)
                    }
                    Seg.Close -> path.close()
                }
            }
            return path
        }

        private fun quadruple(a: Double, b: Double, c: Double, d: Double): DoubleArray = doubleArrayOf(a, b, c, d)
    }
}
