package dev.svgpro.core.svg

/**
 * XmlPullParserに依存しないパース用の統一トークン。
 * プラットフォーム実装がこのシーケンスを供給する（Android: XmlPullParser、JVMテスト: javax SAX）。
 */
sealed class Token {
    data class StartTag(
        val name: String,
        val attrs: LinkedHashMap<String, String>,
        val selfClosed: Boolean,
    ) : Token()

    data object EndTag : Token()

    class Text(var content: String) : Token()

    class Comment(var text: String) : Token()

    data object EndDocument : Token()
}

/** トークン供給源 */
interface TokenSource {
    fun next(): Token
}
