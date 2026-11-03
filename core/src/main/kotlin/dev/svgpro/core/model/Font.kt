package dev.svgpro.core.model

/**
 * 読み込み済みフォントのメタデータ。
 * プラットフォーム非依存（AndroidのTypefaceはapp側で管理）。
 */
data class FontFace(
    /** フォントファミリ名（CSS font-familyとして使用） */
    val family: String,
    /** フォントファイルのパス（ファイルピッカーで選択されたURIパス等） */
    val filePath: String?,
    /** フォントスタイル: "normal" / "italic" / "oblique" */
    val style: String = "normal",
    /** フォントウェイト: "100"〜"900" または "normal"/"bold" */
    val weight: String = "normal",
)
