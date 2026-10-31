package dev.svgpro.editor

import dev.svgpro.core.edit.Attach
import dev.svgpro.core.edit.AttrChange
import dev.svgpro.core.edit.ChangeSet
import dev.svgpro.core.edit.Detach
import dev.svgpro.core.edit.EditBatch
import dev.svgpro.core.edit.UndoStack
import dev.svgpro.core.geom.Bounds
import dev.svgpro.core.geom.Matrix
import dev.svgpro.core.geom.Vec
import dev.svgpro.core.model.Color
import dev.svgpro.core.model.Guide
import dev.svgpro.core.model.Paint
import dev.svgpro.core.model.Shapes
import dev.svgpro.core.model.StyleUtil
import dev.svgpro.core.model.SvgDocument
import dev.svgpro.core.path.NodeKind
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import dev.svgpro.core.path.SubpathNodes
import dev.svgpro.core.svg.Element
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round

/** ツール種別 */
enum class ToolType {
    SELECT, NODE, PEN, FREEHAND,
    RECT, RRECT, ELLIPSE, LINE, POLYGON, STAR, POLYLINE,
    TEXT, HAND, EYEDROPPER, GRADIENT,
}

/** ノード参照（サブパスindex, ノードindex）— コアで定義されたものを使用 */
typealias NodeRef = dev.svgpro.core.path.NodeRef

/**
 * ノード編集セッション。選択中の1 path 要素のノードモデルを保持する。
 */
class NodeSession(val el: Element, private val trace: Trace) {
    val models: MutableList<SubpathNodes> = ArrayList()
    val selected = LinkedHashSet<NodeRef>()

    /** ドラッグ開始時のd属性（undo用） */
    var dragStartD: String? = null
    var activeHandle: Pair<NodeRef, Boolean>? = null // (node, isIn)

    init {
        rebuild()
    }

    fun rebuild() {
        models.clear()
        selected.clear()
        val pd = PathData.parse(el.attr("d")) ?: return
        for (sp in pd.subpaths()) {
            val m = SubpathNodes.fromSubpath(pd, sp) ?: continue
            models.add(m)
        }
    }

    fun nodePos(ref: NodeRef): Vec? {
        val m = models.getOrNull(ref.sub) ?: return null
        val n = m.nodes.getOrNull(ref.node) ?: return null
        return n.p
    }

    /** すべてのノードの絶対座標（ヒットテスト・描画用） */
    fun allNodes(): List<Pair<NodeRef, Vec>> {
        val out = ArrayList<Pair<NodeRef, Vec>>()
        for (si in models.indices) {
            val m = models[si]
            for (ni in m.nodes.indices) {
                out.add(NodeRef(si, ni) to m.nodes[ni].p)
            }
        }
        return out
    }

    /** ハンドル一覧: (node, isIn, pos) */
    fun allHandles(selectedOnly: Boolean = true): List<Triple<NodeRef, Boolean, Vec>> {
        val out = ArrayList<Triple<NodeRef, Boolean, Vec>>()
        for (ref in selected.sorted()) {
            val m = models.getOrNull(ref.sub) ?: continue
            val n = m.nodes.getOrNull(ref.node) ?: continue
            n.cin?.let { out.add(Triple(ref, true, it)) }
            n.cout?.let { out.add(Triple(ref, false, it)) }
        }
        return out
    }

    /** セグメントの近似点列（描画用） */
    fun segmentPolylines(tol: Double = 0.05): List<List<Vec>> {
        val out = ArrayList<List<Vec>>()
        for (m in models) {
            val sub = SubpathNodes.toSegs(m)
            val pd = PathData.fromSegs(sub)
            out.add(pd.flatten(tol))
        }
        return out
    }

    fun selectOnly(ref: NodeRef) {
        selected.clear()
        selected.add(ref)
    }

    fun toggleSelect(ref: NodeRef) {
        if (!selected.add(ref)) selected.remove(ref)
    }

    fun selectInBox(b: Bounds) {
        selected.clear()
        for ((ref, p) in allNodes()) {
            if (b.contains(p)) selected.add(ref)
        }
    }

    /** モデル → d属性へ反映 */
    fun flush() {
        val segs = ArrayList<Seg>()
        for (m in models) {
            segs.addAll(SubpathNodes.toSegs(m))
        }
        if (segs.isEmpty()) return
        el.setAttr("d", PathData(segs).write())
    }

    fun nodeKind(ref: NodeRef): NodeKind? =
        models.getOrNull(ref.sub)?.nodeKind(ref.node)

    fun moveNode(ref: NodeRef, delta: Vec) {
        models.getOrNull(ref.sub)?.moveNode(ref.node, delta)
    }

    fun convertSelected(kind: NodeKind) {
        for (ref in selected) {
            models.getOrNull(ref.sub)?.convertKind(ref.node, kind)
        }
        flush()
    }

    fun deleteSelected(): Boolean {
        // 後ろから削除（indexずれ回避）
        val groups = selected.groupBy { it.sub }
        var ok = false
        for ((sub, refs) in groups) {
            val m = models.getOrNull(sub) ?: continue
            for (ref in refs.sortedByDescending { it.node }) {
                if (m.removeNode(ref.node)) ok = true
            }
        }
        selected.clear()
        if (ok) flush()
        return ok
    }

    fun insertNodeNear(p: Vec, snapTol: Double): Boolean {
        // 最も近いセグメントに挿入
        var best: Triple<Int, Int, Double>? = null // (sub, node-index-after, dist)
        for (si in models.indices) {
            val m = models[si]
            for (j in 0 until m.segmentCount) {
                val a = m.nodes[j].p
                val b = m.nodes[(j + 1) % m.nodes.size].p
                // セグメント種別に応じた近似最近点
                val proj = projectOnSegment(m, j, p)
                val d = proj.second.distanceTo(p)
                if (best == null || d < best.third) best = Triple(si, j, d)
            }
        }
        val (si, j, d) = best ?: return false
        if (d > snapTol * 3) return false
        val m = models[si]
        val t = paramOnSegment(m, j, p)
        val idx = m.insertNodeAt(j, t)
        selected.clear()
        selected.add(NodeRef(si, idx))
        flush()
        return true
    }

    private fun projectOnSegment(m: SubpathNodes, j: Int, p: Vec): Pair<Double, Vec> {
        val nodeA = m.nodes[j]
        val nodeB = m.nodes[(j + 1) % m.nodes.size]
        val a = nodeA.p
        val b = nodeB.p
        val type = m.segTypeOf(j)
        return when (type) {
            dev.svgpro.core.path.SegType.LINE -> {
                val ab = b - a
                val len2 = ab.dot(ab)
                var t = if (len2 == 0.0) 0.0 else ((p - a).dot(ab)) / len2
                t = t.coerceIn(0.0, 1.0)
                t to (a + ab * t)
            }
            else -> {
                val c1 = nodeA.cout ?: Vec((a.x * 2.0 + b.x) / 3.0, (a.y * 2.0 + b.y) / 3.0)
                val c2 = nodeB.cin ?: Vec((a.x + b.x * 2.0) / 3.0, (a.y + b.y * 2.0) / 3.0)
                val pd = PathData.fromSegs(
                    listOf(
                        Seg.Move(a.x, a.y),
                        Seg.Cubic(c1.x, c1.y, c2.x, c2.y, b.x, b.y),
                    ),
                )
                val pr = pd.project(p, 0.01)!!
                pr.t to pr.point
            }
        }
    }

    private fun paramOnSegment(m: SubpathNodes, j: Int, p: Vec): Double {
        val a = m.nodes[j].p
        val b = m.nodes[(j + 1) % m.nodes.size].p
        val ab = b - a
        val len2 = ab.dot(ab)
        val t = if (len2 == 0.0) 0.0 else ((p - a).dot(ab)) / len2
        return t.coerceIn(0.05, 0.95)
    }

    fun closeOpenSelected(close: Boolean) {
        for (m in models) {
            if (close) m.closeSubpath() else m.openSubpath()
        }
        flush()
    }

    fun reverseAll() {
        // モデル列を逆順セグメントで作り直す
        val segs = ArrayList<Seg>()
        for (m in models) {
            val pd = PathData.fromSegs(SubpathNodes.toSegs(m))
            segs.addAll(pd.reversed().segs)
        }
        el.setAttr("d", PathData(segs).write())
        rebuild()
    }

    /** 選択ノードの整列 */
    fun alignSelected(mode: String) {
        val refs = selected.sorted()
        if (refs.size < 2) return
        val pts = refs.mapNotNull { nodePos(it) }
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        when (mode) {
            "left" -> for (ref in refs) setX(ref, minX)
            "right" -> for (ref in refs) setX(ref, maxX)
            "centerX" -> for (ref in refs) setX(ref, (minX + maxX) / 2)
            "top" -> for (ref in refs) setY(ref, minY)
            "bottom" -> for (ref in refs) setY(ref, maxY)
            "centerY" -> for (ref in refs) setY(ref, (minY + maxY) / 2)
        }
        flush()
    }

    private fun setX(ref: NodeRef, x: Double) {
        val p = nodePos(ref) ?: return
        models[ref.sub].setNodePosition(ref.node, Vec(x, p.y))
    }

    private fun setY(ref: NodeRef, y: Double) {
        val p = nodePos(ref) ?: return
        models[ref.sub].setNodePosition(ref.node, Vec(p.x, y))
    }
}

/**
 * エディタ本体。ドキュメント・選択・ビュー変換・ツール状態・Undoを保持する。
 * 描画はCanvasView、UIはComposeからこのクラスを操作する。
 */
class Editor {

    // ―――― ドキュメント ――――
    var doc: SvgDocument = SvgDocument.create()
        private set

    val undoStack = UndoStack()
    var dirty = false
        private set

    var documentName: String = "untitled.svg"

    // ―――― 選択 ――――
    val selection = ArrayList<Element>()

    // ―――― 表示変換（screen = viewMatrix.apply(user)） ――――
    var viewMatrix = Matrix()

    /** マルチタッチジェスチャから直接設定する（フィット時のみ使用） */
    fun applyViewMatrix(m: Matrix) {
        viewMatrix = m
        onZoomChanged()
        onChanged()
    }

    // 0で初期化（初回のsetViewportで「最初のサイズ変更」と判定され、fitToPageが走る）。
    // 以前は1000固定だったため最初のフィットが一度も行われず、起動直後が100%固定になっていた
    var viewportW = 0.0
    var viewportH = 0.0

    // ―――― ツール/ドラフト状態 ――――
    var tool: ToolType = ToolType.SELECT
        set(value) {
            val old = field
            field = value
            if (old == ToolType.PEN && penDraft != null) {
                // ペン作成中にツールを切り替えた場合は開いたまま確定する
                commitPenDraft(close = false)
            }
            freehandDraft = null
            shapeDraft = null
            updateNodeSession()
            onChanged()
        }

    /** ペンツールの作成中パス */
    var penDraft: PathData? = null

    /** ペンドラフトの確定（要素化）。close=trueで始点へ閉じる */
    fun commitPenDraft(close: Boolean) {
        val draft = penDraft ?: return
        if (close) {
            val first = draft.segs.firstOrNull()
            if (first is Seg.Move && draft.segs.size >= 2) {
                when (val last = draft.segs.last()) {
                    is Seg.Line -> draft.segs[draft.segs.size - 1] = Seg.Line(first.x, first.y)
                    is Seg.Cubic -> {
                        last.x = first.x
                        last.y = first.y
                    }
                    else -> {}
                }
                draft.segs.add(Seg.Close)
            }
        }
        penDraft = null
        if (draft.segs.size >= 2) {
            val el = Element("path")
            el.setAttr("d", draft.write())
            applyCurrentStyle(el)
            addElement(el, "pen path")
        } else {
            onChanged()
        }
    }

    fun cancelPenDraft() {
        if (penDraft != null) {
            penDraft = null
            onChanged()
        }
    }

    /** フリーハンドのポイント列 */
    var freehandDraft: MutableList<Vec>? = null
    /** 図形ドラッグ中の draft (start, current, kind) */
    var shapeDraft: Triple<Vec, Vec, ToolType>? = null

    var nodeSession: NodeSession? = null

    // ―――― 設定 ――――
    var gridSize = 10.0
    var showGrid = false
    var showRulers = false
    var snapToGrid = false
    var snapToObject = true
    var penOnly = false // スタイラスのみで作図（指はパン/ズーム）
    var usePressure = true

    // ―――― スナップ詳細設定 ――――
    /** スナップ許容距離（画面px基準。拡大率でuser距離へ変換） */
    var snapTolerancePx = 8.0
    /** オブジェクトのノード（パス上の点）へスナップ */
    var snapToNode = false
    /** オブジェクトの中心へスナップ */
    var snapToCenter = true
    /** 移動中のスマートガイド（他オブジェクトとの整列補助線） */
    var smartGuides = true
    /** ガイド線へスナップ */
    var snapToGuides = true

    // ―――― ガイド ――――
    val guides = ArrayList<Guide>()

    // ―――― 整列の基準 ――――
    enum class AlignRelative { SELECTION, CANVAS, KEY_OBJECT }

    /** キーオブジェクト（整列基準。UIで明示指定する） */
    var keyObject: Element? = null

    // ―――― 変形の繰り返し ――――
    /** 直近の数値変形（transform again用） */
    var lastTransformOp: TransformOp? = null
        private set

    fun recordTransform(op: TransformOp) {
        lastTransformOp = op
    }

    // ―――― 移動中の状態表示（ステータスバー用） ――――
    /** 直近のドラッグ移動量（user単位）。距離表示に使う */
    var lastMoveDelta: Vec = Vec.ZERO
        private set

    internal fun setLastMoveDelta(v: Vec) {
        lastMoveDelta = v
    }

    // ―――― スマートガイド表示（CanvasView描画用） ――――
    /** 整列補助線（user座標・縦線のx集合/横線のy集合） */
    var smartGuideV: MutableList<Double> = ArrayList()
    var smartGuideH: MutableList<Double> = ArrayList()

    // ―――― 新規作成スタイル ――――
    var curFill: Paint = Paint.Solid(Color.withAlpha(0xFF1473E6.toInt(), 0.85))
    var curStroke: Paint = Paint.None
    var curStrokeWidth = 2.0
    var curPolygonSides = 5
    var curStarPoints = 5
    var curTextSize = 24.0

    // ―――― グラデーション編集 ――――
    /** 現在編集中のグラデーション（GRADIENTツール使用時） */
    var gradientEdit: GradientEditState? = null

    // ―――― コールバック ――――
    var onChanged: () -> Unit = {}
    var onSelectionChanged: () -> Unit = {}
    var onZoomChanged: () -> Unit = {}
    var onDocumentReplaced: () -> Unit = {}
    var requestTextInput: ((Vec) -> Unit)? = null

    // ―――― キャッシュ ――――
    var trace: Trace = Trace.build(doc)
        private set

    fun invalidateTrace() {
        trace = Trace.build(doc, trace)
    }

    fun notifyChanged() {
        invalidateTrace()
        dirty = true
        onChanged()
    }

    fun notifySelection() {
        onSelectionChanged()
        onChanged()
    }

    // ―――― ドキュメント操作 ――――

    fun loadDocument(doc: SvgDocument, name: String?) {
        this.doc = doc
        selection.clear()
        nodeSession = null
        undoStack.clear()
        dirty = false
        if (name != null) documentName = name
        guides.clear()
        guides.addAll(Guide.Codec.decode(doc.root.attr(Guide.ATTR)))
        keyObject = null
        lastTransformOp = null
        invalidateTrace()
        fitToPage()
        onDocumentReplaced()
        onChanged()
    }

    fun newDocument(w: Double, h: Double) {
        loadDocument(SvgDocument.create(w, h), "untitled.svg")
    }

    fun markSaved() {
        dirty = false
        onChanged()
    }

    // ―――― 座標変換 ――――

    fun userToScreen(v: Vec): Vec = viewMatrix.apply(v)

    fun screenToUser(v: Vec): Vec {
        val inv = viewMatrix.invert()
        return inv.apply(v)
    }

    val zoom: Double get() = viewMatrix.a

    fun setViewport(w: Double, h: Double) {
        val first = viewportW <= 1.0 || viewportH <= 1.0
        viewportW = w
        viewportH = h
        if (first) fitToPage()
        onChanged()
    }

    fun panBy(screenDx: Double, screenDy: Double) {
        viewMatrix = viewMatrix.translate(screenDx, screenDy)
        onZoomChanged()
        onChanged()
    }

    fun zoomBy(factor: Double, focusScreen: Vec) {
        val f = factor.coerceIn(0.02, 200.0)
        val newScale = (viewMatrix.a * f).coerceIn(0.02, 200.0)
        val user = screenToUser(focusScreen)
        // screen = user*scale + offset。user点をfocusScreenに固定する
        viewMatrix = Matrix(
            a = newScale, b = 0.0,
            c = 0.0, d = newScale,
            e = focusScreen.x - user.x * newScale,
            f = focusScreen.y - user.y * newScale,
        )
        onZoomChanged()
        onChanged()
    }

    fun setZoom(scale: Double, focusScreen: Vec? = null) {
        val focus = focusScreen ?: Vec(viewportW / 2, viewportH / 2)
        zoomBy(scale / viewMatrix.a, focus)
    }

    fun fitRect(user: Bounds, margin: Double = 24.0) {
        if (user.width <= 0 || user.height <= 0) return
        val scale = minOf(
            (viewportW - margin * 2) / user.width,
            (viewportH - margin * 2) / user.height,
        ).coerceIn(0.01, 100.0)
        val offX = (viewportW - user.width * scale) / 2 - user.x * scale
        val offY = (viewportH - user.height * scale) / 2 - user.y * scale
        viewMatrix = Matrix(e = offX, f = offY).let { Matrix(a = scale, d = scale).then(it) }
        onZoomChanged()
        onChanged()
    }

    fun fitToPage() {
        val vb = doc.viewBox
        fitRect(Bounds(vb.minX, vb.minY, vb.width, vb.height))
    }

    fun fitToSelection() {
        val b = trace.boundsOf(selection) ?: fitToPage().let { return }
        fitRect(b)
    }

    fun zoomTo100() {
        val center = Vec(viewportW / 2, viewportH / 2)
        val userCenter = screenToUser(center)
        viewMatrix = Matrix(e = center.x - userCenter.x, f = center.y - userCenter.y)
        onZoomChanged()
        onChanged()
    }

    // ―――― 選択 ――――

    fun select(el: Element?, additive: Boolean = false) {
        if (!additive) selection.clear()
        if (el != null && doc.isSelectableShape(el)) {
            if (!selection.contains(el)) selection.add(el)
        }
        updateNodeSession()
        notifySelection()
    }

    fun selectAllInBox(b: Bounds, additive: Boolean) {
        if (!additive) selection.clear()
        for (item in trace.items.reversed()) {
            if (!item.visible || item.locked) continue
            if (!doc.isSelectableShape(item.el)) continue
            val gb = trace.boundsOf(item.el) ?: continue
            val fullyInside = gb.x >= b.x && gb.right <= b.right && gb.y >= b.y && gb.bottom <= b.bottom
            if (fullyInside) {
                if (!selection.contains(item.el)) selection.add(item.el)
            }
        }
        updateNodeSession()
        notifySelection()
    }

    fun clearSelection() {
        selection.clear()
        updateNodeSession()
        notifySelection()
    }

    fun isSelected(el: Element): Boolean = selection.contains(el)

    fun updateNodeSession() {
        if (tool != ToolType.NODE) {
            nodeSession = null
            return
        }
        val sel = selection.firstOrNull { PathData.parse(it.attr("d")) != null || Shapes.isPrimitive(it) }
        if (sel == null) {
            nodeSession = null
            return
        }
        // プリミティブはpathへ変換してから編集
        if (Shapes.isPrimitive(sel)) {
            convertToPath(listOf(sel))
        }
        val target = selection.firstOrNull()
        val existing = nodeSession
        if (existing != null && existing.el === target) return
        nodeSession = if (target != null) NodeSession(target, trace) else null
    }

    // ―――― Undo統合ドラッグ ――――

    class DragSession internal constructor(
        val label: String?,
        val batch: EditBatch,
        val touched: List<Element>,
    )

    fun beginDrag(label: String, elements: List<Element> = selection): DragSession? {
        if (elements.isEmpty()) return null
        val batch = EditBatch()
        for (el in elements) batch.touch(el)
        return DragSession(label, batch, elements)
    }

    fun endDrag(session: DragSession?) {
        if (session == null) return
        val cs = session.batch.endAndCommit(session.label)
        if (cs != null) {
            undoStack.push(cs)
            notifyChanged()
        } else {
            onChanged()
        }
    }

    fun cancelDrag(session: DragSession?) {
        if (session == null) return
        // スナップショットへ戻す（簡易: ChangeSet生成前に直接復元はしない。undoスタック未pushなので
        // 呼び出し側は beginDrag前に保持した値で復元する）
        onChanged()
    }

    /** 単発の変更を1履歴としてpush */
    fun commit(label: String, build: (EditBatch) -> Unit) {
        val batch = EditBatch()
        build(batch)
        val cs = batch.endAndCommit(label)
        if (cs != null) {
            undoStack.push(cs)
            notifyChanged()
        }
    }

    fun undo() {
        val cs = undoStack.undo()
        if (cs != null) {
            restoreSelection(cs.selectionHint)
            nodeSession = null
            updateNodeSession()
            notifyChanged()
            notifySelection()
        }
    }

    fun redo() {
        val cs = undoStack.redo()
        if (cs != null) {
            restoreSelection(cs.selectionHint)
            nodeSession = null
            updateNodeSession()
            notifyChanged()
            notifySelection()
        }
    }

    private fun restoreSelection(hint: List<Any>) {
        if (hint.isEmpty()) return
        invalidateTrace()
        val existing = hint.filterIsInstance<Element>().filter { findParent(it) != null }
        selection.clear()
        selection.addAll(existing)
    }

    private fun collectElements(): List<Element> {
        val out = ArrayList<Element>()
        doc.root.walk { out.add(it) }
        return out
    }

    // ―――― スナップ ――――

    /**
     * 点をスナップする。
     * 優先度: ガイド > オブジェクト（コーナー/中心/ノード） > グリッド。
     * 画面px基準の許容距離を拡大率でuser距離へ変換するため、ズーム非依存の操作感。
     */
    fun snapPoint(p: Vec, ignore: List<Element> = emptyList()): Vec {
        val tolUser = snapTolerancePx / zoom.coerceAtLeast(1e-6)
        var result = p

        // ガイドスナップ（最優先・個別トグル）。ロックは「編集不可」でありスナップ対象からは除外しない
        if (snapToGuides) {
            var snapped = false
            for (g in guides) {
                if (!g.visible) continue
                if (g.horizontal && abs(p.y - g.pos) < tolUser) {
                    result = Vec(result.x, g.pos); snapped = true
                } else if (!g.horizontal && abs(p.x - g.pos) < tolUser) {
                    result = Vec(g.pos, result.y); snapped = true
                }
            }
            if (snapped) return result
        }

        if (snapToObject) {
            var bestDelta: Vec? = null
            var bestDist = tolUser
            val ignoreSet = ignore.toHashSet()
            for (item in trace.items) {
                if (item.locked || !item.visible) continue
                if (selection.contains(item.el) || item.el in ignoreSet) continue
                val b = trace.boundsOf(item.el) ?: continue
                val candidates = ArrayList<Vec>(10)
                candidates.add(Vec(b.x, b.y)); candidates.add(Vec(b.centerX, b.y)); candidates.add(Vec(b.right, b.y))
                candidates.add(Vec(b.x, b.centerY)); candidates.add(Vec(b.right, b.centerY))
                candidates.add(Vec(b.x, b.bottom)); candidates.add(Vec(b.centerX, b.bottom)); candidates.add(Vec(b.right, b.bottom))
                if (snapToCenter) candidates.add(Vec(b.centerX, b.centerY))
                for (c in candidates) {
                    val d = c.distanceTo(result)
                    if (d < bestDist) {
                        bestDist = d
                        bestDelta = c - result
                    }
                }
                // ノードスナップ: パス要素のノード座標
                if (snapToNode) {
                    val geo = item.geometry
                    if (geo != null && geo.nodeCountEstimate() <= 512) {
                        for (sp in geo.subpaths()) {
                            val segs = geo.segs
                            for (i in sp.segStart..sp.segEnd) {
                                val s = segs.getOrNull(i) ?: continue
                                if (s is Seg.Close) continue
                                val nodeP = geo.endPoint(i)
                                val d = nodeP.distanceTo(result)
                                if (d < bestDist) {
                                    bestDist = d
                                    bestDelta = nodeP - result
                                }
                            }
                        }
                    }
                }
            }
            if (bestDelta != null) result += bestDelta
            if (bestDelta != null) return result
        }

        if (snapToGrid) {
            result = Vec(round(result.x / gridSize) * gridSize, round(result.y / gridSize) * gridSize)
        }
        return result
    }

    /** ガイド列をドキュメント属性へ保存（可逆） */
    fun saveGuides() {
        val enc = Guide.Codec.encode(guides)
        commit("guides") { batch ->
            batch.touch(doc.root)
            doc.root.setAttr(Guide.ATTR, enc)
        }
    }

    fun addGuide(g: Guide) {
        guides.add(g)
        saveGuides()
    }

    /** ドラッグ中のガイド移動（undo記録なし。確定時にsaveGuidesを呼ぶ） */
    fun moveGuideSilent(index: Int, pos: Double) {
        if (index !in guides.indices) return
        val g = guides[index]
        guides[index] = g.copy(pos = pos)
        onChanged()
    }

    fun updateGuide(index: Int, g: Guide) {
        if (index !in guides.indices) return
        guides[index] = g
        saveGuides()
    }

    fun removeGuide(index: Int) {
        if (index !in guides.indices) return
        guides.removeAt(index)
        saveGuides()
    }

    fun guideAtScreen(screen: Vec, tolerancePx: Double = 12.0): Int? {
        var best: Int? = null
        var bestD = tolerancePx
        for ((i, g) in guides.withIndex()) {
            if (!g.visible || g.locked) continue
            val d = if (g.horizontal) abs(userToScreen(Vec(0.0, g.pos)).y - screen.y)
            else abs(userToScreen(Vec(g.pos, 0.0)).x - screen.x)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    // ―――― オブジェクト操作 ――――

    fun deleteSelection() {
        if (selection.isEmpty()) return
        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        for (el in selection.toList()) {
            val parent = findParent(el) ?: continue
            changes.add(Detach(el, parent, parent.children.indexOf(el), "delete"))
        }
        val cs = ChangeSet(changes, "delete")
        cs.apply()
        selection.clear()
        undoStack.push(cs)
        updateNodeSession()
        notifyChanged()
        notifySelection()
    }

    fun duplicateSelection() {
        if (selection.isEmpty()) return
        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        val newSel = ArrayList<Element>()
        for (el in selection.toList()) {
            val parent = findParent(el) ?: continue
            val copy = el.deepCopy()
            // 少しずらす
            val t = Shapes.localTransform(el)
            copy.setAttr("transform", Matrix.toSvgString(t.then(Matrix(e = 8.0 / zoom, f = 8.0 / zoom))))
            val idx = parent.children.indexOf(el) + 1
            changes.add(Attach(copy, parent, idx, doc.root, "duplicate"))
            newSel.add(copy)
        }
        val cs = ChangeSet(changes, "duplicate", newSel)
        cs.apply()
        selection.clear()
        selection.addAll(newSel)
        undoStack.push(cs)
        updateNodeSession()
        notifyChanged()
        notifySelection()
    }

    fun groupSelection() {
        if (selection.size < 2) return
        val parent = findParent(selection.first()) ?: return
        // 選択の最小index位置へgを挿入
        val indices = selection.mapNotNull { parent.children.indexOf(it) }.filter { it >= 0 }
        if (indices.size != selection.size) return // 親が混在（未対応）
        val insertAt = indices.min()
        val g = Element("g")
        g.setAttr("id", "group_${System.currentTimeMillis() % 100000}")
        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        // 選択要素を順に g へ移動（applyが実移動を行う）
        var i = 0
        for (el in selection.toList()) {
            changes.add(Attach(el, g, i, doc.root, "group"))
            i++
        }
        changes.add(Attach(g, parent, insertAt, doc.root, "group"))
        val cs = ChangeSet(changes, "group", listOf(g))
        cs.apply()
        selection.clear()
        selection.add(g)
        undoStack.push(cs)
        updateNodeSession()
        notifyChanged()
        notifySelection()
    }

    fun ungroupSelection() {
        val groups = selection.filter { it.tag == "g" }
        if (groups.isEmpty()) return
        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        val newSel = ArrayList<Element>()
        for (g in groups) {
            val parent = findParent(g) ?: continue
            val insertAt = parent.children.indexOf(g)
            val children = g.childElements().toList()
            for (i in children.indices) {
                val c = children[i]
                // 親のtransformを子へ継承（簡単化: gのtransformがidentity以外なら合成）
                val gt = Shapes.localTransform(g)
                if (!gt.isIdentity()) {
                    val ct = Shapes.localTransform(c)
                    c.setAttr("transform", Matrix.toSvgString(gt.then(ct)))
                }
                changes.add(Attach(c, parent, insertAt + i, doc.root, "ungroup"))
                newSel.add(c)
            }
            changes.add(Detach(g, parent, parent.children.indexOf(g), "ungroup"))
        }
        val cs = ChangeSet(changes, "ungroup", groups)
        cs.apply()
        selection.clear()
        selection.addAll(newSel)
        undoStack.push(cs)
        updateNodeSession()
        notifyChanged()
        notifySelection()
    }

    fun reorderSelection(front: Boolean, absolute: Boolean) {
        if (selection.isEmpty()) return
        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        for (el in selection.toList()) {
            val parent = findParent(el) ?: continue
            val from = parent.children.indexOf(el)
            if (from < 0) continue
            val to = if (absolute) {
                if (front) parent.children.size - 1 else 0
            } else {
                (from + if (front) 1 else -1).coerceIn(0, parent.children.size - 1)
            }
            if (to == from) continue
            changes.add(Attach(el, parent, to, doc.root, "order"))
        }
        if (changes.isNotEmpty()) {
            val cs = ChangeSet(changes, "order")
            cs.apply()
            undoStack.push(cs)
            notifyChanged()
        }
    }

    fun setLocked(el: Element, locked: Boolean) {
        commit("lock") { batch ->
            batch.touch(el)
            el.setAttr(Trace.LOCK_ATTR, if (locked) "1" else null)
        }
    }

    fun setHidden(el: Element, hidden: Boolean) {
        commit("visibility") { batch ->
            batch.touch(el)
            if (hidden) {
                StyleUtil.setProperty(el, "display", "none")
            } else {
                StyleUtil.removeProperty(el, "display")
            }
        }
    }

    fun renameElement(el: Element, name: String) {
        commit("rename") { batch ->
            batch.touch(el)
            el.setAttr("id", name)
        }
    }

    /** 整列（選択bbox基準）。mode: left/centerX/right/top/centerY/bottom */
    fun alignSelection(mode: String) {
        if (selection.size < 2) return
        val total = trace.boundsOf(selection) ?: return
        commit("align") { batch ->
            for (el in selection) {
                val b = trace.boundsOf(el) ?: continue
                val parentAbs = trace.parentAbsOf(el)
                val parentInv = parentAbs.invert()
                var targetUser: Vec = when (mode) {
                    "left" -> Vec(total.x, b.centerY)
                    "centerX" -> Vec(total.centerX, b.centerY)
                    "right" -> Vec(total.right, b.centerY)
                    "top" -> Vec(b.centerX, total.y)
                    "centerY" -> Vec(b.centerX, total.centerY)
                    "bottom" -> Vec(b.centerX, total.bottom)
                    else -> return@commit
                }
                // 変換後bboxの基準点が targetUser に来るように平行移動
                val curPoint: Vec = when (mode) {
                    "left" -> Vec(b.x, b.centerY)
                    "centerX" -> Vec(b.centerX, b.centerY)
                    "right" -> Vec(b.right, b.centerY)
                    "top" -> Vec(b.centerX, b.y)
                    "centerY" -> Vec(b.centerX, b.centerY)
                    "bottom" -> Vec(b.centerX, b.bottom)
                    else -> return@commit
                }
                val deltaUser = targetUser - curPoint
                val localDelta = parentInv.transformVec(deltaUser)
                val elLocal = Shapes.localTransform(el)
                batch.touch(el)
                el.setAttr("transform", Matrix.toSvgString(elLocal.then(Matrix(e = localDelta.x, f = localDelta.y))))
            }
        }
    }

    /** 間隔の均等化（水平/垂直） */
    fun distributeSelection(horizontal: Boolean) {
        if (selection.size < 3) return
        val items = selection.mapNotNull { el -> trace.boundsOf(el)?.let { el to it } }
        if (items.size < 3) return
        val sorted = if (horizontal) items.sortedBy { it.second.x } else items.sortedBy { it.second.y }
        val first = sorted.first().second
        val last = sorted.last().second
        val total = if (horizontal) last.centerX - first.centerX else last.centerY - first.centerY
        val step = total / (sorted.size - 1)
        commit("distribute") { batch ->
            for (i in 1 until sorted.size - 1) {
                val (el, b) = sorted[i]
                val targetCenter = if (horizontal) first.centerX + step * i else first.centerY + step * i
                val curCenter = if (horizontal) b.centerX else b.centerY
                val deltaUser = if (horizontal) Vec(targetCenter - curCenter, 0.0) else Vec(0.0, targetCenter - curCenter)
                val parentInv = trace.parentAbsOf(el).invert()
                val localDelta = parentInv.transformVec(deltaUser)
                val elLocal = Shapes.localTransform(el)
                batch.touch(el)
                el.setAttr("transform", Matrix.toSvgString(elLocal.then(Matrix(e = localDelta.x, f = localDelta.y))))
            }
        }
    }

    fun flipSelection(horizontal: Boolean) {
        if (selection.isEmpty()) return
        val b = trace.boundsOf(selection) ?: return
        commit("flip") { batch ->
            for (el in selection.toList()) {
                val elb = trace.boundsOf(el) ?: continue
                val parentInv = trace.parentAbsOf(el).invert()
                val cx = if (horizontal) (b.centerX) else elb.centerX
                val cy = if (horizontal) elb.centerY else b.centerY
                // parent空間での中心へ反転
                val cLocal = parentInv.apply(Vec(cx, cy))
                val sx = if (horizontal) -1.0 else 1.0
                val sy = if (horizontal) 1.0 else -1.0
                val flip = Matrix(a = sx, b = 0.0, c = 0.0, d = sy, e = 0.0, f = 0.0)
                val m = Matrix(e = -cLocal.x, f = -cLocal.y)
                    .then(flip)
                    .then(Matrix(e = cLocal.x, f = cLocal.y))
                val elLocal = Shapes.localTransform(el)
                batch.touch(el)
                el.setAttr("transform", Matrix.toSvgString(elLocal.then(m)))
            }
        }
    }

    // ―――― 変換ハンドル（select tool） ――――

    fun moveSelection(deltaUser: Vec) {
        if (selection.isEmpty()) return
        for (el in selection) {
            val parentInv = trace.parentAbsOf(el).invert()
            val localDelta = parentInv.transformVec(deltaUser)
            val elLocal = Shapes.localTransform(el)
            // 親空間での平行移動 = ローカル変換の後にtranslation（thenは時間順）
            el.setAttr("transform", Matrix.toSvgString(elLocal.then(Matrix(e = localDelta.x, f = localDelta.y))))
        }
        setLastMoveDelta(deltaUser)
        invalidateTrace()
        onChanged()
    }

    /** 選択全体を center(user) を基準に scale 倍 */
    fun scaleSelection(scaleX: Double, scaleY: Double, centerUser: Vec) {
        if (selection.isEmpty()) return
        for (el in selection) {
            val parentInv = trace.parentAbsOf(el).invert()
            val cLocal = parentInv.apply(centerUser)
            val s = Matrix(a = scaleX, b = 0.0, c = 0.0, d = scaleY, e = 0.0, f = 0.0)
            // then()は時間順（左から右へ適用）: -c → S → +c でcenter周りの拡縮
            val m = Matrix(e = -cLocal.x, f = -cLocal.y).then(s).then(Matrix(e = cLocal.x, f = cLocal.y))
            val elLocal = Shapes.localTransform(el)
            el.setAttr("transform", Matrix.toSvgString(elLocal.then(m)))
        }
        invalidateTrace()
        onChanged()
    }

    fun rotateSelection(deg: Double, centerUser: Vec) {
        if (selection.isEmpty()) return
        for (el in selection) {
            val parentInv = trace.parentAbsOf(el).invert()
            val cLocal = parentInv.apply(centerUser)
            val r = Matrix().rotateDeg(deg)
            val m = Matrix(e = -cLocal.x, f = -cLocal.y).then(r).then(Matrix(e = cLocal.x, f = cLocal.y))
            val elLocal = Shapes.localTransform(el)
            el.setAttr("transform", Matrix.toSvgString(elLocal.then(m)))
        }
        invalidateTrace()
        onChanged()
    }

    // ―――― スタイル ――――

    fun applyFillToSelection(paint: Paint) {
        if (selection.isEmpty()) return
        commit("fill") { batch ->
            for (el in selection) {
                batch.touch(el)
                when (paint) {
                    is Paint.None -> StyleUtil.setProperty(el, "fill", "none")
                    is Paint.Solid -> StyleUtil.setProperty(el, "fill", Color.css(paint.argb))
                    is Paint.Server -> StyleUtil.setProperty(el, "fill", "url(#${paint.id})")
                }
            }
        }
    }

    fun applyStrokeToSelection(paint: Paint) {
        if (selection.isEmpty()) return
        commit("stroke") { batch ->
            for (el in selection) {
                batch.touch(el)
                when (paint) {
                    is Paint.None -> StyleUtil.setProperty(el, "stroke", "none")
                    is Paint.Solid -> StyleUtil.setProperty(el, "stroke", Color.css(paint.argb))
                    is Paint.Server -> StyleUtil.setProperty(el, "stroke", "url(#${paint.id})")
                }
            }
        }
    }

    fun applyStrokeWidthToSelection(w: Double) {
        if (selection.isEmpty()) return
        commit("stroke-width") { batch ->
            for (el in selection) {
                batch.touch(el)
                StyleUtil.setProperty(el, "stroke-width", dev.svgpro.core.util.Numbers.format(w))
            }
        }
    }

    fun applyStrokeCapToSelection(cap: String) {
        if (selection.isEmpty()) return
        commit("stroke-linecap") { batch ->
            for (el in selection) {
                batch.touch(el)
                StyleUtil.setProperty(el, "stroke-linecap", cap)
            }
        }
    }

    fun applyStrokeJoinToSelection(join: String) {
        if (selection.isEmpty()) return
        commit("stroke-linejoin") { batch ->
            for (el in selection) {
                batch.touch(el)
                StyleUtil.setProperty(el, "stroke-linejoin", join)
            }
        }
    }

    fun applyDashToSelection(dash: List<Double>?) {
        if (selection.isEmpty()) return
        commit("stroke-dasharray") { batch ->
            for (el in selection) {
                batch.touch(el)
                if (dash == null || dash.isEmpty()) {
                    StyleUtil.setProperty(el, "stroke-dasharray", "none")
                } else {
                    StyleUtil.setProperty(el, "stroke-dasharray", dash.joinToString(",") { dev.svgpro.core.util.Numbers.format(it) })
                }
            }
        }
    }

    fun applyOpacityToSelection(opacity: Double) {
        if (selection.isEmpty()) return
        commit("opacity") { batch ->
            for (el in selection) {
                batch.touch(el)
                StyleUtil.setProperty(el, "opacity", dev.svgpro.core.util.Numbers.format(opacity.coerceIn(0.0, 1.0)))
            }
        }
    }

    // ―――― 要素生成 ――――

    fun findParent(el: Element): Element? {
        // Traceキャッシュを優先（未登録なら走査）
        trace.parentOf(el)?.let { return it }
        var found: Element? = null
        doc.root.walk { e -> if (found == null && e.children.contains(el)) found = e }
        return found
    }

    /** 新要素をルート直下（または現在のレイヤー）へ追加し、選択する */
    fun addElement(el: Element, label: String) {
        val layer = currentLayer()
        val parent = layer ?: doc.root
        val cs = ChangeSet(listOf(Attach(el, parent, parent.children.size, doc.root, label)), label)
        cs.apply()
        undoStack.push(cs)
        selection.clear()
        selection.add(el)
        notifyChanged()
        notifySelection()
    }

    fun currentLayer(): Element? {
        // 選択要素のルート直下の親gをレイヤーとする。無ければ null（root直下）
        if (selection.isNotEmpty()) {
            var el = selection.first()
            while (true) {
                val p = findParent(el) ?: return null
                if (p === doc.root) return if (el.tag == "g") el else null
                el = p
            }
        }
        return null
    }

    fun createLayer(name: String): Element {
        val g = Element("g")
        g.setAttr("id", name)
        val cs = ChangeSet(listOf(Attach(g, doc.root, doc.root.children.size, doc.root, "new layer")), "new layer")
        cs.apply()
        undoStack.push(cs)
        notifyChanged()
        return g
    }

    fun deleteElement(el: Element) {
        val parent = findParent(el) ?: return
        val cs = ChangeSet(listOf(Detach(el, parent, parent.children.indexOf(el), "delete")), "delete")
        cs.apply()
        selection.remove(el)
        undoStack.push(cs)
        updateNodeSession()
        notifyChanged()
        notifySelection()
    }

    /** 新規図形要素のスタイル適用 */
    fun applyCurrentStyle(el: Element) {
        when (val f = curFill) {
            is Paint.None -> StyleUtil.setProperty(el, "fill", "none")
            is Paint.Solid -> StyleUtil.setProperty(el, "fill", Color.css(f.argb))
            is Paint.Server -> StyleUtil.setProperty(el, "fill", "url(#${f.id})")
        }
        when (val s = curStroke) {
            is Paint.None -> {}
            is Paint.Solid -> {
                StyleUtil.setProperty(el, "stroke", Color.css(s.argb))
                StyleUtil.setProperty(el, "stroke-width", dev.svgpro.core.util.Numbers.format(curStrokeWidth))
            }
            is Paint.Server -> {
                StyleUtil.setProperty(el, "stroke", "url(#${s.id})")
                StyleUtil.setProperty(el, "stroke-width", dev.svgpro.core.util.Numbers.format(curStrokeWidth))
            }
        }
    }

    // ―――― 図形作成（ドラッグ確定時） ――――

    fun createShape(kind: ToolType, startUser: Vec, endUser: Vec) {
        val el = buildShapeElement(kind, startUser, endUser) ?: return
        applyCurrentStyle(el)
        addElement(el, "draw ${kind.name.lowercase()}")
    }

    fun buildShapeElement(kind: ToolType, startUser: Vec, endUser: Vec): Element? {
        val x = minOf(startUser.x, endUser.x)
        val y = minOf(startUser.y, endUser.y)
        val w = abs(endUser.x - startUser.x)
        val h = abs(endUser.y - startUser.y)
        val n = dev.svgpro.core.util.Numbers
        return when (kind) {
            ToolType.RECT -> Element("rect").apply {
                setAttr("x", n.format(x)); setAttr("y", n.format(y))
                setAttr("width", n.format(w)); setAttr("height", n.format(h))
            }
            ToolType.RRECT -> Element("rect").apply {
                setAttr("x", n.format(x)); setAttr("y", n.format(y))
                setAttr("width", n.format(w)); setAttr("height", n.format(h))
                setAttr("rx", n.format(minOf(w, h) * 0.2)); setAttr("ry", n.format(minOf(w, h) * 0.2))
            }
            ToolType.ELLIPSE -> Element("ellipse").apply {
                setAttr("cx", n.format(x + w / 2)); setAttr("cy", n.format(y + h / 2))
                setAttr("rx", n.format(w / 2)); setAttr("ry", n.format(h / 2))
            }
            ToolType.LINE -> Element("line").apply {
                setAttr("x1", n.format(startUser.x)); setAttr("y1", n.format(startUser.y))
                setAttr("x2", n.format(endUser.x)); setAttr("y2", n.format(endUser.y))
            }
            ToolType.POLYLINE -> Element("polyline").apply {
                setAttr("points", Shapes.pointsString(PathData.of(startUser, endUser)))
            }
            ToolType.POLYGON -> {
                val pts = regularPolygonPoints(startUser, endUser, curPolygonSides, 0.0)
                Element("polygon").apply { setAttr("points", Shapes.pointsString(PathData.polygon(pts))) }
            }
            ToolType.STAR -> {
                val pts = starPoints(startUser, endUser, curStarPoints)
                Element("polygon").apply { setAttr("points", Shapes.pointsString(PathData.polygon(pts))) }
            }
            else -> null
        }
    }

    private fun regularPolygonPoints(start: Vec, end: Vec, sides: Int, rot: Double): List<Vec> {
        val cx = (start.x + end.x) / 2
        val cy = (start.y + end.y) / 2
        val r = hypot(end.x - start.x, end.y - start.y) / 2
        val out = ArrayList<Vec>()
        for (i in 0 until sides) {
            val a = rot + Math.PI * 2 * i / sides - Math.PI / 2
            out.add(Vec(cx + r * kotlin.math.cos(a), cy + r * kotlin.math.sin(a)))
        }
        return out
    }

    private fun starPoints(start: Vec, end: Vec, points: Int): List<Vec> {
        val cx = (start.x + end.x) / 2
        val cy = (start.y + end.y) / 2
        val r = hypot(end.x - start.x, end.y - start.y) / 2
        val ri = r * 0.45
        val out = ArrayList<Vec>()
        for (i in 0 until points * 2) {
            val a = Math.PI * i / points - Math.PI / 2
            val rr = if (i % 2 == 0) r else ri
            out.add(Vec(cx + rr * kotlin.math.cos(a), cy + rr * kotlin.math.sin(a)))
        }
        return out
    }

    // ―――― パス操作（高度） ――――

    fun convertToPath(elems: List<Element>): Boolean {
        var changed = false
        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        val newSel = ArrayList<Element>()
        for (el in elems) {
            if (!Shapes.isPrimitive(el) && el.tag != "text") continue
            val pd = if (el.tag == "text") {
                textToPathData(el) ?: continue
            } else {
                Shapes.geometryOf(el) ?: continue
            }
            val abs = trace.absOf(el)
            pd.transform(abs)
            val parent = findParent(el) ?: continue
            val idx = parent.children.indexOf(el)
            val p = Element("path")
            for ((k, v) in el.attrs) {
                if (k in setOf("x", "y", "cx", "cy", "r", "rx", "ry", "width", "height", "x1", "x2", "y1", "y2", "points", "d", "font-family", "font-size", "font-weight", "font-style", "text-anchor")) continue
                p.attrs[k] = v
            }
            p.setAttr("d", pd.write())
            changes.add(Attach(p, parent, idx, doc.root, "convert"))
            changes.add(Detach(el, parent, idx, "convert"))
            newSel.add(p)
            changed = true
        }
        if (changed) {
            val cs = ChangeSet(changes, "convert to path", newSel)
            cs.apply()
            selection.clear()
            selection.addAll(newSel)
            undoStack.push(cs)
            invalidateTrace()
            updateNodeSession()
            notifyChanged()
            notifySelection()
        }
        return changed
    }

    /** テキスト→パス（プラットフォーム実装へ委譲。未登録ならnull） */
    var textToPathProvider: ((Element) -> PathData?)? = null

    private fun textToPathData(el: Element): PathData? = textToPathProvider?.invoke(el)

    fun booleanOp(op: GeometryOpKind) {
        val targets = selection.filter { PathData.parse(it.attr("d")) != null || Shapes.isPrimitive(it) }
        if (targets.size < 2) return
        val geos = ArrayList<Pair<Element, PathData>>()
        for (el in targets) {
            val pd = (Shapes.geometryOf(el) ?: continue).clone()
            pd.transform(trace.absOf(el))
            geos.add(el to pd)
        }
        if (geos.size < 2) return
        var acc = geos[0].second
        for (i in 1 until geos.size) {
            val op2 = when (op) {
                GeometryOpKind.UNION -> dev.svgpro.core.ops.GeometryOps.BoolOp.UNION
                GeometryOpKind.DIFFERENCE -> dev.svgpro.core.ops.GeometryOps.BoolOp.DIFFERENCE
                GeometryOpKind.INTERSECTION -> dev.svgpro.core.ops.GeometryOps.BoolOp.INTERSECTION
                GeometryOpKind.XOR -> dev.svgpro.core.ops.GeometryOps.BoolOp.XOR
            }
            acc = dev.svgpro.core.ops.GeometryOps.boolean(acc, geos[i].second, op2, 0.02 / zoom.coerceAtLeast(0.01)) ?: run {
                acc
            }
        }
        if (acc.isEmpty()) return
        val first = geos.first().first
        val parent = findParent(first) ?: doc.root
        val insertAt = parent.children.indexOf(first).coerceAtLeast(0)
        val result = Element("path")
        for ((k, v) in first.attrs) {
            if (k == "d" || k == "transform" || k == "points" || k == "x" || k == "y" || k == "width" || k == "height" ||
                k == "cx" || k == "cy" || k == "r" || k == "rx" || k == "ry" || k == "x1" || k == "x2" || k == "y1" || k == "y2"
            ) continue
            result.attrs[k] = v
        }
        result.setAttr("d", acc.write())
        result.setAttr("fill-rule", "evenodd")
        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        for ((el, _) in geos) {
            val p = findParent(el) ?: continue
            changes.add(Detach(el, p, p.children.indexOf(el), op.name.lowercase()))
        }
        changes.add(Attach(result, parent, insertAt, doc.root, op.name.lowercase()))
        val cs = ChangeSet(changes, op.name.lowercase(), listOf(result))
        cs.apply()
        selection.clear()
        selection.add(result)
        undoStack.push(cs)
        invalidateTrace()
        updateNodeSession()
        notifyChanged()
        notifySelection()
    }

    fun offsetPath(dist: Double) {
        val el = selection.firstOrNull() ?: return
        val pd = (Shapes.geometryOf(el) ?: return).clone()
        pd.transform(trace.absOf(el))
        val res = dev.svgpro.core.ops.GeometryOps.offset(pd, dist, 0.02 / zoom.coerceAtLeast(0.01)) ?: return
        replaceWithPathElement(el, res, "offset")
    }

    fun simplifyPath(tol: Double) {
        val el = selection.firstOrNull() ?: return
        val pd = (Shapes.geometryOf(el) ?: return).clone()
        pd.transform(trace.absOf(el))
        val res = dev.svgpro.core.ops.GeometryOps.simplify(pd, tol)
        replaceWithPathElement(el, res, "simplify")
    }

    fun smoothPath() {
        // 単純化してスムージング（軽い平滑化）
        simplifyPath(0.6 / zoom.coerceAtLeast(0.01))
    }

    internal fun replaceWithPathElement(old: Element, pd: PathData, label: String) {
        val parent = findParent(old) ?: doc.root
        val idx = parent.children.indexOf(old).coerceAtLeast(0)
        val p = Element("path")
        for ((k, v) in old.attrs) {
            if (k in setOf("d", "transform", "points", "x", "y", "width", "height", "cx", "cy", "r", "rx", "ry", "x1", "x2", "y1", "y2")) continue
            p.attrs[k] = v
        }
        p.setAttr("d", pd.write())
        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        changes.add(Attach(p, parent, idx, doc.root, label))
        changes.add(Detach(old, parent, idx, label))
        val cs = ChangeSet(changes, label, listOf(p))
        cs.apply()
        selection.clear()
        selection.add(p)
        undoStack.push(cs)
        invalidateTrace()
        updateNodeSession()
        notifyChanged()
        notifySelection()
    }

    fun reversePath() {
        val el = selection.firstOrNull() ?: return
        val pd = PathData.parse(el.attr("d")) ?: return
        commit("reverse") { batch ->
            batch.touch(el)
            el.setAttr("d", pd.reversed().write())
        }
        nodeSession = null
        updateNodeSession()
    }

    fun joinPaths() {
        // 複数のpath要素を1つのpathのサブパスへ統合
        val targets = selection.filter { PathData.parse(it.attr("d")) != null }
        if (targets.size < 2) return
        val segs = ArrayList<Seg>()
        for (el in targets) {
            val pd = PathData.parse(el.attr("d")) ?: continue
            val abs = trace.absOf(el)
            pd.transform(abs)
            segs.addAll(pd.segs)
        }
        val first = targets.first()
        val parent = findParent(first) ?: doc.root
        val insertAt = parent.children.indexOf(first).coerceAtLeast(0)
        val result = Element("path")
        for ((k, v) in first.attrs) {
            if (k == "d" || k == "transform") continue
            result.attrs[k] = v
        }
        result.setAttr("d", PathData(segs).write())
        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        for (el in targets) {
            val p = findParent(el) ?: continue
            changes.add(Detach(el, p, p.children.indexOf(el), "join"))
        }
        changes.add(Attach(result, parent, insertAt, doc.root, "join"))
        val cs = ChangeSet(changes, "join", listOf(result))
        cs.apply()
        selection.clear()
        selection.add(result)
        undoStack.push(cs)
        invalidateTrace()
        notifyChanged()
        notifySelection()
    }

    // ―――― スタイルの取得（パネル表示用） ――――

    fun selectionFill(): Paint? {
        val el = selection.firstOrNull() ?: return null
        return inheritedPaint(el, "fill")
    }

    fun selectionStroke(): Paint? {
        val el = selection.firstOrNull() ?: return null
        return inheritedPaint(el, "stroke")
    }

    fun selectionOpacity(): Double {
        val el = selection.firstOrNull() ?: return 1.0
        return StyleUtil.opacity(el)
    }

    fun selectionStrokeWidth(): Double {
        val el = selection.firstOrNull() ?: return 1.0
        return dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(el, "stroke-width")) ?: 1.0
    }

    fun inheritedPaint(el: Element, prop: String): Paint {
        var cur: Element? = el
        while (cur != null) {
            val v = StyleUtil.getProperty(cur, prop)
            if (v != null) {
                val p = Paint.parse(v)
                if (p != null) return p
                return when (v.trim().lowercase()) {
                    "none" -> Paint.None
                    else -> Paint.Solid(0xFF000000.toInt())
                }
            }
            cur = findParent(cur)
        }
        return if (prop == "fill") Paint.Solid(0xFF000000.toInt()) else Paint.None
    }
}

enum class GeometryOpKind { UNION, DIFFERENCE, INTERSECTION, XOR }

/** Vecの変換ヘルパ（行列をdeltaに適用） */
internal fun Matrix.transformVec(v: Vec): Vec {
    // 線形成分のみ（平行移動なし）
    return Vec(a * v.x + c * v.y, b * v.x + d * v.y)
}
