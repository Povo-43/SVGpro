package dev.svgpro.canvas

import android.view.MotionEvent
import dev.svgpro.core.geom.Bounds
import dev.svgpro.core.geom.Vec
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import dev.svgpro.core.model.StyleUtil
import dev.svgpro.editor.Editor
import dev.svgpro.editor.NodeRef
import dev.svgpro.editor.ToolType
import dev.svgpro.editor.computeSmartGuides
import dev.svgpro.editor.selectParent
import dev.svgpro.editor.gradientHandlePoints
import dev.svgpro.editor.setupGradientEdit
import dev.svgpro.editor.computeSmartGuides
import dev.svgpro.editor.selectParent
import dev.svgpro.editor.gradientHandlePoints
import dev.svgpro.editor.setupGradientEdit
import dev.svgpro.editor.updateGradientHandles
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round

/**
 * ツールごとの入力処理（状態機械）。
 * CanvasViewからユーザー座標へ変換済みのイベントが渡される。
 */
class ToolController(val editor: Editor) {

    /** パン処理（CanvasViewから設定: 画面deltaでeditor.panByを呼ぶ） */
    var panBy: ((Double, Double) -> Unit)? = null

    // ドラッグ状態
    private var mode: Mode = Mode.NONE
    private var lastUser = Vec.ZERO
    private var lastScreen = Vec.ZERO
    private var downUser = Vec.ZERO

    // select用
    private var dragSession: Editor.DragSession? = null
    private var preTransforms: List<Pair<dev.svgpro.core.svg.Element, String?>>? = null
    private var scaleAnchor = Vec.ZERO
    private var scaleStart = Vec.ZERO
    private var rotateStart = Vec.ZERO
    private var rotateCenter = Vec.ZERO
    private var marqueeStart = Vec.ZERO
    private var handleKind: SelectionHandle? = null

    // node用
    private var nodeDragStart = Vec.ZERO
    private var nodeMirror = true

    // gradient用
    private var gradientDragEnd = true
    private var gradientBatch: dev.svgpro.core.edit.EditBatch? = null

    // pen用
    private var penDownPos = Vec.ZERO
    private var penDragging = false
    private var penLastOut: Vec? = null

    // freehand
    private var freehandActive = false
    /** フリーハンド中の筆圧サンプル（pointsと並行。筆圧非対応端末は0.5固定） */
    private var freehandPressures = ArrayList<Float>()

    // タップ検出
    private var downTime = 0L
    private var moved = false

    enum class Mode { NONE, MOVE, SCALE, ROTATE, MARQUEE, NODE_DRAG, HANDLE_DRAG, SHAPE, FREEHAND, PAN, GRADIENT_DRAG }

    enum class SelectionHandle { TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, ROTATE }

    /** 選択bboxのハンドル画面位置を計算（8ハンドル+回転） */
    fun selectionHandlePositions(): Map<SelectionHandle, Vec>? {
        if (editor.selection.isEmpty()) return null
        val b = editor.trace.boundsOf(editor.selection) ?: return null
        val ul = editor.userToScreen(Vec(b.x, b.y))
        val ur = editor.userToScreen(Vec(b.right, b.y))
        val ll = editor.userToScreen(Vec(b.x, b.bottom))
        val lr = editor.userToScreen(Vec(b.right, b.bottom))
        fun mid(a: Vec, c: Vec) = Vec((a.x + c.x) / 2, (a.y + c.y) / 2)
        val top = mid(ul, ur)
        val right = mid(ur, lr)
        val bottom = mid(lr, ll)
        val left = mid(ll, ul)
        val rotate = Vec(top.x, top.y - 34.0)
        return mapOf(
            SelectionHandle.TOP_LEFT to ul,
            SelectionHandle.TOP to top,
            SelectionHandle.TOP_RIGHT to ur,
            SelectionHandle.RIGHT to right,
            SelectionHandle.BOTTOM_RIGHT to lr,
            SelectionHandle.BOTTOM to bottom,
            SelectionHandle.BOTTOM_LEFT to ll,
            SelectionHandle.LEFT to left,
            SelectionHandle.ROTATE to rotate,
        )
    }

    /** ハンドルヒット（画面座標系、許容半径） */
    fun hitHandle(screen: Vec, radius: Double): SelectionHandle? {
        val positions = selectionHandlePositions() ?: return null
        var best: SelectionHandle? = null
        var bestD = radius
        for ((kind, p) in positions) {
            val d = p.distanceTo(screen)
            if (d < bestD) {
                bestD = d
                best = kind
            }
        }
        return best
    }

    fun onDown(user: Vec, screen: Vec, ev: MotionEvent, isStylus: Boolean, doubleTap: Boolean) {
        lastUser = user
        lastScreen = screen
        downUser = user
        downTime = System.currentTimeMillis()
        moved = false
        nodeMirror = !isStylus || (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) == 0

        when (editor.tool) {
            ToolType.HAND -> {
                mode = Mode.PAN
            }
            ToolType.SELECT -> onSelectDown(user, screen, isStylus, doubleTap)
            ToolType.NODE -> onNodeDown(user, screen, doubleTap, isStylus)
            ToolType.PEN -> onPenDown(user, isStylus)
            ToolType.GRADIENT -> onGradientDown(user, screen)
            ToolType.FREEHAND -> {
                mode = Mode.FREEHAND
                freehandActive = true
                freehandPressures = ArrayList()
                freehandPressures.add(ev.pressure.coerceIn(0.05f, 1.0f))
                editor.freehandDraft = mutableListOf(user)
            }
            ToolType.RECT, ToolType.RRECT, ToolType.ELLIPSE, ToolType.LINE, ToolType.POLYGON, ToolType.STAR, ToolType.POLYLINE -> {
                mode = Mode.SHAPE
                editor.shapeDraft = Triple(user, user, editor.tool)
            }
            ToolType.TEXT -> {
                editor.requestTextInput?.invoke(user)
            }
            ToolType.EYEDROPPER -> {
                pickColor(user)
            }
            ToolType.GRADIENT -> {
                onGradientDown(user, screen)
            }
        }
        editor.notifyChanged()
    }

    /** グラデーションツール: ハンドル（始点/終点）をつかんでドラッグ */
    private fun onGradientDown(user: Vec, screen: Vec) {
        editor.setupGradientEdit()
        val handles = editor.gradientHandlePoints() ?: return
        val s = handles.second
        val e = handles.third
        val ss = editor.userToScreen(s)
        val es = editor.userToScreen(e)
        if (ss.distanceTo(screen) > 28 && es.distanceTo(screen) > 28) return // ハンドル外
        gradientDragEnd = es.distanceTo(screen) <= ss.distanceTo(screen)
        val defId = editor.gradientEdit?.defId ?: return
        val def = editor.doc.root.findById(defId) ?: return
        val batch = dev.svgpro.core.edit.EditBatch()
        batch.touch(def)
        gradientBatch = batch
        mode = Mode.GRADIENT_DRAG
    }

    fun onMove(user: Vec, screen: Vec, ev: MotionEvent) {
        val delta = user - lastUser
        if (hypot(user.x - downUser.x, user.y - downUser.y) * editor.zoom > 4) moved = true

        when (mode) {
            Mode.PAN -> panBy?.invoke(screen.x - lastScreen.x, screen.y - lastScreen.y)
            Mode.GRADIENT_DRAG -> {
                val handles = editor.gradientHandlePoints() ?: return
                val s = handles.second
                val e = handles.third
                if (gradientDragEnd) editor.updateGradientHandles(s, user)
                else editor.updateGradientHandles(user, e)
            }
            Mode.MOVE -> {
                var d = delta
                // スナップ（選択bbox基準）
                d = applySnapForMove(delta)
                // スマートガイド（他オブジェクトとの整列補助）
                editor.smartGuideV.clear()
                editor.smartGuideH.clear()
                if (editor.smartGuides) {
                    val b = editor.trace.boundsOf(editor.selection)
                    if (b != null) {
                        val tol = editor.snapTolerancePx / editor.zoom.coerceAtLeast(1e-6)
                        val (adj, vl, hl) = editor.computeSmartGuides(b, d, tol)
                        d = adj
                        editor.smartGuideV.addAll(vl)
                        editor.smartGuideH.addAll(hl)
                    }
                }
                editor.moveSelection(d)
            }
            Mode.SCALE -> {
                restorePreTransforms()
                val sx = if (abs(scaleStart.x - scaleAnchor.x) < 1e-9) 1.0 else (user.x - scaleAnchor.x) / (scaleStart.x - scaleAnchor.x)
                val sy = if (abs(scaleStart.y - scaleAnchor.y) < 1e-9) 1.0 else (user.y - scaleAnchor.y) / (scaleStart.y - scaleAnchor.y)
                val uniform = (ev.pointerCount > 1) || isShift(ev)
                val fx = if (uniform) (sx + sy) / 2 else sx
                val fy = if (uniform) (sx + sy) / 2 else sy
                editor.scaleSelection(
                    if (abs(fx) < 0.01) 0.01 else fx,
                    if (abs(fy) < 0.01) 0.01 else fy,
                    scaleAnchor,
                )
            }
            Mode.ROTATE -> {
                restorePreTransforms()
                val a1 = atan2(scaleStart.y - rotateCenter.y, scaleStart.x - rotateCenter.x)
                val a2 = atan2(user.y - rotateCenter.y, user.x - rotateCenter.x)
                var deg = Math.toDegrees(a2 - a1)
                if (isShift(ev)) {
                    deg = (deg / 15.0).let { kotlin.math.round(it) * 15.0 }
                }
                editor.rotateSelection(deg, rotateCenter)
            }
            Mode.MARQUEE -> {
                editor.notifyChanged()
            }
            Mode.NODE_DRAG -> {
                val session = editor.nodeSession ?: return
                var d = user - nodeDragStart
                d = snapNodeDelta(d)
                for (ref in session.selected) {
                    val m = session.models.getOrNull(ref.sub) ?: continue
                    val start = nodeDragStartPositions[ref] ?: continue
                    var np = start + d
                    // グリッド → ガイドの順でスナップ（ドラッグ中のノード自身）
                    if (editor.snapToGrid) {
                        np = Vec(round(np.x / editor.gridSize) * editor.gridSize, round(np.y / editor.gridSize) * editor.gridSize)
                    }
                    np = snapToGuideLines(np)
                    m.setNodePosition(ref.node, np)
                }
                session.flush()
                editor.onChanged()
            }
            Mode.HANDLE_DRAG -> {
                val session = editor.nodeSession ?: return
                val (ref, isIn) = session.activeHandle ?: return
                val m = session.models.getOrNull(ref.sub) ?: return
                var p = user
                if (editor.snapToGrid) {
                    p = Vec(round(p.x / editor.gridSize) * editor.gridSize, round(p.y / editor.gridSize) * editor.gridSize)
                }
                val mirror = when {
                    isShift(ev) -> dev.svgpro.core.path.HandleMirror.NONE
                    nodeMirror -> dev.svgpro.core.path.HandleMirror.SYMMETRIC
                    else -> dev.svgpro.core.path.HandleMirror.SMOOTH
                }
                m.setHandle(ref.node, isIn, p, mirror)
                session.flush()
                editor.onChanged()
            }
            Mode.SHAPE -> {
                editor.shapeDraft = Triple(downUser, user, editor.tool)
                editor.notifyChanged()
            }
            Mode.FREEHAND -> {
                val draft = editor.freehandDraft ?: return
                val lastPoint = draft.lastOrNull() ?: user
                if (lastPoint.distanceTo(user) * editor.zoom > 2.0) {
                    draft.add(user)
                    freehandPressures.add(if (editor.usePressure) ev.pressure.coerceIn(0.05f, 1.0f) else 0.5f)
                    editor.notifyChanged()
                }
            }
            else -> {}
        }
        lastUser = user
        lastScreen = screen
    }

    fun onUp(user: Vec, screen: Vec, ev: MotionEvent, cancelled: Boolean) {
        val tap = !moved && (System.currentTimeMillis() - downTime) < 600
        when (mode) {
            Mode.MOVE, Mode.SCALE, Mode.ROTATE -> {
                if (cancelled) {
                    restorePreTransforms()
                }
                // 履歴へ
                val cs = dragSession?.batch?.endAndCommit(labelFor(mode))
                if (cs != null) editor.undoStack.push(cs)
                dragSession = null
                preTransforms = null
                editor.smartGuideV.clear()
                editor.smartGuideH.clear()
                editor.setLastMoveDelta(Vec.ZERO)
                editor.notifyChanged()
            }
            Mode.MARQUEE -> {
                val b = rectFrom(downUser, user)
                if (b.width > 1 || b.height > 1) {
                    editor.selectAllInBox(b, additive = isShift(ev) || isCtrl(ev))
                } else if (tap) {
                    // 空タップ: 選択解除
                    val hit = editor.trace.hitTest(user, 8.0 / editor.zoom)
                    editor.select(hit, additive = isShift(ev))
                }
                editor.notifyChanged()
            }
            Mode.NODE_DRAG, Mode.HANDLE_DRAG -> {
                val session = editor.nodeSession
                if (session != null) {
                    val startD = session.dragStartD
                    val el = session.el
                    if (startD != null && startD != el.attr("d")) {
                        editor.undoStack.push(
                            dev.svgpro.core.edit.ChangeSet(
                                listOf(dev.svgpro.core.edit.AttrChange(el, "d", startD, el.attr("d"), "node edit")),
                                "node edit",
                            ),
                        )
                    }
                    session.dragStartD = null
                    session.activeHandle = null
                }
                editor.notifyChanged()
            }
            Mode.SHAPE -> {
                val draft = editor.shapeDraft
                editor.shapeDraft = null
                if (draft != null && !cancelled) {
                    val (s, e, kind) = draft
                    if (hypot(e.x - s.x, e.y - s.y) * editor.zoom > 4) {
                        val end = editor.snapPoint(e, ignore = emptyList())
                        editor.createShape(kind, s, end)
                    }
                }
                editor.notifyChanged()
            }
            Mode.FREEHAND -> {
                val draft = editor.freehandDraft
                editor.freehandDraft = null
                freehandActive = false
                if (draft != null && draft.size >= 2 && !cancelled) {
                    val el = if (editor.usePressure && freehandPressures.size >= draft.size) {
                        buildFreehandWithPressure(draft, freehandPressures)
                    } else {
                        buildFreehand(draft)
                    }
                    if (el != null) editor.addElement(el, "freehand")
                }
                freehandPressures = ArrayList()
                editor.notifyChanged()
            }
            Mode.GRADIENT_DRAG -> {
                val batch = gradientBatch
                gradientBatch = null
                if (batch != null) {
                    val cs = batch.endAndCommit("gradient edit")
                    if (cs != null) editor.undoStack.push(cs)
                }
                editor.notifyChanged()
            }
            Mode.PAN -> {}
            else -> {}
        }
        mode = Mode.NONE
    }

    fun cancelActive() {
        if (mode == Mode.SHAPE) {
            editor.shapeDraft = null
        }
        if (mode == Mode.FREEHAND) {
            editor.freehandDraft = null
            freehandActive = false
        }
        mode = Mode.NONE
    }

    /** ペン: タップで直線/ドラッグでカーブ、始点タップで閉じる */
    private fun onPenDown(user: Vec, isStylus: Boolean) {
        penDownPos = user
        penDragging = false
        // 既存ドラフトの始点ヒット → 閉じる
        val draft = editor.penDraft
        if (draft != null) {
            val first = draft.segs.firstOrNull()
            if (first is Seg.Move) {
                val start = Vec(first.x, first.y)
                if (start.distanceTo(user) * editor.zoom < 18) {
                    penLastOut = null
                    editor.commitPenDraft(close = true)
                    return
                }
            }
        }
    }

    /** ペンはdown/upで判定（moveでハンドルドラッグ） */
    fun onPenMove(user: Vec) {
        if (editor.tool != ToolType.PEN) return
        if (penDownPos.distanceTo(user) * editor.zoom > 6) penDragging = true
        if (!penDragging) return
        // ドラッグ中: ハンドルを引き出す
        val draft = editor.penDraft ?: return
        val anchor = anchorOf(draft) ?: return
        val out = Vec(anchor.x + (user.x - anchor.x), anchor.y + (user.y - anchor.y))
        // 最終セグメントの第2制御点を設定
        when (val last = draft.segs.lastOrNull()) {
            is Seg.Cubic -> {
                last.c2x = user.x
                last.c2y = user.y
                // 対側のハンドル（前アンカーのout）は維持
            }
            is Seg.Line -> {
                // 直線をカーブへ昇格
                val idx = draft.segs.size - 1
                draft.segs[idx] = Seg.Cubic(anchor.x, anchor.y, user.x, user.y, last.x, last.y)
            }
            else -> {}
        }
        penLastOut = out
        editor.notifyChanged()
    }

    fun onPenUp(user: Vec, doubleTap: Boolean) {
        if (editor.tool != ToolType.PEN) return
        val startHit = penDownPos.distanceTo(user) * editor.zoom < 6
        if (!penDragging && startHit) {
            // タップ（始点ヒットはonPenDownで処理済み）
            return
        }
        val draft = editor.penDraft
        if (draft == null) {
            // 新規パス開始
            val p0 = editor.snapPoint(penDownPos, ignore = emptyList())
            val segs = ArrayList<Seg>()
            segs.add(Seg.Move(p0.x, p0.y))
            if (penDragging) {
                segs.add(Seg.Cubic(p0.x, p0.y, user.x, user.y, user.x, user.y))
            } else {
                val p1 = editor.snapPoint(user, ignore = emptyList())
                segs.add(Seg.Line(p1.x, p1.y))
            }
            editor.penDraft = PathData(segs)
            penLastOut = null
            editor.notifyChanged()
            return
        }
        // セグメント追加
        val anchor = anchorOf(draft) ?: return
        val p0 = Vec(anchor.x, anchor.y)
        // スムーズ継続: 前のoutハンドルを反映
        val prevOut = prevOutHandle(draft)
        if (penDragging) {
            val c1 = prevOut ?: p0
            draft.segs.add(Seg.Cubic(c1.x, c1.y, user.x, user.y, user.x, user.y))
        } else {
            val p1 = editor.snapPoint(user, ignore = emptyList())
            draft.segs.add(Seg.Line(p1.x, p1.y))
        }
        penLastOut = null
        editor.notifyChanged()
    }

    fun commitPen(close: Boolean) {
        penLastOut = null
        editor.commitPenDraft(close)
    }

    fun penDraftActive(): Boolean = editor.penDraft != null

    private fun anchorOf(pd: PathData): Vec? = when (val last = pd.segs.lastOrNull()) {
        is Seg.Move -> Vec(last.x, last.y)
        is Seg.Line -> Vec(last.x, last.y)
        is Seg.Cubic -> Vec(last.x, last.y)
        is Seg.Quad -> Vec(last.x, last.y)
        is Seg.Arc -> Vec(last.x, last.y)
        else -> null
    }

    private fun prevOutHandle(pd: PathData): Vec? = when (val last = pd.segs.lastOrNull()) {
        is Seg.Cubic -> Vec(last.c2x, last.c2y)
        else -> null
    }

    // ―――― SELECT tool ――――

    private fun onSelectDown(user: Vec, screen: Vec, isStylus: Boolean, doubleTap: Boolean) {
        // ハンドル判定
        val handle = hitHandle(screen, 22.0)
        if (handle != null && editor.selection.isNotEmpty()) {
            val b = editor.trace.boundsOf(editor.selection) ?: return
            preTransforms = editor.selection.map { it to it.attr("transform") }
            dragSession = editor.beginDrag("transform")
            handleKind = handle
            when (handle) {
                SelectionHandle.ROTATE -> {
                    mode = Mode.ROTATE
                    rotateCenter = Vec(b.centerX, b.centerY)
                    rotateStart = user
                }
                else -> {
                    mode = Mode.SCALE
                    scaleStart = user
                    scaleAnchor = oppositeAnchor(handle, b)
                }
            }
            return
        }
        // ダブルタップ: グループ選択（所属する最上位グループを選択）
        if (doubleTap) {
            val hitInner = editor.trace.hitTest(user, 10.0 / editor.zoom)
            if (hitInner != null) {
                val before = editor.selection.toList()
                editor.select(hitInner, additive = false)
                editor.selectParent()
                if (editor.selection.isEmpty()) {
                    editor.selection.addAll(before)
                }
                editor.notifyChanged()
                return
            }
        }
        // 要素ヒット
        val hit = editor.trace.hitTest(user, 10.0 / editor.zoom)
        if (hit != null) {
            if (!editor.isSelected(hit)) {
                editor.select(hit, additive = false)
            }
            preTransforms = editor.selection.map { it to it.attr("transform") }
            dragSession = editor.beginDrag("move")
            mode = Mode.MOVE
        } else {
            mode = Mode.MARQUEE
            marqueeStart = user
        }
    }

    private fun oppositeAnchor(h: SelectionHandle, b: Bounds): Vec = when (h) {
        SelectionHandle.TOP_LEFT -> Vec(b.right, b.bottom)
        SelectionHandle.TOP -> Vec(b.centerX, b.bottom)
        SelectionHandle.TOP_RIGHT -> Vec(b.x, b.bottom)
        SelectionHandle.RIGHT -> Vec(b.x, b.centerY)
        SelectionHandle.BOTTOM_RIGHT -> Vec(b.x, b.y)
        SelectionHandle.BOTTOM -> Vec(b.centerX, b.y)
        SelectionHandle.BOTTOM_LEFT -> Vec(b.right, b.y)
        SelectionHandle.LEFT -> Vec(b.right, b.centerY)
        SelectionHandle.ROTATE -> Vec(b.centerX, b.centerY)
    }

    private fun restorePreTransforms() {
        val pre = preTransforms ?: return
        for ((el, t) in pre) el.setAttr("transform", t)
    }

    private fun applySnapForMove(delta: Vec): Vec {
        if (!editor.snapToGrid && !editor.snapToObject) return delta
        // 現在の選択bboxをdeltaで動かした場合のスナップ
        val b = editor.trace.boundsOf(editor.selection) ?: return delta
        val candidate = Bounds(b.x + delta.x, b.y + delta.y, b.width, b.height)
        val snapped = editor.snapPoint(Vec(candidate.x, candidate.y), ignore = editor.selection)
        var d = Vec(snapped.x - candidate.x, snapped.y - candidate.y)
        // 回転などでbboxが変化する場合はそのまま
        return delta + d * 0.999
    }

    private fun labelFor(mode: Mode): String = when (mode) {
        Mode.MOVE -> "move"
        Mode.SCALE -> "scale"
        Mode.ROTATE -> "rotate"
        else -> "edit"
    }

    // ―――― NODE tool ――――

    private var nodeDragStartPositions: Map<NodeRef, Vec> = HashMap()

    private fun onNodeDown(user: Vec, screen: Vec, doubleTap: Boolean, isStylus: Boolean) {
        val session = editor.nodeSession
        if (session == null) {
            // path要素を選択する
            val hit = editor.trace.hitTest(user, 10.0 / editor.zoom)
            editor.select(hit)
            return
        }
        // ハンドル優先
        val handle = hitNodeHandle(session, screen, 20.0)
        if (handle != null) {
            session.dragStartD = session.el.attr("d")
            session.activeHandle = handle.first to handle.second
            mode = Mode.HANDLE_DRAG
            nodeDragStart = user
            return
        }
        // ノード
        val node = hitNode(session, screen, 22.0)
        if (node != null) {
            if (isShiftModifier()) {
                session.toggleSelect(node)
            } else if (!session.selected.contains(node)) {
                session.selectOnly(node)
            }
            session.dragStartD = session.el.attr("d")
            nodeDragStart = user
            nodeDragStartPositions = session.selected.associateWith { session.nodePos(it) ?: Vec.ZERO }
            mode = Mode.NODE_DRAG
            editor.notifyChanged()
            return
        }
        if (doubleTap) {
            // セグメントへのノード挿入
            session.insertNodeNear(user, 10.0 / editor.zoom)
            session.dragStartD = session.el.attr("d")
            editor.notifyChanged()
            return
        }
        // 範囲選択 or 要素再選択
        mode = Mode.MARQUEE
        marqueeStart = user
    }

    private fun hitNode(session: dev.svgpro.editor.NodeSession, screen: Vec, radius: Double): NodeRef? {
        var best: NodeRef? = null
        var bestD = radius
        for ((ref, p) in session.allNodes()) {
            val s = editor.userToScreen(p)
            val d = s.distanceTo(screen)
            if (d < bestD) {
                bestD = d
                best = ref
            }
        }
        return best
    }

    private fun hitNodeHandle(session: dev.svgpro.editor.NodeSession, screen: Vec, radius: Double): Pair<NodeRef, Boolean>? {
        var best: Pair<NodeRef, Boolean>? = null
        var bestD = radius
        for ((ref, isIn, p) in session.allHandles()) {
            val s = editor.userToScreen(p)
            val d = s.distanceTo(screen)
            if (d < bestD) {
                bestD = d
                best = ref to isIn
            }
        }
        return best
    }

    private fun snapNodeDelta(delta: Vec): Vec = delta

    /** 点をガイド線へスナップ（ガイド近傍の座標をガイド上へ寄せる） */
    private fun snapToGuideLines(p: Vec): Vec {
        if (!editor.snapToGuides || editor.guides.isEmpty()) return p
        val tol = editor.snapTolerancePx / editor.zoom.coerceAtLeast(1e-6)
        var x = p.x
        var y = p.y
        for (g in editor.guides) {
            if (!g.visible || g.locked) continue
            if (g.horizontal && abs(p.y - g.pos) < tol) y = g.pos
            else if (!g.horizontal && abs(p.x - g.pos) < tol) x = g.pos
        }
        return Vec(x, y)
    }

    private fun isShift(ev: MotionEvent): Boolean =
        (ev.metaState and android.view.KeyEvent.META_SHIFT_ON) != 0

    private fun isCtrl(ev: MotionEvent): Boolean =
        (ev.metaState and android.view.KeyEvent.META_CTRL_ON) != 0

    private fun isShiftModifier(): Boolean = false

    // ―――― freehand ――――

    private fun buildFreehand(points: List<Vec>): dev.svgpro.core.svg.Element? {
        if (points.size < 2) return null
        // RDPで簡略化（画面1px相当）
        val simplified = rdp(points, 1.0 / editor.zoom)
        if (simplified.size < 2) return null
        val segs = ArrayList<Seg>()
        val catmull = catmullRomToCubics(simplified)
        segs.addAll(catmull)
        val el = dev.svgpro.core.svg.Element("path")
        el.setAttr("d", PathData(segs).write())
        editor.applyCurrentStyle(el)
        el.setAttr("fill", "none")
        if (editor.curStroke is dev.svgpro.core.model.Paint.None) {
            StyleUtil.setProperty(el, "stroke", dev.svgpro.core.model.Color.css(0xFF222222.toInt()))
            StyleUtil.setProperty(el, "stroke-width", dev.svgpro.core.util.Numbers.format(2.0))
            StyleUtil.setProperty(el, "stroke-linecap", "round")
            StyleUtil.setProperty(el, "stroke-linejoin", "round")
        }
        return el
    }

    private fun rdp(points: List<Vec>, tol: Double): List<Vec> {
        if (points.size < 3) return points
        // Douglas-Peucker（反復実装）
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = 0.0
            var maxI = -1
            val a = points[s]
            val b = points[e]
            for (i in s + 1 until e) {
                val d = pointLineDistance(points[i], a, b)
                if (d > maxD) {
                    maxD = d
                    maxI = i
                }
            }
            if (maxD > tol && maxI > 0) {
                keep[maxI] = true
                stack.add(s to maxI)
                stack.add(maxI to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun pointLineDistance(p: Vec, a: Vec, b: Vec): Double {
        val ab = b - a
        val len = ab.length()
        if (len == 0.0) return p.distanceTo(a)
        val cross = ab.cross(p - a)
        return abs(cross) / len
    }

    /** Catmull-Rom → cubicベジェ */
    private fun catmullRomToCubics(pts: List<Vec>): List<Seg> {
        val segs = ArrayList<Seg>()
        if (pts.isEmpty()) return segs
        segs.add(Seg.Move(pts[0].x, pts[0].y))
        val n = pts.size
        for (i in 0 until n - 1) {
            val p0 = pts[if (i == 0) 0 else i - 1]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[if (i + 2 < n) i + 2 else n - 1]
            val c1 = Vec(p1.x + (p2.x - p0.x) / 6.0, p1.y + (p2.y - p0.y) / 6.0)
            val c2 = Vec(p2.x - (p3.x - p1.x) / 6.0, p2.y - (p3.y - p1.y) / 6.0)
            segs.add(Seg.Cubic(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y))
        }
        return segs
    }

    /**
     * 筆圧対応フリーハンド: 点列を筆圧で幅が変わるポリゴン輪郭へ変換する。
     * 筆圧非対応端末では一定幅のストロークと同じ見た目になる。
     */
    private fun buildFreehandWithPressure(points: List<Vec>, pressures: List<Float>): dev.svgpro.core.svg.Element? {
        if (points.size < 2) return null
        val simplified = rdp(points, 1.0 / editor.zoom)
        if (simplified.size < 2) return null
        // 簡略化後の各点に近い筆圧を対応付ける
        val baseWidth = if (editor.curStroke is dev.svgpro.core.model.Paint.None) 3.0 else editor.curStrokeWidth.coerceAtLeast(1.0)
        // 各点の幅（base × pressure×2をクランプ）
        val widths = simplified.map { p ->
            var best = 0.5f
            var bestD = Double.MAX_VALUE
            for ((i, q) in points.withIndex()) {
                val d = p.distanceTo(q)
                if (d < bestD) {
                    bestD = d
                    best = pressures.getOrElse(i) { 0.5f }
                }
            }
            (baseWidth * (0.35 + best * 1.3)).coerceAtLeast(0.3)
        }
        // 輪郭: 各点で法線方向に±幅/2
        val left = ArrayList<Vec>()
        val right = ArrayList<Vec>()
        for (i in simplified.indices) {
            val prev = simplified[if (i == 0) 0 else i - 1]
            val next = simplified[if (i == simplified.size - 1) simplified.size - 1 else i + 1]
            val dir = (next - prev).normalized()
            val n = Vec(-dir.y, dir.x)
            val half = widths[i] / 2.0
            left.add(simplified[i] + n * half)
            right.add(simplified[i] - n * half)
        }
        val segs = ArrayList<Seg>()
        segs.add(Seg.Move(left[0].x, left[0].y))
        for (i in 1 until left.size) segs.add(Seg.Line(left[i].x, left[i].y))
        for (i in right.indices.reversed()) segs.add(Seg.Line(right[i].x, right[i].y))
        segs.add(Seg.Close)
        val el = dev.svgpro.core.svg.Element("path")
        el.setAttr("d", dev.svgpro.core.path.PathData(segs).write())
        el.setAttr("fill-rule", "nonzero")
        val strokeColor = (editor.curStroke as? dev.svgpro.core.model.Paint.Solid)?.argb ?: 0xFF222222.toInt()
        dev.svgpro.core.model.StyleUtil.setProperty(el, "fill", dev.svgpro.core.model.Color.css(strokeColor))
        dev.svgpro.core.model.StyleUtil.setProperty(el, "stroke", "none")
        editor.applyCurrentStyle(el)
        // 塗りを筆圧輪郭の色で上書き（applyCurrentStyleがfillを設定するため再度）
        dev.svgpro.core.model.StyleUtil.setProperty(el, "fill", dev.svgpro.core.model.Color.css(strokeColor))
        dev.svgpro.core.model.StyleUtil.setProperty(el, "stroke", "none")
        return el
    }

    // ―――― eyedropper ――――

    private fun pickColor(user: Vec) {
        val hit = editor.trace.hitTest(user, 6.0 / editor.zoom) ?: return
        val fill = editor.inheritedPaint(hit, "fill")
        if (fill is dev.svgpro.core.model.Paint.Solid) {
            editor.curFill = fill
        } else {
            val stroke = editor.inheritedPaint(hit, "stroke")
            if (stroke is dev.svgpro.core.model.Paint.Solid) {
                editor.curStroke = stroke
            }
        }
        editor.onChanged()
    }

    private fun rectFrom(a: Vec, b: Vec): Bounds {
        val x = minOf(a.x, b.x)
        val y = minOf(a.y, b.y)
        return Bounds(x, y, abs(b.x - a.x), abs(b.y - a.y))
    }

    /** ペン用: タップ座標のドラフト始点ヒット判定（onDownで処理するためここでは未使用） */
    fun marqueeRect(): Bounds? = if (mode == Mode.MARQUEE) rectFrom(marqueeStart, lastUser) else null
}
