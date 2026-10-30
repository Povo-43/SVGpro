package dev.svgpro.canvas

/**
 * キャンバスの描画色（テーマ連動）。
 * 色はすべて #RRGGBB / #AARRGGBB。UIテーマ(ui.Palette)と同じトーンで定義する。
 */
object CanvasTheme {
    data class Colors(
        val canvasBg: Int,        // ページ外の余白
        val pageBorder: Int,      // ページ枠
        val grid: Int,            // グリッド線
        val selection: Int,       // 選択枠
        val selectionFill: Int,   // 選択枠の塗り
        val handle: Int,          // 変形ハンドル
        val handleStroke: Int,    // ハンドル外周
        val node: Int,            // 通常アンカー
        val nodeSelected: Int,    // 選択アンカー
        val nodeSmooth: Int,      // スムーズアンカー
        val handleLine: Int,      // ハンドル線
        val draft: Int,           // 作図中プレビュー
        val marquee: Int,         // 選択マーキー
        val smartGuide: Int,      // スマートガイド
        val guide: Int,           // ユーザーガイド線
        val guideLocked: Int,     // ロックされたガイド線
        val gradientLine: Int,    // グラデーションハンドル線
        val rulerBg: Int,         // ルーラー背景
        val rulerTick: Int,       // ルーラー目盛り
        val rulerText: Int,       // ルーラー数値
        val hover: Int,           // スタイラスホバー
        val checkerA: Int,        // 透過チェッカー (明)
        val checkerB: Int,        // 透過チェッカー (暗)
    )

    /** ダークテーマ（Illustrator風） */
    val Dark = Colors(
        canvasBg = 0xFF1B1B1B.toInt(),
        pageBorder = 0xFF3F3F3F.toInt(),
        grid = 0x2E9E9E9E,
        selection = 0xFF1473E6.toInt(),
        selectionFill = 0x1A1473E6,
        handle = 0xFFF2F2F2.toInt(),
        handleStroke = 0xFF1473E6.toInt(),
        node = 0xFFE8E8E8.toInt(),
        nodeSelected = 0xFF1473E6.toInt(),
        nodeSmooth = 0xFF56A36C.toInt(),
        handleLine = 0x801473E6.toInt(),
        draft = 0xFFE8A33D.toInt(),
        marquee = 0xFF1473E6.toInt(),
        smartGuide = 0xFFC566F0.toInt(),
        guide = 0xFF00E5FF.toInt(),
        guideLocked = 0x6600E5FF.toInt(),
        gradientLine = 0xFF1473E6.toInt(),
        rulerBg = 0xEE252525.toInt(),
        rulerTick = 0xFF8A8A8A.toInt(),
        rulerText = 0xFFC8C8C8.toInt(),
        hover = 0x99F2F2F2.toInt(),
        checkerA = 0xFFE0E0E0.toInt(),
        checkerB = 0xFFBDBDBD.toInt(),
    )

    /** ライトテーマ */
    val Light = Colors(
        canvasBg = 0xFFE2E2E2.toInt(),
        pageBorder = 0xFFABABAB.toInt(),
        grid = 0x38808080,
        selection = 0xFF0D66D0.toInt(),
        selectionFill = 0x1A0D66D0,
        handle = 0xFFFFFFFF.toInt(),
        handleStroke = 0xFF0D66D0.toInt(),
        node = 0xFF2A2A2A.toInt(),
        nodeSelected = 0xFF0D66D0.toInt(),
        nodeSmooth = 0xFF2E7D4F.toInt(),
        handleLine = 0x800D66D0.toInt(),
        draft = 0xFFB26A10.toInt(),
        marquee = 0xFF0D66D0.toInt(),
        smartGuide = 0xFF9C27B0.toInt(),
        guide = 0xFF0097A7.toInt(),
        guideLocked = 0x660097A7.toInt(),
        gradientLine = 0xFF0D66D0.toInt(),
        rulerBg = 0xEEFAFAFA.toInt(),
        rulerTick = 0xFF787878.toInt(),
        rulerText = 0xFF444444.toInt(),
        hover = 0x992A2A2A.toInt(),
        checkerA = 0xFFFFFFFF.toInt(),
        checkerB = 0xFFE6E6E6.toInt(),
    )
}
