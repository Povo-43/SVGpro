package dev.svgpro.editor

import dev.svgpro.core.edit.Attach
import dev.svgpro.core.edit.ChildListChange
import dev.svgpro.core.edit.ChangeSet
import dev.svgpro.core.edit.Detach
import dev.svgpro.core.geom.Bounds
import dev.svgpro.core.geom.Matrix
import dev.svgpro.core.geom.Vec
import dev.svgpro.core.model.ClipMaskUtil
import dev.svgpro.core.model.Color
import dev.svgpro.core.model.GradientModel
import dev.svgpro.core.model.Paint
import dev.svgpro.core.model.Shapes
import dev.svgpro.core.model.StyleUtil
import dev.svgpro.core.model.SvgDocument
import dev.svgpro.core.ops.GeometryOps
import dev.svgpro.core.ops.StrokeCap
import dev.svgpro.core.ops.StrokeJoin
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import dev.svgpro.core.path.SubpathNodes
import dev.svgpro.core.path.distributeNodePositions
import dev.svgpro.core.path.equalsTol
import dev.svgpro.core.path.joinSubpathEndNodes
import dev.svgpro.core.path.reduceNodes
import dev.svgpro.core.path.removeSegments
import dev.svgpro.core.path.setHandlePolar
import dev.svgpro.core.path.setHandlePolar
import dev.svgpro.core.path.setSegmentCurve
import dev.svgpro.core.path.setSegmentLine
import dev.svgpro.core.path.splitAtNode
import dev.svgpro.core.svg.Element
import kotlin.math.abs

/**
 * Editorの拡張操作群（選択・整列・数値変形・複製・パス操作・クリップ/マスク・シンボル・グラデーション）。
 * すべてUndo統合（commit / ChangeSet）で実装し、SVGへ正しく保存される。
 */

// ―――――――――――――――――――――――――――――――――――――――――――
// 数値変形
// ―――――――――――――――――――――――――――――――――――――――――――

/** 数値変形操作（変形の繰り返し・複製に使う） */
sealed class TransformOp {
    data class Move(val dx: Double, val dy: Double) : TransformOp()
    data class Scale(val sx: Double, val sy: Double, val centerX: Double, val centerY: Double) : TransformOp()
    data class Rotate(val deg: Double, val centerX: Double, val centerY: Double) : TransformOp()
    data class Skew(val axDeg: Double, val ayDeg: Double, val centerX: Double, val centerY: Double) : TransformOp()
}

/** 傾斜変形 */
fun Editor.skewSelection(axDeg: Double, ayDeg: Double, centerUser: Vec) {
    if (selection.isEmpty()) return
    for (el in selection) {
        val parentInv = trace.parentAbsOf(el).invert()
        val cLocal = parentInv.apply(centerUser)
        val skew = Matrix(
            a = 1.0,
            b = kotlin.math.tan(Math.toRadians(ayDeg)),
            c = kotlin.math.tan(Math.toRadians(axDeg)),
            d = 1.0,
        )
        val m = Matrix(e = -cLocal.x, f = -cLocal.y).then(skew).then(Matrix(e = cLocal.x, f = cLocal.y))
        val elLocal = Shapes.localTransform(el)
        el.setAttr("transform", Matrix.toSvgString(elLocal.then(m)))
    }
    invalidateTrace()
    onChanged()
}

/** 複製してから数値変形を適用（複製は同位置・自動オフセットなし） */
fun Editor.applyTransform(op: TransformOp, duplicate: Boolean = false) {
    if (selection.isEmpty()) return
    if (duplicate) duplicateInPlace()
    if (selection.isEmpty()) return
    commit("transform") { batch ->
        for (el in selection) batch.touch(el)
        when (op) {
            is TransformOp.Move -> moveSelection(Vec(op.dx, op.dy))
            is TransformOp.Scale -> scaleSelection(op.sx, op.sy, Vec(op.centerX, op.centerY))
            is TransformOp.Rotate -> rotateSelection(op.deg, Vec(op.centerX, op.centerY))
            is TransformOp.Skew -> skewSelection(op.axDeg, op.ayDeg, Vec(op.centerX, op.centerY))
        }
    }
    recordTransform(op)
}

/** 同位置に複製する（undo統合・自動オフセットなし） */
fun Editor.duplicateInPlace(): Boolean {
    if (selection.isEmpty()) return false
    val sources = selection.toList()
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    val newSel = ArrayList<Element>()
    for (el in sources) {
        val parent = findParent(el) ?: continue
        val copy = el.deepCopy()
        copy.setAttr("id", null)
        changes.add(Attach(copy, parent, parent.children.indexOf(el) + 1, doc.root, "duplicate"))
        newSel.add(copy)
    }
    if (changes.isEmpty()) return false
    val cs = ChangeSet(changes, "duplicate", newSel)
    cs.apply()
    undoStack.push(cs)
    selection.clear()
    selection.addAll(newSel)
    invalidateTrace()
    updateNodeSession()
    notifyChanged()
    notifySelection()
    return true
}

/** 変形の繰り返し（直近の変形をもう一度適用） */
fun Editor.transformAgain(count: Int = 1, duplicateEach: Boolean = false) {
    val op = lastTransformOp ?: return
    repeat(count.coerceIn(1, 100)) {
        applyTransform(op, duplicate = duplicateEach)
    }
}

/** 選択範囲の左上を指定座標へ移動（数値配置） */
fun Editor.setSelectionPosition(x: Double, y: Double) {
    val b = trace.boundsOf(selection) ?: return
    applyTransform(TransformOp.Move(x - b.x, y - b.y))
}

/** 選択範囲を指定サイズへ拡大縮小（左上アンカー・数値指定） */
fun Editor.setSelectionSize(w: Double, h: Double) {
    val b = trace.boundsOf(selection) ?: return
    if (b.width < 1e-9 || b.height < 1e-9) return
    applyTransform(TransformOp.Scale(w / b.width, h / b.height, b.x, b.y))
}

// ―――――――――――――――――――――――――――――――――――――――――――
// 複製・反復
// ―――――――――――――――――――――――――――――――――――――――――――

/** 指定距離での複製（自動オフセットなしで複製してから移動） */
fun Editor.duplicateWithOffset(dx: Double, dy: Double) {
    if (selection.isEmpty()) return
    val sources = selection.toList()
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    val newSel = ArrayList<Element>()
    for (el in sources) {
        val parent = findParent(el) ?: continue
        val copy = el.deepCopy()
        copy.setAttr("id", null)
        val idx = parent.children.indexOf(el) + 1
        changes.add(Attach(copy, parent, idx, doc.root, "duplicate offset"))
        newSel.add(copy)
    }
    if (changes.isEmpty()) return
    val cs = ChangeSet(changes, "duplicate offset", newSel)
    cs.apply()
    undoStack.push(cs)
    selection.clear()
    selection.addAll(newSel)
    invalidateTrace()
    // 移動（1履歴に統合したいのでドラッグセッションで包む）
    val drag = beginDrag("duplicate offset", newSel)
    moveSelection(Vec(dx, dy))
    endDrag(drag)
    updateNodeSession()
    notifySelection()
    recordTransform(TransformOp.Move(dx, dy))
}

/** 回転中心を指定した反復複製（ロゴ・装飾向け） */
fun Editor.rotateCopies(count: Int, centerUser: Vec, totalDegrees: Double = 360.0) {
    val n = count.coerceIn(1, 360)
    if (selection.isEmpty()) return
    val step = totalDegrees / (n + 1)
    val sources = selection.toList()
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    val newSel = ArrayList<Element>()
    for (i in 1..n) {
        val deg = step * i
        for (src in sources) {
            val parent = findParent(src) ?: continue
            val copy = src.deepCopy()
            val parentInv = trace.parentAbsOf(src).invert()
            val cLocal = parentInv.apply(centerUser)
            val r = Matrix().rotateDeg(deg)
            val m = Matrix(e = -cLocal.x, f = -cLocal.y).then(r).then(Matrix(e = cLocal.x, f = cLocal.y))
            copy.setAttr("transform", Matrix.toSvgString(Shapes.localTransform(src).then(m)))
            val idx = parent.children.indexOf(src) + i
            changes.add(Attach(copy, parent, idx, doc.root, "rotate copies"))
            newSel.add(copy)
        }
    }
    if (changes.isEmpty()) return
    val cs = ChangeSet(changes, "rotate copies", newSel)
    cs.apply()
    selection.clear()
    selection.addAll(newSel)
    undoStack.push(cs)
    updateNodeSession()
    notifyChanged()
    notifySelection()
}

/** グリッド状の複製（cols×rows、間隔dx/dy） */
fun Editor.gridDuplicate(cols: Int, rows: Int, dx: Double, dy: Double) {
    if (selection.isEmpty()) return
    val c = cols.coerceIn(1, 100)
    val r = rows.coerceIn(1, 100)
    if (c == 1 && r == 1) return
    val sources = selection.toList()
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    val newSel = ArrayList<Element>()
    for (row in 0 until r) {
        for (col in 0 until c) {
            if (row == 0 && col == 0) continue // 元はそのまま
            for (src in sources) {
                val parent = findParent(src) ?: continue
                val copy = src.deepCopy()
                val parentInv = trace.parentAbsOf(src).invert()
                val localD = parentInv.transformVec(Vec(dx * col, dy * row))
                copy.setAttr(
                    "transform",
                    Matrix.toSvgString(Shapes.localTransform(src).then(Matrix(e = localD.x, f = localD.y))),
                )
                changes.add(Attach(copy, parent, parent.children.size, doc.root, "grid duplicate"))
                newSel.add(copy)
            }
        }
    }
    if (changes.isEmpty()) return
    val cs = ChangeSet(changes, "grid duplicate", newSel)
    cs.apply()
    selection.clear()
    selection.addAll(newSel)
    undoStack.push(cs)
    updateNodeSession()
    notifyChanged()
    notifySelection()
}

// ―――――――――――――――――――――――――――――――――――――――――――
// 選択拡張
// ―――――――――――――――――――――――――――――――――――――――――――

/** 選択の反転（選択可能要素全体が対象） */
fun Editor.invertSelection() {
    val newSel = ArrayList<Element>()
    for (item in trace.items) {
        if (!item.visible || item.locked) continue
        if (!doc.isSelectableShape(item.el)) continue
        if (!selection.contains(item.el)) newSel.add(item.el)
    }
    selection.clear()
    selection.addAll(newSel)
    updateNodeSession()
    notifySelection()
}

/** 同じ塗りのオブジェクトを選択に追加 */
fun Editor.selectSameFill() {
    val ref = selection.firstOrNull() ?: return
    val paint = inheritedPaint(ref, "fill")
    selectSameBy { inheritedPaint(it, "fill").matchesForSame(paint) }
}

/** 同じ線のオブジェクトを選択に追加 */
fun Editor.selectSameStroke() {
    val ref = selection.firstOrNull() ?: return
    val paint = inheritedPaint(ref, "stroke")
    selectSameBy { inheritedPaint(it, "stroke").matchesForSame(paint) }
}

/** 同じ種類（タグ）のオブジェクトを選択に追加 */
fun Editor.selectSameKind() {
    val ref = selection.firstOrNull() ?: return
    selectSameBy { it.tag == ref.tag }
}

private fun Paint.matchesForSame(other: Paint): Boolean = when {
    this is Paint.Solid && other is Paint.Solid -> (argb and 0x00FFFFFF) == (other.argb and 0x00FFFFFF)
    this is Paint.None && other is Paint.None -> true
    this is Paint.Server && other is Paint.Server -> id == other.id
    else -> false
}

private fun Editor.selectSameBy(predicate: (Element) -> Boolean) {
    val seeds = selection.toList()
    if (seeds.isEmpty()) return
    for (item in trace.items) {
        if (!item.visible || item.locked) continue
        if (!doc.isSelectableShape(item.el)) continue
        if (selection.contains(item.el)) continue
        if (predicate(item.el)) selection.add(item.el)
    }
    updateNodeSession()
    notifySelection()
}

/** グループ選択: 選択要素の最上位の親グループを選択 */
fun Editor.selectParent() {
    val first = selection.firstOrNull() ?: return
    var top: Element? = null
    var cur: Element? = first
    while (true) {
        val p = findParent(cur ?: break) ?: break
        if (p === doc.root) break
        top = p
        cur = p
    }
    val g = top ?: return
    selection.clear()
    selection.add(g)
    updateNodeSession()
    notifySelection()
}

/** グループ内部へ: 選択グループの子を選択 */
fun Editor.selectChildren() {
    val groups = selection.filter { it.tag == "g" }
    if (groups.isEmpty()) return
    val newSel = ArrayList<Element>()
    for (g in groups) {
        for (c in g.childElements()) {
            if (doc.isSelectableShape(c) || c.tag == "g") newSel.add(c)
        }
    }
    if (newSel.isEmpty()) return
    selection.clear()
    selection.addAll(newSel)
    updateNodeSession()
    notifySelection()
}

// ―――――――――――――――――――――――――――――――――――――――――――
// 整列・分布（基準指定つき）
// ―――――――――――――――――――――――――――――――――――――――――――

/**
 * 整列（基準を選択可能）。
 * @param relative SELECTION=選択範囲 / CANVAS=キャンバス / KEY_OBJECT=キーオブジェクト
 */
fun Editor.alignSelectionTo(mode: String, relative: Editor.AlignRelative = Editor.AlignRelative.SELECTION) {
    if (selection.isEmpty()) return
    val vb = doc.viewBox
    val reference: Bounds = when (relative) {
        Editor.AlignRelative.CANVAS -> Bounds(vb.minX, vb.minY, vb.width, vb.height)
        Editor.AlignRelative.KEY_OBJECT -> {
            val key = keyObject?.takeIf { selection.contains(it) && selection.size >= 2 }
                ?: selection.firstOrNull() ?: return
            trace.boundsOf(key) ?: return
        }
        else -> trace.boundsOf(selection) ?: return
    }
    val skipKey = relative == Editor.AlignRelative.KEY_OBJECT && keyObject != null && selection.contains(keyObject)
    commit("align") { batch ->
        for (el in selection) {
            if (skipKey && el === keyObject) continue
            val b = trace.boundsOf(el) ?: continue
            val parentInv = trace.parentAbsOf(el).invert()
            val parentRefBounds = parentInv.transformBounds(reference)
            val targetPoint: Vec = when (mode) {
                "left" -> Vec(parentRefBounds!!.x, b.centerY)
                "centerX" -> Vec(parentRefBounds!!.centerX, b.centerY)
                "right" -> Vec(parentRefBounds!!.right, b.centerY)
                "top" -> Vec(b.centerX, parentRefBounds!!.y)
                "centerY" -> Vec(b.centerX, parentRefBounds!!.centerY)
                "bottom" -> Vec(b.centerX, parentRefBounds!!.bottom)
                else -> return@commit
            }
            val curPoint: Vec = when (mode) {
                "left" -> Vec(b.x, b.centerY)
                "centerX" -> Vec(b.centerX, b.centerY)
                "right" -> Vec(b.right, b.centerY)
                "top" -> Vec(b.centerX, b.y)
                "centerY" -> Vec(b.centerX, b.centerY)
                "bottom" -> Vec(b.centerX, b.bottom)
                else -> return@commit
            }
            val deltaUser = targetPoint - curPoint
            val localDelta = parentInv.transformVec(deltaUser)
            val elLocal = Shapes.localTransform(el)
            batch.touch(el)
            el.setAttr("transform", Matrix.toSvgString(elLocal.then(Matrix(e = localDelta.x, f = localDelta.y))))
        }
    }
}

/**
 * 分布（等間隔配置）。
 * @param useGap true=オブジェクト間の「隙間」を均等 / false=中心間隔を均等
 * @param gap useGap時の基準隙間（0で範囲内で均等）
 */
fun Editor.distributeSelectionEx(horizontal: Boolean, useGap: Boolean = false, gap: Double = 0.0) {
    if (selection.size < 3) return
    val items = selection.mapNotNull { el -> trace.boundsOf(el)?.let { el to it } }
    if (items.size < 3) return
    val sorted = if (horizontal) items.sortedBy { it.second.x } else items.sortedBy { it.second.y }
    commit("distribute") { batch ->
        if (useGap) {
            // 隙間均等: 端の2つは固定、間のオブジェクトを隙間gapで配置
            val first = sorted.first().second
            val last = sorted.last().second
            if (gap > 0.0) {
                // 指定隙間で先頭から積み直す
                var cursor = if (horizontal) first.x else first.y
                for ((i, pair) in sorted.withIndex()) {
                    val (el, b) = pair
                    val size = if (horizontal) b.width else b.height
                    val targetPos = cursor
                    val curPos = if (horizontal) b.x else b.y
                    if (i > 0) {
                        val deltaUser = if (horizontal) Vec(targetPos - curPos, 0.0) else Vec(0.0, targetPos - curPos)
                        applyDistributeDelta(batch, el, deltaUser)
                    }
                    cursor = targetPos + size + gap
                }
            } else {
                // 全体範囲内で隙間を均等化
                val sizes = sorted.sumOf { if (horizontal) it.second.width else it.second.height }
                val span = if (horizontal) last.right - first.x else last.bottom - first.y
                val free = span - sizes
                val g = (free / (sorted.size - 1)).coerceAtLeast(0.0)
                var cursor = if (horizontal) first.x else first.y
                for ((i, pair) in sorted.withIndex()) {
                    val (el, b) = pair
                    val size = if (horizontal) b.width else b.height
                    val curPos = if (horizontal) b.x else b.y
                    if (i > 0) {
                        val deltaUser = if (horizontal) Vec(cursor - curPos, 0.0) else Vec(0.0, cursor - curPos)
                        applyDistributeDelta(batch, el, deltaUser)
                    }
                    cursor += size + g
                }
            }
        } else {
            // 中心間隔の均等化（従来動作）
            val first = sorted.first().second
            val last = sorted.last().second
            val total = if (horizontal) last.centerX - first.centerX else last.centerY - first.centerY
            val step = total / (sorted.size - 1)
            for (i in 1 until sorted.size - 1) {
                val (el, b) = sorted[i]
                val targetCenter = if (horizontal) first.centerX + step * i else first.centerY + step * i
                val curCenter = if (horizontal) b.centerX else b.centerY
                val deltaUser = if (horizontal) Vec(targetCenter - curCenter, 0.0) else Vec(0.0, targetCenter - curCenter)
                applyDistributeDelta(batch, el, deltaUser)
            }
        }
    }
}

private fun Editor.applyDistributeDelta(batch: dev.svgpro.core.edit.EditBatch, el: Element, deltaUser: Vec) {
    val parentInv = trace.parentAbsOf(el).invert()
    val localDelta = parentInv.transformVec(deltaUser)
    val elLocal = Shapes.localTransform(el)
    batch.touch(el)
    el.setAttr("transform", Matrix.toSvgString(elLocal.then(Matrix(e = localDelta.x, f = localDelta.y))))
}

/** オブジェクト間の距離（選択2つのbbox間隔）を返す。整列確認・表示用 */
fun Editor.selectionGaps(): Pair<Double, Double>? {
    if (selection.size != 2) return null
    val a = trace.boundsOf(selection[0]) ?: return null
    val b = trace.boundsOf(selection[1]) ?: return null
    val gx = if (a.right <= b.x) b.x - a.right else if (b.right <= a.x) a.x - b.right else 0.0
    val gy = if (a.bottom <= b.y) b.y - a.bottom else if (b.bottom <= a.y) a.y - b.bottom else 0.0
    return gx to gy
}

/** グリッドに沿った配置（左上をグリッドへスナップ） */
fun Editor.snapSelectionToGrid() {
    val b = trace.boundsOf(selection) ?: return
    val gx = kotlin.math.round(b.x / gridSize) * gridSize
    val gy = kotlin.math.round(b.y / gridSize) * gridSize
    applyTransform(TransformOp.Move(gx - b.x, gy - b.y))
}

// ―――――――――――――――――――――――――――――――――――――――――――
// スマートガイド（移動中の整列補助）
// ―――――――――――――――――――――――――――――――――――――――――――

/**
 * 移動中の選択bboxを他オブジェクトの辺/中心に整列させる。
 * @return 補正後のdeltaと、表示する補助線（縦線x / 横線y）
 */
fun Editor.computeSmartGuides(b: Bounds, delta: Vec, toleranceUser: Double): Triple<Vec, List<Double>, List<Double>> {
    val vLines = ArrayList<Double>()
    val hLines = ArrayList<Double>()
    if (!smartGuides) return Triple(delta, vLines, hLines)
    val candX = listOf(b.x, b.centerX, b.right)
    val candY = listOf(b.y, b.centerY, b.bottom)
    var bestX: Pair<Double, Double>? = null // (targetX, matchedLine)
    var bestY: Pair<Double, Double>? = null
    var bestDx = toleranceUser
    var bestDy = toleranceUser
    val ignore = selection.toHashSet()
    for (item in trace.items) {
        if (item.locked || !item.visible || item.el in ignore) continue
        val ob = trace.boundsOf(item.el) ?: continue
        for (line in listOf(ob.x, ob.centerX, ob.right)) {
            for (c in candX) {
                val d = abs((c + delta.x) - line)
                if (d < bestDx) {
                    bestDx = d
                    bestX = line to (line - c)
                }
            }
        }
        for (line in listOf(ob.y, ob.centerY, ob.bottom)) {
            for (c in candY) {
                val d = abs((c + delta.y) - line)
                if (d < bestDy) {
                    bestDy = d
                    bestY = line to (line - c)
                }
            }
        }
    }
    var newDelta = delta
    bestX?.let { (line, adjust) ->
        newDelta = Vec(newDelta.x + adjust, newDelta.y)
        vLines.add(line)
    }
    bestY?.let { (line, adjust) ->
        newDelta = Vec(newDelta.x, newDelta.y + adjust)
        hLines.add(line)
    }
    return Triple(newDelta, vLines, hLines)
}

// ―――――――――――――――――――――――――――――――――――――――――――
// パス操作（複合パス・fill-rule・ストローク化・分割）
// ―――――――――――――――――――――――――――――――――――――――――――

/** 複合パスの作成: 複数パスを1要素へ統合し、evenoddで穴を保持 */
fun Editor.compoundSelection(): Boolean {
    val targets = selection.filter { PathData.parse(it.attr("d")) != null || Shapes.isPrimitive(it) }
    if (targets.size < 2) return false
    val segs = ArrayList<Seg>()
    for (el in targets) {
        val pd = (Shapes.geometryOf(el) ?: continue).clone()
        pd.transform(trace.absOf(el))
        segs.addAll(pd.segs)
    }
    if (segs.isEmpty()) return false
    val first = targets.first()
    val parent = findParent(first) ?: doc.root
    val insertAt = parent.children.indexOf(first).coerceAtLeast(0)
    val result = Element("path")
    for ((k, v) in first.attrs) {
        if (k == "d" || k == "transform" || k in setOf("points", "x", "y", "width", "height", "cx", "cy", "r", "rx", "ry", "x1", "x2", "y1", "y2")) continue
        result.attrs[k] = v
    }
    result.setAttr("d", PathData(segs).write())
    result.setAttr("fill-rule", "evenodd")
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    for (el in targets) {
        val p = findParent(el) ?: continue
        changes.add(Detach(el, p, p.children.indexOf(el), "compound"))
    }
    changes.add(Attach(result, parent, insertAt, doc.root, "compound"))
    val cs = ChangeSet(changes, "compound path", listOf(result))
    cs.apply()
    selection.clear()
    selection.add(result)
    undoStack.push(cs)
    invalidateTrace()
    updateNodeSession()
    notifyChanged()
    notifySelection()
    return true
}

/** 複合パスの解除: サブパスを個別のpath要素へ分解（穴の形状も保持される） */
fun Editor.releaseCompound(): Boolean {
    val el = selection.firstOrNull() ?: return false
    if (el.tag != "path") return false
    val parts = ClipMaskUtil.splitSubpaths(el) ?: return false
    val parent = findParent(el) ?: doc.root
    val insertAt = parent.children.indexOf(el).coerceAtLeast(0)
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    changes.add(Detach(el, parent, insertAt, "release compound"))
    for ((i, p) in parts.withIndex()) {
        changes.add(Attach(p, parent, insertAt + 1 + i, doc.root, "release compound"))
    }
    val cs = ChangeSet(changes, "release compound", parts)
    cs.apply()
    selection.clear()
    selection.addAll(parts)
    undoStack.push(cs)
    invalidateTrace()
    updateNodeSession()
    notifyChanged()
    notifySelection()
    return true
}

/** 塗り規則の切り替え（nonzero / evenodd） */
fun Editor.setFillRuleSelection(rule: String) {
    if (selection.isEmpty()) return
    commit("fill-rule") { batch ->
        for (el in selection) {
            batch.touch(el)
            StyleUtil.setFillRule(el, rule)
        }
    }
}

/** ストロークのアウトライン化（コア実装・開パス対応・cap/join保持） */
fun Editor.strokeToPathSelection(): Boolean {
    val el = selection.firstOrNull() ?: return false
    val geo = Shapes.geometryOf(el) ?: return false
    val width = selectionStrokeWidth()
    if (width <= 0.0) return false
    val cap = when (StyleUtil.getProperty(el, "stroke-linecap")) {
        "round" -> StrokeCap.ROUND
        "square" -> StrokeCap.SQUARE
        else -> StrokeCap.BUTT
    }
    val join = when (StyleUtil.getProperty(el, "stroke-linejoin")) {
        "round" -> StrokeJoin.ROUND
        "bevel" -> StrokeJoin.BEVEL
        else -> StrokeJoin.MITER
    }
    val abs = trace.absOf(el)
    val pd = geo.clone()
    pd.transform(abs)
    val outline = GeometryOps.strokeOutline(pd, width, cap, join, miterLimit = 4.0, tol = 0.02 / zoom.coerceAtLeast(0.01))
        ?: return false
    replaceWithPathElement(el, outline, "stroke to path")
    val newEl = selection.firstOrNull() ?: return true
    commit("stroke to path style") { batch ->
        batch.touch(newEl)
        StyleUtil.setProperty(newEl, "fill", StyleUtil.getProperty(el, "stroke") ?: "#000000")
        StyleUtil.setProperty(newEl, "stroke", "none")
        newEl.setAttr("fill-rule", "evenodd")
    }
    return true
}

/** パスの分割: 複数サブパスを持つpathを個別要素へ（複合パス解除と同義の要素分解） */
fun Editor.splitPathToElements(): Boolean = releaseCompound()

// ―――――――――――――――――――――――――――――――――――――――――――
// クリッピング / マスク
// ―――――――――――――――――――――――――――――――――――――――――――

/**
 * クリッピングの作成: 選択の最後（最前面）の図形で、それ以外をクリップする。
 * クリッピング形状はdefsのclipPathへ移り、対象にclip-path属性が付く（非破壊）。
 */
fun Editor.createClipFromSelection(): Boolean {
    if (selection.size < 2) return false
    val targets = selection.toList()
    val clippers = targets.lastOrNull { Shapes.geometryOf(it) != null } ?: return false
    val clippees = targets.filter { it !== clippers }
    if (clippees.isEmpty()) return false
    val clipId = uniqueId("clip")
    val defs = doc.defsElement()
    val clip = ClipMaskUtil.createClipPath(
        id = clipId,
        sources = listOf(clippers),
        absOf = { trace.absOf(it) },
        targetAbs = trace.absOf(clippees.first()),
    )
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    // クリッパーをドキュメントから取り除きdefsへ
    val clipperParent = findParent(clippers)
    if (clipperParent != null) {
        changes.add(Detach(clippers, clipperParent, clipperParent.children.indexOf(clippers), "clip"))
    }
    changes.add(Attach(clip, defs, defs.children.size, doc.root, "clip"))
    for (t in clippees) {
        t.setAttr("clip-path", "url(#$clipId)")
        changes.add(dev.svgpro.core.edit.AttrChange(t, "clip-path", null, "url(#$clipId)", "clip"))
    }
    val cs = ChangeSet(changes, "create clip", clippees)
    cs.apply()
    selection.clear()
    selection.addAll(clippees)
    undoStack.push(cs)
    invalidateTrace()
    updateNodeSession()
    notifyChanged()
    notifySelection()
    return true
}

/** クリッピング解除（定義は保持） */
fun Editor.releaseClipSelection(): Boolean {
    val targets = selection.filter { it.attr("clip-path") != null }
    if (targets.isEmpty()) return false
    commit("release clip") { batch ->
        for (t in targets) {
            batch.touch(t)
            t.setAttr("clip-path", null)
        }
    }
    return true
}

/** 不透明マスクの作成: 最前面の図形の内側だけを表示するマスクを残りへ適用 */
fun Editor.createMaskFromSelection(): Boolean {
    if (selection.size < 2) return false
    val targets = selection.toList()
    val maskers = targets.lastOrNull { Shapes.geometryOf(it) != null } ?: return false
    val maskTargets = targets.filter { it !== maskers }
    if (maskTargets.isEmpty()) return false
    val maskId = uniqueId("mask")
    val defs = doc.defsElement()
    val mask = ClipMaskUtil.createMask(
        id = maskId,
        sources = listOf(maskers),
        absOf = { trace.absOf(it) },
        targetAbs = trace.absOf(maskTargets.first()),
        targetGeometryBounds = trace.boundsOf(maskTargets.first()),
    ) ?: return false
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    val maskerParent = findParent(maskers)
    if (maskerParent != null) {
        changes.add(Detach(maskers, maskerParent, maskerParent.children.indexOf(maskers), "mask"))
    }
    changes.add(Attach(mask, defs, defs.children.size, doc.root, "mask"))
    for (t in maskTargets) {
        val old = t.attr("mask")
        t.setAttr("mask", "url(#$maskId)")
        changes.add(dev.svgpro.core.edit.AttrChange(t, "mask", old, t.attr("mask"), "mask"))
    }
    val cs = ChangeSet(changes, "create mask", maskTargets)
    cs.apply()
    selection.clear()
    selection.addAll(maskTargets)
    undoStack.push(cs)
    invalidateTrace()
    updateNodeSession()
    notifyChanged()
    notifySelection()
    return true
}

/** マスク解除（定義は保持） */
fun Editor.releaseMaskSelection(): Boolean {
    val targets = selection.filter { it.attr("mask") != null }
    if (targets.isEmpty()) return false
    commit("release mask") { batch ->
        for (t in targets) {
            batch.touch(t)
            t.setAttr("mask", null)
        }
    }
    return true
}

// ―――――――――――――――――――――――――――――――――――――――――――
// シンボル
// ―――――――――――――――――――――――――――――――――――――――――――

/**
 * 選択要素からシンボルを作成する。
 * シンボル定義（絶対座標へ焼き込み）をdefsへ置き、ルート直下にuseインスタンスを配置する。
 * @return 最初のuseインスタンス（失敗時null）
 */
fun Editor.createSymbolFromSelection(): Element? {
    if (selection.isEmpty()) return null
    val sources = selection.toList()
    val symbolId = uniqueId("symbol")
    val defs = doc.defsElement()
    val symbol = Element("symbol")
    symbol.setAttr("id", symbolId)
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    val uses = ArrayList<Element>()
    for (src in sources) {
        val copy = src.deepCopy()
        // シンボル内容はuser空間へ焼き込む（useはルート直下に置くため渲染が一致する）
        val abs = trace.absOf(src)
        copy.setAttr("transform", if (abs.isIdentity()) null else Matrix.toSvgString(abs))
        symbol.children.add(copy)
        // 元要素を取り除き、ルート直下へuse
        val srcParent = findParent(src)
        val srcIdx = srcParent?.children?.indexOf(src) ?: -1
        if (srcParent != null && srcIdx >= 0) {
            changes.add(Detach(src, srcParent, srcIdx, "symbol"))
        }
        val use = Element("use")
        use.setAttr("href", "#$symbolId")
        use.setAttr("xlink:href", "#$symbolId")
        changes.add(Attach(use, doc.root, doc.root.children.size, doc.root, "symbol"))
        uses.add(use)
    }
    changes.add(Attach(symbol, defs, defs.children.size, doc.root, "symbol"))
    val cs = ChangeSet(changes, "create symbol", uses)
    cs.apply()
    selection.clear()
    selection.addAll(uses)
    undoStack.push(cs)
    invalidateTrace()
    updateNodeSession()
    notifyChanged()
    notifySelection()
    return uses.firstOrNull()
}

/** シンボルのインスタンスを新規配置 */
fun Editor.placeSymbolInstance(symbolId: String): Element? {
    val symbol = doc.root.findById(symbolId) ?: return null
    if (symbol.tag != "symbol") return null
    val vb = doc.viewBox
    val use = Element("use")
    use.setAttr("href", "#$symbolId")
    use.setAttr("xlink:href", "#$symbolId")
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    changes.add(Attach(use, doc.root, doc.root.children.size, doc.root, "place symbol"))
    val cs = ChangeSet(changes, "place symbol", listOf(use))
    cs.apply()
    selection.clear()
    selection.add(use)
    undoStack.push(cs)
    invalidateTrace()
    updateNodeSession()
    notifyChanged()
    notifySelection()
    return use
}

/** 選択中のuseが参照するシンボル定義を返す */
fun Editor.symbolOfSelection(): Element? {
    val use = selection.firstOrNull() ?: return null
    if (use.tag != "use") return null
    val href = StyleUtil.href(use) ?: return null
    val target = doc.root.findById(href.removePrefix("#")) ?: return null
    return if (target.tag == "symbol") target else null
}

/** シンボルインスタンスの解除（参照をやめて実体の編集可能なコピーを置く） */
fun Editor.breakSymbolInstance(): Boolean {
    val use = selection.firstOrNull() ?: return false
    if (use.tag != "use") return false
    val href = StyleUtil.href(use) ?: return false
    val symbol = doc.root.findById(href.removePrefix("#")) ?: return false
    val parent = findParent(use) ?: doc.root
    val useAbs = trace.absOf(use)
    val parentAbs = trace.parentAbsOf(use)
    // newT = P⁻¹ ∘ U ∘ childLocal（数学積）。then()時間順では childLocal → U → P⁻¹
    val insertAt = parent.children.indexOf(use).coerceAtLeast(0)
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    val newSel = ArrayList<Element>()
    val children = symbol.childElements()
    if (children.isEmpty()) {
        // 単一要素シンボルの簡易ケース
        val copy = symbol.deepCopy()
        copy.setAttr("id", null)
        copy.setAttr("transform", Matrix.toSvgString(Shapes.localTransform(symbol).then(useAbs).then(parentAbs.invert())))
        changes.add(Attach(copy, parent, insertAt, doc.root, "break symbol"))
        newSel.add(copy)
    } else {
        val wrapper = Element("g")
        for (c in children) {
            val copy = c.deepCopy()
            val composed = Shapes.localTransform(c).then(useAbs).then(parentAbs.invert())
            copy.setAttr("transform", Matrix.toSvgString(composed))
            wrapper.children.add(copy)
        }
        changes.add(Attach(wrapper, parent, insertAt, doc.root, "break symbol"))
        newSel.add(wrapper)
    }
    changes.add(Detach(use, parent, parent.children.indexOf(use), "break symbol"))
    val cs = ChangeSet(changes, "break symbol", newSel)
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

/** シンボル定義の削除（全インスタンスも削除） */
fun Editor.deleteSymbol(symbolId: String): Boolean {
    val symbol = doc.root.findById(symbolId) ?: return false
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    // useをすべて削除
    doc.root.walk { el ->
        if (el.tag == "use") {
            val href = StyleUtil.href(el) ?: return@walk
            if (href.removePrefix("#") == symbolId) {
                val p = findParent(el)
                if (p != null) changes.add(Detach(el, p, p.children.indexOf(el), "delete symbol"))
            }
        }
    }
    val defs = doc.defsElement()
    val idx = defs.children.indexOf(symbol)
    if (idx >= 0) changes.add(Detach(symbol, defs, idx, "delete symbol"))
    if (changes.isEmpty()) return false
    val cs = ChangeSet(changes, "delete symbol")
    cs.apply()
    selection.clear()
    undoStack.push(cs)
    invalidateTrace()
    updateNodeSession()
    notifyChanged()
    notifySelection()
    return true
}

/** ドキュメント内のシンボル定義一覧 */
fun Editor.symbols(): List<Element> = doc.defsElement().childElements().filter { it.tag == "symbol" }

// ―――――――――――――――――――――――――――――――――――――――――――
// グラデーション編集
// ―――――――――――――――――――――――――――――――――――――――――――

/** グラデーション編集セッション（キャンバスハンドル用） */
class GradientEditState(
    val defId: String,
    val target: Element,
)

/** グラデーション編集のターゲットを設定（GRADIENTツールで選択時に呼ぶ） */
fun Editor.setupGradientEdit() {
    gradientEdit = null
    val el = selection.firstOrNull() ?: return
    val fill = inheritedPaint(el, "fill")
    if (fill is Paint.Server) {
        val def = doc.root.findById(fill.id)
        if (def != null && (def.tag == "linearGradient" || def.tag == "radialGradient")) {
            gradientEdit = GradientEditState(fill.id, el)
        }
    }
}

/** 選択要素の塗りからグラデーション定義モデルを取得 */
fun Editor.gradientModelOfSelection(): GradientModel? {
    val el = selection.firstOrNull() ?: return null
    val fill = inheritedPaint(el, "fill")
    if (fill !is Paint.Server) return null
    val def = doc.root.findById(fill.id) ?: return null
    return GradientModel.of(def) ?: return null
}

/** 現在の塗り色をもとに新規グラデーションを作成して選択へ適用 */
fun Editor.createGradientForSelection(radial: Boolean): String? {
    val base = (selectionFill() as? Paint.Solid)?.argb ?: (curFill as? Paint.Solid)?.argb ?: 0xFF1473E6.toInt()
    val id = uniqueId("grad")
    val defs = doc.defsElement()
    val g = dev.svgpro.core.svg.Element(if (radial) "radialGradient" else "linearGradient")
    g.setAttr("id", id)
    if (!radial) {
        g.setAttr("x1", "0"); g.setAttr("y1", "0"); g.setAttr("x2", "1"); g.setAttr("y2", "0")
    } else {
        g.setAttr("cx", "0.5"); g.setAttr("cy", "0.5"); g.setAttr("r", "0.5")
    }
    val s1 = dev.svgpro.core.svg.Element("stop")
    s1.setAttr("offset", "0")
    s1.setAttr("stop-color", Color.css(base))
    val s2 = dev.svgpro.core.svg.Element("stop")
    s2.setAttr("offset", "1")
    s2.setAttr("stop-color", Color.css(Color.withAlpha(base xor 0x00FFFFFF, 1.0)))
    g.children.add(s1)
    g.children.add(s2)
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    changes.add(Attach(g, defs, defs.children.size, doc.root, "gradient"))
    val cs = ChangeSet(changes, "new gradient", listOf(g))
    cs.apply()
    undoStack.push(cs)
    applyGradientToSelection(id)
    return id
}

/** 定義済みグラデーションを選択へ適用 */
fun Editor.applyGradientToSelection(id: String) {
    if (selection.isEmpty()) return
    commit("apply gradient") { batch ->
        for (el in selection) {
            batch.touch(el)
            StyleUtil.setProperty(el, "fill", "url(#$id)")
        }
    }
    setupGradientEdit()
}

/** キャンバスハンドル用: グラデーションの始点/終点（user座標） */
fun Editor.gradientHandlePoints(): Triple<GradientModel, Vec, Vec>? {
    val state = gradientEdit ?: return null
    val def = doc.root.findById(state.defId) ?: return null
    val model = GradientModel.of(def) ?: return null
    val b = trace.boundsOf(state.target) ?: return null
    if (model.userSpace) {
        return if (model.radial) {
            val c = Vec(model.cx, model.cy)
            Triple(model, c, Vec(c.x + model.r, c.y))
        } else {
            Triple(model, Vec(model.x1, model.y1), Vec(model.x2, model.y2))
        }
    }
    val (s, e) = model.handlePoints(b)
    return Triple(model, s, e)
}

/** キャンバスハンドルのドラッグ反映（ドラッグ確定時に呼ぶ・undo統合は呼び出し側） */
fun Editor.updateGradientHandles(start: Vec, end: Vec) {
    val state = gradientEdit ?: return
    val def = doc.root.findById(state.defId) ?: return
    val model = GradientModel.of(def) ?: return
    val b = trace.boundsOf(state.target) ?: return
    if (model.userSpace) {
        if (model.radial) {
            model.cx = start.x; model.cy = start.y; model.r = start.distanceTo(end)
        } else {
            model.x1 = start.x; model.y1 = start.y; model.x2 = end.x; model.y2 = end.y
        }
    } else {
        model.setFromHandles(b, start, end)
    }
    model.write()
    invalidateTrace()
    onChanged()
}

/** グラデーションのストップ操作（undo統合・子要素の構造変更も記録） */
fun Editor.addGradientStop(offset: Double): Boolean {
    val def = gradientDef() ?: return false
    val beforeChildren = def.children.toList()
    val beforeAttrs = LinkedHashMap(def.attrs)
    val model = GradientModel.of(def)!!
    model.addStop(offset)
    model.write()
    pushStructuralChange(def, beforeChildren, beforeAttrs, "gradient stop")
    return true
}

fun Editor.removeGradientStop(index: Int): Boolean {
    val def = gradientDef() ?: return false
    val beforeChildren = def.children.toList()
    val beforeAttrs = LinkedHashMap(def.attrs)
    val model = GradientModel.of(def)!!
    if (!model.removeStop(index)) return false
    model.write()
    pushStructuralChange(def, beforeChildren, beforeAttrs, "gradient stop")
    return true
}

fun Editor.moveGradientStop(index: Int, offset: Double): Boolean {
    val def = gradientDef() ?: return false
    val beforeChildren = def.children.toList()
    val beforeAttrs = LinkedHashMap(def.attrs)
    val model = GradientModel.of(def)!!
    if (index in model.stops.indices) {
        model.stops[index].offset = offset.coerceIn(0.0, 1.0)
        model.stops.sortBy { it.offset }
    }
    model.write()
    pushStructuralChange(def, beforeChildren, beforeAttrs, "gradient stop")
    return true
}

fun Editor.setGradientStopColor(index: Int, argb: Int): Boolean {
    val def = gradientDef() ?: return false
    val beforeChildren = def.children.toList()
    val beforeAttrs = LinkedHashMap(def.attrs)
    val model = GradientModel.of(def)!!
    if (index in model.stops.indices) model.stops[index].argb = argb
    model.write()
    pushStructuralChange(def, beforeChildren, beforeAttrs, "gradient stop color")
    return true
}

/** 属性+子要素両方の変更を1履歴としてpush（グラデーションstop等の構造編集用） */
private fun Editor.pushStructuralChange(
    def: Element,
    beforeChildren: List<dev.svgpro.core.svg.Node>,
    beforeAttrs: Map<String, String>,
    label: String,
) {
    val changes = ArrayList<dev.svgpro.core.edit.Change>()
    changes.add(ChildListChange(def, beforeChildren, def.children.toList(), label))
    for ((k, oldV) in beforeAttrs) {
        val newV = def.attr(k)
        if (oldV != newV) changes.add(dev.svgpro.core.edit.AttrChange(def, k, oldV, newV, label))
    }
    for (k in def.attrs.keys) {
        if (!beforeAttrs.containsKey(k)) {
            changes.add(dev.svgpro.core.edit.AttrChange(def, k, null, def.attr(k), label))
        }
    }
    val cs = ChangeSet(changes, label)
    cs.apply()
    undoStack.push(cs)
    invalidateTrace()
    notifyChanged()
}

private fun Editor.gradientDef(): Element? {
    val state = gradientEdit
    if (state != null) return doc.root.findById(state.defId)
    val model = gradientModelOfSelection() ?: return null
    return model.def
}

// ―――――――――――――――――――――――――――――――――――――――――――
// テキスト
// ―――――――――――――――――――――――――――――――――――――――――――

/**
 * パスに沿ったテキスト（SVG標準のtextPath構造を作る）。
 * 選択: text要素1つ + path要素1つ。
 */
fun Editor.makeTextPath(): Boolean {
    val text = selection.firstOrNull { it.tag == "text" } ?: return false
    val path = selection.firstOrNull { it.tag == "path" && it !== text } ?: return false
    val content = text.textContent()
    if (content.isEmpty()) return false
    var pathId = path.attr("id")
    if (pathId.isNullOrBlank()) {
        pathId = uniqueId("textpath")
        commit("path id") { batch ->
            batch.touch(path)
            path.setAttr("id", pathId)
        }
    }
    commit("text on path") { batch ->
        batch.touch(text)
        val tp = Element("textPath")
        tp.setAttr("href", "#$pathId")
        tp.setAttr("xlink:href", "#$pathId")
        tp.setAttr("startOffset", "0")
        tp.setTextContent(content)
        text.children.clear()
        text.children.add(tp)
    }
    return true
}

// ―――――――――――――――――――――――――――――――――――――――――――
// ノード編集（拡張）
// ―――――――――――――――――――――――――――――――――――――――――――

/** ノード選択の反転 */
fun NodeSession.invertNodeSelection() {
    val all = allNodes().map { it.first }
    val newSel = LinkedHashSet<NodeRef>(all.size)
    for (r in all) if (r !in selected) newSel.add(r)
    selected.clear()
    selected.addAll(newSel)
}

/** 全ノード選択 */
fun NodeSession.selectAllNodes() {
    selected.clear()
    selected.addAll(allNodes().map { it.first })
}

/** 選択ノードの等間隔配置（座標順）。成功時にdへ反映 */
fun NodeSession.distributeSelectedNodes(horizontal: Boolean): Boolean {
    val refs = selected.toList()
    if (refs.size < 3) return false
    val positions = refs.map { nodePos(it) ?: return false }
    val target = distributeNodePositions(positions, horizontal) ?: return false
    for ((i, ref) in refs.withIndex()) {
        val m = models.getOrNull(ref.sub) ?: continue
        m.setNodePosition(ref.node, target[i])
    }
    flush()
    return true
}

/** 選択ノード間のセグメントを直線/曲線へ変換 */
fun NodeSession.convertSelectedSegments(curve: Boolean): Boolean {
    val segs = segmentsBetweenSelected() ?: return false
    var changed = false
    for ((m, j) in segs) {
        if (curve) m.setSegmentCurve(j) else m.setSegmentLine(j)
        changed = true
    }
    if (changed) flush()
    return changed
}

private fun NodeSession.segmentsBetweenSelected(): List<Pair<SubpathNodes, Int>>? {
    if (selected.isEmpty()) return null
    val out = ArrayList<Pair<SubpathNodes, Int>>()
    for ((si, m) in models.withIndex()) {
        val selInSub = selected.filter { it.sub == si }.map { it.node }.toSet()
        if (selInSub.isEmpty()) continue
        for (j in 0 until m.segmentCount) {
            val a = j
            val b = (j + 1) % m.nodes.size
            if (a in selInSub && b in selInSub) out.add(m to j)
        }
    }
    return if (out.isEmpty()) null else out
}

/** 選択ノード間のセグメント削除（パスの切断）。構造変更後は選択を解消 */
fun NodeSession.deleteSelectedSegments(): Boolean {
    if (selected.isEmpty()) return false
    var changed = false
    var si = models.size - 1
    while (si >= 0) {
        val m = models[si]
        val selInSub = selected.filter { it.sub == si }.map { it.node }.toSet()
        val selectedSegs = HashSet<Int>()
        for (j in 0 until m.segmentCount) {
            val a = j
            val b = (j + 1) % m.nodes.size
            if (a in selInSub && b in selInSub) selectedSegs.add(j)
        }
        if (selectedSegs.isNotEmpty()) {
            val parts = m.removeSegments(selectedSegs)
            models.removeAt(si)
            models.addAll(si, parts)
            changed = true
        }
        si--
    }
    selected.clear()
    if (changed) flush()
    return changed
}

/** 端点の接続: 選択端点同士を結ぶ（同一サブパスなら閉じる） */
fun NodeSession.joinSelectedEnds(): NodeRef? {
    val ends = selected.filter { ref ->
        val m = models.getOrNull(ref.sub) ?: return@filter false
        !m.closed && (ref.node == 0 || ref.node == m.nodes.size - 1)
    }
    if (ends.size < 2) return null
    val result = joinSubpathEndNodes(models, ends[0], ends[1])
    if (result != null) {
        selected.clear()
        selected.add(result)
        flush()
    }
    return result
}

/** 選択ノードの位置でパスを切断 */
fun NodeSession.splitAtSelectedNodes(): Boolean {
    val targets = selected.mapNotNull { nodePos(it) }
    if (targets.isEmpty()) return false
    selected.clear()
    var changed = false
    for (t in targets) {
        var done = false
        for (si in models.indices) {
            val m = models[si]
            for (ni in m.nodes.indices) {
                if (m.nodes[ni].p.equalsTol(t, 1e-6)) {
                    val before = m.nodeCount
                    val parts = m.splitAtNode(ni)
                    if (parts.size > 1 || parts[0].nodeCount > before) {
                        models.removeAt(si)
                        models.addAll(si, parts)
                        changed = true
                    }
                    done = true
                    break
                }
            }
            if (done) break
        }
    }
    if (changed) flush()
    return changed
}

/** ノードの削減（許容誤差tol内のノードを除去）。@return 除去数 */
fun NodeSession.reduceNodesInPath(tol: Double): Int {
    var total = 0
    for (m in models) {
        total += m.reduceNodes(tol)
    }
    if (total > 0) flush()
    return total
}

/** 選択ノードのハンドルを数値指定（長さ+角度）。mirrorは既定SMOOTH */
fun NodeSession.setHandleNumeric(isIn: Boolean, length: Double, angleDeg: Double) {
    val ref = selected.firstOrNull() ?: return
    val m = models.getOrNull(ref.sub) ?: return
    m.setHandlePolar(ref.node, isIn, length, angleDeg)
    flush()
}

// ―――――――――――――――――――――――――――――――――――――――――――
// ユーティリティ
// ―――――――――――――――――――――――――――――――――――――――――――

/** ドキュメント内でユニークなidを生成 */
fun Editor.uniqueId(base: String): String {
    val existing = HashSet<String>()
    doc.root.walk { existing.add(it.attr("id") ?: "") }
    var i = 1
    while (true) {
        val candidate = "${base}_$i"
        if (candidate !in existing) return candidate
        i++
    }
}


// ―――――――――――――――――――――――――――――――――――――――――――
// 書き出し・色一括置換
// ―――――――――――――――――――――――――――――――――――――――――

/**
 * ドキュメント（または選択範囲）をPNGへ描画する。
 * @param boundsPx 出力ピクセル寸法
 * @param transparent 背景を透明にする（falseで白背景）
 * @param selectionOnly 選択範囲のみ
 */
fun Editor.renderToBitmap(
    boundsPx: Pair<Int, Int>,
    transparent: Boolean,
    selectionOnly: Boolean,
): android.graphics.Bitmap {
    val target = if (selectionOnly) {
        trace.boundsOf(selection) ?: doc.viewBox.let { dev.svgpro.core.geom.Bounds(it.minX, it.minY, it.width, it.height) }
    } else {
        val vb = doc.viewBox
        dev.svgpro.core.geom.Bounds(vb.minX, vb.minY, vb.width, vb.height)
    }
    val (w, h) = boundsPx
    val scale = minOf(w.toDouble() / target.width.coerceAtLeast(1e-6), h.toDouble() / target.height.coerceAtLeast(1e-6))
    // user → pixel の変換行列（中央寄せ）
    val offsetX = (w - target.width * scale) / 2.0
    val offsetY = (h - target.height * scale) / 2.0
    val vm = dev.svgpro.core.geom.Matrix(a = scale, d = scale, e = offsetX - target.x * scale, f = offsetY - target.y * scale)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    if (!transparent) {
        canvas.drawColor(android.graphics.Color.WHITE)
    }
    val renderer = dev.svgpro.canvas.SvgRenderer()
    renderer.draw(canvas, this, vm)
    return bmp
}

/** 色の一括置換（undo統合）。@return 置換された要素数 */
fun Editor.replaceColorEverywhere(from: Int, to: Int): Int {
    val batch = dev.svgpro.core.edit.EditBatch()
    doc.root.walk { batch.touch(it) }
    val count = dev.svgpro.core.model.ColorOps.replaceColor(doc.root, from, to)
    val cs = batch.endAndCommit("replace color")
    if (cs != null) {
        undoStack.push(cs)
        notifyChanged()
    }
    return count
}
