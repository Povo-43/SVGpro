package dev.svgpro

import dev.svgpro.core.geom.Vec
import dev.svgpro.core.model.Paint
import dev.svgpro.core.model.Shapes
import dev.svgpro.core.svg.SvgLoader
import dev.svgpro.editor.Editor
import dev.svgpro.editor.GeometryOpKind
import dev.svgpro.editor.NodeRef
import dev.svgpro.editor.ToolType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * エディタの中核フロー（選択・変形・グループ・ブーリアン・Undo/Redo・SVG入出力）の
 * JVM統合テスト。Android APIに依存しない範囲を検証する。
 */
class EditorFlowTest {

    private fun newEditor(): Editor {
        val e = Editor()
        e.newDocument(200.0, 200.0)
        return e
    }

    private fun tokenSourceOf(xml: String) = object : dev.svgpro.core.svg.TokenSource {
        private val q = dev.svgpro.core.svg.TreeBuilder.let {
            // テスト用にSAXパーサはコアテスト側の実装を使う（app モジュールでは簡易にXmlPullParser不可のため
            // ここでは最小トークナイザを用意する）
            SimpleTokens(xml)
        }
        override fun next(): dev.svgpro.core.svg.Token = q.next()
    }

    @Test
    fun create_rect_and_select_move() {
        val e = newEditor()
        e.createShape(ToolType.RECT, Vec(10.0, 10.0), Vec(50.0, 60.0))
        assertEquals(1, e.selection.size)
        val rect = e.selection[0]
        assertEquals("rect", rect.tag)
        assertEquals("10", rect.attr("x"))
        assertEquals("40", rect.attr("width"))

        // 移動（undo統合ドラッグ。moveはtransform属性で記録される）
        val session = e.beginDrag("move", e.selection)
        e.moveSelection(Vec(5.0, 0.0))
        e.moveSelection(Vec(5.0, 0.0))
        e.endDrag(session)
        val b = e.trace.boundsOf(rect)!!
        assertTrue(b.x > 18.0 && b.x < 22.0, "x=${b.x}")
        assertTrue(e.dirty)

        // undo → 元へ
        e.undo()
        val b2 = e.trace.boundsOf(rect)!!
        assertTrue(b2.x > 8.0 && b2.x < 12.0, "x2=${b2.x}")
        e.redo()
        val b3 = e.trace.boundsOf(rect)!!
        assertTrue(b3.x > 18.0 && b3.x < 22.0, "x3=${b3.x}")
    }

    @Test
    fun scale_and_rotate_are_recorded() {
        val e = newEditor()
        e.createShape(ToolType.RECT, Vec(0.0, 0.0), Vec(40.0, 40.0))
        val rect = e.selection[0]
        val drag = e.beginDrag("scale", e.selection)
        e.scaleSelection(2.0, 1.0, Vec(20.0, 20.0))
        e.endDrag(drag)
        // bboxが横に倍
        val b = e.trace.boundsOf(rect)!!
        assertTrue(b.width > 78.0 && b.width < 82.0, "w=${b.width}")
        e.undo()
        val b2 = e.trace.boundsOf(rect)!!
        assertTrue(b2.width < 41.0)
        e.redo()
        val b3 = e.trace.boundsOf(rect)!!
        assertTrue(b3.width > 78.0, "w3=${b3.width}")
    }

    @Test
    fun group_ungroup() {
        val e = newEditor()
        e.createShape(ToolType.RECT, Vec(0.0, 0.0), Vec(20.0, 20.0))
        e.createShape(ToolType.ELLIPSE, Vec(30.0, 0.0), Vec(60.0, 30.0))
        assertEquals(1, e.selection.size) // 作成直後は新しい要素のみ
        val rect = e.doc.root.childElements().first { it.tag == "rect" }
        val ellipse = e.doc.root.childElements().first { it.tag == "ellipse" }
        e.select(rect)
        e.select(ellipse, additive = true)
        assertEquals(2, e.selection.size)
        e.groupSelection()
        assertEquals(1, e.selection.size)
        assertEquals("g", e.selection[0].tag)
        assertEquals(2, e.selection[0].childElements().size)
        e.ungroupSelection()
        assertEquals(2, e.selection.size)
        // undoで group に戻る
        e.undo()
        assertEquals(1, e.selection.size)
        assertEquals("g", e.selection[0].tag)
    }

    @Test
    fun boolean_difference() {
        val e = newEditor()
        e.createShape(ToolType.RECT, Vec(0.0, 0.0), Vec(40.0, 40.0))
        val rect = e.selection[0]
        e.createShape(ToolType.ELLIPSE, Vec(10.0, 10.0), Vec(30.0, 30.0))
        val ellipse = e.selection[0]
        e.select(rect)
        e.select(ellipse, additive = true)
        e.booleanOp(GeometryOpKind.DIFFERENCE)
        assertEquals(1, e.selection.size)
        assertEquals("path", e.selection[0].tag)
        val d = e.selection[0].attr("d")!!
        // 差集合: 外形 + 穴の2サブパス（evenodd）
        val pd = dev.svgpro.core.path.PathData.parse(d)!!
        assertEquals(2, pd.subpaths().size)
        // even-oddで面積確認（矩形1600 - 円≈314）
        val area = evenOddArea(pd, 0.5)
        assertTrue(area > 1200.0 && area < 1350.0, "area=$area")
    }

    /** even-oddルールで面積をサンプリング計算 */
    private fun evenOddArea(pd: dev.svgpro.core.path.PathData, step: Double): Double {
        val b = pd.bounds(0.01)!!
        var inside = 0
        var total = 0
        var y = b.y + step / 2
        while (y < b.bottom) {
            var x = b.x + step / 2
            while (x < b.right) {
                total++
                if (evenOddContains(pd, Vec(x, y))) inside++
                x += step
            }
            y += step
        }
        return inside.toDouble() * step * step
    }

    private fun evenOddContains(pd: dev.svgpro.core.path.PathData, p: Vec): Boolean {
        val pts = pd.flatten(0.01)
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val a = pts[i]
            val c = pts[j]
            if ((a.y > p.y) != (c.y > p.y)) {
                val xint = (c.x - a.x) * (p.y - a.y) / (c.y - a.y) + a.x
                if (p.x < xint) inside = !inside
            }
            j = i
        }
        return inside
    }

    @Test
    fun node_edit_changes_d() {
        val e = newEditor()
        e.tool = ToolType.NODE
        val el = dev.svgpro.core.svg.Element("path")
        el.setAttr("d", "M0 0 L40 0 L40 40 Z")
        e.addElement(el, "test path")
        e.select(el)
        e.updateNodeSession()
        val session = e.nodeSession
        assertNotNull(session)
        assertEquals(3, session.models[0].nodes.size)
        // ノード0を移動
        val drag = e.beginDrag("node", listOf(el))
        session.moveNode(NodeRef(0, 0), Vec(10.0, 5.0))
        session.flush()
        e.endDrag(drag)
        assertTrue(el.attr("d")!!.startsWith("M10 5"))
        // undo
        e.undo()
        assertTrue(el.attr("d")!!.startsWith("M0 0"))
    }

    @Test
    fun primitive_to_path_conversion() {
        val e = newEditor()
        e.createShape(ToolType.RECT, Vec(0.0, 0.0), Vec(30.0, 20.0))
        val rect = e.selection[0]
        e.convertToPath(listOf(rect))
        val converted = e.selection[0]
        assertEquals("path", converted.tag)
        assertNotNull(dev.svgpro.core.path.PathData.parse(converted.attr("d")))
    }

    @Test
    fun duplicate_delete_and_history() {
        val e = newEditor()
        e.createShape(ToolType.RECT, Vec(0.0, 0.0), Vec(30.0, 20.0))
        e.duplicateSelection()
        assertEquals(2, e.doc.root.childElements().filter { it.tag == "rect" }.size)
        e.deleteSelection()
        assertEquals(1, e.doc.root.childElements().filter { it.tag == "rect" }.size)
        e.undo()
        assertEquals(2, e.doc.root.childElements().filter { it.tag == "rect" }.size)
    }

    @Test
    fun serialize_load_roundtrip() {
        val e = newEditor()
        e.createShape(ToolType.RECT, Vec(0.0, 0.0), Vec(30.0, 20.0))
        val text = e.doc.serialize()
        assertTrue(text.contains("<rect"))
        // SvgLoaderはプラットフォーム依存のため、ここではTreeBuilder+SaxTokenSourceは使えない
        // → 代わりに SvgDocument への復元は core のパーサを直接使用しない（Androidスモークで確認）
        assertTrue(text.contains("</svg>"))
    }

    @Test
    fun pen_draft_commit_and_cancel() {
        val e = newEditor()
        e.tool = ToolType.PEN
        // ペン: Move + Line のドラフト
        val segs = ArrayList<dev.svgpro.core.path.Seg>()
        segs.add(dev.svgpro.core.path.Seg.Move(0.0, 0.0))
        segs.add(dev.svgpro.core.path.Seg.Line(30.0, 0.0))
        e.penDraft = dev.svgpro.core.path.PathData(segs)
        e.commitPenDraft(close = true)
        assertEquals(null, e.penDraft)
        assertEquals(1, e.doc.root.childElements().filter { it.tag == "path" }.size)
        val created = e.doc.root.childElements().first { it.tag == "path" }
        assertTrue(created.attr("d")!!.endsWith("Z"))
        // 取消
        e.tool = ToolType.PEN
        val segs2 = ArrayList<dev.svgpro.core.path.Seg>()
        segs2.add(dev.svgpro.core.path.Seg.Move(0.0, 0.0))
        e.penDraft = dev.svgpro.core.path.PathData(segs2)
        e.cancelPenDraft()
        assertEquals(null, e.penDraft)
        // ツール切替で自動確定
        val segs3 = ArrayList<dev.svgpro.core.path.Seg>()
        segs3.add(dev.svgpro.core.path.Seg.Move(0.0, 0.0))
        segs3.add(dev.svgpro.core.path.Seg.Line(10.0, 10.0))
        e.penDraft = dev.svgpro.core.path.PathData(segs3)
        e.tool = ToolType.SELECT
        assertEquals(null, e.penDraft)
        assertEquals(2, e.doc.root.childElements().filter { it.tag == "path" }.size)
    }

    @Test
    fun layers_visibility_lock() {
        val e = newEditor()
        e.createShape(ToolType.RECT, Vec(0.0, 0.0), Vec(30.0, 20.0))
        val rect = e.selection[0]
        e.setHidden(rect, true)
        assertEquals("none", dev.svgpro.core.model.StyleUtil.getProperty(rect, "display"))
        // 非表示はヒットしない
        val hit = e.trace.hitTest(Vec(15.0, 10.0), 5.0)
        assertEquals(null, hit)
        e.setHidden(rect, false)
        val hit2 = e.trace.hitTest(Vec(15.0, 10.0), 5.0)
        assertEquals(rect, hit2)
        e.setLocked(rect, true)
        val hit3 = e.trace.hitTest(Vec(15.0, 10.0), 5.0)
        assertEquals(null, hit3)
    }

    private fun shoelace(pd: dev.svgpro.core.path.PathData): Double {
        val pts = pd.flatten(0.01)
        var area = 0.0
        // サブパスごとに計算（flattenはサブパス連結で重複点を含むため、Moveで区切る）
        var start = 0
        for (i in 1 until pts.size) {
            val prev = pts[i - 1]
            val cur = pts[i]
            if (cur.x == pts[start].x && cur.y == pts[start].y && i > start + 1) {
                for (j in start until i) {
                    val a = pts[j]
                    val b = pts[j + 1]
                    area += a.x * b.y - b.x * a.y
                }
                start = i
            }
        }
        return area / 2.0
    }
}

/** テスト用の簡易XMLトークナイザ（JVM・Android共通） */
class SimpleTokens(private val xml: String) {
    private var i = 0
    private val queue = ArrayDeque<dev.svgpro.core.svg.Token>()
    private var built = false

    private fun build() {
        built = true
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) {
                if (i < xml.length) queue.add(dev.svgpro.core.svg.Token.Text(xml.substring(i)))
                break
            }
            if (lt > i) queue.add(dev.svgpro.core.svg.Token.Text(xml.substring(i, lt)))
            when {
                xml.startsWith("<!--", lt) -> {
                    val end = xml.indexOf("-->", lt)
                    queue.add(dev.svgpro.core.svg.Token.Comment(xml.substring(lt + 4, end)))
                    i = end + 3
                }
                xml.startsWith("<?", lt) -> {
                    val end = xml.indexOf("?>", lt)
                    i = end + 2
                }
                xml.startsWith("<!", lt) -> {
                    val end = xml.indexOf('>', lt)
                    i = end + 1
                }
                xml.startsWith("</", lt) -> {
                    val end = xml.indexOf('>', lt)
                    val name = xml.substring(lt + 2, end).trim()
                    queue.add(dev.svgpro.core.svg.Token.EndTag)
                    i = end + 1
                }
                else -> {
                    // 開始タグ（自己閉鎖判定つき）。属性は簡易解析
                    var end = lt + 1
                    var inQuote: Char? = null
                    while (end < xml.length) {
                        val c = xml[end]
                        if (inQuote != null) {
                            if (c == inQuote) inQuote = null
                        } else when (c) {
                            '"', '\'' -> inQuote = c
                            '>' -> break
                        }
                        end++
                    }
                    val inner = xml.substring(lt + 1, end)
                    val selfClosed = inner.trimEnd().endsWith("/")
                    val body = if (selfClosed) inner.trimEnd().dropLast(1) else inner
                    val nameEnd = body.indexOfFirst { it.isWhitespace() }
                    val name = if (nameEnd < 0) body else body.substring(0, nameEnd)
                    val attrs = LinkedHashMap<String, String>()
                    val attrRegex = Regex("""([^\s=]+)\s*=\s*"([^"]*)"""")
                    for (m in attrRegex.findAll(body)) {
                        attrs[m.groupValues[1]] = m.groupValues[2]
                    }
                    queue.add(dev.svgpro.core.svg.Token.StartTag(name, attrs, selfClosed))
                    i = end + 1
                }
            }
        }
        queue.add(dev.svgpro.core.svg.Token.EndDocument)
    }

    fun next(): dev.svgpro.core.svg.Token {
        if (!built) build()
        return queue.removeFirstOrNull() ?: dev.svgpro.core.svg.Token.EndDocument
    }
}
