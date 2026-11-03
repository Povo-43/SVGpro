package dev.svgpro.core.svg

import dev.svgpro.core.model.StyleUtil

/**
 * SVGツリーの最適化・整形・バリデーション。
 * すべて Element ツリーに対して非破壊に近い変換を行い、変更の有無を返す。
 */
object SvgOptimizer {

    /** url(#id) 参照を抽出する */
    private fun urlRefs(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return Regex("""url\(['"]?#([^)'"\s]+)['"]?\)""")
            .findAll(value)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .toList()
    }

    /** 要素が url(...) 参照を含む属性を持つか */
    private fun hasUrlRef(el: Element): Boolean {
        var found = false
        el.attrs.values.forEach { v ->
            if (urlRefs(v).isNotEmpty()) found = true
        }
        return found
    }

    /**
     * 定義されていない id への url 参照があればリストで返す。
     * @param checkUnused true なら defs 内で未使用の id も合わせて返す
     */
    data class ValidateResult(
        val duplicateIds: List<String>,
        val danglingRefs: List<String>,
        val unusedDefIds: List<String>,
    ) {
        val isValid: Boolean get() = duplicateIds.isEmpty() && danglingRefs.isEmpty()
    }

    /** バリデーション：ID重複・ダングリング参照・未使用def id */
    fun validate(root: Element): ValidateResult {
        val idCounts = HashMap<String, Int>()
        root.walk { e ->
            e.id?.let { idCounts[it] = (idCounts[it] ?: 0) + 1 }
        }
        val duplicateIds = idCounts.filter { it.value > 1 }.keys.toList()

        val definedIds = idCounts.keys.toHashSet()
        val referencedIds = HashSet<String>()
        root.walk { e ->
            if (hasUrlRef(e)) {
                elUrlRefs(e).forEach { referencedIds.add(it) }
            }
        }
        val danglingRefs = referencedIds - definedIds

        // defs 内の id 定義一覧
        val defsIdSet = HashSet<String>()
        root.findByTag("defs").forEach { defs ->
            defs.childElements().filter { it.id != null }.forEach { defsIdSet.add(it.id!!) }
        }
        val unusedDefIds = defsIdSet - referencedIds

        return ValidateResult(duplicateIds, danglingRefs.toList(), unusedDefIds.toList())
    }

    private fun elUrlRefs(el: Element): List<String> =
        el.attrs.values.flatMap { urlRefs(it) }

    /** 重複 ID を一意化して付け直し（参照も合わせて更新）。変更があれば true */
    fun dedupeIds(root: Element): Boolean {
        val idCounts = HashMap<String, Int>()
        root.walk { e ->
            e.id?.let { idCounts[it] = (idCounts[it] ?: 0) + 1 }
        }
        val dupes = idCounts.filter { it.value > 1 }.keys.toHashSet()
        if (dupes.isEmpty()) return false

        val used = HashMap<String, String>() // oldId -> newId
        val taken = HashSet<String>()
        root.walk { e ->
            e.id?.let { id ->
                if (id in dupes) {
                    var candidate = id
                    var i = 2
                    while (candidate in taken || candidate in idCounts) {
                        candidate = "${id}_$i"
                        i++
                    }
                    used[id] = candidate
                    taken.add(candidate)
                    e.setAttr("id", candidate)
                } else {
                    taken.add(id)
                }
            }
        }
        // 参照先を更新
        root.walk { e ->
            e.attrs.keys.toList().forEach { k ->
                val v = e.attrs[k]!!
                val refs = urlRefs(v)
                if (refs.isNotEmpty()) {
                    val newV = Regex("""url\(['"]?#([^)'"\s]+)['"]?\)""")
                        .replace(v) { m ->
                            val old = m.groupValues[1]
                            val repl = used[old] ?: old
                            "url(#$repl)"
                        }
                    e.setAttr(k, newV)
                }
            }
        }
        return true
    }

    /** 未使用の defs 内定義（symbol/clipPath/mask/gradient/pattern/marker 等）を削除。変更があれば true */
    fun removeUnusedDefs(root: Element): Boolean {
        val defs = root.firstByTag("defs") ?: return false
        val referenced = HashSet<String>()
        root.walk { elUrlRefs(it).forEach { referenced.add(it) } }
        var removed = false
        defs.childElements().filterIsInstance<Element>().forEach { child ->
            val id = child.id
            if (id != null && id !in referenced && child.id != null) {
                defs.detachChild(child)
                removed = true
            }
        }
        return removed
    }

    /**
     * SVGを最適化（minify的圧縮）。
     * - 不要なコメント・空白の整理は SvgWriter 側で既に制御済みのため、
     *   ここでは構造的最適化（重複ID付け直し・未使用def削除）を行う。
     */
    fun optimize(root: Element): OptimizationResult {
        val deduped = dedupeIds(root)
        val removed = removeUnusedDefs(root)
        return OptimizationResult(changed = deduped || removed, validate(root))
    }

    data class OptimizationResult(
        val changed: Boolean,
        val validation: ValidateResult,
    )
}
