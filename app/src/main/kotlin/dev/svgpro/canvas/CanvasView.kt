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
import dev.svgpro.editor.gradientHandlePoints
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
    private var stylusHoverSeen = false // ペンが検知範囲にいる間 true（パームリジェクション用）
    private var hoverPos: Vec? = null

    // ペン側ボタンでのパン（Illustratorのスペースドラッグ相当）
    private var penButtonPan = false
    private var panLast = Vec.ZERO

    // ダブルタップ検出
    private var lastTapTime = 0L
    private var lastTapPos = Vec.ZERO

    // ペン用の状態追跡
    private var penPointerId = -1

    // 長押しコンテキストメニュー
    private var longPressStartTime = 0L
    private var longPressStartScreen = Vec.ZERO
    private var longPressCancelled = false
    private val LONG_PRESS_TIMEOUT = 500L // ms
    private val LONG_PRESS_MOVE_THRESHOLD = 10f // px

    /** 長押し時にコンテキストメニューを表示するコールバック（screenX, screenY） */
    var onLongPress: ((Float, Float) -> Unit)? = null

    // ―――― 描画ペイント（テーマ連動: onDraw時に CanvasTheme から更新） ――――
    private val checkerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val pixelGridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 2f), 0f)
    }
    private val selectionBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val selectionFillPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodeSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodeSmoothPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val handleLinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val draftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = CornerPathEffect(2f)
    }
    private val marqueePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val whitePaint = Paint().apply { color = Color.WHITE }

    /** 適用済みテーマ（変化時のみペイントを更新） */
    private var appliedDark: Boolean? = null
    private var checkerTintAlpha = 60

    private fun applyTheme(dark: Boolean) {
        val c = if (dark) CanvasTheme.Dark else CanvasTheme.Light
        pageBorderPaint.color = c.pageBorder
        gridPaint.color = c.grid
        pixelGridPaint.color = c.pixelGrid
        selectionBoxPaint.color = c.selection
        selectionFillPaint.color = c.selectionFill
        handlePaint.color = c.handle
        handleStrokePaint.color = c.handleStroke
        nodePaint.color = c.node
        nodeSelectedPaint.color = c.nodeSelected
        nodeSmoothPaint.color = c.nodeSmooth
        handleLinePaint.color = c.handleLine
        draftPaint.color = c.draft
        marqueePaint.color = c.marquee
        hoverPaint.color = c.hover
        guidePaint.color = c.guide
        guideLockedPaint.color = c.guideLocked
        smartGuidePaint.color = c.smartGuide
        gradientLinePaint.color = c.selection
        rulerPaint.color = c.rulerBg
        rulerTickPaint.color = c.rulerTick
        rulerTextPaint.color = c.rulerText
        whitePaint.alpha = 60
        // チェッカータイルを作り直す
        val tile = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        canvas.drawColor(c.checkerA)
        val p = Paint().apply { color = c.checkerB }
        canvas.drawRect(0f, 0f, 8f, 8f, p)
        canvas.drawRect(8f, 8f, 16f, 16f, p)
        checkerPaint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        checkerTintAlpha = 60
        invalidate()
    }

    /** UIテーマから呼び出される（ダーク=true） */
    fun setDarkTheme(dark: Boolean) {
        if (appliedDark == dark) return
        appliedDark = dark
        applyTheme(dark)
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // テーマ反映はonDraw初回（appliedDark == null時）に行う
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

    /** ガイド操作状態（ルーラーからの作成・ドラッグ） */
    private var guideDrag = -1
    private var guideDragNew = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!this::editor.isInitialized) return true
        // ガイド/ルーラー操作（単一ポインタのみ）
        if (guideDrag >= 0) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount <= 1) {
                        val user = editor.screenToUser(Vec(ev.x.toDouble(), ev.y.toDouble()))
                        // 斜めガイドは角度を保ったまま点に追従、水平/垂直はposを更新
                        editor.moveGuideToPoint(guideDrag, user)
                        invalidate()
                        return true
                    }
                    guideDrag = -1
                    guideDragNew = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    editor.saveGuides()
                    guideDrag = -1
                    guideDragNew = false
                    invalidate()
                    return true
                }
            }
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // ルーラーゾーンからのガイド作成
                val rulerThickness = 22.0
                if (editor.showRulers && ev.y <= rulerThickness && ev.x > rulerThickness) {
                    val user = editor.screenToUser(Vec(ev.x.toDouble(), ev.y.toDouble()))
                    editor.addGuideSnapped(dev.svgpro.core.model.Guide(horizontal = true, pos = user.y))
                    guideDrag = editor.guides.size - 1
                    guideDragNew = true
                    invalidate()
                    return true
                }
                if (editor.showRulers && ev.x <= rulerThickness && ev.y > rulerThickness) {
                    val user = editor.screenToUser(Vec(ev.x.toDouble(), ev.y.toDouble()))
                    editor.addGuideSnapped(dev.svgpro.core.model.Guide(horizontal = false, pos = user.x))
                    guideDrag = editor.guides.size - 1
                    guideDragNew = true
                    invalidate()
                    return true
                }
                // 既存ガイドのドラッグ（ロック中は対象外）
                val gidx = editor.guideAtScreen(Vec(ev.x.toDouble(), ev.y.toDouble()))
                if (gidx != null) {
                    guideDrag = gidx
                    guideDragNew = false
                    invalidate()
                    return true
                }
                activePointers = 1
                val isStylus = ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                    ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                if (isStylus) {
                    stylusActive = true
                    stylusDrawing = true
                    penPointerId = ev.getPointerId(0)
                    // ペン側ボタン + ドラッグ = パン（ツール操作を優先しない）
                    if ((ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
                        penButtonPan = true
                        panLast = Vec(ev.x.toDouble(), ev.y.toDouble())
                        return true
                    }
                } else if (editor.penOnly && (stylusActive || stylusHoverSeen)) {
                    // ペン優先モード中、ペンが近くにある間は指を無視（パームリジェクション）
                    return true
                }

                // 長押しコンテキストメニューのトラッキング開始
                longPressStartTime = System.currentTimeMillis()
                longPressStartScreen = Vec(ev.x.toDouble(), ev.y.toDouble())
                longPressCancelled = false

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
                // ペンボタンによるパン
                if (penButtonPan && ev.pointerCount == 1) {
                    val p = Vec(ev.x.toDouble(), ev.y.toDouble())
                    editor.panBy(p.x - panLast.x, p.y - panLast.y)
                    panLast = p
                    return true
                }
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
                // 長押し中に閾値以上移動したらキャンセル
                if (!longPressCancelled) {
                    val dx = ev.getX(0) - longPressStartScreen.x
                    val dy = ev.getY(0) - longPressStartScreen.y
                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > LONG_PRESS_MOVE_THRESHOLD) {
                        longPressCancelled = true
                    }
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
                penButtonPan = false
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

                // 長押しコンテキストメニュー: キャンセルされておらず、時間閾値を超えた場合に表示
                if (!cancelled && !longPressCancelled) {
                    val elapsed = System.currentTimeMillis() - longPressStartTime
                    if (elapsed >= LONG_PRESS_TIMEOUT) {
                        onLongPress?.invoke(longPressStartScreen.x.toFloat(), longPressStartScreen.y.toFloat())
                        longPressCancelled = true // 二重発火防止
                    }
                }
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
                stylusHoverSeen = true
                val screen = Vec(event.x.toDouble(), event.y.toDouble())
                hoverPos = screen
                invalidate()
                true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                stylusHoverSeen = false
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
        if (appliedDark == null) setDarkTheme(true) // 初回はダーク既定で初期化
        val dark = appliedDark ?: true
        canvas.drawColor(if (dark) CanvasTheme.Dark.canvasBg else CanvasTheme.Light.canvasBg)

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

        // キャンバスビュー回転: コンテンツ全体を画面中心で回転させる。
        // ルーラー・ホバーカーソルは画面固定のため、回転ブロックの外側で描画する。
        canvas.save()
        val rot = editor.canvasRotation
        if (rot != 0.0) {
            val cx = width / 2.0f
            val cy = height / 2.0f
            canvas.rotate(rot.toFloat(), cx, cy)
        }

        // ページ背景（市松模様）
        canvas.drawRect(pageScreen, checkerPaint)
        canvas.drawRect(pageScreen, whitePaint)

        // ドキュメント描画。
        // drawItemが要素ごとに「ローカル→user(abs)→画面(viewMatrix)」の完全な行列を
        // 合成してconcatするため、ここでviewMatrixを前置きしてはならない。
        // 二重に適用するとズーム²スケールとなり、選択枠・ページ背景（viewMatrix1回分）と
        // 描画がズレる（書き出しは1回適用なので正確に見える、という症状の原因）。
        // キーフレームアニメーション適用（描画時のみ属性を一時上書き）
        // 再生中に加え、スクラブで時刻を指定した場合（t>0）もそのフレームを表示する
        //（以前は再生中のみで、停止中のスクラブ/キーフレームクリックがキャンバスに反映されなかった）
        if (editor.isKeyframePlaying || editor.keyframeTimeMs > 0L) {
            editor.applyAnimationAt(editor.keyframeTimeMs)
        }
        renderer.draw(canvas, editor)
        if (editor.isKeyframePlaying || editor.keyframeTimeMs > 0L) {
            editor.clearAnimationOverrides()
        }

        // グリッド
        if (editor.showGrid) {
            drawGrid(canvas, pageScreen)
        }
        // ピクセルグリッド（1 user unit 間隔。十分拡大時のみ表示）
        if (editor.showPixelGrid) {
            drawPixelGrid(canvas)
        }

        // ページ枠
        canvas.drawRect(pageScreen, pageBorderPaint)

        // ガイド線
        drawGuides(canvas)

        // 選択表示
        drawSelection(canvas)
        // ノード表示
        drawNodes(canvas)
        // ドラフト
        drawDrafts(canvas, am)
        // マーキー
        drawMarquee(canvas)
        // 投げ縄選択（Shift+ドラッグ）
        drawLasso(canvas)
        // スマートガイド（移動中の整列補助線）
        drawSmartGuides(canvas)
        // グラデーションハンドル
        if (editor.tool == ToolType.GRADIENT) {
            drawGradientHandles(canvas)
        }
        // 計測線（メジャーツール）
        drawMeasureLine(canvas, dark)
        canvas.restore()

        // hover（画面固定・回転に影響されない）
        hoverPos?.let { drawHoverCursor(canvas, it) }
        // 計測ラベル（画面固定・回転に影響されない）
        drawMeasureLabel(canvas, dark)
        // コメントマーカー（画面固定・回転に影響されない）
        drawCommentMarkers(canvas, dark)
        // ルーラー（画面固定・回転に影響されない）
        if (editor.showRulers) {
            drawRulers(canvas)
        }
    }

    private val commentBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8A33D.toInt() }
    private val commentBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xFF1A1A1A.toInt()
    }
    private val commentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }

    /** コメント/注釈のマーカー（以前はキャンバスに描画されず、ダイアログ一覧のみだった） */
    private fun drawCommentMarkers(canvas: Canvas, dark: Boolean) {
        val comments = editor.comments
        if (comments.isEmpty()) return
        commentBorderPaint.color = if (dark) 0xFF1A1A1A.toInt() else 0xFFE8E8E8.toInt()
        commentTextPaint.color = if (dark) 0xFF1A1A1A.toInt() else 0xFF3A2A10.toInt()
        for ((i, c) in comments.withIndex()) {
            val p = editor.userToScreen(Vec(c.x, c.y))
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f, commentBgPaint)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f, commentBorderPaint)
            canvas.drawText((i + 1).toString(), p.x.toFloat(), p.y.toFloat() + 7f, commentTextPaint)
        }
    }

    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
        color = 0xFF42A5F5.toInt()
    }

    /** メジャーツールの計測線（user空間→回転ブロック内で描画） */
    private fun drawMeasureLine(canvas: Canvas, dark: Boolean) {
        val s = editor.measureStart ?: return
        val e = editor.measureEnd ?: return
        val a = editor.userToScreen(s)
        val b = editor.userToScreen(e)
        measurePaint.color = if (dark) CanvasTheme.Dark.selection else CanvasTheme.Light.selection
        canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), measurePaint)
        for (p in listOf(a, b)) {
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 4f, handlePaint)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 4f, handleStrokePaint)
        }
    }

    /** 計測値ラベル（長さ+角度。画面固定で回転に影響されない） */
    private fun drawMeasureLabel(canvas: Canvas, dark: Boolean) {
        val s = editor.measureStart ?: return
        val e = editor.measureEnd ?: return
        if (s.distanceTo(e) < 1e-6) return
        val len = editor.rulerLength(s.distanceTo(e))
        var ang = -Math.toDegrees(kotlin.math.atan2(e.y - s.y, e.x - s.x))
        ang = (ang % 360.0 + 360.0) % 360.0
        if (ang > 180.0) ang -= 360.0
        val text = dev.svgpro.core.util.Numbers.format(len, 2) + editor.rulerUnitLabel() +
            "  ∠" + dev.svgpro.core.util.Numbers.format(ang, 1) + "°"
        val mid = editor.userToScreen(Vec((s.x + e.x) / 2.0, (s.y + e.y) / 2.0))
        val x = mid.x.toFloat() + 12f
        val y = mid.y.toFloat() - 12f
        rulerTextPaint.color = if (dark) CanvasTheme.Dark.rulerText else CanvasTheme.Light.rulerText
        val tw = rulerTextPaint.measureText(text)
        val bg = android.graphics.RectF(x - 6f, y - rulerTextPaint.textSize - 6f, x + tw + 8f, y + 6f)
        canvas.drawRoundRect(bg, 4f, 4f, rulerPaint)
        canvas.drawText(text, x, y, rulerTextPaint)
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFF00E5FF.toInt()
    }
    private val guideLockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x6600E5FF.toInt()
    }
    private val smartGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFFFF4D6D.toInt()
    }
    private val gradientLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xFF42A5F5.toInt()
    }
    private val rulerPaint = Paint().apply { color = 0xEE232A30.toInt() }
    private val rulerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFF8A939B.toInt()
    }
    private val rulerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB8C0C8.toInt()
        textSize = 16f
    }

    private fun drawGuides(canvas: Canvas) {
        for (g in editor.guides) {
            if (!g.visible) continue
            val paint = if (g.locked) guideLockedPaint else guidePaint
            if (g.isAngled) {
                // 任意角度のガイド: 線を画面座標へ変換し、画面全体を横切る長さで描く
                val a = editor.userToScreen(g.anchor())
                val b = editor.userToScreen(g.anchor() + g.direction() * 100.0)
                var dx = b.x - a.x
                var dy = b.y - a.y
                val len = kotlin.math.sqrt(dx * dx + dy * dy)
                if (len < 1e-6) continue
                dx /= len
                dy /= len
                val ext = (width + height).toFloat()
                canvas.drawLine(
                    (a.x - dx * ext).toFloat(), (a.y - dy * ext).toFloat(),
                    (a.x + dx * ext).toFloat(), (a.y + dy * ext).toFloat(), paint,
                )
            } else if (g.horizontal) {
                val sy = editor.userToScreen(Vec(0.0, g.pos)).y
                canvas.drawLine(0f, sy.toFloat(), width.toFloat(), sy.toFloat(), paint)
            } else {
                val sx = editor.userToScreen(Vec(g.pos, 0.0)).x
                canvas.drawLine(sx.toFloat(), 0f, sx.toFloat(), height.toFloat(), paint)
            }
        }
    }

    /** スマートガイド（移動中の整列補助線） */
    private fun drawSmartGuides(canvas: Canvas) {
        for (x in editor.smartGuideV) {
            val sx = editor.userToScreen(Vec(x, 0.0)).x
            canvas.drawLine(sx.toFloat(), 0f, sx.toFloat(), height.toFloat(), smartGuidePaint)
        }
        for (y in editor.smartGuideH) {
            val sy = editor.userToScreen(Vec(0.0, y)).y
            canvas.drawLine(0f, sy.toFloat(), width.toFloat(), sy.toFloat(), smartGuidePaint)
        }
    }

    private fun drawGradientHandles(canvas: Canvas) {
        val handles = editor.gradientHandlePoints() ?: return
        val s = editor.userToScreen(handles.second)
        val e = editor.userToScreen(handles.third)
        canvas.drawLine(s.x.toFloat(), s.y.toFloat(), e.x.toFloat(), e.y.toFloat(), gradientLinePaint)
        for (p in listOf(s, e)) {
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 6.5f, handlePaint)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 6.5f, handleStrokePaint)
        }
    }

    /** ルーラー（上・左）。user座標の目盛りを描く。グリッド間隔と連動する */
    private fun drawRulers(canvas: Canvas) {
        val thickness = 22f
        canvas.drawRect(0f, 0f, width.toFloat(), thickness, rulerPaint)
        canvas.drawRect(0f, 0f, thickness, height.toFloat(), rulerPaint)
        // グリッド間隔を基準に、画面上で 70〜140px 前後になる目盛り間隔を決定（格子に合わせやすく）
        var step = editor.gridSize.coerceAtLeast(1e-6)
        while (step * editor.zoom < 70.0) step *= 2.0
        while (step * editor.zoom > 140.0) step /= 2.0
        val vb = editor.doc.viewBox
        val startX = kotlin.math.floor((editor.screenToUser(Vec(0.0, 0.0)).x) / step) * step
        var x = startX
        while (x < editor.screenToUser(Vec(width.toDouble(), 0.0)).x + step) {
            val sx = editor.userToScreen(Vec(x, 0.0)).x
            if (sx >= thickness) {
                canvas.drawLine(sx.toFloat(), thickness - 6f, sx.toFloat(), thickness.toFloat(), rulerTickPaint)
                if (step * editor.zoom > 55) {
                    val v = editor.rulerLength(x)
                    canvas.drawText(
                        dev.svgpro.core.util.Numbers.format(v, 1) + editor.rulerUnitLabel(),
                        sx.toFloat() + 3f, thickness - 7f, rulerTextPaint,
                    )
                }
            }
            x += step
        }
        val startY = kotlin.math.floor((editor.screenToUser(Vec(0.0, 0.0)).y) / step) * step
        var y = startY
        while (y < editor.screenToUser(Vec(0.0, height.toDouble())).y + step) {
            val sy = editor.userToScreen(Vec(0.0, y)).y
            if (sy >= thickness) {
                canvas.drawLine(thickness - 6f, sy.toFloat(), thickness.toFloat(), sy.toFloat(), rulerTickPaint)
                if (step * editor.zoom > 55) {
                    val v = editor.rulerLength(y)
                    val text = dev.svgpro.core.util.Numbers.format(v, 1) + editor.rulerUnitLabel()
                    canvas.save()
                    canvas.rotate(-90f, thickness - 7f, sy.toFloat() - 3f)
                    canvas.drawText(text, thickness - 7f, sy.toFloat() - 3f, rulerTextPaint)
                    canvas.restore()
                }
            }
            y += step
        }
        // 角
        canvas.drawRect(0f, 0f, thickness, thickness, rulerPaint)
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

    /** ピクセルグリッド（1 user unit 間隔）。十分拡大時のみ描画する。 */
    private fun drawPixelGrid(canvas: Canvas) {
        val zoom = editor.zoom
        if (zoom < 1.0) return
        val vb = editor.doc.viewBox
        val path = Path()
        var x = kotlin.math.ceil(vb.minX)
        val maxX = vb.minX + vb.width
        while (x <= maxX) {
            val sx = editor.userToScreen(Vec(x, vb.minY)).x
            path.moveTo(sx.toFloat(), 0f)
            path.lineTo(sx.toFloat(), height.toFloat())
            x += 1.0
        }
        var y = kotlin.math.ceil(vb.minY)
        val maxY = vb.minY + vb.height
        while (y <= maxY) {
            val sy = editor.userToScreen(Vec(vb.minX, y)).y
            path.moveTo(0f, sy.toFloat())
            path.lineTo(width.toFloat(), sy.toFloat())
            y += 1.0
        }
        canvas.drawPath(path, pixelGridPaint)
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
        // セグメントライン（draftPaintの色は一時的にアクセントへ変更し、後で戻す）
        val draftColor = draftPaint.color
        draftPaint.color = if (appliedDark == false) CanvasTheme.Light.selection else CanvasTheme.Dark.selection
        val lines = session.segmentPolylines(0.2 / editor.zoom.coerceAtLeast(0.01))
        val path = Path()
        for (poly in lines) {
            for ((i, p) in poly.withIndex()) {
                val s = editor.userToScreen(p)
                if (i == 0) path.moveTo(s.x.toFloat(), s.y.toFloat()) else path.lineTo(s.x.toFloat(), s.y.toFloat())
            }
        }
        canvas.drawPath(path, draftPaint)
        draftPaint.color = draftColor

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

    /** 投げ縄選択パス（Shift+ドラッグで記録） */
    private fun drawLasso(canvas: Canvas) {
        val pts = tools.lassoPath()
        if (pts.size < 2) return
        val path = Path()
        val first = editor.userToScreen(pts[0]) ?: return
        path.moveTo(first.x.toFloat(), first.y.toFloat())
        for (i in 1 until pts.size) {
            val p = editor.userToScreen(pts[i]) ?: continue
            path.lineTo(p.x.toFloat(), p.y.toFloat())
        }
        canvas.drawPath(path, marqueePaint)
    }

    private fun drawHoverCursor(canvas: Canvas, screen: Vec) {
        val x = screen.x.toFloat()
        val y = screen.y.toFloat()
        // 十字カーソル（Illustrator風）
        canvas.drawLine(x - 13, y, x - 4, y, hoverPaint)
        canvas.drawLine(x + 4, y, x + 13, y, hoverPaint)
        canvas.drawLine(x, y - 13, x, y - 4, hoverPaint)
        canvas.drawLine(x, y + 4, x, y + 13, hoverPaint)
        canvas.drawCircle(x, y, 1.5f, hoverPaint)
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
