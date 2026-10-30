package dev.svgpro.core.svg

import dev.svgpro.core.model.SvgDocument

/**
 * SVG テキスト → Elementツリー。プラットフォームは TokenSource を与える。
 */
object SvgLoader {
    fun load(tokensSource: (String) -> TokenSource, xml: String): SvgDocument? {
        val tokens = tokensSource(xml)
        val root = TreeBuilder.build(tokens) ?: return null
        return SvgDocument(root)
    }
}
