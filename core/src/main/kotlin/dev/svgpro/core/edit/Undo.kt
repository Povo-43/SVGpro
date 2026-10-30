package dev.svgpro.core.edit

import dev.svgpro.core.path.PathData
import dev.svgpro.core.svg.Element
import dev.svgpro.core.svg.Text

/** ドキュメントへの1変更（逆操作は revert()） */
interface Change {
    val label: String?
    fun apply()
    fun revert()
}

/** 属性変更 */
class AttrChange(
    val el: Element,
    val key: String,
    val oldV: String?,
    val newV: String?,
    override val label: String? = null,
) : Change {
    override fun apply() {
        el.setAttr(key, newV)
    }

    override fun revert() {
        el.setAttr(key, oldV)
    }
}

/** テキスト内容変更 */
class TextContentChange(
    val el: Element,
    val oldV: String,
    val newV: String,
    override val label: String? = null,
) : Change {
    override fun apply() = el.setTextContent(newV)
    override fun revert() = el.setTextContent(oldV)
}

/** 親からの切り離し（undoは戻し） */
class Detach(val el: Element, val parent: Element, val index: Int, override val label: String? = null) : Change {
    override fun apply() {
        val i = parent.children.indexOf(el)
        if (i >= 0) parent.children.removeAt(i)
    }

    override fun revert() {
        if (!parent.children.contains(el)) {
            parent.children.add(index.coerceIn(0, parent.children.size), el)
        }
    }
}

/** 子の挿入/移動（undoで元の位置へ戻す）。doc は旧親探索に使う（新規ノードならnull可）。 */
class Attach(
    val el: Element,
    val parent: Element,
    val index: Int,
    val docRoot: Element? = null,
    override val label: String? = null,
) : Change {
    private var prevParent: Element? = null
    private var prevIndex: Int = -1

    private fun findParent(start: Element?): Element? {
        val rootEl = docRoot ?: return null
        var found: Element? = null
        rootEl.walk { e -> if (found == null && e.children.contains(el)) found = e }
        return found
    }

    override fun apply() {
        prevParent = findParent(docRoot)
        prevIndex = prevParent?.children?.indexOf(el) ?: -1
        prevParent?.children?.remove(el)
        parent.children.add(index.coerceIn(0, parent.children.size), el)
    }

    override fun revert() {
        val cur = parent.children.indexOf(el)
        if (cur >= 0) parent.children.removeAt(cur)
        val p = prevParent
        if (p != null && prevIndex >= 0) {
            p.children.add(prevIndex.coerceIn(0, p.children.size), el)
        }
    }
}

/** 変更のまとまり（undoの1単位）。selectionHintはundo/redo後の選択復元候補 */
class ChangeSet(
    val changes: List<Change>,
    val label: String?,
    val selectionHint: List<Any> = emptyList(),
) {
    fun apply() { for (c in changes) c.apply() }
    fun revert() { for (c in changes.reversed()) c.revert() }
}

/**
 * Undo/Redoスタック。上で Coalesce 相当は呼びだし側（ドラッグ等）が1回だけ push する設計。
 */
class UndoStack(val limit: Int = 400) {
    private val undo = ArrayList<ChangeSet>()
    private val redo = ArrayList<ChangeSet>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun push(cs: ChangeSet) {
        if (cs.changes.isEmpty()) return
        undo.add(cs)
        if (undo.size > limit) undo.removeAt(0)
        redo.clear()
    }

    fun undoLabel(): String? = undo.lastOrNull()?.label
    fun redoLabel(): String? = redo.lastOrNull()?.label

    fun undo(): ChangeSet? {
        val cs = undo.removeLastOrNull() ?: return null
        cs.revert()
        redo.add(cs)
        return cs
    }

    fun redo(): ChangeSet? {
        val cs = redo.removeLastOrNull() ?: return null
        cs.apply()
        undo.add(cs)
        return cs
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }
}

/**
 * 編集バッチレコーダ。
 * begin() → 複数要素編集 → endAndCommit(stack, label) で元の値とのDiffからChangeSet生成。
 * ドラッグ1回分を単一履歴に統合する用途を想定。
 */
class EditBatch {
    private val before = LinkedHashMap<Element, Map<String, String>>()
    private val beforeText = LinkedHashMap<Element, String>()

    fun touch(el: Element) {
        if (!before.containsKey(el)) {
            before[el] = LinkedHashMap(el.attrs)
            beforeText[el] = el.textContent()
        }
    }

    fun endAndCommit(label: String?): ChangeSet? {
        val changes = ArrayList<Change>()
        for ((el, beforeAttrs) in before) {
            val afterText = el.textContent()
            val bt = beforeText[el]
            if (bt != null && bt != afterText) {
                changes.add(TextContentChange(el, bt, afterText, label))
            }
            for ((k, oldV) in beforeAttrs) {
                val newV = el.attr(k)
                if (oldV != newV) {
                    changes.add(AttrChange(el, k, oldV, newV, label))
                }
            }
            for (k in el.attrs.keys) {
                if (!beforeAttrs.containsKey(k)) {
                    changes.add(AttrChange(el, k, null, el.attr(k), label))
                }
            }
        }
        if (changes.isEmpty()) return null
        return ChangeSet(changes, label)
    }
}

/** 便利ヘルパ: 属性値を変えつつ記録する */
fun changeAttr(el: Element, key: String, value: String?): AttrChange {
    val ch = AttrChange(el, key, el.attr(key), value)
    ch.apply()
    return ch
}
