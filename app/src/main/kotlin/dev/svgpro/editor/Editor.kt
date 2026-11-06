package dev.svgpro.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import dev.svgpro.core.animation.AnimationTrack
import dev.svgpro.core.animation.BezierEase
import dev.svgpro.core.animation.EaseType
import dev.svgpro.core.animation.Keyframe
import dev.svgpro.core.animation.KeyframeEngine
import dev.svgpro.core.animation.Marker
import dev.svgpro.core.animation.MotionPath
import dev.svgpro.core.svg.Animation
import dev.svgpro.core.svg.AnimationKind
import dev.svgpro.core.svg.AnimationState
import dev.svgpro.core.svg.AttributeType
import dev.svgpro.core.svg.CalcMode
import dev.svgpro.core.svg.FillMode
import dev.svgpro.core.svg.activeEndSeconds
import dev.svgpro.core.svg.animationsFromElement
import dev.svgpro.core.svg.animationToElement
import dev.svgpro.core.svg.computeTransform
import dev.svgpro.core.svg.computeValue
import dev.svgpro.core.svg.writeAnimationsToTree
import dev.svgpro.core.svg.Element
import dev.svgpro.core.svg.SvgLoader
import dev.svgpro.core.svg.SvgOptimizer
import dev.svgpro.core.svg.SvgWriter
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/** ツール種別 */
enum class ToolType {
    SELECT, NODE, PEN, FREEHAND,
    RECT, RRECT, ELLIPSE, LINE, POLYGON, STAR, POLYLINE,
    ARC, SECTOR, SPIRAL,
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

    /**
     * UIから直接読まれるフラグはSnapshot Stateで保持する。
     * Composeが読み取りを購読するため、refreshカウンタの伝播に頼らず
     * strong skipping下でも確実に再構成される（ツールハイライト・トグル・dirty表示）。
     */
    var dirty by mutableStateOf(false)
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
    private val toolState = mutableStateOf(ToolType.SELECT)

    var tool: ToolType
        get() = toolState.value
        set(value) {
            val old = toolState.value
            toolState.value = value
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

    /**
     * ペン継続対象の既存パス（端点からの続き描き）。null=新規パス作成中。
     * 確定時は新要素を作らず、対象の d 属性を更新する。
     */
    var penContinueTarget: Element? = null

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
        val target = penContinueTarget
        penContinueTarget = null
        if (target != null) {
            // 既存パスの端点からの続き描き: 新要素を作らず d を更新する
            if (draft.segs.size >= 2) {
                commit("extend path") { batch ->
                    batch.touch(target)
                    target.setAttr("d", draft.write())
                }
                select(target)
            } else {
                onChanged()
            }
        } else if (draft.segs.size >= 2) {
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
            penContinueTarget = null
            onChanged()
        }
    }

    /** フリーハンドのポイント列 */
    var freehandDraft: MutableList<Vec>? = null
    /** 図形ドラッグ中の draft (start, current, kind) */
    var shapeDraft: Triple<Vec, Vec, ToolType>? = null

    var nodeSession: NodeSession? = null

    // ―――― 設定 ――――
    /** グリッド間隔（user単位）。UIから変更可能。ルーラー目盛りと連動する。 */
    var gridSize by mutableStateOf(10.0)
    var showGrid by mutableStateOf(false)
    var showPixelGrid by mutableStateOf(false)
    var showOutlineMode by mutableStateOf(false)
    var showRulers by mutableStateOf(false)
    var snapToGrid by mutableStateOf(false)
    var snapToObject = true
    /** ルーラーからガイド作成/移動時にグリッドへスナップする（Shift等で格子に吸着） */
    var snapGuidesToGrid = true
    var penOnly by mutableStateOf(false) // スタイラスのみで作図（指はパン/ズーム）。UIトグルから読まれる
    var usePressure = true

    /** カスタムキーボードショートカット（アクション名 -> keyCode、Ctrl修飾子前提） */
    val customKeyBindings = mutableMapOf<String, Int>()

    fun setCustomShortcut(action: String, keyCode: Int) {
        customKeyBindings[action] = keyCode
        notifyChanged()
    }

    // ―――― 閲覧専用モード（カテゴリ17） ――――

    /** 閲覧専用モード。true の場合、編集操作（変形・スタイル変更・削除等）を無効化する。 */
    var viewOnlyMode = false
        private set

    fun setViewOnlyMode(enabled: Boolean) {
        viewOnlyMode = enabled
        notifyChanged()
    }

    /** 編集が許可されるか。閲覧専用モード時またはアニメーション再生時は false。 */
    fun isEditable(): Boolean = !viewOnlyMode && !isKeyframePlaying

    // ―――― アニメーションモード（カテゴリ14: SVGアニメーション対応） ――――

    /** アニメーション編集モード。trueの場合、UIはアニメーション専用パネルに切り替わる。 */
    var animationMode = false
        private set

    fun setAnimationMode(enabled: Boolean) {
        animationMode = enabled
        if (enabled) {
            loadAnimationsFromDocument()
        }
        notifyChanged()
    }

    /** アニメーション一覧（ドキュメントレベル） */
    val animations = mutableListOf<Animation>()

    /** アニメーション再生状態 */
    var animationState by mutableStateOf(AnimationState())
        private set

    /** アニメーション再生中か */
    val isAnimating: Boolean
        get() = animationState.isPlaying

    /** アニメーション再生/一時停止を切り替える */
    fun toggleAnimation() {
        if (animationState.isPlaying) {
            animationState = animationState.copy(isPlaying = false)
        } else {
            animationState = animationState.copy(isPlaying = true, currentTime = animationState.currentTime)
        }
        notifyChanged()
    }

    /** アニメーションを停止する */
    fun stopAnimation() {
        animationState = AnimationState(animations = animations, currentTime = 0.0, isPlaying = false)
        notifyChanged()
    }

    /** アニメーション進捗をシーク（0〜1） */
    fun seekAnimation(progress: Double) {
        val dur = animationState.duration.coerceAtLeast(0.001)
        animationState = animationState.copy(
            currentTime = progress * dur,
            isPlaying = false,
        )
        applyAnimationAt(animationState.currentTime)
        notifyChanged()
    }

    /** ドキュメントからアニメーション要素を読み込む */
    fun loadAnimationsFromDocument() {
        animations.clear()
        animations.addAll(animationsFromElement(doc.root))
        val dur = animations.map { activeEndSeconds(it) }.maxOrNull() ?: 0.0
        animationState = AnimationState(
            animations = animations.toList(),
            currentTime = 0.0,
            isPlaying = false,
            duration = dur,
        )
        notifyChanged()
    }

    /** アニメーション定義をドキュメントに書き戻す（保存前） */
    fun saveAnimationsToDocument() {
        writeAnimationsToTree(animations, doc.root)
        notifyChanged()
    }

    /** 選択要素に新しいアニメーションを追加する */
    fun addAnimationToSelection(tag: String, attributeName: String?) {
        val target = selection.firstOrNull() ?: return
        val targetId = target.id ?: return
        val kind = when (tag) {
            "animate" -> AnimationKind.ANIMATE
            "animateTransform" -> AnimationKind.ANIMATE_TRANSFORM
            "animateMotion" -> AnimationKind.ANIMATE_MOTION
            "set" -> AnimationKind.SET
            else -> AnimationKind.ANIMATE
        }
        val anim = Animation(
            kind = kind,
            targetRef = targetId,
            attributeName = attributeName ?: "",
            dur = "1s",
            fill = FillMode.REMOVE,
            calcMode = CalcMode.LINEAR,
            transformType = if (tag == "animateTransform") "translate" else null,
        )
        animations.add(anim)
        saveAnimationsToDocument()
        val dur = animations.map { activeEndSeconds(it) }.maxOrNull() ?: 0.0
        animationState = animationState.copy(
            animations = animations.toList(),
            duration = dur,
        )
        notifyChanged()
    }

    /** アニメーションを削除する */
    fun removeAnimation(id: String) {
        animations.removeAll { it.id == id }
        saveAnimationsToDocument()
        val dur = animations.map { activeEndSeconds(it) }.maxOrNull() ?: 0.0
        animationState = animationState.copy(
            animations = animations.toList(),
            duration = dur,
        )
        notifyChanged()
    }

    /** アニメーション属性を更新する */
    fun updateAnimation(id: String, build: (Animation.Builder) -> Unit) {
        val idx = animations.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = animations[idx]
        val builder = Animation.Builder(old)
        build(builder)
        val updated = builder.build()
        animations[idx] = updated
        saveAnimationsToDocument()
        val dur = animations.map { activeEndSeconds(it) }.maxOrNull() ?: 0.0
        animationState = animationState.copy(
            animations = animations.toList(),
            duration = dur,
        )
        notifyChanged()
    }

    /** 指定時刻におけるアニメーション値を適用（描画反映用） */
    private fun applyAnimationAt(seconds: Double) {
        for (anim in animations) {
            val targetRef = anim.targetRef
            val target = if (targetRef != null) doc.root.findById(targetRef) else null
            if (target == null) continue
            when (anim.kind) {
                AnimationKind.ANIMATE_TRANSFORM -> {
                    computeTransform(anim, seconds)?.let { (_, matrix) ->
                        target.setAttr("transform", dev.svgpro.core.geom.Matrix.toSvgString(matrix))
                    }
                }
                else -> {
                    computeValue(anim, seconds)?.let { (phase, value) ->
                        when (value) {
                            is dev.svgpro.core.svg.AnimValue.Number ->
                                target.setAttr(anim.attributeName, dev.svgpro.core.util.Numbers.format(value.v))
                            is dev.svgpro.core.svg.AnimValue.Color ->
                                target.setAttr(anim.attributeName, dev.svgpro.core.model.Color.css(value.argb))
                            is dev.svgpro.core.svg.AnimValue.String -> {
                                val strVal = (value as dev.svgpro.core.svg.AnimValue.String).s
                                target.setAttr(anim.attributeName, strVal)
                            }
                            is dev.svgpro.core.svg.AnimValue.Transform ->
                                target.setAttr("transform", dev.svgpro.core.geom.Matrix.toSvgString(value.matrix))
                        }
                    }
                }
            }
        }
    }

    /** アニメーション要素のビルダー（Animationのコピー補助） */
    class Builder private constructor(
        private var kind: AnimationKind,
        private var targetRef: String?,
        private var attributeName: String,
        private var attributeType: AttributeType,
        private var begin: String,
        private var dur: String,
        private var end: String?,
        private var min: String?,
        private var max: String?,
        private var repeatCount: String?,
        private var repeatDur: String?,
        private var fill: FillMode,
        private var calcMode: CalcMode,
        private var from: String?,
        private var to: String?,
        private var by: String?,
        private var values: String?,
        private var keyTimes: String?,
        private var keySplines: String?,
        private var additive: String,
        private var accumulate: String,
        private var transformType: String?,
        private var motionPath: String?,
    ) {
        constructor(old: Animation) : this(
            old.kind, old.targetRef, old.attributeName, old.attributeType, old.begin, old.dur,
            old.end, old.min, old.max, old.repeatCount, old.repeatDur, old.fill, old.calcMode,
            old.from, old.to, old.by, old.values, old.keyTimes, old.keySplines,
            old.additive, old.accumulate, old.transformType, old.motionPath,
        )

        fun kind(v: AnimationKind) = apply { kind = v }
        fun targetRef(v: String?) = apply { targetRef = v }
        fun attributeName(v: String) = apply { attributeName = v }
        fun attributeType(v: AttributeType) = apply { attributeType = v }
        fun begin(v: String) = apply { begin = v }
        fun dur(v: String) = apply { dur = v }
        fun end(v: String?) = apply { end = v }
        fun fill(v: FillMode) = apply { fill = v }
        fun calcMode(v: CalcMode) = apply { calcMode = v }
        fun from(v: String?) = apply { from = v }
        fun to(v: String?) = apply { to = v }
        fun by(v: String?) = apply { by = v }
        fun values(v: String?) = apply { values = v }
        fun keyTimes(v: String?) = apply { keyTimes = v }
        fun keySplines(v: String?) = apply { keySplines = v }
        fun transformType(v: String?) = apply { transformType = v }

        fun build() = Animation(
            kind = kind, targetRef = targetRef, attributeName = attributeName,
            attributeType = attributeType, begin = begin, dur = dur, end = end,
            min = min, max = max, repeatCount = repeatCount, repeatDur = repeatDur,
            fill = fill, calcMode = calcMode, from = from, to = to, by = by,
            values = values, keyTimes = keyTimes, keySplines = keySplines,
            additive = additive, accumulate = accumulate,
            transformType = transformType, motionPath = motionPath,
        )
    }

    // ―――― キーフレームアニメーション（カテゴリ14: SVGアニメーション対応） ――――

    /** キーフレームアニメーション補間エンジン */
    private val keyframeEngine = KeyframeEngine()

    /** アニメーショントラック一覧（オブジェクト × 時刻 × 属性値） */
    val animationTracks = mutableListOf<AnimationTrack>()

    /** キーフレームアニメーション再生位置（ミリ秒） */
    var keyframeTimeMs by mutableStateOf(0L)
        private set

    /** キーフレームアニメーション再生中か */
    var isKeyframePlaying by mutableStateOf(false)
        private set

    /** キーフレームアニメーションのトータル時間（ミリ秒） */
    var keyframeDurationMs by mutableStateOf(2000L)
        private set

    /** 一時的なアニメーションオーバーライド（描画時のみ要素属性を上書き、描画後に復元） */
    private val animationBackup = mutableMapOf<Element, LinkedHashMap<String, String?>>()

    /** キーフレームアニメーションを再生/一時停止 */
    fun toggleKeyframeAnimation() {
        isKeyframePlaying = !isKeyframePlaying
        notifyChanged()
    }

    /** キーフレームアニメーションを停止し、最初に戻す */
    fun stopKeyframeAnimation() {
        isKeyframePlaying = false
        keyframeTimeMs = 0
        clearAnimationOverrides()
        notifyChanged()
    }

    /** キーフレームアニメーションをシーク（ミリ秒） */
    fun seekKeyframeAnimation(timeMs: Long) {
        keyframeTimeMs = timeMs.coerceIn(0, keyframeDurationMs)
        // アニメーション再生中はUndo/Trace再構築・dirtyフラグを立てない（毎フレーム呼ばれるため軽量化）。
        // CanvasView.onDraw 側で applyAnimationAt / clearAnimationOverrides を行う。
    }

    /** ドキュメントからキーフレームトラックを読み込む（data-svgpro-keyframes 属性） */
    fun loadKeyframesFromDocument() {
        animationTracks.clear()
        val s = doc.root.attr("data-svgpro-keyframes") ?: return
        try {
            val arr = dev.svgpro.core.util.Json.parse(s) ?: return
            if (arr is List<*>) {
                for (item in arr) {
                    if (item is Map<*, *>) {
                        val id = item["id"]?.toString() ?: java.util.UUID.randomUUID().toString()
                        val targetId = item["targetId"]?.toString() ?: ""
                        val targetName = item["targetName"]?.toString() ?: ""
                        val durationMs = item["durationMs"]?.toString()?.toLongOrNull() ?: 2000L
                        val loop = (item["loop"]?.toString() ?: "false") == "true"
                        val enabled = (item["enabled"]?.toString() ?: "true") == "true"
                        var track = AnimationTrack(
                            id = id,
                            targetId = targetId,
                            targetName = targetName,
                            durationMs = durationMs,
                            loop = loop,
                            enabled = enabled,
                            parentId = item["parentId"]?.toString()?.takeIf { it.isNotEmpty() },
                            motionBlur = (item["motionBlur"]?.toString() ?: "false") == "true",
                            shutterAngle = item["shutterAngle"]?.toString()?.toDoubleOrNull() ?: 180.0,
                            timeRemap = item["timeRemap"]?.toString()?.toDoubleOrNull() ?: 1.0,
                        )
                        // モーションパス復元
                        val mp = item["motionPath"]
                        if (mp is Map<*, *>) {
                            val pd = mp["pathData"]?.toString() ?: ""
                            val md = mp["durationMs"]?.toString()?.toLongOrNull() ?: 2000L
                            val mloop = (mp["loop"]?.toString() ?: "false") == "true"
                            track = track.copy(motionPath = MotionPath(pathData = pd, durationMs = md, loop = mloop))
                        }
                        val kfs = item["keyframes"]
                        if (kfs is List<*>) {
                            for (kfItem in kfs) {
                                if (kfItem is Map<*, *>) {
                                    val kfTime = kfItem["timeMs"]?.toString()?.toLongOrNull() ?: 0L
                                    val (easeType, bezier) = Keyframe.easeFromString(kfItem["easing"]?.toString())
                                    val props = LinkedHashMap<String, String>()
                                    val kfProps = kfItem["properties"]
                                    if (kfProps is Map<*, *>) {
                                        for ((k, v) in kfProps) {
                                            props[k.toString()] = v.toString()
                                        }
                                    }
                                    track.keyframes.add(Keyframe(timeMs = kfTime, properties = props, easeType = easeType, bezier = bezier))
                                }
                            }
                        }
                        // マーカー復元
                        val mkrs = item["markers"]
                        if (mkrs is List<*>) {
                            for (mItem in mkrs) {
                                if (mItem is Map<*, *>) {
                                    val mTime = mItem["timeMs"]?.toString()?.toLongOrNull() ?: 0L
                                    val mLabel = mItem["label"]?.toString() ?: ""
                                    val mColor = mItem["color"]?.toString() ?: "#FF0000"
                                    track.markers.add(Marker(timeMs = mTime, label = mLabel, color = mColor))
                                }
                            }
                        }
                        animationTracks.add(track)
                    }
                }
            }
        } catch (e: Exception) {
            // 解析失敗時は空のまま
        }
        recomputeKeyframeDuration()
        notifyChanged()
    }

    /** キーフレームトラックをドキュメント属性に保存（data-svgpro-keyframes） */
    fun saveKeyframesToDocument() {
        val arr = animationTracks.map { track ->
            mapOf(
                "id" to track.id,
                "targetId" to track.targetId,
                "targetName" to track.targetName,
                "durationMs" to dev.svgpro.core.util.Numbers.format(track.durationMs.toDouble()),
                "loop" to track.loop,
                "enabled" to track.enabled,
                "parentId" to track.parentId,
                "motionBlur" to track.motionBlur,
                "shutterAngle" to track.shutterAngle,
                "timeRemap" to track.timeRemap,
                "motionPath" to (if (track.motionPath != null) mapOf(
                    "pathData" to track.motionPath!!.pathData,
                    "durationMs" to dev.svgpro.core.util.Numbers.format(track.motionPath!!.durationMs.toDouble()),
                    "loop" to track.motionPath!!.loop,
                ) else null),
                "keyframes" to track.keyframes.map { kf ->
                    mapOf(
                        "timeMs" to dev.svgpro.core.util.Numbers.format(kf.timeMs.toDouble()),
                        "easing" to kf.easeToString(),
                        "properties" to kf.properties,
                    )
                },
                "markers" to track.markers.map { m ->
                    mapOf(
                        "id" to m.id,
                        "timeMs" to dev.svgpro.core.util.Numbers.format(m.timeMs.toDouble()),
                        "label" to m.label,
                        "color" to m.color,
                    )
                },
            )
        }
        doc.root.setAttr("data-svgpro-keyframes", dev.svgpro.core.util.Json.stringify(arr))
        notifyChanged()
    }

    /** トータル再生時間を再計算 */
    fun recomputeKeyframeDuration() {
        val max = animationTracks.mapNotNull { track ->
            track.durationMs.takeIf { track.enabled }
        }.maxOrNull() ?: 2000L
        keyframeDurationMs = max
    }

    /** 選択オブジェクトにキーフレームトラックを新規作成（または既存トラックにキーフレーム追加） */
    fun addKeyframeForSelection(timeMs: Long = 0) {
        val target = selection.firstOrNull() ?: return
        val targetId = target.id ?: return
        val targetName = labelFor(target)

        // 既存トラックを探す
        var track = animationTracks.firstOrNull { it.targetId == targetId }
        if (track == null) {
            track = AnimationTrack(targetId = targetId, targetName = targetName)
            animationTracks.add(track)
        }

        // 現在のオブジェクト属性をスナップショット
        val props = LinkedHashMap<String, String>()
        // 位置（transform から x,y を抽出、または x/y 属性）
        val x = target.attr("x") ?: "0"
        val y = target.attr("y") ?: "0"
        val transform = target.attr("transform") ?: ""
        val fill = target.attr("fill") ?: ""
        val opacity = target.attr("opacity") ?: "1"
        val strokeWidth = target.attr("stroke-width") ?: "1"
        props["x"] = x
        props["y"] = y
        props["transform"] = transform
        props["fill"] = fill
        props["opacity"] = opacity
        props["stroke-width"] = strokeWidth

        // 同時刻のキーフレームがあれば更新、なければ追加
        val existing = track.keyframes.firstOrNull { it.timeMs == timeMs }
        if (existing != null) {
            val idx = track.keyframes.indexOf(existing)
            track.keyframes[idx] = Keyframe(timeMs = timeMs, properties = props, easeType = existing.easeType, bezier = existing.bezier)
        } else {
            track.keyframes.add(Keyframe(timeMs = timeMs, properties = props, easeType = EaseType.LINEAR))
            track.keyframes.sortBy { it.timeMs }
        }

        recomputeKeyframeDuration()
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** トラックを削除 */
    fun removeKeyframeTrack(trackId: String) {
        animationTracks.removeAll { it.id == trackId }
        recomputeKeyframeDuration()
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** トラックの有効/無効を切替 */
    fun toggleKeyframeTrackEnabled(trackId: String) {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return
        val idx = animationTracks.indexOf(track)
        animationTracks[idx] = track.copy(enabled = !track.enabled)
        recomputeKeyframeDuration()
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** トラックのループ設定を切替 */
    fun toggleKeyframeTrackLoop(trackId: String) {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return
        val idx = animationTracks.indexOf(track)
        animationTracks[idx] = track.copy(loop = !track.loop)
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** キーフレームのイージングを変更 */
    fun setKeyframeEasing(trackId: String, timeMs: Long, easing: String) {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return
        val kf = track.keyframes.firstOrNull { it.timeMs == timeMs } ?: return
        val (easeType, bezier) = Keyframe.easeFromString(easing)
        val idx = track.keyframes.indexOf(kf)
        track.keyframes[idx] = kf.copy(easeType = easeType, bezier = bezier)
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** キーフレームを削除 */
    fun removeKeyframe(trackId: String, timeMs: Long) {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return
        track.keyframes.removeAll { it.timeMs == timeMs }
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** アニメーションオーバーライドを元に戻す（描画後） */
    fun clearAnimationOverrides() {
        for ((el, backup) in animationBackup) {
            for ((k, v) in backup) {
                el.setAttr(k, v)
            }
        }
        animationBackup.clear()
    }

    /** 親トラックのIDリストを再帰的に取得（循環参照防止付き） */
    private fun resolveParentChain(trackId: String, visited: MutableSet<String> = mutableSetOf()): List<String> {
        if (visited.contains(trackId)) return emptyList() // 循環参照防止
        visited.add(trackId)
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return emptyList()
        return track.parentId?.let { parentId ->
            listOf(parentId) + resolveParentChain(parentId, visited)
        } ?: emptyList()
    }

    /** 指定トラックにおける親の合成変換行列を取得（親の transform を乗算） */
    private fun getParentTransformMatrix(trackId: String, timeMs: Long): Matrix? {
        val chain = resolveParentChain(trackId)
        if (chain.isEmpty()) return null
        var combined = Matrix.IDENTITY
        for (parentId in chain) {
            val parentTrack = animationTracks.firstOrNull { it.id == parentId } ?: continue
            val target = doc.root.findById(parentTrack.targetId) ?: continue
            val parentTransformStr = target.attr("transform")
            if (parentTransformStr.isNullOrBlank()) continue
            val parentMatrix = Matrix.fromSvgTransform(parentTransformStr)
            combined = combined.then(parentMatrix)
        }
        return if (combined == Matrix.IDENTITY) null else combined
    }

    /**
     * 指定時刻におけるアニメーション値を要素に一時適用（描画前）。
     * 親子関係（parentId）に対応：親の transform を子に合成して適用する。
     */
    fun applyAnimationAt(timeMs: Long) {
        animationBackup.clear()
        // 1. 全トラックの値を事前計算
        val trackValues = mutableMapOf<String, Map<String, String>>()
        for (track in animationTracks) {
            if (!track.enabled) continue
            val values = keyframeEngine.getValuesAt(track, timeMs)
            if (values.isNotEmpty()) {
                trackValues[track.id] = values
            }
        }
        // 2. 親子関係を考慮して適用（親から子へ順に）
        for (track in animationTracks) {
            if (!track.enabled) continue
            val target = doc.root.findById(track.targetId) ?: continue
            val values = trackValues[track.id] ?: continue
            val backup = animationBackup.getOrPut(target) { LinkedHashMap() }
            for ((k, v) in values) {
                if (!backup.containsKey(k)) {
                    backup[k] = target.attr(k)
                }
                if (k == "transform") {
                    // 親の変換を合成
                    val parentMatrix = getParentTransformMatrix(track.id, timeMs)
                    if (parentMatrix != null) {
                        val childMatrix = Matrix.fromSvgTransform(v)
                        val composed = parentMatrix.then(childMatrix)
                        target.setAttr(k, Matrix.toSvgString(composed))
                    } else {
                        target.setAttr(k, v)
                    }
                } else {
                    target.setAttr(k, v)
                }
            }
        }
    }

    /** モーションブラー用：指定トラックの速度ベクトルを取得（ピクセル/秒） */
    fun getTrackVelocity(trackId: String, timeMs: Long): Pair<Double, Double>? {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return null
        return keyframeEngine.getVelocity(track, timeMs)
    }

    /** タイムリマッピング適用後の時刻を取得 */
    fun getRemappedTime(trackId: String, timeMs: Long): Long {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return timeMs
        if (track.timeRemap == 0.0) return timeMs
        return (timeMs.toDouble() / track.timeRemap.coerceAtLeast(0.001)).toLong()
    }

    /** マーカーをトラックに追加 */
    fun addMarker(trackId: String, timeMs: Long, label: String, color: String = "#FF0000") {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return
        val idx = animationTracks.indexOf(track)
        val newMarkers = mutableListOf<Marker>()
        newMarkers.addAll(track.markers)
        newMarkers.add(Marker(timeMs = timeMs, label = label, color = color))
        animationTracks[idx] = track.copy(markers = newMarkers)
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** マーカーを削除 */
    fun removeMarker(trackId: String, markerId: String) {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return
        val idx = animationTracks.indexOf(track)
        val newMarkers = mutableListOf<Marker>()
        newMarkers.addAll(track.markers.filter { it.id != markerId })
        animationTracks[idx] = track.copy(markers = newMarkers)
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** トラックのモーションブラー設定を切替 */
    fun toggleKeyframeTrackMotionBlur(trackId: String) {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return
        val idx = animationTracks.indexOf(track)
        animationTracks[idx] = track.copy(motionBlur = !track.motionBlur)
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** トラックのタイムリマッピングを設定 */
    fun setKeyframeTrackTimeRemap(trackId: String, remap: Double) {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return
        val idx = animationTracks.indexOf(track)
        animationTracks[idx] = track.copy(timeRemap = remap.coerceIn(-10.0, 10.0))
        recomputeKeyframeDuration()
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** トラックの親子関係を設定 */
    fun setKeyframeTrackParent(trackId: String, parentId: String?) {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return
        // 循環参照チェック
        if (parentId != null) {
            val chain = resolveParentChain(parentId)
            if (chain.contains(trackId)) return // 循環参照防止
        }
        val idx = animationTracks.indexOf(track)
        animationTracks[idx] = track.copy(parentId = parentId)
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** トラックのシャッター角度を設定 */
    fun setKeyframeTrackShutterAngle(trackId: String, angle: Double) {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return
        val idx = animationTracks.indexOf(track)
        animationTracks[idx] = track.copy(shutterAngle = angle.coerceIn(0.0, 720.0))
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** トラックのモーションパスを設定 */
    fun setKeyframeTrackMotionPath(trackId: String, pathData: String, durationMs: Long) {
        val track = animationTracks.firstOrNull { it.id == trackId } ?: return
        val idx = animationTracks.indexOf(track)
        animationTracks[idx] = track.copy(
            motionPath = MotionPath(pathData = pathData, durationMs = durationMs)
        )
        recomputeKeyframeDuration()
        saveKeyframesToDocument()
        notifyChanged()
    }

    /** 対象オブジェクトの表示用ラベルを取得 */
    private fun labelFor(el: Element): String {
        val id = el.attr("id")
        if (!id.isNullOrBlank()) return id
        return when (el.tag) {
            "g" -> "グループ"
            "text" -> "テキスト \"${el.textContent().take(12)}\""
            "image" -> "画像"
            "use" -> "参照"
            else -> el.tag
        }
    }

    // ―――― コメント/注釈機能（カテゴリ17） ――――

    /** ドキュメント上のコメント/注釈。 */
    data class Comment(val id: String, val x: Double, val y: Double, val text: String, val author: String, val timestamp: Long)

    val comments = mutableListOf<Comment>()

    /** コメントを追加する。 */
    fun addComment(x: Double, y: Double, text: String, author: String = "匿名") {
        val c = Comment(uniqueId("comment"), x, y, text, author, System.currentTimeMillis())
        comments.add(c)
        saveComments()
        notifyChanged()
    }

    /** コメントを削除する。 */
    fun deleteComment(id: String) {
        comments.removeAll { it.id == id }
        saveComments()
        notifyChanged()
    }

    /** コメントのテキストを変更する。 */
    fun editComment(id: String, text: String) {
        val idx = comments.indexOfFirst { it.id == id }
        if (idx >= 0) {
            comments[idx] = comments[idx].copy(text = text)
            saveComments()
            notifyChanged()
        }
    }

    /** コメント一覧をドキュメント属性に保存（可逆）。 */
    private fun saveComments() {
        val arr = comments.mapIndexed { i, c ->
            mapOf(
                "id" to c.id,
                "x" to dev.svgpro.core.util.Numbers.format(c.x),
                "y" to dev.svgpro.core.util.Numbers.format(c.y),
                "text" to c.text,
                "author" to c.author,
                "timestamp" to c.timestamp,
            )
        }
        doc.root.setAttr("data-svgpro-comments", dev.svgpro.core.util.Json.stringify(arr))
    }

    /** コメント一覧をドキュメント属性から復元（ドキュメント読み込き時に呼ぶ）。 */
    fun restoreComments() {
        comments.clear()
        val s = doc.root.attr("data-svgpro-comments") ?: return
        try {
            val arr = dev.svgpro.core.util.Json.parse(s) ?: return
            if (arr is List<*>) {
                for (item in arr) {
                    if (item is Map<*, *>) {
                        val id = item["id"]?.toString() ?: uniqueId("comment")
                        val x = item["x"]?.toString()?.toDoubleOrNull() ?: 0.0
                        val y = item["y"]?.toString()?.toDoubleOrNull() ?: 0.0
                        val text = item["text"]?.toString() ?: ""
                        val author = item["author"]?.toString() ?: "匿名"
                        val ts = item["timestamp"]?.toString()?.toLongOrNull() ?: System.currentTimeMillis()
                        comments.add(Comment(id, x, y, text, author, ts))
                    }
                }
            }
        } catch (e: Exception) {
            // 解析失敗時は空のまま
        }
    }

    // ―――― スタイルプリセット（カテゴリ6/17） ――――

    /** 塗り・線・線幅のプリセット。 */
    data class StylePreset(
        val name: String,
        val fill: dev.svgpro.core.model.Paint,
        val stroke: dev.svgpro.core.model.Paint,
        val strokeWidth: Double,
    )

    /** スタイルプリセット一覧。 */
    val stylePresets = mutableListOf<StylePreset>()

    /** 現在の塗り・線・線幅をプリセットとして保存する。 */
    fun saveStylePreset(name: String) {
        stylePresets.removeAll { it.name == name }
        stylePresets.add(StylePreset(name, curFill, curStroke, curStrokeWidth))
        saveStylePresets()
        notifyChanged()
    }

    /** プリセットを現在の塗り・線・線幅に適用する。 */
    fun applyStylePreset(index: Int) {
        if (index in stylePresets.indices) {
            val p = stylePresets[index]
            curFill = p.fill
            curStroke = p.stroke
            curStrokeWidth = p.strokeWidth
            onChanged()
        }
    }

    /** プリセットを削除する。 */
    fun deleteStylePreset(index: Int) {
        if (index in stylePresets.indices) {
            stylePresets.removeAt(index)
            saveStylePresets()
            notifyChanged()
        }
    }

    /** スタイルプリセットをドキュメント属性に保存（可逆）。 */
    private fun saveStylePresets() {
        val arr = stylePresets.mapIndexed { i, p ->
            mapOf(
                "id" to "preset_$i",
                "name" to p.name,
                "fill" to when (p.fill) {
                    is dev.svgpro.core.model.Paint.None -> "none"
                    is dev.svgpro.core.model.Paint.Solid -> dev.svgpro.core.model.Color.css(p.fill.argb)
                    is dev.svgpro.core.model.Paint.Server -> "url(#${p.fill.id})"
                },
                "stroke" to when (p.stroke) {
                    is dev.svgpro.core.model.Paint.None -> "none"
                    is dev.svgpro.core.model.Paint.Solid -> dev.svgpro.core.model.Color.css(p.stroke.argb)
                    is dev.svgpro.core.model.Paint.Server -> "url(#${p.stroke.id})"
                },
                "strokeWidth" to dev.svgpro.core.util.Numbers.format(p.strokeWidth),
            )
        }
        doc.root.setAttr("data-svgpro-style-presets", dev.svgpro.core.util.Json.stringify(arr))
    }

    /** スタイルプリセットをドキュメント属性から復元。 */
    fun restoreStylePresets() {
        stylePresets.clear()
        val s = doc.root.attr("data-svgpro-style-presets") ?: return
        try {
            val arr = dev.svgpro.core.util.Json.parse(s) ?: return
            if (arr is List<*>) {
                for (item in arr) {
                    if (item is Map<*, *>) {
                        val name = item["name"]?.toString() ?: "プリセット"
                        val fill = parsePaint(item["fill"]?.toString())
                        val stroke = parsePaint(item["stroke"]?.toString())
                        val strokeWidth = item["strokeWidth"]?.toString()?.toDoubleOrNull() ?: 2.0
                        stylePresets.add(StylePreset(name, fill, stroke, strokeWidth))
                    }
                }
            }
        } catch (e: Exception) {
            // 解析失敗時は空のまま
        }
    }

    /** 文字列から Paint をパース（簡易）。 */
    private fun parsePaint(s: String?): dev.svgpro.core.model.Paint {
        if (s == null || s == "none") return dev.svgpro.core.model.Paint.None
        if (s.startsWith("url(#")) {
            return dev.svgpro.core.model.Paint.Server(s.substringAfter("url(#").substringBefore(")"))
        }
        val c = dev.svgpro.core.model.Color.parse(s)
        return if (c != null) dev.svgpro.core.model.Paint.Solid(c) else dev.svgpro.core.model.Paint.Solid(0xFF000000.toInt())
    }

    // キャンバスビュー回転（度、正=画面上で時計回り）。editデータの座標は不変。
    var canvasRotation by mutableStateOf(0.0)

    /** ビューをdeg度回転させる（累積）。 */
    fun rotateCanvas(deltaDeg: Double) {
        canvasRotation = ((canvasRotation + deltaDeg) % 360.0 + 360.0) % 360.0
        onZoomChanged()
        onChanged()
    }

    /** ビュー回転を絶対角度に設定する。 */
    fun setCanvasRotationDeg(deg: Double) {
        canvasRotation = ((deg % 360.0 + 360.0) % 360.0)
        onZoomChanged()
        onChanged()
    }

    /** ルーラー表示単位。 */
    enum class RulerUnit { PX, MM, CM, IN, PT }

    /** ルーラー単位（user=pxを基準にDPIで物理単位へ換算）。 */
    var rulerUnit by mutableStateOf(RulerUnit.PX)
    /** DPI（user単位→物理長さの換算基準。SVGの1ユーザ単位=1/96インチを既定とする）。 */
    var rulerDpi by mutableStateOf(96.0)

    /** user単位の長さをルーラー単位へ換算 */
    fun rulerLength(userPx: Double): Double = when (rulerUnit) {
        RulerUnit.PX -> userPx
        RulerUnit.MM -> userPx * 25.4 / rulerDpi.coerceAtLeast(1.0)
        RulerUnit.CM -> userPx * 2.54 / rulerDpi.coerceAtLeast(1.0)
        RulerUnit.IN -> userPx / rulerDpi.coerceAtLeast(1.0)
        RulerUnit.PT -> userPx * 72.0 / rulerDpi.coerceAtLeast(1.0)
    }

    /** ルーラー単位のラベル */
    fun rulerUnitLabel(): String = when (rulerUnit) {
        RulerUnit.PX -> "px"
        RulerUnit.MM -> "mm"
        RulerUnit.CM -> "cm"
        RulerUnit.IN -> "in"
        RulerUnit.PT -> "pt"
    }

    // ―――― アートボード（複数ページ管理） ――――

    /** 現在選択中のアートボードインデックス。 */
    var currentArtboardIndex by mutableStateOf(0)

    /** 現在のアートボードを返す（未設定ならnull）。 */
    fun currentArtboard(): dev.svgpro.core.model.SvgDocument.Artboard? =
        doc.artboards.getOrNull(currentArtboardIndex.coerceIn(0, doc.artboards.size - 1))

    /** アートボードを切り替え、viewBoxをその領域にフィットさせる。 */
    fun setCurrentArtboard(index: Int) {
        if (index !in doc.artboards.indices) return
        currentArtboardIndex = index
        val vb = doc.artboardViewBox(index) ?: return
        fitRect(Bounds(vb.minX, vb.minY, vb.width, vb.height))
        onChanged()
    }

    /** アートボードを追加し、viewBoxを新しい領域にフィットさせる。 */
    fun addArtboard(name: String, x: Double, y: Double, w: Double, h: Double) {
        val idx = doc.addArtboard(name, x, y, w, h)
        currentArtboardIndex = idx
        val vb = doc.artboardViewBox(idx) ?: return
        fitRect(Bounds(vb.minX, vb.minY, vb.width, vb.height))
        onChanged()
    }

    /** 現在のアートボードを削除（最後の1枚は残す）。 */
    fun removeCurrentArtboard(): Boolean {
        if (!doc.removeArtboard(currentArtboardIndex)) return false
        if (currentArtboardIndex >= doc.artboards.size) currentArtboardIndex = doc.artboards.lastIndex
        val vb = doc.artboardViewBox(currentArtboardIndex) ?: return true
        fitRect(Bounds(vb.minX, vb.minY, vb.width, vb.height))
        onChanged()
        return true
    }

    /** 現在のアートボード名を変更。 */
    fun renameCurrentArtboard(name: String) {
        doc.renameArtboard(currentArtboardIndex, name)
        onChanged()
    }

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

    /** 距離スナップ（移動を固定間隔でスナップ） */
    var snapToDistance = false
    /** 距離スナップの間隔（user単位） */
    var snapDistance = 10.0

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
    var curFontFamily = "sans-serif"
    var curFontWeight = "normal"
    var curFontStyle = "normal"

    // カラースウォッチ（選択色を登録・再利用）
    val colorSwatches = mutableStateListOf(0xFF1473E6.toInt(), 0xFF22B573.toInt(), 0xFFF5A623.toInt(), 0xFFE03E3E.toInt(), 0xFF9B5DE5.toInt())

    /** 最近使用した色（新しいものが先頭・最大12件。FigmaのRecent相当） */
    val recentColors = mutableStateListOf<Int>()

    /** 色を使用済みリストの先頭へ記録する */
    fun rememberColor(argb: Int) {
        val i = recentColors.indexOf(argb)
        if (i == 0) return
        if (i > 0) recentColors.removeAt(i)
        recentColors.add(0, argb)
        while (recentColors.size > 12) recentColors.removeAt(recentColors.lastIndex)
    }

    fun addColorSwatch(argb: Int) {
        if (argb !in colorSwatches) colorSwatches.add(argb)
        rememberColor(argb)
    }

    fun removeColorSwatch(index: Int) {
        if (index in colorSwatches.indices) colorSwatches.removeAt(index)
    }

    // ―――― グラデーション編集 ――――
    /** 現在編集中のグラデーション（GRADIENTツール使用時） */
    var gradientEdit: GradientEditState? = null

    // ―――― コールバック ――――
    var onChanged: () -> Unit = {}
    var onSelectionChanged: () -> Unit = {}
    var onZoomChanged: () -> Unit = {}
    var onDocumentReplaced: () -> Unit = {}
    var requestTextInput: ((Vec) -> Unit)? = null
    /** 既存テキスト要素の編集要求（element → ）。ダブルタップ等から呼ばれる。 */
    var requestTextEdit: ((Element) -> Unit)? = null
    /** 要素のfont-family/weight/styleに応じたTypefaceを解決するコールバック（ViewModelから登録）。 */
    var typefaceResolver: ((Element) -> android.graphics.Typeface)? = null

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
        doc.restoreArtboards()
        restoreComments()
        restoreStylePresets()
        restoreBrushPresets()
        currentArtboardIndex = 0
        keyObject = null
        lastTransformOp = null
        invalidateTrace()
        fitToPage()
        onDocumentReplaced()
        onChanged()
        // ドキュメントからアニメーション定義を読み込む
        loadAnimationsFromDocument()
        // キーフレームトラックを読み込む
        loadKeyframesFromDocument()
    }

    fun newDocument(w: Double, h: Double) {
        loadDocument(SvgDocument.create(w, h), "untitled.svg")
    }

    // ―――― 複数ドキュメント（タブ）用セッション入れ替え ――――

    /**
     * ドキュメント単位の編集セッション。タブ切替時に Editor へ退避・復元する。
     * doc と selection は同じセッションに属するため要素参照のまま保持できる。
     */
    class SessionSnapshot(
        val doc: SvgDocument,
        val name: String,
        val dirty: Boolean,
        val undo: UndoStack,
        val selection: List<Element>,
        val viewMatrix: Matrix,
        val canvasRotation: Double,
        val currentArtboardIndex: Int,
        val keyObject: Element?,
    )

    /**
     * 現在のセッションを退避する（Undo履歴・選択・ビューごと取り出す）。
     * 呼び出し後の Editor は空の選択/履歴で動く（ViewModelが新しいdocをロードする）。
     */
    fun exportSession(): SessionSnapshot {
        val wasDirty = dirty
        // ガイド・アニメーション定義をドキュメント属性へ退避（履歴を汚さないよう直接書き込む）
        if (guides.isNotEmpty()) doc.root.setAttr(Guide.ATTR, Guide.Codec.encode(guides))
        saveAnimationsToDocument()
        saveKeyframesToDocument()
        val snap = SessionSnapshot(
            doc = doc,
            name = documentName,
            dirty = wasDirty,
            undo = undoStack.detach(),
            selection = selection.toList(),
            viewMatrix = viewMatrix,
            canvasRotation = canvasRotation,
            currentArtboardIndex = currentArtboardIndex,
            keyObject = keyObject,
        )
        nodeSession = null
        penDraft = null
        freehandDraft = null
        shapeDraft = null
        gradientEdit = null
        selection.clear()
        return snap
    }

    /** 退避したセッションを復元する（doc/undo/選択/ビューを丸ごと戻す）。 */
    fun importSession(s: SessionSnapshot) {
        doc = s.doc
        documentName = s.name
        dirty = s.dirty
        undoStack.attach(s.undo)
        selection.clear()
        selection.addAll(s.selection)
        viewMatrix = s.viewMatrix
        canvasRotation = s.canvasRotation
        currentArtboardIndex = s.currentArtboardIndex
        keyObject = s.keyObject
        nodeSession = null
        penDraft = null
        freehandDraft = null
        shapeDraft = null
        gradientEdit = null
        guides.clear()
        guides.addAll(Guide.Codec.decode(doc.root.attr(Guide.ATTR)))
        doc.restoreArtboards()
        restoreComments()
        restoreStylePresets()
        restoreBrushPresets()
        invalidateTrace()
        onDocumentReplaced()
        loadAnimationsFromDocument()
        loadKeyframesFromDocument()
        onZoomChanged()
        notifySelection()
    }

    fun markSaved() {
        dirty = false
        onChanged()
    }

    // ―――― 座標変換 ――――

    /** 画面の中心（ビュー回転の中心）。未設定時は(0,0)。 */
    private fun viewCenter(): Vec = Vec(viewportW.coerceAtLeast(1.0) / 2.0, viewportH.coerceAtLeast(1.0) / 2.0)

    /** user→screen（viewMatrix＋キャンバスビュー回転を適用）。 */
    fun userToScreen(v: Vec): Vec {
        val p = viewMatrix.apply(v)
        if (canvasRotation == 0.0) return p
        val c = viewCenter()
        val r = Math.toRadians(canvasRotation)
        val ca = cos(r); val sa = sin(r)
        val d = p - c
        return c + Vec(ca * d.x - sa * d.y, sa * d.x + ca * d.y)
    }

    /** screen→user（キャンバスビュー回転を逆適用してからviewMatrixを逆変換）。 */
    fun screenToUser(v: Vec): Vec {
        val p = if (canvasRotation == 0.0) v else {
            val c = viewCenter()
            val r = Math.toRadians(-canvasRotation)
            val ca = cos(r); val sa = sin(r)
            val d = v - c
            c + Vec(ca * d.x - sa * d.y, sa * d.x + ca * d.y)
        }
        return viewMatrix.invert().apply(p)
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

    /** 指定したuser座標をビューポート中央に表示する（ナビゲーターのクリック移動用）。 */
    fun centerOnUser(v: Vec) {
        val scale = viewMatrix.a.coerceAtLeast(1e-6)
        viewMatrix = Matrix(
            a = scale, b = 0.0, c = 0.0, d = scale,
            e = viewportW / 2.0 - v.x * scale,
            f = viewportH / 2.0 - v.y * scale,
        )
        onZoomChanged()
        onChanged()
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
            recordMacroStep(label, cs)
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
     * 点をガイド線へスナップする（水平/垂直＋任意角度の斜めガイド対応）。
     * - 水平/垂直: 軸ごとに独立してスナップ（複数ガイドの交点へ寄せられる）
     * - 斜め: 最も近い1線への垂線投影。軸スナップが起きた場合はそちらを優先
     *
     * スナップしなかった場合はnullを返す。includeLocked=falseでロック中のガイドを除外。
     */
    fun snapToGuideLines(p: Vec, tolUser: Double, includeLocked: Boolean): Vec? {
        if (!snapToGuides || guides.isEmpty()) return null
        var x = p.x
        var y = p.y
        var snapped = false
        var bestAngled: Guide? = null
        var bestD = tolUser
        for (g in guides) {
            if (!g.visible) continue
            if (!includeLocked && g.locked) continue
            if (g.isAngled) {
                val d = g.distanceTo(p)
                if (d < bestD) {
                    bestD = d
                    bestAngled = g
                }
            } else if (g.horizontal && abs(p.y - g.pos) < tolUser) {
                y = g.pos; snapped = true
            } else if (!g.horizontal && abs(p.x - g.pos) < tolUser) {
                x = g.pos; snapped = true
            }
        }
        if (snapped) return Vec(x, y)
        return bestAngled?.project(p)
    }

    /**
     * 点をスナップする。
     * 優先度: ガイド > オブジェクト（コーナー/中心/ノード） > グリッド。
     * 画面px基準の許容距離を拡大率でuser距離へ変換するため、ズーム非依存の操作感。
     */
    fun snapPoint(p: Vec, ignore: List<Element> = emptyList()): Vec {
        val tolUser = snapTolerancePx / zoom.coerceAtLeast(1e-6)
        var result = p

        // ガイドスナップ（最優先・個別トグル）。ロックは「編集不可」でありスナップ対象からは除外しない
        snapToGuideLines(p, tolUser, includeLocked = true)?.let { return it }

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
        var finalPos = pos
        if (snapGuidesToGrid) {
            finalPos = kotlin.math.round(finalPos / gridSize.coerceAtLeast(1e-6)) * gridSize
        }
        guides[index] = g.copy(pos = finalPos)
        onChanged()
    }

    /**
     * ドラッグ中のガイドを点へ移動（undo記録なし。確定時にsaveGuidesを呼ぶ）。
     * 斜めガイドは角度を保ったまま線が点を通るように動く。水平/垂直はposを更新。
     */
    fun moveGuideToPoint(index: Int, p: Vec) {
        if (index !in guides.indices) return
        val g = guides[index]
        if (!g.isAngled) {
            moveGuideSilent(index, if (g.horizontal) p.y else p.x)
            return
        }
        var np = p
        if (snapGuidesToGrid) {
            np = Vec(
                kotlin.math.round(np.x / gridSize.coerceAtLeast(1e-6)) * gridSize,
                kotlin.math.round(np.y / gridSize.coerceAtLeast(1e-6)) * gridSize,
            )
        }
        guides[index] = g.copy(point = np)
        onChanged()
    }

    /** グリッドにスナップした位置でガイドを追加する */
    fun addGuideSnapped(g: Guide) {
        if (g.isAngled) {
            var pt = g.point ?: return addGuide(g)
            if (snapGuidesToGrid) {
                pt = Vec(
                    kotlin.math.round(pt.x / gridSize.coerceAtLeast(1e-6)) * gridSize,
                    kotlin.math.round(pt.y / gridSize.coerceAtLeast(1e-6)) * gridSize,
                )
            }
            addGuide(g.copy(point = pt))
            return
        }
        var pos = g.pos
        if (snapGuidesToGrid) {
            pos = kotlin.math.round(pos / gridSize.coerceAtLeast(1e-6)) * gridSize
        }
        addGuide(g.copy(pos = pos))
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
            val d = if (g.isAngled) {
                // 線を画面座標へ変換し、画面点との直線距離を取る
                val a = userToScreen(g.anchor())
                val b = userToScreen(g.anchor() + g.direction())
                val abx = b.x - a.x
                val aby = b.y - a.y
                val len = kotlin.math.sqrt(abx * abx + aby * aby)
                if (len < 1e-9) Double.MAX_VALUE
                else abs((screen.x - a.x) * aby - (screen.y - a.y) * abx) / len
            } else if (g.horizontal) abs(userToScreen(Vec(0.0, g.pos)).y - screen.y)
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
        (paint as? Paint.Solid)?.let { rememberColor(it.argb) }
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
        (paint as? Paint.Solid)?.let { rememberColor(it.argb) }
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

    /** 線の位置調整（center=中央 / inside=内側 / outside=外側）。data-svgpro-stroke-align 属性で保持。 */
    fun applyStrokeAlignmentToSelection(alignment: String) {
        if (selection.isEmpty()) return
        commit("stroke-align") { batch ->
            for (el in selection) {
                batch.touch(el)
                if (alignment.equals("center", true)) {
                    StyleUtil.removeProperty(el, "data-svgpro-stroke-align")
                    el.setAttr("data-svgpro-stroke-align", null)
                } else {
                    StyleUtil.setProperty(el, "data-svgpro-stroke-align", alignment.lowercase())
                }
            }
        }
    }

    /** 選択要素の線位置調整値を取得（未設定なら center） */
    fun selectionStrokeAlignment(): String {
        return selection.firstOrNull()?.let { v ->
            val a = StyleUtil.getProperty(v, "data-svgpro-stroke-align")
            if (a == null) "center" else a
        } ?: "center"
    }

    // ―――― エフェクト（ぼかし・ドロップシャドウ・光彩・色調） ――――

    /** ガウスぼかし半径を選択要素に適用（user単位）。data-svgpro-effect-blur 属性で保持。 */
    fun applyEffectBlurToSelection(radius: Double) {
        if (selection.isEmpty()) return
        commit("effect-blur") { batch ->
            for (el in selection) {
                batch.touch(el)
                if (radius <= 0.0) {
                    StyleUtil.removeProperty(el, "data-svgpro-effect-blur")
                    el.setAttr("data-svgpro-effect-blur", null)
                } else {
                    StyleUtil.setProperty(el, "data-svgpro-effect-blur", dev.svgpro.core.util.Numbers.format(radius.coerceIn(0.0, 256.0)))
                }
            }
        }
    }

    /** 選択要素のぼかし半径を取得（未設定なら 0）。 */
    fun selectionEffectBlur(): Double {
        return selection.firstOrNull()?.let { v ->
            val s = StyleUtil.getProperty(v, "data-svgpro-effect-blur")
            if (s == null) 0.0 else dev.svgpro.core.util.Numbers.parse(s) ?: 0.0
        } ?: 0.0
    }

    /** ドロップシャドウを選択要素に適用。data-svgpro-effect-dropshadow="dx dy blur color"。 */
    fun applyEffectDropShadowToSelection(dx: Double, dy: Double, blur: Double, color: Int) {
        if (selection.isEmpty()) return
        commit("effect-dropshadow") { batch ->
            for (el in selection) {
                batch.touch(el)
                if (blur <= 0.0) {
                    StyleUtil.removeProperty(el, "data-svgpro-effect-dropshadow")
                    el.setAttr("data-svgpro-effect-dropshadow", null)
                } else {
                    StyleUtil.setProperty(el, "data-svgpro-effect-dropshadow",
                        "${dev.svgpro.core.util.Numbers.format(dx)} ${dev.svgpro.core.util.Numbers.format(dy)} " +
                        "${dev.svgpro.core.util.Numbers.format(blur.coerceIn(0.0, 256.0))} ${dev.svgpro.core.model.Color.css(color)}")
                }
            }
        }
    }

    /** 選択要素のドロップシャドウ設定を取得（未設定なら null）。 */
    fun selectionEffectDropShadow(): Triple<Double, Double, Double>? {
        return selection.firstOrNull()?.let { v ->
            val s = StyleUtil.getProperty(v, "data-svgpro-effect-dropshadow") ?: return null
            val parts = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.mapNotNull { dev.svgpro.core.util.Numbers.parse(it) }
            if (parts.size < 3) null else Triple(parts[0], parts[1], parts[2])
        }
    }

    /** 外側光彩を選択要素に適用。data-svgpro-effect-glow="blur color"。 */
    fun applyEffectGlowToSelection(blur: Double, color: Int) {
        if (selection.isEmpty()) return
        commit("effect-glow") { batch ->
            for (el in selection) {
                batch.touch(el)
                if (blur <= 0.0) {
                    StyleUtil.removeProperty(el, "data-svgpro-effect-glow")
                    el.setAttr("data-svgpro-effect-glow", null)
                } else {
                    StyleUtil.setProperty(el, "data-svgpro-effect-glow",
                        "${dev.svgpro.core.util.Numbers.format(blur.coerceIn(0.0, 256.0))} ${dev.svgpro.core.model.Color.css(color)}")
                }
            }
        }
    }

    /** 選択要素の光彩設定を取得（未設定なら null）。 */
    fun selectionEffectGlow(): Pair<Double, Int>? {
        return selection.firstOrNull()?.let { v ->
            val s = StyleUtil.getProperty(v, "data-svgpro-effect-glow") ?: return null
            val parts = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 2) null else {
                val radius = dev.svgpro.core.util.Numbers.parse(parts[0]) ?: 0.0
                val c = dev.svgpro.core.model.Color.parse(parts[1]) ?: 0xFF000000.toInt()
                Pair(radius, c)
            }
        }
    }

    /** 色調変更（ColorMatrix）を選択要素に適用。data-svgpro-effect-colormatrix="a b c d e f g h i j"。 */
    fun applyEffectColorMatrixToSelection(matrix: String) {
        if (selection.isEmpty()) return
        commit("effect-colormatrix") { batch ->
            for (el in selection) {
                batch.touch(el)
                if (matrix.isBlank()) {
                    StyleUtil.removeProperty(el, "data-svgpro-effect-colormatrix")
                    el.setAttr("data-svgpro-effect-colormatrix", null)
                } else {
                    StyleUtil.setProperty(el, "data-svgpro-effect-colormatrix", matrix)
                }
            }
        }
    }

    /** 選択要素の色調行列文字列を取得（未設定なら null）。 */
    fun selectionEffectColorMatrix(): String? {
        return selection.firstOrNull()?.let { v ->
            StyleUtil.getProperty(v, "data-svgpro-effect-colormatrix")
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

    /** テキスト装飾（underline / line-through / none）を選択テキストへ適用 */
    fun applyTextDecorationSelection(decoration: String) {
        if (selection.isEmpty()) return
        commit("text-decoration") { batch ->
            for (el in selection) {
                batch.touch(el)
                if (decoration.equals("none", true)) {
                    StyleUtil.removeProperty(el, "text-decoration")
                } else {
                    StyleUtil.setProperty(el, "text-decoration", decoration)
                }
            }
        }
    }

    /** 文字間隔（letter-spacing）を選択テキストへ適用 */
    fun applyTextLetterSpacingSelection(value: String) {
        if (selection.isEmpty()) return
        commit("letter-spacing") { batch ->
            for (el in selection) {
                batch.touch(el)
                StyleUtil.setProperty(el, "letter-spacing", value)
            }
        }
    }

    /** 行間（line-height）を選択テキストへ適用 */
    fun applyTextLineHeightSelection(value: String) {
        if (selection.isEmpty()) return
        commit("line-height") { batch ->
            for (el in selection) {
                batch.touch(el)
                StyleUtil.setProperty(el, "line-height", value)
            }
        }
    }

    /** 垂直方向揃え（dominant-baseline）を選択テキストへ適用 */
    fun applyTextVerticalAlignSelection(value: String) {
        if (selection.isEmpty()) return
        commit("dominant-baseline") { batch ->
            for (el in selection) {
                batch.touch(el)
                if (value.equals("auto", true)) {
                    StyleUtil.removeProperty(el, "dominant-baseline")
                } else {
                    StyleUtil.setProperty(el, "dominant-baseline", value)
                }
            }
        }
    }

    /** 単語間隔（word-spacing）を選択テキストへ適用 */
    fun applyTextWordSpacingSelection(value: String) {
        if (selection.isEmpty()) return
        commit("word-spacing") { batch ->
            for (el in selection) {
                batch.touch(el)
                StyleUtil.setProperty(el, "word-spacing", value)
            }
        }
    }

    /** テキストの水平揃え（text-anchor: start/middle/end）を選択テキストへ適用 */
    fun applyTextAnchorSelection(value: String) {
        if (selection.isEmpty()) return
        commit("text-anchor") { batch ->
            for (el in selection) {
                batch.touch(el)
                StyleUtil.setProperty(el, "text-anchor", value)
            }
        }
    }

    /** 現在の選択テキストの text-anchor を取得（未設定なら start） */
    fun selectionTextAnchor(): String {
        return selection.firstOrNull()?.let { v ->
            StyleUtil.getProperty(v, "text-anchor") ?: "start"
        } ?: "start"
    }

    /** 書字方向（writing-mode: horizontal-tb / vertical-lr / vertical-rl）を選択テキストへ適用 */
    fun applyTextWritingModeSelection(value: String) {
        if (selection.isEmpty()) return
        commit("writing-mode") { batch ->
            for (el in selection) {
                batch.touch(el)
                if (value.equals("horizontal-tb", true)) {
                    StyleUtil.removeProperty(el, "writing-mode")
                    el.setAttr("writing-mode", null)
                } else {
                    StyleUtil.setProperty(el, "writing-mode", value)
                }
            }
        }
    }

    /** 現在の選択テキストの writing-mode を取得（未設定なら horizontal-tb） */
    fun selectionWritingMode(): String {
        return selection.firstOrNull()?.let { v ->
            StyleUtil.getProperty(v, "writing-mode") ?: v.attr("writing-mode") ?: "horizontal-tb"
        } ?: "horizontal-tb"
    }

    /** フォントウェイトを数値（100〜900 または normal/bold）で選択テキストへ適用 */
    fun applyTextFontWeightSelection(weight: String) {
        if (selection.isEmpty()) return
        commit("font-weight") { batch ->
            for (el in selection) {
                batch.touch(el)
                StyleUtil.setProperty(el, "font-weight", weight)
            }
        }
    }

    /** テキスト塗り色を選択テキストへ適用 */
    fun applyTextFillColorSelection(argb: Int) {
        if (selection.isEmpty()) return
        commit("text-fill") { batch ->
            for (el in selection) {
                batch.touch(el)
                StyleUtil.setProperty(el, "fill", dev.svgpro.core.model.Color.css(argb))
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

    // ―――― コピー・カット・ペースト ――――

    /** クリップボード（ディープコピーされた要素リスト） */
    val clipboard = mutableListOf<Element>()

    /** 選択をクリップボードへコピー */
    fun copySelection() {
        if (selection.isEmpty()) return
        clipboard.clear()
        for (el in selection) clipboard.add(el.deepCopy())
        notifyChanged()
    }

    /** 選択をクリップボードへコピーしてから削除 */
    fun cutSelection() {
        if (selection.isEmpty()) return
        copySelection()
        deleteSelection()
    }

    /**
     * クリップボードの内容をペーストする。
     * @param inPlace true で同位置にペースト（インプレース）。false で少しずらす。
     * @param toFront true で親の末尾（最前面）へ、false で元の親位置の直後へ挿入。
     */
    fun pasteFromClipboard(inPlace: Boolean = false, toFront: Boolean = false) {
        if (clipboard.isEmpty()) return
        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        val newSel = ArrayList<Element>()
        val layer = currentLayer()
        val defaultParent = layer ?: doc.root
        for (src in clipboard) {
            val copy = src.deepCopy()
            copy.setAttr("id", null)
            val parent = if (toFront) defaultParent else findParent(src) ?: defaultParent
            val idx = if (toFront) parent.children.size else (parent.children.indexOf(src) + 1).coerceAtLeast(0).coerceAtMost(parent.children.size)
            if (!inPlace) {
                // 少しずらして重ね合わせを避ける（zoomに依存しない固定オフセット）
                val t = Shapes.localTransform(copy)
                copy.setAttr("transform", Matrix.toSvgString(t.then(Matrix(e = 20.0 / zoom, f = 20.0 / zoom))))
            }
            changes.add(Attach(copy, parent, idx, doc.root, "paste"))
            newSel.add(copy)
        }
        if (changes.isEmpty()) return
        val cs = ChangeSet(changes, "paste", newSel)
        cs.apply()
        selection.clear()
        selection.addAll(newSel)
        undoStack.push(cs)
        updateNodeSession()
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

    /** レイヤー（g 要素）を複製する */
    fun duplicateLayer(el: Element) {
        if (el.tag != "g") return
        val parent = findParent(el) ?: doc.root
        val copy = el.deepCopy()
        copy.setAttr("id", null)
        val idx = parent.children.indexOf(el) + 1
        val cs = ChangeSet(listOf(Attach(copy, parent, idx, doc.root, "duplicate layer")), "duplicate layer", listOf(copy))
        cs.apply()
        selection.clear()
        selection.add(copy)
        undoStack.push(cs)
        updateNodeSession()
        notifyChanged()
        notifySelection()
    }

    /** 選択中のレイヤー（g）を1つに結合：最初のレイヤーに他のレイヤーの子を移し、残りを削除 */
    fun mergeSelectedLayers() {
        val layers = selection.filter { it.tag == "g" }
        if (layers.size < 2) return
        val target = layers.first()
        val others = layers.drop(1)
        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        for (other in others) {
            val parent = findParent(other) ?: continue
            val idx = parent.children.indexOf(other)
            val children = other.childElements().toList()
            for (c in children) {
                changes.add(Attach(c, target, target.childElements().size, doc.root, "merge layers"))
            }
            changes.add(Detach(other, parent, idx.coerceAtLeast(0), "merge layers"))
        }
        if (changes.isEmpty()) return
        val cs = ChangeSet(changes, "merge layers", listOf(target))
        cs.apply()
        selection.clear()
        selection.add(target)
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
            ToolType.ARC, ToolType.SECTOR -> {
                val center = startUser
                val r = hypot(endUser.x - startUser.x, endUser.y - startUser.y)
                if (r < 1.0) return null
                // ドラッグ方向を終端角度、開始角度は3時方向(0°)とする。
                // user座標系はY軸上向きのため、画面で下方向へドラッグすると角度は負（時計回り）になる。
                val sweepDeg = Math.toDegrees(atan2(endUser.y - startUser.y, endUser.x - startUser.x))
                val pd = if (kind == ToolType.ARC) arcPath(center, r, 0.0, sweepDeg)
                else sectorPath(center, r, 0.0, sweepDeg)
                Element("path").apply { setAttr("d", pd.write()) }
            }
            ToolType.SPIRAL -> {
                val center = startUser
                val r = hypot(endUser.x - startUser.x, endUser.y - startUser.y)
                if (r < 1.0) return null
                val turns = 3.0 // 巻き数
                val points = 64 * turns.toInt()
                val segs = ArrayList<Seg>()
                var first = true
                for (i in 0..points) {
                    val t = i / points.toDouble()
                    val angle = t * 2.0 * Math.PI * turns
                    val rr = r * t
                    val p = Vec(center.x + rr * kotlin.math.cos(angle), center.y + rr * kotlin.math.sin(angle))
                    if (first) {
                        segs.add(Seg.Move(p.x, p.y))
                        first = false
                    } else {
                        segs.add(Seg.Line(p.x, p.y))
                    }
                }
                Element("path").apply { setAttr("d", PathData(segs).write()) }
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

    /** 中心c、半径r、開始角度startDeg、スイープsweepDeg（度、反時計回り正）の円弧パス */
    private fun arcPath(c: Vec, r: Double, startDeg: Double, sweepDeg: Double): PathData {
        val s = Math.toRadians(startDeg)
        val e = s + Math.toRadians(sweepDeg)
        val p1 = Vec(c.x + r * kotlin.math.cos(s), c.y + r * kotlin.math.sin(s))
        val p2 = Vec(c.x + r * kotlin.math.cos(e), c.y + r * kotlin.math.sin(e))
        val sweep = sweepDeg >= 0.0
        val largeArc = kotlin.math.abs(sweepDeg) > 180.0
        return PathData(
            ArrayList(
                listOf(
                    Seg.Move(p1.x, p1.y),
                    Seg.Arc(r, r, 0.0, largeArc, sweep, p2.x, p2.y),
                ),
            ),
        )
    }

    /** 中心c、半径r、開始角度startDeg、スイープsweepDeg の扇形パス（中心を結ぶ2半径＋円弧＋閉じる） */
    private fun sectorPath(c: Vec, r: Double, startDeg: Double, sweepDeg: Double): PathData {
        val s = Math.toRadians(startDeg)
        val e = s + Math.toRadians(sweepDeg)
        val p1 = Vec(c.x + r * kotlin.math.cos(s), c.y + r * kotlin.math.sin(s))
        val p2 = Vec(c.x + r * kotlin.math.cos(e), c.y + r * kotlin.math.sin(e))
        val sweep = sweepDeg >= 0.0
        val largeArc = kotlin.math.abs(sweepDeg) > 180.0
        return PathData(
            ArrayList(
                listOf(
                    Seg.Move(c.x, c.y),
                    Seg.Line(p1.x, p1.y),
                    Seg.Arc(r, r, 0.0, largeArc, sweep, p2.x, p2.y),
                    Seg.Line(c.x, c.y),
                    Seg.Close,
                ),
            ),
        )
    }

    // ―――― パラメトリック図形生成（カテゴリ17） ――――

    /** ハート形パス */
    fun heartPath(cx: Double, cy: Double, size: Double): PathData {
        val segs = ArrayList<Seg>()
        val pts = 64
        for (i in 0..pts) {
            val t = i * 2 * Math.PI / pts
            // ハートのパラメトリック方程式
            val x = 16.0 * kotlin.math.sin(t) * kotlin.math.sin(t) * kotlin.math.sin(t)
            val y = -(13.0 * kotlin.math.cos(t) - 5.0 * kotlin.math.cos(2.0 * t) - 2.0 * kotlin.math.cos(3.0 * t) - kotlin.math.cos(4.0 * t))
            val px = cx + x * size / 17.0
            val py = cy + y * size / 17.0
            if (i == 0) segs.add(Seg.Move(px, py)) else segs.add(Seg.Line(px, py))
        }
        segs.add(Seg.Close)
        return PathData(segs)
    }

    /** ギア形パス（歯数、外径、内径） */
    fun gearPath(cx: Double, cy: Double, teeth: Int, outerR: Double, innerR: Double): PathData {
        val segs = ArrayList<Seg>()
        val step = 2 * Math.PI / teeth
        for (i in 0 until teeth * 2) {
            val angle = i * step / 2
            val r = if (i % 2 == 0) outerR else innerR
            val x = cx + r * kotlin.math.cos(angle)
            val y = cy + r * kotlin.math.sin(angle)
            if (i == 0) segs.add(Seg.Move(x, y)) else segs.add(Seg.Line(x, y))
        }
        segs.add(Seg.Close)
        return PathData(segs)
    }

    /** スパイラル形パス（巻き数） */
    fun spiralPath(cx: Double, cy: Double, turns: Double, maxR: Double): PathData {
        val segs = ArrayList<Seg>()
        val pts = 64 * turns.toInt().coerceAtLeast(1)
        for (i in 0..pts) {
            val t = i / pts.toDouble() * turns * 2 * Math.PI
            val r = maxR * t / (turns * 2 * Math.PI)
            val x = cx + r * kotlin.math.cos(t)
            val y = cy + r * kotlin.math.sin(t)
            if (i == 0) segs.add(Seg.Move(x, y)) else segs.add(Seg.Line(x, y))
        }
        return PathData(segs)
    }

    /** 波形パス（波数、振幅） */
    fun wavePath(cx: Double, cy: Double, width: Double, waves: Double, amplitude: Double): PathData {
        val segs = ArrayList<Seg>()
        val pts = 32
        for (i in 0..pts) {
            val x = cx - width / 2 + width * i / pts
            val y = cy + kotlin.math.sin(2 * Math.PI * waves * i / pts) * amplitude
            if (i == 0) segs.add(Seg.Move(x, y)) else segs.add(Seg.Line(x, y))
        }
        return PathData(segs)
    }

    // ―――― チャート/グラフ生成（カテゴリ17） ――――

    /** 棒グラフ（バーチャート）パスを生成。values は 0〜1 の正規化済み値リスト。 */
    fun barChartPath(cx: Double, cy: Double, width: Double, height: Double, values: List<Double>): PathData {
        val segs = ArrayList<Seg>()
        val n = values.size.coerceAtLeast(1)
        val barW = width / n * 0.7
        val gap = width / n * 0.3
        for (i in values.indices) {
            val v = values[i].coerceIn(0.0, 1.0)
            val bx = cx - width / 2 + i * (barW + gap) + gap / 2
            val by = cy + height / 2 - v * height
            val bw = barW
            val bh = v * height
            // 矩形を 4 セグメントで構成
            segs.add(Seg.Move(bx, cy + height / 2))
            segs.add(Seg.Line(bx + bw, cy + height / 2))
            segs.add(Seg.Line(bx + bw, by))
            segs.add(Seg.Line(bx, by))
            segs.add(Seg.Close)
        }
        return PathData(segs)
    }

    /** 円グラフ（パイチャート）パスを生成。values は 0〜1 の正規化済み値リスト（合計 1.0 を想定）。 */
    fun pieChartPath(cx: Double, cy: Double, outerR: Double, innerR: Double, values: List<Double>): PathData {
        val segs = ArrayList<Seg>()
        val total = values.sum().coerceAtLeast(1e-9)
        var startAngle = -Math.PI / 2 // 12 時方向から開始
        for (v in values) {
            if (v <= 0.0) continue
            val sweep = 2 * Math.PI * (v / total)
            val x1 = cx + outerR * kotlin.math.cos(startAngle)
            val y1 = cy + outerR * kotlin.math.sin(startAngle)
            val x2 = cx + outerR * kotlin.math.cos(startAngle + sweep)
            val y2 = cy + outerR * kotlin.math.sin(startAngle + sweep)
            val largeArc = sweep > Math.PI
            // 中心→外周→アーク→内周→中心 の閉じたパス
            segs.add(Seg.Move(cx, cy))
            segs.add(Seg.Line(x1, y1))
            segs.add(Seg.Arc(outerR, outerR, 0.0, largeArc, true, x2, y2))
            segs.add(Seg.Line(cx + innerR * kotlin.math.cos(startAngle + sweep), cy + innerR * kotlin.math.sin(startAngle + sweep)))
            // 内側を逆方向のアークで戻す
            segs.add(Seg.Arc(innerR, innerR, 0.0, sweep > Math.PI, false, cx + innerR * kotlin.math.cos(startAngle), cy + innerR * kotlin.math.sin(startAngle)))
            segs.add(Seg.Close)
            startAngle += sweep
        }
        return PathData(segs)
    }

    /** 折れ線グラフ（ラインチャート）パスを生成。values は 0〜1 の正規化済み値リスト。 */
    fun lineChartPath(cx: Double, cy: Double, width: Double, height: Double, values: List<Double>): PathData {
        val segs = ArrayList<Seg>()
        val n = values.size.coerceAtLeast(1)
        if (n < 2) return PathData(segs)
        val step = width / (n - 1)
        for (i in values.indices) {
            val v = values[i].coerceIn(0.0, 1.0)
            val x = cx - width / 2 + i * step
            val y = cy + height / 2 - v * height
            if (i == 0) segs.add(Seg.Move(x, y)) else segs.add(Seg.Line(x, y))
        }
        return PathData(segs)
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

    // ========================================================================
    // フォント → パス変換（テキストのアウトライン化）
    // ========================================================================

    /**
     * 選択中の text 要素を、現在のフォント設定に基づいて path 要素へ変換する。
     * カスタムフォント（loadFontFromFile で登録済み）にも対応。
     * 変換後は元の text 要素は削除され、path が選択される。
     */
    fun fontToPathSelection(): Boolean {
        val targets = selection.filter { it.tag == "text" }
        if (targets.isEmpty()) return false
        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        val newSel = ArrayList<Element>()
        for (el in targets) {
            val pd = textToPathData(el) ?: continue
            val abs = trace.absOf(el)
            pd.transform(abs)
            val parent = findParent(el) ?: continue
            val idx = parent.children.indexOf(el).coerceAtLeast(0)
            val p = Element("path")
            // フォント関連属性以外をコピー
            for ((k, v) in el.attrs) {
                if (k in setOf("x", "y", "dx", "dy", "rotate", "textLength", "lengthAdjust",
                    "font-family", "font-size", "font-weight", "font-style", "text-anchor")) continue
                p.attrs[k] = v
            }
            p.setAttr("d", pd.write())
            changes.add(Attach(p, parent, idx, doc.root, "font to path"))
            changes.add(Detach(el, parent, idx, "font to path"))
            newSel.add(p)
        }
        if (changes.isEmpty()) return false
        val cs = ChangeSet(changes, "font to path", newSel)
        cs.apply()
        selection.clear()
        selection.addAll(newSel)
        undoStack.push(cs)
        invalidateTrace()
        updateNodeSession()
        notifyChanged()
        notifySelection()
        return true
    }

    /**
     * 指定文字列をカスタムフォントで path データとして生成（グリフ単位のSVG化用）。
     * ViewModelのfontRegistryを使うため、Editor側では provider経由で呼び出す想定。
     */
    fun textToPathUsingFont(text: String, fontSize: Double, family: String, x: Double = 0.0, y: Double = 0.0,
                            letterSpacing: Double = 0.0): PathData? {
        val provider = textToPathProvider ?: return null
        // 一時的なtext要素を作ってproviderに渡す（providerはElementを受け取る）
        val tmp = Element("text")
        tmp.setAttr("x", dev.svgpro.core.util.Numbers.format(x))
        tmp.setAttr("y", dev.svgpro.core.util.Numbers.format(y))
        tmp.setAttr("font-size", dev.svgpro.core.util.Numbers.format(fontSize))
        tmp.setAttr("font-family", family)
        if (letterSpacing != 0.0) tmp.setAttr("letter-spacing", dev.svgpro.core.util.Numbers.format(letterSpacing))
        tmp.setTextContent(text)
        return provider.invoke(tmp)
    }

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

    // ―――― SVGソースコード直接編集 ――――

    /** 現在のドキュメントをSVG文字列として取得（整形済み） */
    fun svgSource(): String = SvgWriter.write(doc.root)

    /**
     * SVGソース文字列からドキュメントを置き換える。
     * 解析失敗時は false を返し、ドキュメントは変更されない。
     * （ソース置換はドキュメント全体を再構築するため、個別のUndoエントリは作成しない）
     */
    fun replaceSvgSource(source: String): Boolean {
        val newDoc = try {
            SvgLoader.load(::tokenSourceOf, source)
        } catch (e: Exception) {
            null
        } ?: return false
        loadDocument(newDoc, documentName)
        return true
    }

    private fun tokenSourceOf(xml: String): dev.svgpro.core.svg.TokenSource {
        val queue = dev.svgpro.AndroidTokenSource.tokenQueue(xml)
        return object : dev.svgpro.core.svg.TokenSource {
            override fun next(): dev.svgpro.core.svg.Token =
                queue.removeFirstOrNull() ?: dev.svgpro.core.svg.Token.EndDocument
        }
    }

    /** ドキュメントを最適化（重複ID付け直し・未使用def削除）。結果を返す */
    fun optimizeSvgDocument(): SvgOptimizer.OptimizationResult {
        val result = SvgOptimizer.optimize(doc.root)
        if (result.changed) {
            notifyChanged()
            notifySelection()
        }
        return result
    }

    /** 現在のドキュメントを複製して新規ドキュメントとして開く。 */
    fun duplicateDocument() {
        val xml = doc.serialize()
        val newDoc = try {
            SvgLoader.load(::tokenSourceOf, xml)
        } catch (e: Exception) {
            null
        } ?: return
        val name = if (documentName.endsWith(".svg")) {
            documentName.substringBeforeLast(".") + "_copy.svg"
        } else {
            documentName + "_copy.svg"
        }
        loadDocument(newDoc, name)
    }

    // ―――― ドキュメントスナップショット（共有の基盤） ――――

    /** 現在のドキュメント状態を共有可能なJSONスナップショットとして出力する。
     * 実質的な共同編集にはシグナリングサーバ/OT/CRDTが必要なため、
     * ここではドキュメント全体のシリアライズ（スナップショット）を提供するにとどめる。
     */
    fun documentSnapshot(): String {
        val map = linkedMapOf<String, Any?>()
        map["svg"] = doc.serialize()
        map["artboards"] = doc.artboards.mapIndexed { i, a ->
            mapOf("id" to "artboard_$i", "name" to a.name, "x" to a.x, "y" to a.y, "w" to a.w, "h" to a.h)
        }
        map["currentArtboardIndex"] = currentArtboardIndex
        map["guides"] = doc.root.attr(Guide.ATTR)
        return dev.svgpro.core.util.Json.stringify(map)
    }

    // ―――― マクロ記録・再生（カテゴリ17） ――――

    /** マクロの1ステップ（ラベル＋ChangeSetのスナップショット） */
    data class MacroStep(val label: String, val changesetJson: String)

    /** 保存済みマクロ */
    data class Macro(val name: String, val steps: List<MacroStep>)

    /** マクロレコーダー状態 */
    var isRecordingMacro = false
        private set
    var currentMacroName: String? = null
        private set
    private var currentMacroSteps = mutableListOf<MacroStep>()

    /** 保存済みマクロ一覧 */
    val macros = mutableListOf<Macro>()

    /** マクロ記録を開始する */
    fun startMacroRecording(name: String) {
        isRecordingMacro = true
        currentMacroName = name
        currentMacroSteps.clear()
        notifyChanged()
    }

    /** マクロ記録を停止し、保存する */
    fun stopMacroRecording(): Boolean {
        if (!isRecordingMacro || currentMacroName == null) return false
        val macro = Macro(currentMacroName!!, currentMacroSteps.toList())
        macros.removeAll { it.name == currentMacroName }
        macros.add(macro)
        isRecordingMacro = false
        currentMacroName = null
        currentMacroSteps.clear()
        notifyChanged()
        return true
    }

    /** マクロを記録中に ChangeSet を1ステップとして記録する（commit から呼ばれる） */
    private fun recordMacroStep(label: String, cs: dev.svgpro.core.edit.ChangeSet) {
        if (!isRecordingMacro) return
        // ChangeSet の変更内容を JSON 化して記録（undo/redo に依存せず再生可能にするため）
        val steps = cs.changes.mapIndexed { i, c ->
            when (c) {
                is dev.svgpro.core.edit.AttrChange -> {
                    mapOf<String, Any>(
                        "type" to "attr",
                        "index" to i,
                        "label" to (c.label ?: ""),
                        "key" to (c.key as Any),
                        "oldV" to (c.oldV as Any),
                        "newV" to (c.newV as Any),
                    )
                }
                is dev.svgpro.core.edit.Detach -> {
                    mapOf<String, Any>(
                        "type" to "detach",
                        "index" to i,
                        "label" to (c.label ?: ""),
                    )
                }
                is dev.svgpro.core.edit.Attach -> {
                    mapOf<String, Any>(
                        "type" to "attach",
                        "index" to i,
                        "label" to (c.label ?: ""),
                    )
                }
                else -> mapOf<String, Any>("type" to "other", "index" to i, "label" to (c.label ?: ""))
            }
        }
        currentMacroSteps.add(MacroStep(label, dev.svgpro.core.util.Json.stringify(steps)))
    }

    /** 保存済みマクロを再生する。各ステップの変更を再適用する。 */
    fun playMacro(index: Int) {
        if (index < 0 || index >= macros.size) return
        val macro = macros[index]
        val batch = EditBatch()
        for (step in macro.steps) {
            try {
                val steps = dev.svgpro.core.util.Json.parse(step.changesetJson) as? List<*> ?: continue
                for (s in steps) {
                    val m = s as? Map<*, *> ?: continue
                    val type = m["type"] as? String
                    when (type) {
                        "attr" -> {
                            // 対象要素を特定：label またはインデックスから現在の選択/ドキュメントを探す
                            // ここではシンプルに、doc.root から一致する属性変更を探して適用
                            val key = m["key"] as? String ?: continue
                            val newV = m["newV"] as? String
                            // 該当する要素を doc.root から走査して属性を変更（簡易実装）
                            applyAttrChangeToDoc(key, newV, batch)
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                // スキップ
            }
        }
        val cs = batch.endAndCommit("play macro: ${macro.name}")
        if (cs != null) {
            undoStack.push(cs)
            notifyChanged()
            notifySelection()
        }
    }

    /** ドキュメント内で属性変更を再適用する（マクロ再生用・簡易実装） */
    private fun applyAttrChangeToDoc(key: String, newV: String?, batch: dev.svgpro.core.edit.EditBatch) {
        // 選択中の要素に優先して適用
        val targets = if (selection.isNotEmpty()) selection else listOf(doc.root)
        for (el in targets) {
            if (el.hasAttr(key) || key == "fill" || key == "stroke") {
                batch.touch(el)
                el.setAttr(key, newV)
            }
        }
    }

    /** マクロを削除する */
    fun deleteMacro(index: Int) {
        if (index in macros.indices) {
            macros.removeAt(index)
            notifyChanged()
        }
    }

    // ―――― ラスタライズ（カテゴリ12） ――――

    /** 選択要素をラスタライズして埋め込み画像（data URI PNG）に変換する。
     *  @param scale 出力ピクセル寸法の倍率（1.0 = viewBoxサイズ）
     */
    fun rasterizeSelection(scale: Double = 1.0) {
        if (selection.isEmpty()) return
        val bounds = trace.boundsOf(selection) ?: return
        val w = (bounds.width * scale).coerceAtLeast(1.0).toInt()
        val h = (bounds.height * scale).coerceAtLeast(1.0).toInt()
        val bmp = renderToBitmap(Pair(w, h), transparent = true, selectionOnly = true)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        val dataUri = "data:image/png;base64,$base64"

        val changes = ArrayList<dev.svgpro.core.edit.Change>()
        val newSel = ArrayList<dev.svgpro.core.svg.Element>()
        for (el in selection.toList()) {
            val parent = findParent(el) ?: continue
            val idx = parent.children.indexOf(el).coerceAtLeast(0)
            val img = dev.svgpro.core.svg.Element("image")
            img.setAttr("href", dataUri)
            img.setAttr("xlink:href", dataUri)
            img.setAttr("x", dev.svgpro.core.util.Numbers.format(bounds.x))
            img.setAttr("y", dev.svgpro.core.util.Numbers.format(bounds.y))
            img.setAttr("width", dev.svgpro.core.util.Numbers.format(bounds.width))
            img.setAttr("height", dev.svgpro.core.util.Numbers.format(bounds.height))
            changes.add(Attach(img, parent, idx, doc.root, "rasterize"))
            changes.add(Detach(el, parent, idx, "rasterize"))
            newSel.add(img)
        }
        if (changes.isEmpty()) return
        val cs = ChangeSet(changes, "rasterize", newSel)
        cs.apply()
        selection.clear()
        selection.addAll(newSel)
        undoStack.push(cs)
        invalidateTrace()
        updateNodeSession()
        notifyChanged()
        notifySelection()
    }

    // ―――― ブラシプリセット（カテゴリ17） ――――

    /** ブラシプリセット（ストローク設定の保存済みセット）。 */
    data class BrushPreset(
        val name: String,
        val strokeWidth: Double,
        val strokeCap: String,   // butt / round / square
        val strokeJoin: String,  // miter / round / bevel
        val dashArray: List<Double>?, // null = 実線
        val strokeColor: Int,    // ARGB
    )

    /** ブラシプリセット一覧。 */
    val brushPresets = mutableListOf<BrushPreset>()

    /** 現在のストローク設定をブラシプリセットとして保存する。 */
    fun saveBrushPreset(name: String) {
        brushPresets.removeAll { it.name == name }
        val firstSel = selection.firstOrNull()
        brushPresets.add(BrushPreset(
            name,
            curStrokeWidth,
            firstSel?.let { StyleUtil.getProperty(it, "stroke-linecap") } ?: "butt",
            firstSel?.let { StyleUtil.getProperty(it, "stroke-linejoin") } ?: "miter",
            firstSel?.let { dashArrayOf(it) } ?: null,
            (curStroke as? dev.svgpro.core.model.Paint.Solid)?.argb ?: 0xFF000000.toInt(),
        ))
        saveBrushPresets()
        notifyChanged()
    }

    /** ブラシプリセットを現在のストローク設定に適用する。 */
    fun applyBrushPreset(index: Int) {
        if (index !in brushPresets.indices) return
        val p = brushPresets[index]
        curStrokeWidth = p.strokeWidth
        curStroke = dev.svgpro.core.model.Paint.Solid(p.strokeColor)
        onChanged()
        // 適用はプロパティパネル側で個別に反映（キャップ/ジョイン/ダッシュ/色）
    }

    /** ブラシプリセットを削除する。 */
    fun deleteBrushPreset(index: Int) {
        if (index in brushPresets.indices) {
            brushPresets.removeAt(index)
            saveBrushPresets()
            notifyChanged()
        }
    }

    /** 要素のダッシュ配列を取得（null = 実線）。 */
    private fun dashArrayOf(el: dev.svgpro.core.svg.Element): List<Double>? {
        val v = StyleUtil.getProperty(el, "stroke-dasharray")
        if (v == null || v.equals("none", true)) return null
        return v.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.mapNotNull { dev.svgpro.core.util.Numbers.parse(it) }
    }

    /** ブラシプリセットをドキュメント属性に保存（可逆）。 */
    private fun saveBrushPresets() {
        val arr = brushPresets.mapIndexed { i, p ->
            mapOf(
                "id" to "brush_$i",
                "name" to p.name,
                "strokeWidth" to dev.svgpro.core.util.Numbers.format(p.strokeWidth),
                "strokeCap" to p.strokeCap,
                "strokeJoin" to p.strokeJoin,
                "dashArray" to (p.dashArray?.joinToString(",") { dev.svgpro.core.util.Numbers.format(it) } ?: "none"),
                "strokeColor" to dev.svgpro.core.model.Color.css(p.strokeColor),
            )
        }
        doc.root.setAttr("data-svgpro-brush-presets", dev.svgpro.core.util.Json.stringify(arr))
    }

    /** ブラシプリセットをドキュメント属性から復元。 */
    fun restoreBrushPresets() {
        brushPresets.clear()
        val s = doc.root.attr("data-svgpro-brush-presets") ?: return
        try {
            val arr = dev.svgpro.core.util.Json.parse(s) ?: return
            if (arr is List<*>) {
                for (item in arr) {
                    if (item is Map<*, *>) {
                        val name = item["name"]?.toString() ?: "ブラシ"
                        val strokeWidth = item["strokeWidth"]?.toString()?.toDoubleOrNull() ?: 2.0
                        val strokeCap = item["strokeCap"]?.toString() ?: "butt"
                        val strokeJoin = item["strokeJoin"]?.toString() ?: "miter"
                        val dashStr = item["dashArray"]?.toString() ?: "none"
                        val dashArray = if (dashStr.equals("none", true)) null else dashStr.split(",").filter { it.isNotEmpty() }.mapNotNull { dev.svgpro.core.util.Numbers.parse(it) }
                        val strokeColor = dev.svgpro.core.model.Color.parse(item["strokeColor"]?.toString()) ?: 0xFF000000.toInt()
                        brushPresets.add(BrushPreset(name, strokeWidth, strokeCap, strokeJoin, dashArray, strokeColor))
                    }
                }
            }
        } catch (e: Exception) {
            // 解析失敗時は空のまま
        }
    }

}

enum class GeometryOpKind { UNION, DIFFERENCE, INTERSECTION, XOR }

/** Vecの変換ヘルパ（行列をdeltaに適用） */
internal fun Matrix.transformVec(v: Vec): Vec {
    // 線形成分のみ（平行移動なし）
    return Vec(a * v.x + c * v.y, b * v.x + d * v.y)
}
