package dev.svgpro.core.model

/**
 * エディタガイド（整列補助線）。
 * ドキュメント属性 `data-svgpro-guides` に可逆保存し、SVGの構造には影響しない。
 */
data class Guide(
    val horizontal: Boolean,
    val pos: Double,
    val locked: Boolean = false,
    val visible: Boolean = true,
) {
    fun encode(): String {
        val sb = StringBuilder()
        sb.append(if (horizontal) 'h' else 'v')
        sb.append(dev.svgpro.core.util.Numbers.format(pos))
        if (locked) sb.append('L')
        if (!visible) sb.append('H')
        return sb.toString()
    }

    companion object {
        const val ATTR = "data-svgpro-guides"

        fun decode(s: String?): Guide? {
            if (s.isNullOrEmpty()) return null
            val t = s.trim()
            if (t.length < 2) return null
            val horizontal = when (t[0]) {
                'h', 'H' -> true
                'v', 'V' -> false
                else -> return null
            }
            var locked = false
            var visible = true
            var numPart = t.substring(1)
            if (numPart.endsWith("L")) {
                locked = true
                numPart = numPart.dropLast(1)
            }
            if (numPart.endsWith("H")) {
                visible = false
                numPart = numPart.dropLast(1)
            }
            val pos = dev.svgpro.core.util.Numbers.parse(numPart) ?: return null
            return Guide(horizontal, pos, locked, visible)
        }
    }

    object Codec {
        /** ガイド列 → 属性値 */
        fun encode(guides: List<Guide>): String? =
            if (guides.isEmpty()) null else guides.joinToString("|") { it.encode() }

        /** 属性値 → ガイド列（不正エントリは無視） */
        fun decode(value: String?): List<Guide> {
            if (value.isNullOrBlank()) return emptyList()
            return value.split('|').mapNotNull { Guide.decode(it) }
        }
    }
}
