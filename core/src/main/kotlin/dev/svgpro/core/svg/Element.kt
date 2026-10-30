package dev.svgpro.core.svg

/**
 * XML/SVG DOMを汎用ライクに表すツリー。
 * 編集対象外の要素（metadata, style, filter, animation等）も「無傷のままで保持」するため、
 * すべてをこのツリーのまま扱う。
 */
class Element(val tag: String) : Node() {

    /** 属性（出現順を保持） */
    val attrs: LinkedHashMap<String, String> = LinkedHashMap()

    val children: MutableList<Node> = ArrayList()

    // ―――― 属性操作 ――――

    fun attr(name: String): String? = attrs[name]

    fun attr(name: String, default: String): String = attrs[name] ?: default

    fun setAttr(name: String, value: String?) {
        if (value == null) attrs.remove(name) else attrs[name] = value
    }

    fun removeAttr(name: String) {
        attrs.remove(name)
    }

    fun hasAttr(name: String): Boolean = attrs.containsKey(name)

    val id: String? get() = attrs["id"]

    // ―――― 子ノード操作 ――――

    fun childElements(): List<Element> = children.filterIsInstance<Element>()

    fun textContent(): String = children.filterIsInstance<Text>().joinToString("") { it.text }

    fun setTextContent(v: String) {
        val existing = children.filterIsInstance<Text>()
        if (existing.isEmpty()) {
            if (v.isNotEmpty()) children.add(Text(v))
        } else {
            existing[0].text = v
            for (i in 1 until existing.size) children.remove(existing[i])
        }
    }

    /** 子を挿入（index = nullで末尾） */
    fun append(child: Node, index: Int? = null) {
        if (index == null || index > children.size) children.add(child) else children.add(index, child)
    }

    fun indexOf(child: Node): Int = children.indexOf(child)

    fun detachChild(child: Node): Boolean {
        val r = children.remove(child)
        return r
    }

    // ―――― 探索 ――――

    fun findByTag(tag: String): List<Element> {
        val out = ArrayList<Element>()
        walk { if (it.tag == tag) out.add(it) }
        return out
    }

    fun findById(idValue: String): Element? {
        var found: Element? = null
        walk { if (found == null && it.attrs["id"] == idValue) found = it }
        return found
    }

    fun firstByTag(tag: String): Element? = findByTag(tag).firstOrNull()

    fun parentOf(child: Node): Element? {
        var parent: Element? = null
        walk { e -> if (parent == null && e.children.contains(child)) parent = e }
        return parent
    }

    fun walk(fn: (Element) -> Unit) {
        fn(this)
        for (c in children) walkNode(c, fn)
    }

    private fun walkNode(n: Node, fn: (Element) -> Unit) {
        if (n is Element) n.walk(fn)
    }

    /** ツリー全体のディープコピー */
    fun deepCopy(): Element = copyElement(this)

    override fun toString(): String = "<$tag id=${attrs["id"]}>"
}

/** 位置保持用の要素参照（undo等で使用） */
class Reference(val parent: Element, val index: Int, val tag: String, val id: String?) {
    fun find(): Element? = parent.children.getOrNull(index) as? Element
        ?: parent.children.filterIsInstance<Element>().find { it.tag == tag && it.attrs["id"] == id }
}

fun copyElement(e: Element): Element {
    val n = Element(e.tag)
    n.attrs.putAll(e.attrs)
    for (c in e.children) {
        when (c) {
            is Element -> n.children.add(copyElement(c))
            is Text -> n.children.add(Text(c.text))
            else -> n.children.add(c)
        }
    }
    return n
}

sealed class Node

class Text(var text: String) : Node()

/** 無視されるノード（コメント等）: パーサ実装がreturnする場合の出力器のマーク */
class Comment(var text: String) : Node()
