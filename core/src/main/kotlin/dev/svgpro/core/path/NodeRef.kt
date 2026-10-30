package dev.svgpro.core.path

/**
 * ノード参照（サブパスindex, ノードindex）。
 * エディタ（appモジュール）とコアのノード編集プリミティブで共有する。
 */
data class NodeRef(val sub: Int, val node: Int) : Comparable<NodeRef> {
    override fun compareTo(other: NodeRef): Int = if (sub != other.sub) sub - other.sub else node - other.node
}
