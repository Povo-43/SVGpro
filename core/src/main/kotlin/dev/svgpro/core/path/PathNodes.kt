package dev.svgpro.core.path

import dev.svgpro.core.geom.Vec
import kotlin.math.abs

/**
 * サブパスを「ノード（アンカー+ハンドル）列」として編集するためのモデル。
 *
 * 不変条件:
 *  - nodes[0] が Move の座標
 *  - segTypes[i] は nodes[i] → nodes[(i+1) % n] のセグメント（closed時は末尾セグメントが nodes[n-1] → nodes[0]）
 *  - 閉じパスでは nodes[0].cin が「末尾セグメントのこの点側ハンドル」を示す
 *  - ハンドルは絶対座標
 */
enum class NodeKind { CORNER, SMOOTH, SYMMETRIC }

/** ノード1点 */
class NodePt(
    var p: Vec,
    /** 前セグメント側のハンドル（絶対座標）。 */
    var cin: Vec?,
    /** 次セグメント側のハンドル（絶対座標） */
    var cout: Vec?,
)

/** セグメント種別 */
enum class SegType { LINE, CUBIC, QUAD }

/** 1サブパスのノード列モデル */
class SubpathNodes(
    val nodes: MutableList<NodePt>,
    val segTypes: MutableList<SegType>,
    var closed: Boolean,
) {
    val nodeCount: Int get() = nodes.size

    /** セグメント数（閉パス = node数、開パス = node数 - 1） */
    val segmentCount: Int get() = if (closed) nodes.size else (nodes.size - 1).coerceAtLeast(0)

    fun nodeKind(j: Int): NodeKind = kindOf(nodes[j])

    private fun kindOf(n: NodePt): NodeKind {
        val cin = n.cin ?: return NodeKind.CORNER
        val cout = n.cout ?: return NodeKind.CORNER
        val a = cin - n.p
        val b = cout - n.p
        val la = a.length()
        val lb = b.length()
        if (la < 1e-9 || lb < 1e-9) return NodeKind.CORNER
        val cross = abs(a.cross(b)) / (la * lb)
        if (cross > 1e-4) return NodeKind.CORNER
        val ratio = if (lb >= la) lb / la else la / lb
        return if (abs(ratio - 1.0) < 1e-4) NodeKind.SYMMETRIC else NodeKind.SMOOTH
    }

    /** セグメント j の種別 */
    fun segTypeOf(j: Int): SegType = if (j in segTypes.indices) segTypes[j] else SegType.LINE

    // ―――― 編集操作 ――――

    /** ノード移動（ハンドルも平行移動） */
    fun moveNode(j: Int, delta: Vec) {
        val n = nodes[j]
        n.p += delta
        n.cin?.let { n.cin = it + delta }
        n.cout?.let { n.cout = it + delta }
    }

    /** ノードの絶対位置設定 */
    fun setNodePosition(j: Int, newPos: Vec) {
        moveNode(j, newPos - nodes[j].p)
    }

    /** 複数ノードの一括移動 */
    fun moveNodes(indices: List<Int>, delta: Vec) {
        for (j in indices) {
            if (j in nodes.indices) moveNode(j, delta)
        }
    }

    /** ハンドル移動。mirror で対側ハンドルを連動させる */
    fun setHandle(j: Int, inHandle: Boolean, pos: Vec, mirror: HandleMirror) {
        val n = nodes[j]
        if (inHandle) {
            n.cin = pos
            when (mirror) {
                HandleMirror.SYMMETRIC -> n.cout?.let { n.cout = n.p - (pos - n.p) }
                HandleMirror.SMOOTH -> n.cout?.let {
                    val dir = (pos - n.p).normalized() * -(it - n.p).length()
                    n.cout = n.p + dir
                }
                HandleMirror.NONE -> {}
            }
        } else {
            n.cout = pos
            when (mirror) {
                HandleMirror.SYMMETRIC -> n.cin?.let { n.cin = n.p - (pos - n.p) }
                HandleMirror.SMOOTH -> n.cin?.let {
                    val dir = (pos - n.p).normalized() * -(it - n.p).length()
                    n.cin = n.p + dir
                }
                HandleMirror.NONE -> {}
            }
        }
    }

    /** ノード種別の強制変換 */
    fun convertKind(j: Int, kind: NodeKind) {
        val n = nodes[j]
        when (kind) {
            NodeKind.CORNER -> {
                n.cin = null
                n.cout = null
            }
            NodeKind.SMOOTH, NodeKind.SYMMETRIC -> {
                val prevP = prevNodePoint(j)
                val nextP = nextNodePoint(j)
                val defaultLen = defaultHandleLen()
                val lenIn = n.cin?.let { (it - n.p).length() } ?: ((prevP?.distanceTo(n.p) ?: defaultLen) / 3.0)
                val lenOut = n.cout?.let { (it - n.p).length() } ?: ((nextP?.distanceTo(n.p) ?: defaultLen) / 3.0)
                // 方向: 既存ハンドル優先、なければ隣接ノード方向
                val dIn = if (n.cin != null) (n.cin!! - n.p).normalized()
                else if (prevP != null) (prevP - n.p).normalized()
                else Vec(1.0, 0.0)
                val dOut = if (n.cout != null) (n.cout!! - n.p).normalized()
                else if (nextP != null) (nextP - n.p).normalized()
                else Vec(-1.0, 0.0)
                val dir = (dOut - dIn).normalized()
                if (kind == NodeKind.SMOOTH) {
                    // collinear・長さ独立
                    n.cin = n.p - dir * lenIn
                    n.cout = n.p + dir * lenOut
                } else {
                    // 対称・長さ同一
                    val len = (lenIn + lenOut) / 2.0
                    n.cin = n.p - dir * len
                    n.cout = n.p + dir * len
                }
            }
        }
    }

    private fun prevNodePoint(j: Int): Vec? {
        val idx = if (j == 0) (if (closed) nodes.size - 1 else -1) else j - 1
        return if (idx >= 0) nodes[idx].p else null
    }

    private fun nextNodePoint(j: Int): Vec? {
        val idx = (j + 1) % nodes.size
        return if (closed || j + 1 < nodes.size) nodes[idx].p else null
    }

    private fun defaultHandleLen(): Double {
        var l = 0.0
        for (j in 0 until nodeCount) l += nodeDist(j, j + 1)
        return l / nodeCount / 3.0
    }

    private fun nodeDist(a: Int, b: Int): Double {
        val ia = a % nodes.size
        val ib = b % nodes.size
        return nodes[ia].p.distanceTo(nodes[ib].p)
    }

    /**
     * セグメント j を t で分割して新しいノードを挿入する（リング対応）。
     * @return 挿入されたノードのindex
     */
    fun insertNodeAt(j: Int, t: Double): Int {
        require(j >= 0 && j < segmentCount) { "segment index out of range: $j" }
        val idxA = j
        val idxB = (idxA + 1) % nodes.size
        val a = nodes[idxA]
        val b = nodes[idxB]
        val type = segTypeOf(j)

        val newNode: NodePt
        when (type) {
            SegType.LINE -> {
                val q = a.p + (b.p - a.p) * t
                newNode = NodePt(q, null, null)
                nodes.add(idxB, newNode)
                segTypes.add(idxB, SegType.LINE)
            }
            SegType.QUAD -> {
                val c = a.cout ?: b.cin ?: midpoint(a.p, b.p)
                val (left, right) = Bezier.quadSplit(a.p, c, b.p, t)
                // left: [p0, c01, q] / right: [q, c12, p3]
                val q = left[2]
                a.cout = left[1]
                newNode = NodePt(q, left[1], right[1])
                nodes.add(idxB, newNode)
                segTypes[idxA] = SegType.QUAD
                segTypes.add(idxB, SegType.QUAD)
            }
            SegType.CUBIC -> {
                val c1 = a.cout ?: bezierInterpA(a.p, b.p)
                val c2 = b.cin ?: bezierInterpB(a.p, b.p)
                val (left, right) = Bezier.cubicSplit(a.p, c1, c2, b.p, t)
                val q = left[3]
                a.cout = left[1]
                newNode = NodePt(q, left[2], right[1])
                nodes.add(idxB, newNode)
                segTypes[idxA] = SegType.CUBIC
                segTypes.add(idxB, SegType.CUBIC)
            }
        }
        return idxB
    }

    private fun midpoint(a: Vec, b: Vec): Vec = Vec((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
    private fun bezierInterpA(a: Vec, b: Vec) = Vec((a.x * 2.0 + b.x) / 3.0, (a.y * 2.0 + b.y) / 3.0)
    private fun bezierInterpB(a: Vec, b: Vec) = Vec((a.x + b.x * 2.0) / 3.0, (a.y + b.y * 2.0) / 3.0)

    /**
     * ノード削除。隣接セグメントを統合する。
     * @return 削除に成功したか（ノード数が足りない場合 false）
     */
    fun removeNode(j: Int): Boolean {
        if (nodes.size <= 2) return false
        val n = nodes.size
        val prevIdx = if (j == 0 && closed) n - 1 else j - 1
        val nextIdx = (j + 1) % n

        // 隣接セグメント統合: prev → j → next から prev → next へ
        val prev = nodes[prevIdx]
        val next = nodes[nextIdx]
        val mergedType = if (prev.cout == null && next.cin == null) SegType.LINE else SegType.CUBIC

        if (mergedType == SegType.LINE) {
            prev.cout = null
            next.cin = null
        }

        nodes.removeAt(j)

        // segTypes: index prevIdx (=統合後の該当セグメント), 旧 index j（out側）を除去
        if (prevIdx >= 0 && prevIdx < segTypes.size) {
            segTypes[prevIdx] = mergedType
        }
        val removeIdx = j
        if (removeIdx >= 0 && removeIdx < segTypes.size) {
            segTypes.removeAt(removeIdx)
        }
        return true
    }

    /** パスを閉じる（直線で結ぶ） */
    fun closeSubpath() {
        if (closed) return
        if (nodes.size < 2) return
        closed = true
        nodes[0].cin = null
        nodes[nodeCount - 1].cout = null
        segTypes.add(SegType.LINE)
    }

    /** パスを開く */
    fun openSubpath() {
        if (!closed) return
        closed = false
        nodes[0].cin = null
        if (segTypes.isNotEmpty()) segTypes.removeAt(segTypes.size - 1)
    }

    companion object {
        /** PathDataのサブパス sp をノード列モデルへ（アークは *.cubic化* される） */
        fun fromSubpath(pd: PathData, sp: dev.svgpro.core.path.SubPath): SubpathNodes? {
            val nodes = ArrayList<NodePt>()
            val segTypes = ArrayList<SegType>()
            val move = pd.segs[sp.segStart] as Seg.Move
            nodes.add(NodePt(Vec(move.x, move.y), null, null))
            var cur = Vec(move.x, move.y)
            for (i in sp.segStart + 1 until sp.segEnd) {
                when (val s = pd.segs[i]) {
                    is Seg.Line -> {
                        segTypes.add(SegType.LINE)
                        nodes.add(NodePt(Vec(s.x, s.y), null, null))
                        cur = Vec(s.x, s.y)
                    }
                    is Seg.Cubic -> {
                        nodes.last().cout = Vec(s.c1x, s.c1y)
                        segTypes.add(SegType.CUBIC)
                        nodes.add(NodePt(Vec(s.x, s.y), Vec(s.c2x, s.c2y), null))
                        cur = Vec(s.x, s.y)
                    }
                    is Seg.Quad -> {
                        val c = Vec(s.cx, s.cy)
                        nodes.last().cout = c
                        segTypes.add(SegType.QUAD)
                        nodes.add(NodePt(Vec(s.x, s.y), c, null))
                        cur = Vec(s.x, s.y)
                    }
                    is Seg.Arc -> {
                        // A記法はノード編集のためにcubicへ展開（書き戻し時 A→C。表示は同一）
                        for ((c1, c2, e) in dev.svgpro.core.path.ArcConverter.arcToCubics(cur, s)) {
                            nodes.last().cout = c1
                            segTypes.add(SegType.CUBIC)
                            nodes.add(NodePt(e, c2, null))
                        }
                        cur = Vec(s.x, s.y)
                    }
                    Seg.Close, is Seg.Move -> {}
                }
            }
            if (nodes.size < 2) return null
            if (sp.closed) {
                // 閉じセグメント（末尾node → node0）を含む1セグメント分を追加
                // このセグメントの制御点情報は、末尾の描画セグメントのものと同一性格（無制御なら LINE）
                val lastDrawingSeg = pd.segs[sp.segEnd - 1]
                when (lastDrawingSeg) {
                    is Seg.Cubic -> {
                        nodes[0].cin = Vec(lastDrawingSeg.c2x, lastDrawingSeg.c2y)
                        segTypes.add(segTypes.last())
                    }
                    is Seg.Quad -> {
                        nodes[0].cin = Vec(lastDrawingSeg.cx, lastDrawingSeg.cy)
                        segTypes.add(segTypes.last())
                    }
                    else -> segTypes.add(SegType.LINE)
                }
            }
            return SubpathNodes(nodes, segTypes, sp.closed)
        }

        /** ノード列 model → 正規セグメント列（Move ... [L|C|Q] ... [可选 Close]） */
        fun toSegs(model: SubpathNodes): List<Seg> {
            val segs = ArrayList<Seg>()
            val n = model.nodes.size
            if (n == 0) return segs
            val p0 = model.nodes[0]
            segs.add(Seg.Move(p0.p.x, p0.p.y))
            val count = model.segmentCount
            for (j in 0 until count) {
                val a = model.nodes[j]
                val b = model.nodes[(j + 1) % n]
                val baseType = if (j < model.segTypes.size) model.segTypes[j] else SegType.LINE
                val cOut = a.cout
                val cIn = b.cin

                // 実効型: LINEでもハンドルがあればCUBICへ / QUADでハンドルが乖離したらCUBICへ
                val effective: SegType = when {
                    baseType == SegType.LINE -> if (cOut != null || cIn != null) SegType.CUBIC else SegType.LINE
                    baseType == SegType.QUAD ->
                        if (cOut != null && cIn != null &&
                            abs(cOut.x - cIn.x) < 1e-9 && abs(cOut.y - cIn.y) < 1e-9
                        ) SegType.QUAD else SegType.CUBIC
                    else -> SegType.CUBIC
                }

                when (effective) {
                    SegType.LINE -> segs.add(Seg.Line(b.p.x, b.p.y))
                    SegType.QUAD -> {
                        val c = cOut ?: cIn ?: Vec(0.0, 0.0)
                        segs.add(Seg.Quad(c.x, c.y, b.p.x, b.p.y))
                    }
                    SegType.CUBIC -> {
                        val c1 = cOut ?: interpA(a.p, b.p)
                        val c2 = cIn ?: interpB(a.p, b.p)
                        segs.add(Seg.Cubic(c1.x, c1.y, c2.x, c2.y, b.p.x, b.p.y))
                    }
                }
            }
            if (model.closed) {
                segs.add(Seg.Close)
            }
            return segs
        }

        private fun interpA(a: Vec, b: Vec) = Vec((a.x * 2.0 + b.x) / 3.0, (a.y * 2.0 + b.y) / 3.0)
        private fun interpB(a: Vec, b: Vec) = Vec((a.x + b.x * 2.0) / 3.0, (a.y + b.y * 2.0) / 3.0)
    }
}

enum class HandleMirror { NONE, SMOOTH, SYMMETRIC }
