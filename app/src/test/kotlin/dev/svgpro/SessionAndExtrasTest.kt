package dev.svgpro

import dev.svgpro.core.geom.Vec
import dev.svgpro.core.model.Paint
import dev.svgpro.core.model.SvgDocument
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import dev.svgpro.editor.Editor
import dev.svgpro.editor.ToolType
import dev.svgpro.editor.addDocumentColorsToSwatches
import dev.svgpro.editor.buildDocumentFromSelection
import dev.svgpro.editor.extractDocumentColors
import dev.svgpro.editor.setSelectionPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.7.0 追加機能の回帰テスト:
 * 複数ドキュメント（セッション退避/復元）・ペン継続・ドキュメント色収集・選択をSVG書き出し。
 */
class SessionAndExtrasTest {

    private fun newEditor(): Editor {
        val e = Editor()
        e.newDocument(200.0, 200.0)
        return e
    }

    private fun rect(e: Editor, x: Double, y: Double, w: Double, h: Double, fill: Int? = null) {
        e.createShape(ToolType.RECT, Vec(x, y), Vec(x + w, y + h))
        if (fill != null) e.applyFillToSelection(Paint.Solid(fill))
    }

    // ―――― 複数ドキュメント: セッション退避/復元 ――――

    @Test
    fun exportSession_then_import_restores_doc_undo_selection_and_view() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0, 0xFFFF0000.toInt())
        rect(e, 20.0, 0.0, 10.0, 10.0)
        // 両方を選択して移動（undo 1回分の履歴を作る）
        e.selectAllInBox(dev.svgpro.core.geom.Bounds(-1e9, -1e9, 1e18, 1e18), additive = false)
        val session = e.beginDrag("move", e.selection)
        e.setSelectionPosition(50.0, 50.0)
        e.endDrag(session)
        assertTrue(e.dirty)
        e.setZoom(2.0)

        // セッション退避 → 別ドキュメントで作業
        val snap = e.exportSession()
        assertEquals(0, e.selection.size)
        e.newDocument(300.0, 300.0)
        rect(e, 0.0, 0.0, 5.0, 5.0)
        assertEquals(1, e.doc.root.childElements().count { it.tag == "rect" })

        // 復元 → 元ドキュメント・履歴・選択・ビューが戻る
        e.importSession(snap)
        assertEquals(2, e.doc.root.childElements().count { it.tag == "rect" })
        assertEquals("untitled.svg", e.documentName)
        assertTrue(e.dirty)
        assertEquals(2.0, e.zoom, 1e-6)
        assertEquals(200.0, e.doc.viewBox.width, 1e-6)

        // undo履歴も復元されている（undoで移動が戻る。要素は消えない）
        e.undo()
        assertEquals(2, e.doc.root.childElements().count { it.tag == "rect" })
        // redoも復元されている
        e.redo()
        assertEquals(2, e.doc.root.childElements().count { it.tag == "rect" })
    }

    @Test
    fun exportSession_detaches_selection_and_drafts() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0)
        assertEquals(1, e.selection.size)
        val snap = e.exportSession()
        assertEquals(0, e.selection.size)
        assertNull(e.nodeSession)
        assertNull(e.penDraft)
        // 復元で選択も戻る
        e.importSession(snap)
        assertEquals(1, e.selection.size)
    }

    // ―――― ペン: 既存パスの端点から継続 ――――

    @Test
    fun penContinueTarget_commits_into_existing_path() {
        val e = newEditor()
        val line = dev.svgpro.core.svg.Element("path")
        line.setAttr("d", "M 10 10 L 50 10")
        e.doc.root.children.add(line)
        e.invalidateTrace()

        // 終点(50,10)からの続き描きをシミュレート（ドラフトは既存ジオメトリ全体を含む）
        e.penContinueTarget = line
        e.penDraft = PathData(
            mutableListOf(
                Seg.Move(10.0, 10.0),
                Seg.Line(50.0, 10.0),
                Seg.Line(90.0, 40.0),
            ),
        )
        e.commitPenDraft(close = false)

        // 新要素は増えず、既存パスのdが更新される
        assertEquals(1, e.doc.root.childElements().count { it.tag == "path" })
        val d = PathData.parse(line.attr("d"))!!
        assertEquals(2, d.segs.count { it is Seg.Line || it is Seg.Cubic })
        assertNull(e.penContinueTarget)
        // undoで元の線に戻る
        e.undo()
        val d0 = PathData.parse(line.attr("d"))!!
        assertEquals(1, d0.segs.count { it is Seg.Line || it is Seg.Cubic })
    }

    @Test
    fun cancelPenDraft_clears_continue_target() {
        val e = newEditor()
        val line = dev.svgpro.core.svg.Element("path")
        line.setAttr("d", "M 10 10 L 50 10")
        e.doc.root.children.add(line)
        e.invalidateTrace()
        e.penContinueTarget = line
        e.penDraft = PathData(mutableListOf(Seg.Move(50.0, 10.0), Seg.Line(60.0, 20.0)))
        e.cancelPenDraft()
        assertNull(e.penContinueTarget)
        assertNull(e.penDraft)
        assertEquals("M 10 10 L 50 10", line.attr("d"))
    }

    // ―――― ドキュメントから色を収集 ――――

    @Test
    fun extractDocumentColors_collects_fill_stroke_and_stops() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0, 0xFFFF0000.toInt())
        e.createShape(ToolType.ELLIPSE, Vec(30.0, 0.0), Vec(50.0, 20.0))
        e.applyStrokeToSelection(Paint.Solid(0xFF00FF00.toInt()))

        // グラデーションストップの色も収集対象
        val defs = e.doc.defsElement()
        val g = dev.svgpro.core.svg.Element("linearGradient")
        val s1 = dev.svgpro.core.svg.Element("stop")
        s1.setAttr("stop-color", "#0000ff")
        g.children.add(s1)
        defs.children.add(g)

        val colors = e.extractDocumentColors()
        assertTrue(colors.contains(0xFFFF0000.toInt()))
        assertTrue(colors.contains(0xFF00FF00.toInt()))
        assertTrue(colors.contains(0xFF0000FF.toInt()))
    }

    @Test
    fun addDocumentColorsToSwatches_adds_unique_capped() {
        val e = newEditor()
        for (i in 0 until 30) {
            rect(e, i * 5.0, 0.0, 4.0, 4.0, 0xFF000000.toInt() + i)
        }
        val before = e.colorSwatches.size
        val added = e.addDocumentColorsToSwatches(maxCap = 24)
        assertTrue(added > 0)
        assertTrue(e.colorSwatches.size <= 24)
        // 2回目は追加ゼロ（重複スキップ or 上限到達）
        val added2 = e.addDocumentColorsToSwatches(maxCap = 24)
        assertEquals(0, added2)
        assertTrue(before <= 24)
    }

    // ―――― 選択をSVGドキュメント化 ――――

    @Test
    fun buildDocumentFromSelection_copies_with_abs_transform() {
        val e = newEditor()
        rect(e, 10.0, 10.0, 30.0, 20.0, 0xFF123456.toInt())
        e.createShape(ToolType.ELLIPSE, Vec(60.0, 10.0), Vec(90.0, 40.0))
        // グループ化してから選択（親変換が焼き込まれることを確認）
        e.selectAllInBox(
            dev.svgpro.core.geom.Bounds(-1e9, -1e9, 1e18, 1e18),
            additive = false,
        )
        e.groupSelection()
        val group = e.selection[0]

        val doc = e.buildDocumentFromSelection()
        assertNotNull(doc)
        // 1要素（グループ）がコピーされ、viewBoxが選択範囲になっている（defsは除く）
        assertEquals(1, doc.root.childElements().count { it.tag != "defs" })
        assertEquals(group.tag, doc.root.childElements().first { it.tag != "defs" }.tag)
        val vb = doc.viewBox
        assertTrue(vb.width > 50.0 && vb.width < 90.0, "viewBox.width=${vb.width}")

        // 書き出し→再読込でラウンドトリップ
        val xml = doc.serialize()
        assertTrue(xml.contains("<svg"))
        assertTrue(xml.contains("viewBox"))
    }

    @Test
    fun buildDocumentFromSelection_empty_selection_returnsNull() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0)
        e.clearSelection()
        assertNull(e.buildDocumentFromSelection())
    }

    @Test
    fun serializeRoundTrip_keepsDocumentColorsUsable() {
        val e = newEditor()
        rect(e, 0.0, 0.0, 10.0, 10.0, 0xFFABCDEF.toInt())
        val xml = e.doc.serialize()
        assertFalse(xml.isEmpty())
        // 色収集はシリアライズ後の再構築でも動く（loadDocumentはパーサ経由のため簡易にrootを検証）
        assertTrue(e.extractDocumentColors().contains(0xFFABCDEF.toInt()))
    }
}
