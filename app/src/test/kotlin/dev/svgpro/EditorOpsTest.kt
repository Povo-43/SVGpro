package dev.svgpro

import dev.svgpro.core.geom.Vec
import dev.svgpro.core.model.Guide
import dev.svgpro.core.model.StyleUtil
import dev.svgpro.core.path.PathData
import dev.svgpro.editor.Editor
import dev.svgpro.editor.Editor.AlignRelative
import dev.svgpro.editor.NodeRef
import dev.svgpro.editor.ToolType
import dev.svgpro.editor.TransformOp
import dev.svgpro.editor.addGradientStop
import dev.svgpro.editor.alignSelectionTo
import dev.svgpro.editor.applyTransform
import dev.svgpro.editor.breakSymbolInstance
import dev.svgpro.editor.compoundSelection
import dev.svgpro.editor.createClipFromSelection
import dev.svgpro.editor.createGradientForSelection
import dev.svgpro.editor.convertSelectedSegments
import dev.svgpro.editor.deleteSelectedSegments
import dev.svgpro.editor.distributeSelectedNodes
import dev.svgpro.editor.invertNodeSelection
import dev.svgpro.editor.joinSelectedEnds
import dev.svgpro.editor.selectAllNodes
import dev.svgpro.editor.splitAtSelectedNodes
import dev.svgpro.editor.createMaskFromSelection
import dev.svgpro.editor.createSymbolFromSelection
import dev.svgpro.editor.duplicateWithOffset
import dev.svgpro.editor.gradientModelOfSelection
import dev.svgpro.editor.gridDuplicate
import dev.svgpro.editor.invertSelection
import dev.svgpro.editor.makeTextPath
import dev.svgpro.editor.placeSymbolInstance
import dev.svgpro.editor.releaseCompound
import dev.svgpro.editor.releaseClipSelection
import dev.svgpro.editor.releaseMaskSelection
import dev.svgpro.editor.replaceColorEverywhere
import dev.svgpro.editor.rotateCopies
import dev.svgpro.editor.selectSameFill
import dev.svgpro.editor.setFillRuleSelection
import dev.svgpro.editor.setSelectionPosition
import dev.svgpro.editor.setSelectionSize
import dev.svgpro.editor.symbols
import dev.svgpro.editor.transformAgain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 拡張操作（選択・整列・数値変形・複製・複合パス・クリップ/マスク・シンボル・グラデーション・色置換）の統合テスト */
class EditorOpsTest {

    private fun newEditor(): Editor {
        val e = Editor()
        e.newDocument(200.0, 200.0)
        return e
    }

    private fun rect(e: Editor, x: Double, y: Double, w: Double, h: Double, fill: Int? = null) {
        e.createShape(ToolType.RECT, Vec(x, y), Vec(x + w, y + h))
        if (fill != null) e.applyFillToSelection(dev.svgpro.core.model.Paint.Solid(fill))
    }

    // ―――― 選択拡張 ――――

    @Test
    fun selectSameFill_and_invert() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0, 0xFFFF0000.toInt())
        rect(e, 20.0, 0.0, 10.0, 10.0, 0xFFFF0000.toInt())
        rect(e, 40.0, 0.0, 10.0, 10.0, 0xFF00FF00.toInt())
        val rects = e.doc.root.childElements().filter { it.tag == "rect" }
        e.select(rects[0])
        e.selectSameFill()
        assertEquals(2, e.selection.size) // 赤い2つ
        e.invertSelection()
        assertEquals(1, e.selection.size)
        assertEquals(rects[2], e.selection[0])
    }

    // ―――― 整列・配置 ――――

    @Test
    fun align_to_canvas() {
        val e = newEditor()
        rect(e, 80.0, 90.0, 20.0, 20.0)
        val r = e.selection[0]
        e.alignSelectionTo("left", AlignRelative.CANVAS)
        val b = e.trace.boundsOf(r)!!
        assertEquals(0.0, b.x, 0.01)
        assertEquals(90.0, b.y, 0.01) // yは不変
        e.alignSelectionTo("top", AlignRelative.CANVAS)
        assertEquals(0.0, e.trace.boundsOf(r)!!.y, 0.01)
        // undo
        e.undo()
        assertEquals(90.0, e.trace.boundsOf(r)!!.y, 0.01)
    }

    @Test
    fun key_object_alignment() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0)
        val key = e.selection[0]
        rect(e, 50.0, 50.0, 10.0, 10.0)
        val moving = e.selection[0]
        e.select(key)
        e.select(moving, additive = true)
        e.keyObject = key
        e.alignSelectionTo("left", AlignRelative.KEY_OBJECT)
        assertEquals(0.0, e.trace.boundsOf(moving)!!.x, 0.01) // keyの左へ
        assertEquals(0.0, e.trace.boundsOf(key)!!.x, 0.01) // keyは動かない
    }

    // ―――― 数値変形・変形の繰り返し ――――

    @Test
    fun numeric_transform_and_again() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0)
        val r = e.selection[0]
        e.applyTransform(TransformOp.Move(5.0, 0.0))
        assertEquals(5.0, e.trace.boundsOf(r)!!.x, 0.01)
        e.transformAgain()
        assertEquals(10.0, e.trace.boundsOf(r)!!.x, 0.01)
        e.undo()
        e.undo()
        assertEquals(0.0, e.trace.boundsOf(r)!!.x, 0.01)
    }

    @Test
    fun transform_duplicate() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0)
        val first = e.selection[0]
        e.applyTransform(TransformOp.Move(20.0, 0.0), duplicate = true)
        assertEquals(2, e.doc.root.childElements().filter { it.tag == "rect" }.size)
        assertEquals(20.0, e.trace.boundsOf(e.selection[0])!!.x, 0.01)
        assertEquals(0.0, e.trace.boundsOf(first)!!.x, 0.01) // 元は不動
    }

    @Test
    fun numeric_position_size() {
        val e = newEditor()
        rect(e, 10.0, 10.0, 20.0, 10.0)
        val r = e.selection[0]
        e.setSelectionPosition(0.0, 5.0)
        val b = e.trace.boundsOf(r)!!
        assertEquals(0.0, b.x, 0.01)
        assertEquals(5.0, b.y, 0.01)
        e.setSelectionSize(40.0, 20.0)
        val b2 = e.trace.boundsOf(r)!!
        assertEquals(40.0, b2.width, 0.05)
        assertEquals(20.0, b2.height, 0.05)
        assertEquals(0.0, b2.x, 0.5) // 左上アンカー（誤差許容）
    }

    // ―――― 複製・反復 ――――

    @Test
    fun duplicate_with_offset() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0)
        e.duplicateWithOffset(15.0, 0.0)
        assertEquals(2, e.doc.root.childElements().filter { it.tag == "rect" }.size)
        assertEquals(15.0, e.trace.boundsOf(e.selection[0])!!.x, 0.01)
    }

    @Test
    fun rotate_copies() {
        val e = newEditor()
        // 中心(100,100)に小さな正方形
        rect(e, 98.0, 60.0, 4.0, 4.0)
        e.rotateCopies(count = 3, centerUser = Vec(100.0, 100.0), totalDegrees = 360.0)
        assertEquals(4, e.doc.root.childElements().filter { it.tag == "rect" }.size)
        // 全体のbboxは中心対称
        val all = e.doc.root.childElements().filter { it.tag == "rect" }
        val bounds = e.trace.boundsOf(all)!!
        assertEquals(100.0, bounds.centerX, 0.5)
        assertEquals(100.0, bounds.centerY, 0.5)
        e.undo()
        assertEquals(1, e.doc.root.childElements().filter { it.tag == "rect" }.size)
    }

    @Test
    fun grid_duplicate() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0)
        e.gridDuplicate(cols = 3, rows = 2, dx = 20.0, dy = 15.0)
        assertEquals(6, e.doc.root.childElements().filter { it.tag == "rect" }.size)
        e.undo()
        assertEquals(1, e.doc.root.childElements().filter { it.tag == "rect" }.size)
    }

    // ―――― 複合パス ――――

    @Test
    fun compound_path_roundtrip() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 40.0, 40.0)
        rect(e, 10.0, 10.0, 20.0, 20.0)
        val two = e.doc.root.childElements().filter { it.tag == "rect" }
        e.select(two[0])
        e.select(two[1], additive = true)
        assertTrue(e.compoundSelection())
        assertEquals(1, e.selection.size)
        assertEquals("path", e.selection[0].tag)
        assertEquals("evenodd", StyleUtil.fillRule(e.selection[0]))
        val pd = PathData.parse(e.selection[0].attr("d"))!!
        assertEquals(2, pd.subpaths().size)
        // 穴の保持（面積 = 1600-400 = 1200）
        val area = polygonArea(pd)
        assertTrue(area > 1100 && area < 1300, "area=$area")
        // 解除 → 2要素
        assertTrue(e.releaseCompound())
        assertEquals(2, e.selection.size)
        assertEquals(2, e.doc.root.childElements().filter { it.tag == "path" }.size)
        // undo2回で元の2rect
        e.undo()
        e.undo()
        assertEquals(2, e.doc.root.childElements().filter { it.tag == "rect" }.size)
    }

    @Test
    fun fill_rule_toggle() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0)
        e.setFillRuleSelection("evenodd")
        assertEquals("evenodd", StyleUtil.fillRule(e.selection[0]))
        e.setFillRuleSelection("nonzero")
        assertEquals("nonzero", StyleUtil.fillRule(e.selection[0]))
        e.undo()
        assertEquals("evenodd", StyleUtil.fillRule(e.selection[0]))
    }

    // ―――― クリッピング / マスク ――――

    @Test
    fun clip_create_and_release() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 50.0, 50.0)
        val target = e.selection[0]
        rect(e, 10.0, 10.0, 20.0, 20.0)
        val clipper = e.selection[0]
        e.select(target)
        e.select(clipper, additive = true)
        assertTrue(e.createClipFromSelection())
        // クリッパーはドキュメントツリーから外れ、targetにclip-pathが付く
        assertEquals(1, e.doc.root.childElements().filter { it.tag == "rect" }.size)
        assertNotNull(target.attr("clip-path"))
        // defsにclipPathがある
        val defs = e.doc.defsElement()
        assertEquals(1, defs.childElements().filter { it.tag == "clipPath" }.size)
        // 解除
        assertTrue(e.releaseClipSelection())
        assertNull(target.attr("clip-path"))
        // undoで復元
        e.undo()
        assertNotNull(target.attr("clip-path"))
    }

    @Test
    fun mask_create_and_release() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 50.0, 50.0)
        val target = e.selection[0]
        rect(e, 10.0, 10.0, 20.0, 20.0)
        val masker = e.selection[0]
        e.select(target)
        e.select(masker, additive = true)
        assertTrue(e.createMaskFromSelection())
        assertNotNull(target.attr("mask"))
        val defs = e.doc.defsElement()
        assertEquals(1, defs.childElements().filter { it.tag == "mask" }.size)
        assertTrue(e.releaseMaskSelection())
        assertNull(target.attr("mask"))
    }

    // ―――― シンボル ――――

    @Test
    fun symbol_create_place_break() {
        val e = newEditor()
        rect(e, 10.0, 10.0, 20.0, 20.0)
        assertTrue(e.createSymbolFromSelection() != null)
        assertEquals(1, e.symbols().size)
        // 元のrectは削除され、useが置かれた
        assertEquals(0, e.doc.root.childElements().filter { it.tag == "rect" }.size)
        assertEquals(1, e.doc.root.childElements().filter { it.tag == "use" }.size)
        // インスタンス追加
        val use2 = e.placeSymbolInstance(e.symbols()[0].attr("id")!!)
        assertNotNull(use2)
        assertEquals(2, e.doc.root.childElements().filter { it.tag == "use" }.size)
        // インスタンス解除 → 編集可能な実体
        e.select(use2)
        assertTrue(e.breakSymbolInstance())
        assertEquals(1, e.doc.root.childElements().filter { it.tag == "use" }.size)
        // undo: 解除前に戻る
        e.undo()
        assertEquals(2, e.doc.root.childElements().filter { it.tag == "use" }.size)
    }

    // ―――― グラデーション ――――

    @Test
    fun gradient_create_edit_stops() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 100.0, 100.0, 0xFF1473E6.toInt())
        val id = e.createGradientForSelection(radial = false)
        assertNotNull(id)
        val model = e.gradientModelOfSelection()
        assertNotNull(model)
        assertEquals(2, model!!.stops.size)
        // ストップ追加
        assertTrue(e.addGradientStop(0.5))
        val model2 = e.gradientModelOfSelection()!!
        assertEquals(3, model2.stops.size)
        // undoで2つに戻る
        e.undo()
        assertEquals(2, e.gradientModelOfSelection()!!.stops.size)
    }

    // ―――― ガイド ――――

    @Test
    fun guides_add_and_persist() {
        val e = newEditor()
        e.addGuide(Guide(horizontal = true, pos = 50.0))
        e.addGuide(Guide(horizontal = false, pos = 30.0, locked = true))
        assertEquals(2, e.guides.size)
        // ドキュメント属性に保存される
        val attr = e.doc.root.attr(Guide.ATTR)
        assertNotNull(attr)
        // スナップ: ガイドへ引き寄せられる
        e.snapToGuides = true
        e.snapToObject = false
        val snapped = e.snapPoint(Vec(31.0, 20.0))
        assertEquals(30.0, snapped.x, 0.001)
        // 新ドキュメントへ持って行くと復元される（serialize→attr復元）
        val e2 = newEditor()
        e2.doc.root.setAttr(Guide.ATTR, attr)
        e2.guides.clear()
        e2.guides.addAll(dev.svgpro.core.model.Guide.Codec.decode(attr))
        assertEquals(2, e2.guides.size)
    }

    // ―――― テキストパス ――――

    @Test
    fun text_on_path_structure() {
        val e = newEditor()
        val p = dev.svgpro.core.svg.Element("path")
        p.setAttr("d", "M0 100 C50 50 150 50 200 100")
        e.addElement(p, "curve")
        val t = dev.svgpro.core.svg.Element("text")
        t.setTextContent("Hello")
        e.addElement(t, "text")
        e.select(t)
        e.select(p, additive = true)
        assertTrue(e.makeTextPath())
        val tp = t.childElements().firstOrNull { it.tag == "textPath" }
        assertNotNull(tp)
        assertEquals("Hello", tp.textContent())
        assertNotNull(tp.attr("href"))
        assertEquals("M0 100 C50 50 150 50 200 100", p.attr("d")) // パスは無傷
    }

    // ―――― 色の一括置換 ――――

    @Test
    fun replace_color_everywhere() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0, 0xFFFF0000.toInt())
        rect(e, 20.0, 0.0, 10.0, 10.0, 0xFF00FF00.toInt())
        val count = e.replaceColorEverywhere(0xFFFF0000.toInt(), 0xFF0000FF.toInt())
        assertEquals(1, count)
        val rects = e.doc.root.childElements().filter { it.tag == "rect" }
        assertEquals("rgb(0,0,255)", StyleUtil.getProperty(rects[0], "fill"))
        assertEquals("rgb(0,255,0)", StyleUtil.getProperty(rects[1], "fill"))
        // undo
        e.undo()
        assertEquals("rgb(255,0,0)", StyleUtil.getProperty(rects[0], "fill"))
    }

    // ―――― ノード操作（構造） ――――

    @Test
    fun node_session_split_and_join() {
        val e = newEditor()
        val el = dev.svgpro.core.svg.Element("path")
        el.setAttr("d", "M0 0 L40 0 L40 40 Z")
        e.addElement(el, "triangle")
        e.select(el)
        e.tool = ToolType.NODE
        e.updateNodeSession()
        val session = e.nodeSession!!
        assertEquals(3, session.models[0].nodes.size)

        // ノード1を選択して切断 → 開パス化（4ノード）
        session.selectOnly(NodeRef(0, 1))
        e.commit("split") { b -> b.touch(session.el); assertTrue(session.splitAtSelectedNodes()) }
        e.nodeSession = null
        e.updateNodeSession()
        val s2 = e.nodeSession!!
        assertEquals(4, s2.models[0].nodes.size)
        // 切断されたパスは開いている
        val pd = PathData.parse(el.attr("d"))!!
        assertEquals(1, pd.subpaths().size)

        // 端点2つを選んで接続 → 閉じる
        s2.selectOnly(NodeRef(0, 0))
        s2.toggleSelect(NodeRef(0, 3))
        e.commit("join") { b -> b.touch(session.el); assertNotNull(s2.joinSelectedEnds()) }
        assertTrue(PathData.parse(el.attr("d"))!!.write().contains("Z"))
    }

    @Test
    fun node_session_delete_segments() {
        val e = newEditor()
        val el = dev.svgpro.core.svg.Element("path")
        el.setAttr("d", "M0 0 L10 0 L10 10 L0 10 Z")
        e.addElement(el, "square")
        e.select(el)
        e.tool = ToolType.NODE
        e.updateNodeSession()
        val session = e.nodeSession!!
        // ノード0と1を選択 → セグメント0を削除 → 開いた鎖
        session.selectOnly(NodeRef(0, 0))
        session.toggleSelect(NodeRef(0, 1))
        e.commit("del seg") { b -> b.touch(session.el); assertTrue(session.deleteSelectedSegments()) }
        val pd = PathData.parse(el.attr("d"))!!
        assertEquals(1, pd.subpaths().size)
        assertFalse0(pd.write().contains("Z"))
    }

    @Test
    fun node_session_segment_conversion() {
        val e = newEditor()
        val el = dev.svgpro.core.svg.Element("path")
        el.setAttr("d", "M0 0 L40 0 L40 40 L0 40 Z")
        e.addElement(el, "rect path")
        e.select(el)
        e.tool = ToolType.NODE
        e.updateNodeSession()
        val session = e.nodeSession!!
        session.selectOnly(NodeRef(0, 0))
        session.toggleSelect(NodeRef(0, 1))
        e.commit("curve") { b -> b.touch(session.el); assertTrue(session.convertSelectedSegments(true)) }
        val d = el.attr("d")!!
        assertTrue(d.contains("C"), "d=$d") // 曲線化
        e.commit("line") { b -> b.touch(session.el); assertTrue(session.convertSelectedSegments(false)) }
        val d2 = el.attr("d")!!
        assertTrue(!d2.contains("C") || PathData.parse(d2)!!.segs.none { it is dev.svgpro.core.path.Seg.Cubic && it.y == 0.0 })
    }

    @Test
    fun node_invert_and_distribute() {
        val e = newEditor()
        val el = dev.svgpro.core.svg.Element("path")
        el.setAttr("d", "M0 0 L10 5 L20 0 L30 5 L40 0")
        e.addElement(el, "zigzag")
        e.select(el)
        e.tool = ToolType.NODE
        e.updateNodeSession()
        val session = e.nodeSession!!
        session.selectOnly(NodeRef(0, 0))
        session.invertNodeSelection()
        assertEquals(4, session.selected.size) // 残り4ノード
        // 横に等間隔配置（選択4ノード: x=10,20,30,40 → 保持）
        session.selectAllNodes()
        assertTrue(session.distributeSelectedNodes(horizontal = true))
        val xs = session.models[0].nodes.map { it.p.x }
        assertEquals(0.0, xs[0], 0.01)
        assertEquals(40.0, xs[4], 0.01)
        assertEquals(10.0, xs[1], 0.01)
        assertEquals(30.0, xs[3], 0.01)
    }

    /** サブパスごとの符号付き面積（evenodd: |外周| - |穴|） */
    private fun polygonArea(pd: PathData): Double {
        val rings = ArrayList<Double>()
        for (sp in pd.subpaths()) {
            val segs = ArrayList(pd.segs.subList(sp.segStart, sp.segEnd + if (sp.closed) 1 else 0))
            val pts = PathData(segs).flatten(0.01)
            var s = 0.0
            for (i in pts.indices) {
                val a = pts[i]
                val b = pts[(i + 1) % pts.size]
                s += a.x * b.y - b.x * a.y
            }
            rings.add(kotlin.math.abs(s / 2.0))
        }
        rings.sortDescending()
        // 最外 - 残り（1穴想定のテストヘルパ）
        var area = rings.firstOrNull() ?: 0.0
        for (i in 1 until rings.size) area -= rings[i]
        return area
    }
}

private fun assertFalse0(b: Boolean) {
    if (b) throw AssertionError("expected false")
}
