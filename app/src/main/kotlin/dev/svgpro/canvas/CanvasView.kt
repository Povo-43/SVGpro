package dev.svgpro.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.svgpro.core.geom.Vec
import dev.svgpro.editor.Editor
import dev.svgpro.editor.ToolType
import kotlin.math.abs

/**
 * 編集キャンバス。
 * - 2本指: 常にパン/ピンチ（作図中を除く優先制御）
 * - 1本指 / スタイラス: ツール操作
 * - hover: スタイラスカーソル表示
 */
class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    lateinit var editor: Editor
    private val renderer = SvgRenderer()
    private lateinit var tools: ToolController

    // ―――― ジェスチャ検出 ――――
    private var activePointers = 0
    private var multiTouchActive = false
    private var multiTouchStartDist = 0f
    private var multiTouchStartScale = 1.0
    private var multiTouchStartMid = Vec.ZERO
    private var multiTouchStartView = dev.svgpro.core.geom.Matrix()

    private var stylusActive = false
    private var stylusDrawing = false
    private var hoverPos: Vec? = null

    // ダブルタップ検出
    private var lastTapTime = 0L
    private var lastTapPos = Vec.ZERO

    // ペン用の状態追跡
    private var penPointerId = -1

    // ―――― 描画ペイント ――――
    private val checkerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFF3A4750.toInt()
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x33808080
    }
    private val selectionBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xFF42A5F5.toInt()
    }
    private val selectionFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x2242A5F5
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xFF42A5F5.toInt()
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFE8EAF0.toInt()
    }
    private val nodeSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF42A5F5.toInt()
    }
    private val nodeSmoothPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF66BB6A.toInt()
    }
    private val handleLinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = 0x9942A5F5.toInt()
    }
    private val draftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFFFB74D.toInt()
        pathEffect = CornerPathEffect(2f)
    }
    private val marqueePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFF42A5F5.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = 0xAAAAEEEE.toInt()
    }
    private val whitePaint = Paint().apply { color = Color.WHITE }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // チェッカーボード
        val tile = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        c.drawColor(0xFFE0E0E0.toInt())
        val p = Paint().apply { color = 0xFFBDBDBD.toInt() }
        c.drawRect(0f, 0f, 8f, 8f, p)
        c.drawRect(8f, 8f, 16f, 16f, p)
        checkerPaint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    fun setup(ed: Editor) {
        this.editor = ed
        tools = ToolController(ed)
        tools.panBy = { dx, dy -> editor.panBy(dx, dy) }
        if (width > 0 && height > 0) {
            editor.setViewport(width.toDouble(), height.toDouble())
        } else {
            editor.fitToPage()
        }
        invalidate()
    }

    // ―――― サイズ変更 ――――

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (this::editor.isInitialized) {
            editor.setViewport(w.toDouble(), h.toDouble())
        }
    }

    // ―――― 入力処理 ――――

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!this::editor.isInitialized) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointers = 1
                val isStylus = ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                    ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                if (isStylus) {
                    stylusActive = true
                    stylusDrawing = true
                    penPointerId = ev.getPointerId(0)
                } else if (stylusActive && editor.penOnly) {
                    // ペン優先モード中の指は無視（パームリジェクション）
                    return true
                }
                if (!isStylus && editor.penOnly && stylusActive) return true

                // ツール操作を開始
                val screen = Vec(ev.x.toDouble(), ev.y.toDouble())
                val user = editor.screenToUser(screen)
                val doubleTap = checkDoubleTap(screen)
                tools.onDown(user, screen, ev, isStylus, doubleTap)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointers = ev.pointerCount
                if (activePointers >= 2) {
                    // 作図/変形をキャンセルしてマルチタッチ（パン・ズーム）へ
                    tools.cancelActive()
                    multiTouchActive = true
                    multiTouchStartDist = dist(ev)
                    multiTouchStartScale = editor.zoom
                    multiTouchStartMid = Vec((ev.getX(0) + ev.getX(1)) / 2.0, (ev.getY(0) + ev.getY(1)) / 2.0)
                    multiTouchStartView = editor.viewMatrix
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouchActive && ev.pointerCount >= 2) {
                    val d = dist(ev)
                    val mid = Vec((ev.getX(0) + ev.getX(1)) / 2.0, (ev.getY(0) + ev.getY(1)) / 2.0)
                    if (multiTouchStartDist > 10f) {
                        val scale = (d / multiTouchStartDist).toDouble()
                        val newScale = (multiTouchStartScale * scale).coerceIn(0.02, 200.0)
                        val actual = newScale / multiTouchStartScale
                        // start時のviewに対して mid固定でスケール + mid移動
                        val base = multiTouchStartView
                        val s = base.a * actual
                        // user座標(マルチタッチ開始時の中点)を現在の中点へ
                        val inv = base.invert()
                        val userMid = inv.apply(multiTouchStartMid)
                        editor.applyViewMatrix(
                            dev.svgpro.core.geom.Matrix(
                                a = s, b = 0.0, c = 0.0, d = s,
                                e = mid.x - userMid.x * s,
                                f = mid.y - userMid.y * s,
                            ),
                        )
                    }
                    return true
                }
                // 単一ポインタ: ツールへ
                val idx = if (penPointerId >= 0) ev.findPointerIndex(penPointerId).takeIf { it >= 0 } ?: 0 else 0
                val screen = Vec(ev.getX(idx).toDouble(), ev.getY(idx).toDouble())
                val user = editor.screenToUser(screen)
                if (editor.tool == ToolType.PEN) {
                    tools.onPenMove(user)
                }
                tools.onMove(user, screen, ev)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                activePointers = ev.pointerCount - 1
                if (activePointers < 2) {
                    multiTouchActive = false
                    // 残った指でツール操作を継続しない（誤操作防止）
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointers = 0
                multiTouchActive = false
                val cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL
                val isStylus = ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                    ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                if (isStylus) {
                    stylusActive = false
                    stylusDrawing = false
                    penPointerId = -1
                }
                val screen = Vec(ev.x.toDouble(), ev.y.toDouble())
                val user = editor.screenToUser(screen)
                if (editor.tool == ToolType.PEN && !cancelled) {
                    tools.onPenUp(user, false)
                }
                tools.onUp(user, screen, ev, cancelled)
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun checkDoubleTap(screen: Vec): Boolean {
        val now = System.currentTimeMillis()
        val dt = now - lastTapTime
        val isDouble = dt < 320 && lastTapPos.distanceTo(screen) < 40
        lastTapTime = now
        lastTapPos = screen
        return isDouble
    }

    private fun dist(ev: MotionEvent): Float {
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    // ―――― hover (スタイラス) ――――

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val screen = Vec(event.x.toDouble(), event.y.toDouble())
                hoverPos = screen
                invalidate()
                true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hoverPos = null
                invalidate()
                true
            }
            MotionEvent.ACTION_SCROLL -> {
                // ホイールでズーム
                if (this::editor.isInitialized) {
                    val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    editor.zoomBy(1.0 + 0.1 * scroll, Vec(event.x.toDouble(), event.y.toDouble()))
                }
                true
            }
            else -> super.onGenericMotionEvent(event)
        }
    }

    // ―――― 描画 ――――

    override fun onDraw(canvas: Canvas) {
        if (!this::editor.isInitialized) return
        canvas.drawColor(0xFF12181D.toInt())

        val vm = editor.viewMatrix
        val vb = editor.doc.viewBox
        val pageUser = RectF(
            vb.minX.toFloat(),
            vb.minY.toFloat(),
            (vb.minX + vb.width).toFloat(),
            (vb.minY + vb.height).toFloat(),
        )
        val pageScreen = RectF()
        val am = SvgRenderer.toAndroid(vm)
        am.mapRect(pageScreen, pageUser)

        // ページ背景（市松模様）
        canvas.drawRect(pageScreen, checkerPaint)
        canvas.drawRect(pageScreen, whitePaint.apply { alpha = 60 })

        // ドキュメント描画
        canvas.save()
        canvas.concat(am)
        renderer.draw(canvas, editor)
        canvas.restore()

        // グリッド
        if (editor.showGrid) {
            drawGrid(canvas, pageScreen)
        }

        // ページ枠
        canvas.drawRect(pageScreen, pageBorderPaint)

        // 選択表示
        drawSelection(canvas)
        // ノード表示
        drawNodes(canvas)
        // ドラフト
        drawDrafts(canvas, am)
        // マーキー
        drawMarquee(canvas)
        // hover
        hoverPos?.let { drawHoverCursor(canvas, it) }
    }

    private fun drawGrid(canvas: Canvas, pageScreen: RectF) {
        val gridSize = editor.gridSize
        val screenStep = gridSize * editor.zoom
        if (screenStep < 6) return // 密すぎ
        val path = Path()
        val vb = editor.doc.viewBox
        var x = vb.minX
        while (x <= vb.minX + vb.width + 0.001) {
            val sx = editor.userToScreen(Vec(x, vb.minY)).x
            path.moveTo(sx.toFloat(), pageScreen.top)
            path.lineTo(sx.toFloat(), pageScreen.bottom)
            x += gridSize
        }
        var y = vb.minY
        while (y <= vb.minY + vb.height + 0.001) {
            val sy = editor.userToScreen(Vec(vb.minX, y)).y
            path.moveTo(pageScreen.left, sy.toFloat())
            path.lineTo(pageScreen.right, sy.toFloat())
            y += gridSize
        }
        canvas.drawPath(path, gridPaint)
    }

    private fun drawSelection(canvas: Canvas) {
        val sel = editor.selection
        if (sel.isEmpty()) return
        val b = editor.trace.boundsOf(sel) ?: return
        val ul = editor.userToScreen(Vec(b.x, b.y))
        val ur = editor.userToScreen(Vec(b.right, b.y))
        val ll = editor.userToScreen(Vec(b.x, b.bottom))
        val lr = editor.userToScreen(Vec(b.right, b.bottom))
        val path = Path()
        path.moveTo(ul.x.toFloat(), ul.y.toFloat())
        path.lineTo(ur.x.toFloat(), ur.y.toFloat())
        path.lineTo(lr.x.toFloat(), lr.y.toFloat())
        path.lineTo(ll.x.toFloat(), ll.y.toFloat())
        path.close()
        canvas.drawPath(path, selectionFillPaint)
        canvas.drawPath(path, selectionBoxPaint)

        if (editor.tool == ToolType.SELECT) {
            // ハンドル
            val positions = tools.selectionHandlePositions() ?: return
            val r = 6f
            for ((_, p) in positions) {
                canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, handlePaint)
                canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, handleStrokePaint)
            }
        }
    }

    private fun drawNodes(canvas: Canvas) {
        val session = editor.nodeSession ?: return
        // セグメントライン
        val lines = session.segmentPolylines(0.2 / editor.zoom.coerceAtLeast(0.01))
        val path = Path()
        for (poly in lines) {
            for ((i, p) in poly.withIndex()) {
                val s = editor.userToScreen(p)
                if (i == 0) path.moveTo(s.x.toFloat(), s.y.toFloat()) else path.lineTo(s.x.toFloat(), s.y.toFloat())
            }
        }
        canvas.drawPath(path, draftPaint.apply { color = 0xFF42A5F5.toInt() })

        // ハンドル
        for ((ref, isIn, p) in session.allHandles()) {
            val sp = editor.userToScreen(p)
            val nodeP = session.nodePos(ref)?.let { editor.userToScreen(it) } ?: continue
            canvas.drawLine(nodeP.x.toFloat(), nodeP.y.toFloat(), sp.x.toFloat(), sp.y.toFloat(), handleLinePaint)
            canvas.drawCircle(sp.x.toFloat(), sp.y.toFloat(), 4.5f, if (isIn) nodeSmoothPaint else handlePaint)
            canvas.drawCircle(sp.x.toFloat(), sp.y.toFloat(), 4.5f, handleStrokePaint)
        }
        // ノード
        val r = 5f
        for ((ref, p) in session.allNodes()) {
            val sp = editor.userToScreen(p)
            val kind = session.nodeKind(ref)
            val paint = when {
                session.selected.contains(ref) -> nodeSelectedPaint
                kind == dev.svgpro.core.path.NodeKind.SMOOTH || kind == dev.svgpro.core.path.NodeKind.SYMMETRIC -> nodeSmoothPaint
                else -> nodePaint
            }
            canvas.drawCircle(sp.x.toFloat(), sp.y.toFloat(), r, paint)
            canvas.drawCircle(sp.x.toFloat(), sp.y.toFloat(), r, handleStrokePaint)
        }
    }

    private fun drawDrafts(canvas: Canvas, am: android.graphics.Matrix) {
        // ペン
        editor.penDraft?.let { pd ->
            canvas.save()
            canvas.concat(am)
            val path = SvgRenderer.toAndroidPath(pd)
            canvas.drawPath(path, draftPaint)
            canvas.restore()
            // 始点マーカー
            val first = pd.segs.firstOrNull()
            if (first is dev.svgpro.core.path.Seg.Move) {
                val s = editor.userToScreen(Vec(first.x, first.y))
                canvas.drawCircle(s.x.toFloat(), s.y.toFloat(), 9f, handleStrokePaint)
            }
        }
        // フリーハンド
        editor.freehandDraft?.let { pts ->
            if (pts.size >= 2) {
                val path = Path()
                val first = editor.userToScreen(pts[0])
                path.moveTo(first.x.toFloat(), first.y.toFloat())
                for (i in 1 until pts.size) {
                    val s = editor.userToScreen(pts[i])
                    path.lineTo(s.x.toFloat(), s.y.toFloat())
                }
                canvas.drawPath(path, draftPaint)
            }
        }
        // 図形
        editor.shapeDraft?.let { (s, e, kind) ->
            val el = editor.buildShapeElement(kind, s, e)
            if (el != null) {
                val geo = dev.svgpro.core.model.Shapes.geometryOf(el)
                if (geo != null) {
                    canvas.save()
                    canvas.concat(am)
                    canvas.drawPath(SvgRenderer.toAndroidPath(geo), draftPaint)
                    canvas.restore()
                }
            }
        }
    }

    private fun drawMarquee(canvas: Canvas) {
        val r = tools.marqueeRect() ?: return
        val a = editor.userToScreen(Vec(r.x, r.y))
        val b = editor.userToScreen(Vec(r.right, r.bottom))
        canvas.drawRect(
            RectF(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat()),
            marqueePaint,
        )
    }

    private fun drawHoverCursor(canvas: Canvas, screen: Vec) {
        val x = screen.x.toFloat()
        val y = screen.y.toFloat()
        canvas.drawLine(x - 14, y, x - 4, y, hoverPaint)
        canvas.drawLine(x + 4, y, x + 14, y, hoverPaint)
        canvas.drawLine(x, y - 14, x, y - 4, hoverPaint)
        canvas.drawLine(x, y + 4, x, y + 14, hoverPaint)
        // ノードツール時: 近傍ノードハイライト
        if (editor.tool == ToolType.NODE) {
            val session = editor.nodeSession
            if (session != null) {
                val user = editor.screenToUser(screen)
                var best: Vec? = null
                var bestD = 22.0
                for ((_, p) in session.allNodes()) {
                    val s = editor.userToScreen(p)
                    val d = s.distanceTo(screen)
                    if (d < bestD) {
                        bestD = d
                        best = s
                    }
                }
                best?.let {
                    canvas.drawCircle(it.x.toFloat(), it.y.toFloat(), 8f, handleStrokePaint)
                }
            }
        }
    }
}
