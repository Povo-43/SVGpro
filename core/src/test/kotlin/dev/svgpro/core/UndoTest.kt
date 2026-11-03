package dev.svgpro.core

import dev.svgpro.core.edit.EditBatch
import dev.svgpro.core.edit.UndoStack
import dev.svgpro.core.edit.changeAttr
import dev.svgpro.core.model.SvgDocument
import dev.svgpro.core.svg.Element
import dev.svgpro.core.svg.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UndoTest {

    private fun makeDoc(): SvgDocument {
        val doc = SvgDocument.create(100.0, 100.0)
        val rect = Element("rect")
        rect.setAttr("x", "10")
        rect.setAttr("y", "10")
        rect.setAttr("width", "20")
        rect.setAttr("height", "20")
        doc.root.children.add(rect)
        return doc
    }

    @Test
    fun attr_change_undo_redo() {
        val doc = makeDoc()
        val rect = doc.root.firstByTag("rect")!!
        val change = changeAttr(rect, "x", "42")
        assertEquals("42", rect.attr("x"))
        val stack = UndoStack()
        stack.push(dev.svgpro.core.edit.ChangeSet(listOf(change), "move x"))
        stack.undo()
        assertEquals("10", rect.attr("x"))
        stack.redo()
        assertEquals("42", rect.attr("x"))
        assertFalse(stack.canRedo)
        assertTrue(stack.canUndo)
    }

    @Test
    fun batch_diff_coalesces_drag() {
        val doc = makeDoc()
        val rect = doc.root.firstByTag("rect")!!
        val stack = UndoStack()
        // ドラッグを想定: begin→複数回の更新→end で1履歴にまとまる
        val batch = EditBatch()
        batch.touch(rect)
        rect.setAttr("x", "11")
        rect.setAttr("x", "12")
        rect.setAttr("y", "15")
        val cs = batch.endAndCommit("drag rect")
        stack.push(cs!!)
        // 統合後の履歴は1件で、undoすると開始状態に戻る
        stack.undo()
        assertEquals("10", rect.attr("x"))
        assertEquals("10", rect.attr("y"))
        stack.redo()
        assertEquals("12", rect.attr("x"))
        assertEquals("15", rect.attr("y"))
    }

    @Test
    fun struct_change_attach_detach() {
        val doc = makeDoc()
        val rect = doc.root.firstByTag("rect")!!
        val g = Element("g")
        doc.root.children.add(g)
        val stack = UndoStack()
        // Attachを適用すると rect は g の先頭へ移動する
        val cs = dev.svgpro.core.edit.ChangeSet(
            listOf(dev.svgpro.core.edit.Attach(rect, g, 0, docRoot = doc.root, label = "group")),
            "group",
        )
        stack.push(cs)
        cs.apply()
        assertTrue(g.children.contains(rect))
        assertFalse(doc.root.children.contains(rect))
        stack.undo()
        assertTrue(doc.root.children.contains(rect))
        assertFalse(g.children.contains(rect))
        // defs + rect + g
        assertEquals(3, doc.root.children.size)
        stack.redo()
        assertTrue(g.children.contains(rect))
    }

    @Test
    fun text_change() {
        val doc = makeDoc()
        val t = Element("text")
        t.children.add(Text("hello"))
        doc.root.children.add(t)
        val batch = EditBatch()
        batch.touch(t)
        t.setTextContent("world")
        val cs = batch.endAndCommit("edit text")
        val stack = UndoStack()
        stack.push(cs!!)
        assertEquals("world", t.textContent())
        stack.undo()
        assertEquals("hello", t.textContent())
    }

    @Test
    fun empty_changes_are_not_pushed() {
        val doc = makeDoc()
        val rect = doc.root.firstByTag("rect")!!
        val batch = EditBatch()
        batch.touch(rect)
        // 何も変えない
        assertNull(batch.endAndCommit("noop"))
    }

    @Test
    fun undo_history_exposes_list() {
        val doc = makeDoc()
        val rect = doc.root.firstByTag("rect")!!
        val stack = UndoStack()
        stack.push(dev.svgpro.core.edit.ChangeSet(listOf(changeAttr(rect, "x", "11")), "set 11"))
        stack.push(dev.svgpro.core.edit.ChangeSet(listOf(changeAttr(rect, "x", "12")), "set 12"))
        stack.push(dev.svgpro.core.edit.ChangeSet(listOf(changeAttr(rect, "x", "13")), "set 13"))
        assertEquals(3, stack.undoHistory.size)
        assertEquals("set 13", stack.undoHistory.last().label)
        assertEquals("set 11", stack.undoHistory.first().label)
    }

    @Test
    fun undo_to_size_jumps_to_history_entry() {
        val doc = makeDoc()
        val rect = doc.root.firstByTag("rect")!!
        val stack = UndoStack()
        stack.push(dev.svgpro.core.edit.ChangeSet(listOf(changeAttr(rect, "x", "11")), "set 11"))
        stack.push(dev.svgpro.core.edit.ChangeSet(listOf(changeAttr(rect, "x", "12")), "set 12"))
        stack.push(dev.svgpro.core.edit.ChangeSet(listOf(changeAttr(rect, "x", "13")), "set 13"))
        // index 1（2番目="set 12"）の状態まで巻き戻す → スタックサイズ 2 に
        stack.undoToSize(2)
        assertEquals(2, stack.undoHistory.size)
        assertEquals("12", rect.attr("x"))
        // redoスタックには巻き戻し分が移動
        assertTrue(stack.canRedo)
    }
}
