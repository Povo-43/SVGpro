package dev.svgpro.core.svg

/**
 * TokenSource → Element ツリー構築。
 * - 要素の並び・属性順・テキスト/コメントを順序通り保持（保存時に可逆）
 * - 未知要素・script/metadata/filter 等も消失させない
 */
object TreeBuilder {

    fun build(tokens: TokenSource): Element? {
        var root: Element? = null
        val stack = ArrayList<Element>()

        while (true) {
            val token = tokens.next()
            when (token) {
                is Token.StartTag -> {
                    val parent = stack.lastOrNull()
                    val el = Element(token.name)
                    el.attrs.putAll(token.attrs)
                    parent?.children?.add(el)
                    if (root == null && parent == null) {
                        // 最初のトップレベル要素をルートとする
                        root = el
                    }
                    if (!token.selfClosed) stack.add(el)
                }
                is Token.EndTag -> stack.removeLastOrNull()
                is Token.Text -> {
                    val parent = stack.lastOrNull() ?: continue
                    val last = parent.children.lastOrNull()
                    if (last is Text) last.text += token.content else parent.children.add(Text(token.content))
                }
                is Token.Comment -> stack.lastOrNull()?.children?.add(Comment(token.text)) ?: Unit
                Token.EndDocument -> break
            }
        }
        return root
    }
}
