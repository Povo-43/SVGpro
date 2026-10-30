package dev.svgpro.core.path

import dev.svgpro.core.geom.Vec
import kotlin.math.abs

/**
 * ノードモデル（SubpathNodes）に対する高度な編集プリミティブ。
 * セグメント種別変換・セグメント削除・端点接続・ノード位置での分割・ノード削減など。
 */

/** NodePtの深いコピー */
fun NodePt.copy(): NodePt = NodePt(Vec(p.x, p.y), cin?.let { Vec(it.x, it.y) }, cout?.let { Vec(it.x, it.y) })

/** 逆向きトラバース用に in/out ハンドルを入れ替えたコピー */
fun NodePt.reversedHandles(): NodePt = NodePt(Vec(p.x, p.y), cout?.let { Vec(it.x, it.y) }, cin?.let { Vec(it.x, it.y) })

/** セグメント j が曲線（制御点を実際に使う）か */
fun SubpathNodes.isSegmentCurve(j: Int): Boolean {
    if (j !in 0 until segmentCount) return false
    val a = nodes[j]
    val b = nodes[(j + 1) % nodes.size]
    return a.cout != null || b.cin != null
}

/** セグメント j を直線へ変換（両側ハンドルを除去） */
fun SubpathNodes.setSegmentLine(j: Int) {
    if (j !in 0 until segmentCount) return
    val a = nodes[j]
    val b = nodes[(j + 1) % nodes.size]
    a.cout = null
    b.cin = null
    if (j < segTypes.size) segTypes[j] = SegType.LINE
}

/** セグメント j を曲線へ変換（形状を保ったままハンドルを追加: 直線方向に約1/3配置） */
fun SubpathNodes.setSegmentCurve(j: Int) {
    if (j !in 0 until segmentCount) return
    val a = nodes[j]
    val b = nodes[(j + 1) % nodes.size]
    if (a.cout == null && b.cin == null) {
        val d = b.p - a.p
        val len = d.length()
        if (len < 1e-9) return
        val dir = d.normalized()
        val h = len / 3.0
        a.cout = a.p + dir * h
        b.cin = b.p - dir * h
    }
    if (j < segTypes.size && segTypes[j] == SegType.LINE) segTypes[j] = SegType.CUBIC
}

/**
 * セグメント j を削除する。
 * 閉パス → その位置で開く（全ノード保持、1つの開サブパス）。
 * 開パス → 分割（先頭/末尾セグメントなら1つに縮約、中間なら2つのサブパス）。
 * @return 分割後のサブパス。index不整合時はnull
 */
fun SubpathNodes.removeSegment(j: Int): List<SubpathNodes>? {
    if (j !in 0 until segmentCount) return null
    val n = nodes.size
    if (closed) {
        if (n < 3) return null
        // リングからセグメント j を取り除くと、開いた鎖 (j+1 → j+2 → … → j) になる（全ノード保持）
        val newNodes = ArrayList<NodePt>(n)
        val newTypes = ArrayList<SegType>(n - 1)
        for (k in 0 until n) {
            val src = (j + 1 + k) % n
            newNodes.add(nodes[src].copy())
        }
        // ノード newNodes[k] は元ノード (j+1+k)%n。セグメント newNodes[k]→newNodes[k+1] は元セグメント (j+1+k)%n
        for (k in 0 until n - 1) {
            newTypes.add(segTypeOf((j + 1 + k) % n))
        }
        // 切断面: 先頭ノード（元 j+1）の入力ハンドルと、末尾ノード（元 j）の出力ハンドルは削除セグメント由来 → null
        newNodes.first().cin = null
        newNodes.last().cout = null
        return listOf(SubpathNodes(newNodes, newTypes, closed = false))
    } else {
        // 開パス: j の前後で分ける
        if (segmentCount < 2) return null
        if (j == 0) {
            // 先頭セグメント削除 = 先頭ノードを落とす
            val tail = SubpathNodes(
                ArrayList(nodes.subList(1, nodes.size)),
                ArrayList(segTypes.subList(1, segTypes.size)),
                closed = false,
            )
            return listOf(tail)
        }
        if (j == segmentCount - 1) {
            val head = SubpathNodes(
                ArrayList(nodes.subList(0, nodes.size - 1)),
                ArrayList(segTypes.subList(0, segTypes.size - 1)),
                closed = false,
            )
            return listOf(head)
        }
        val headNodes = ArrayList<NodePt>(nodes.subList(0, j + 1))
        val tailNodes = ArrayList<NodePt>(nodes.subList(j + 1, nodes.size))
        val headTypes = ArrayList(segTypes.subList(0, j))
        val tailTypes = ArrayList(segTypes.subList(j + 1, segTypes.size))
        headNodes.last().cout = null
        tailNodes.first().cin = null
        return listOf(
            SubpathNodes(headNodes, headTypes, closed = false),
            SubpathNodes(tailNodes, tailTypes, closed = false),
        )
    }
}

/**
 * 複数セグメントの一括削除。閉パス → 削除セグメント数と同数の開鎖chain。
 * 開パス → 削除位置で分割されたchain列。
 * @return 分割後のサブパス列（全セグメント削除時は空）
 */
fun SubpathNodes.removeSegments(selected: Set<Int>): List<SubpathNodes> {
    val n = nodes.size
    if (n < 2) return listOf(this)
    val result = ArrayList<SubpathNodes>()
    if (closed) {
        if (selected.size >= segmentCount) return emptyList()
        // 真のchain開始点 = 「削除セグメントの直後の保持セグメント」（環状の継ぎ目を避ける）
        var start = -1
        for (k in 0 until n) {
            val prev = (k - 1 + n) % n
            if (k !in selected && prev in selected) {
                start = k
                break
            }
        }
        if (start < 0) return emptyList()
        var chainNodes = ArrayList<NodePt>()
        var chainTypes = ArrayList<SegType>()
        for (k in 0 until n) {
            val seg = (start + k) % n
            if (seg in selected) {
                if (chainTypes.isNotEmpty()) {
                    chainNodes.last().cout = null
                    result.add(SubpathNodes(chainNodes, chainTypes, closed = false))
                    chainNodes = ArrayList()
                    chainTypes = ArrayList()
                }
                continue
            }
            if (chainTypes.isEmpty()) {
                // chain開始ノード = セグメントsegの始点ノード（入力側は削除済み → cin=null）
                val a = nodes[seg]
                chainNodes.add(NodePt(Vec(a.p.x, a.p.y), null, a.cout?.let { Vec(it.x, it.y) }))
            }
            chainTypes.add(segTypeOf(seg))
            val b = nodes[(seg + 1) % n]
            chainNodes.add(NodePt(Vec(b.p.x, b.p.y), b.cin?.let { Vec(it.x, it.y) }, b.cout?.let { Vec(it.x, it.y) }))
        }
        if (chainTypes.isNotEmpty()) {
            chainNodes.last().cout = null
            result.add(SubpathNodes(chainNodes, chainTypes, closed = false))
        }
        return result
    } else {
        // 開パス: 削除セグメントで区切られたrun
        var chainNodes = ArrayList<NodePt>()
        var chainTypes = ArrayList<SegType>()
        for (j in 0 until segmentCount) {
            if (j in selected) {
                if (chainTypes.isNotEmpty()) {
                    chainNodes.last().cout = null
                    result.add(SubpathNodes(chainNodes, chainTypes, closed = false))
                    chainNodes = ArrayList()
                    chainTypes = ArrayList()
                }
                continue
            }
            if (chainTypes.isEmpty()) {
                val a = nodes[j]
                chainNodes.add(NodePt(Vec(a.p.x, a.p.y), a.cin?.let { Vec(it.x, it.y) }, a.cout?.let { Vec(it.x, it.y) }))
            }
            chainTypes.add(segTypeOf(j))
            val b = nodes[j + 1]
            chainNodes.add(NodePt(Vec(b.p.x, b.p.y), b.cin?.let { Vec(it.x, it.y) }, b.cout?.let { Vec(it.x, it.y) }))
        }
        if (chainTypes.isNotEmpty()) {
            chainNodes.last().cout = null
            result.add(SubpathNodes(chainNodes, chainTypes, closed = false))
        }
        return result
    }
}

/**
 * 開いたサブパスの端点同士を接続して閉じる（接続セグメントは直線）。
 * @return 接続できたか
 */
fun SubpathNodes.joinOwnEnds(): Boolean {
    if (closed || nodes.size < 2) return false
    // 閉じる前に、接続セグメント用のハンドルがあれば保持（out側=末尾ノード、in側=先頭ノード）
    val last = nodes[nodeCount - 1]
    val first = nodes[0]
    closed = true
    segTypes.add(if (last.cout == null && first.cin == null) SegType.LINE else SegType.CUBIC)
    return true
}

/**
 * ノード j の位置でパスを切断する。
 * 閉パス → j を始点/終点として開く（ノード +1、1つの開サブパス）。
 * 開パス → j を境に2つの開サブパスに分割（j は両方に属す）。
 * @return 分割後のサブパス列
 */
fun SubpathNodes.splitAtNode(j: Int): List<SubpathNodes> {
    val n = nodes.size
    if (j !in 0 until n) return listOf(this)
    if (closed) {
        // 閉パス: ノード j で開き、j を複製して終点にする
        // 新しい鎖: j → j+1 → … → j-1 → j(複製)。セグメントは元の n 個すべて
        val newNodes = ArrayList<NodePt>(n + 1)
        val newTypes = ArrayList<SegType>(n)
        for (k in 0 until n) {
            val src = (j + k) % n
            newNodes.add(nodes[src].copy())
        }
        // 複製終点（元ノード j）
        newNodes.add(nodes[j].copy().let { it.cout = null; it })
        // newNodes[k] は元ノード (j+k)%n。セグメント newNodes[k]→newNodes[k+1] は元セグメント (j+k)%n
        for (k in 0 until n) {
            newTypes.add(segTypeOf((j + k) % n))
        }
        // 切断面: 先頭（元 j）の入力ハンドルは「元セグメント j-1」のもの → 終点複製へ移す
        newNodes.first().cin = null
        newNodes.last().cout = null
        return listOf(SubpathNodes(newNodes, newTypes, closed = false))
    } else {
        // 開パス: j で2分割。j は両方に属する（複製）
        if (j == 0 || j == n - 1) {
            // 端点での切断 = 何もしない（すでに開始/終了点）
            return listOf(this)
        }
        val headNodes = ArrayList<NodePt>(j + 1)
        for (k in 0..j) headNodes.add(nodes[k].copy())
        val tailNodes = ArrayList<NodePt>(n - j)
        for (k in j until n) tailNodes.add(nodes[k].copy())
        val headTypes = ArrayList(segTypes.subList(0, j))
        val tailTypes = ArrayList(segTypes.subList(j, segTypes.size))
        headNodes.last().cout = null
        tailNodes.first().cin = null
        return listOf(
            SubpathNodes(headNodes, headTypes, closed = false),
            SubpathNodes(tailNodes, tailTypes, closed = false),
        )
    }
}

/**
 * ノード削減: ノードを削除しても形状の許容誤差 tol 未満しか変わらないノードを貪欲に除去する。
 * 曲線はハンドルを保持したまま統合する。
 * @return 除去したノード数
 */
fun SubpathNodes.reduceNodes(tol: Double): Int {
    if (tol <= 0.0) return 0
    var removed = 0
    var changed = true
    var guard = 0
    outer@ while (changed && guard++ < 1000) {
        changed = false
        val n = nodes.size
        val minNodes = if (closed) 3 else 2
        if (n <= minNodes) break
        // 除去候補を走査（端点は開パスでは保護）
        var j = if (closed) 0 else 1
        while (j < (if (closed) n else n - 1) && nodes.size > minNodes) {
            val candidate = deviationIfRemoved(j)
            if (candidate != null && candidate <= tol) {
                if (removeNode(j)) {
                    removed++
                    changed = true
                    // j の位置を詰め直して続行（indexは削除でシフト）
                    continue@outer
                }
            }
            j++
        }
    }
    return removed
}

/** ノード j を削除した場合の形状逸脱（最大距離）を測る。測定不能ならnull */
private fun SubpathNodes.deviationIfRemoved(j: Int): Double? {
    val n = nodes.size
    val closedP = closed
    val prevIdx = if (j == 0 && closedP) n - 1 else j - 1
    val nextIdx = (j + 1) % n
    if (prevIdx < 0 || nextIdx == j) return null
    val prev = nodes[prevIdx]
    val target = nodes[j]
    val next = nodes[nextIdx]

    // 元: prev → target（seg prevIdx）, target → next（seg j）
    val original = flattenOneSegment(prevIdx)?.let { before ->
        flattenOneSegment(j)?.let { after -> before + after.drop(1) }
    } ?: return null

    // 統合後: prev → next。removeNode と同じ規則でハンドルを合成
    val merged: List<Vec> = if (prev.cout == null && next.cin == null) {
        listOf(prev.p, next.p)
    } else {
        val c1 = prev.cout ?: Vec((prev.p.x * 2 + target.p.x) / 3, (prev.p.y * 2 + target.p.y) / 3)
        val c2 = next.cin ?: Vec((prev.p.x + target.p.x * 2) / 3, (prev.p.y + target.p.y * 2) / 3)
        dev.svgpro.core.path.Bezier.cubicFlatten(prev.p, c1, c2, next.p, 0.01)
    }

    var maxD = 0.0
    for (p in original) {
        val d = distanceToPolyline(p, merged)
        if (d > maxD) maxD = d
    }
    return maxD
}

/** 点と折れ線の最近距離 */
internal fun distanceToPolyline(p: Vec, pts: List<Vec>): Double {
    if (pts.isEmpty()) return Double.MAX_VALUE
    if (pts.size == 1) return p.distanceTo(pts[0])
    var best = Double.MAX_VALUE
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        val ab = b - a
        val len2 = ab.dot(ab)
        val t = if (len2 < 1e-12) 0.0 else (((p - a).dot(ab)) / len2).coerceIn(0.0, 1.0)
        val q = a + ab * t
        val d = p.distanceTo(q)
        if (d < best) best = d
    }
    return best
}

/** セグメント j のフラット化点列（両端含む） */
private fun SubpathNodes.flattenOneSegment(j: Int): List<Vec>? {
    if (j !in 0 until segmentCount) return null
    val a = nodes[j]
    val b = nodes[(j + 1) % nodes.size]
    return when (segTypeOf(j)) {
        SegType.LINE -> listOf(a.p, b.p)
        SegType.QUAD -> {
            val c = a.cout ?: b.cin ?: return listOf(a.p, b.p)
            dev.svgpro.core.path.Bezier.quadFlatten(a.p, c, b.p, 0.01)
        }
        SegType.CUBIC -> {
            val c1 = a.cout ?: Vec((a.p.x * 2 + b.p.x) / 3, (a.p.y * 2 + b.p.y) / 3)
            val c2 = b.cin ?: Vec((a.p.x + b.p.x * 2) / 3, (a.p.y + b.p.y * 2) / 3)
            dev.svgpro.core.path.Bezier.cubicFlatten(a.p, c1, c2, b.p, 0.01)
        }
    }
}

/**
 * ハンドルを極座標で設定（アンカーからの長さ+角度）。
 * mirrorは呼び出し側で SubpathNodes.setHandle を併用する。
 */
fun SubpathNodes.setHandlePolar(j: Int, isIn: Boolean, length: Double, angleDeg: Double) {
    val n = nodes.getOrNull(j) ?: return
    val r = Math.toRadians(angleDeg)
    val pos = n.p + Vec(kotlin.math.cos(r) * length, kotlin.math.sin(r) * length)
    n.p // anchor unchanged
    if (isIn) n.cin = pos else n.cout = pos
    if (j < segTypes.size && segTypes[j] == SegType.LINE) {
        // ハンドルを付けたので曲線化（toSegsが実効判定するが明示しておく）
        segTypes[j] = SegType.CUBIC
    }
}

/**
 * 選択ノードの等間隔配置（座標の純粋計算）。
 * 端の2点は固定し、間のノードを座標順に等間隔へ並べる。
 * @return 新しい位置リスト（入力と同じ順）。配置不要ならnull
 */
fun distributeNodePositions(positions: List<Vec>, horizontal: Boolean): List<Vec>? {
    if (positions.size < 3) return null
    val order = positions.withIndex().sortedBy { if (horizontal) it.value.x else it.value.y }
    val first = order.first().value
    val last = order.last().value
    val total = if (horizontal) last.x - first.x else last.y - first.y
    if (total < 1e-9) return null
    val step = total / (order.size - 1)
    val out = positions.toMutableList()
    for (k in 1 until order.size - 1) {
        val (idx, pos) = order[k]
        val target = if (horizontal) first.x + step * k else first.y + step * k
        out[idx] = if (horizontal) Vec(target, pos.y) else Vec(pos.x, target)
    }
    return out
}

/**
 * 2つの開サブパスの端点ノードを接続して1つに統合する（Illustratorの「結合」相当）。
 * 同一サブパスの2端点が渡された場合は閉じる。接続セグメントは両端のハンドルがあれば曲線、無ければ直線。
 *
 * @param models サブパスリスト（統合で要素が減る）
 * @param a 接続するノード1
 * @param b 接続するノード2
 * @return 成功時の接続点NodeRef（統合後のサブパス内）
 */
fun joinSubpathEndNodes(
    models: MutableList<SubpathNodes>,
    a: NodeRef,
    b: NodeRef,
): NodeRef? {
    if (a.sub !in models.indices || b.sub !in models.indices) return null
    val A = models[a.sub]
    val B = models[b.sub]

    fun isEnd(m: SubpathNodes, i: Int): Boolean = i == 0 || i == m.nodes.size - 1

    if (a.sub == b.sub) {
        // 同一サブパス: 両端点なら閉じる
        if (A.closed) return null
        if (!(isEnd(A, a.node) && isEnd(A, b.node) && a.node != b.node)) return null
        if (A.joinOwnEnds()) return a
        return null
    }

    if (A.closed || B.closed) return null
    if (!(isEnd(A, a.node) && isEnd(B, b.node))) return null

    // AにBを連結する。接続セグメントは A の a.node ↔ B の b.node（両ノード保持）
    val aAtEnd = a.node == A.nodes.size - 1
    val bAtEnd = b.node == B.nodes.size - 1

    // Bを「bNodes[0] が接続側」になる向きに正規化
    val bNodes: List<NodePt> = if (!bAtEnd) {
        B.nodes
    } else {
        B.nodes.reversed().map { it.copy().reversedHandles() }
    }
    val bTypes: List<SegType> = if (!bAtEnd) B.segTypes else B.segTypes.reversed()

    val aEnd = A.nodes[a.node]
    val bConn = bNodes[0]
    val joinType = if (aEnd.cout != null || bConn.cin != null) SegType.CUBIC else SegType.LINE

    val newNodes = ArrayList<NodePt>()
    val newTypes = ArrayList<SegType>()
    var joinNodeIdx: Int
    if (aAtEnd) {
        // A.nodes + [join] + B連鎖（接続点 = A側 a.node）
        newNodes.addAll(A.nodes)
        newTypes.addAll(A.segTypes)
        newTypes.add(joinType)
        newNodes.addAll(bNodes)
        newTypes.addAll(bTypes)
        joinNodeIdx = a.node
    } else {
        // B連鎖(逆順) + [join] + A.nodes（接続点 = B側の接続ノードがAの先頭に隣接）
        val bRevNodes = bNodes.reversed().map { it.copy().reversedHandles() }
        val bRevTypes = bTypes.reversed()
        newNodes.addAll(bRevNodes)
        newTypes.addAll(bRevTypes)
        newTypes.add(joinType)
        newNodes.addAll(A.nodes)
        newTypes.addAll(A.segTypes)
        joinNodeIdx = bRevNodes.size - 1
    }

    val merged = SubpathNodes(newNodes, newTypes, closed = false)
    val keepIdx = if (a.sub < b.sub) a.sub else b.sub
    val removeIdx = if (a.sub < b.sub) b.sub else a.sub
    models[keepIdx] = merged
    models.removeAt(removeIdx)
    return NodeRef(keepIdx, joinNodeIdx)
}

/**
 * サブパス列を「開始点で整列」し直す（先頭ノードの座標を返すユーティリティ）
 */
fun SubpathNodes.firstPoint(): Vec = nodes.firstOrNull()?.p ?: Vec.ZERO

/** 選択ノード群の矩形 */
fun nodeBounds(points: List<Vec>): dev.svgpro.core.geom.Bounds? {
    if (points.isEmpty()) return null
    var minX = points[0].x; var maxX = minX
    var minY = points[0].y; var maxY = minY
    for (p in points) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }
    return dev.svgpro.core.geom.Bounds(minX, minY, maxX - minX, maxY - minY)
}

/** 数値的な同一性チェック（微小誤差許容） */
fun Vec.equalsTol(o: Vec, tol: Double = 1e-6): Boolean = abs(x - o.x) <= tol && abs(y - o.y) <= tol
