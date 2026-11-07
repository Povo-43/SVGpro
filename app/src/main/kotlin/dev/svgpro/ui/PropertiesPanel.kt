package dev.svgpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.svgpro.core.model.Color as SvgColor
import dev.svgpro.core.model.Paint
import dev.svgpro.core.model.StyleUtil
import dev.svgpro.core.svg.Element
import dev.svgpro.editor.Editor
import dev.svgpro.editor.Editor.AlignRelative
import dev.svgpro.editor.GeometryOpKind
import dev.svgpro.core.path.NodeKind
import dev.svgpro.editor.ToolType
import dev.svgpro.editor.addGradientStop
import dev.svgpro.editor.alignSelectionTo
import dev.svgpro.editor.addDocumentColorsToSwatches
import dev.svgpro.editor.applyGradientToSelection
import dev.svgpro.editor.breakSymbolInstance
import dev.svgpro.editor.compoundSelection
import dev.svgpro.editor.createClipFromSelection
import dev.svgpro.editor.createMaskFromSelection
import dev.svgpro.editor.createSymbolFromSelection
import dev.svgpro.editor.gradientHandlePoints
import dev.svgpro.editor.gradientModelOfSelection
import dev.svgpro.editor.invertNodeSelection
import dev.svgpro.editor.invertSelection
import dev.svgpro.editor.moveGradientStop
import dev.svgpro.editor.releaseClipSelection
import dev.svgpro.editor.releaseCompound
import dev.svgpro.editor.releaseMaskSelection
import dev.svgpro.editor.removeGradientStop
import dev.svgpro.editor.selectAllNodes
import dev.svgpro.editor.selectChildren
import dev.svgpro.editor.selectSameFill
import dev.svgpro.editor.selectSameKind
import dev.svgpro.editor.selectSameStroke
import dev.svgpro.editor.setFillRuleSelection
import dev.svgpro.editor.setGradientStopColor
import dev.svgpro.editor.setSelectionPosition
import dev.svgpro.editor.setSelectionSize
import dev.svgpro.editor.snapSelectionToGrid
import dev.svgpro.editor.strokeToPathSelection
import dev.svgpro.editor.swapFillStrokeSelection
import dev.svgpro.editor.makeTextPath
import dev.svgpro.editor.distributeSelectedNodes
import dev.svgpro.editor.convertSelectedSegments
import dev.svgpro.editor.deleteSelectedSegments
import dev.svgpro.editor.joinSelectedEnds
import dev.svgpro.editor.splitAtSelectedNodes
import dev.svgpro.editor.reduceNodesInPath
import dev.svgpro.editor.distributeSelectionEx
import dev.svgpro.editor.transformAgain
import dev.svgpro.editor.updateGradientHandles

/**
 * プロパティパネル（Figma / Illustrator 風のタブ式プロパティ）。
 *
 * 8タブ: 塗り / 線 / 文字 / 効果 / 変形 / 整列 / パス / ノード。
 * 各タブは選択状態に応じて操作を有効化し、対応するダイアログへ接続する。
 */

/** プロパティタブ種別。 */
enum class PropertyTab(val label: String) {
    Fill("塗り"),
    Stroke("線"),
    Text("文字"),
    Effects("効果"),
    Transform("変形"),
    Align("整列"),
    Path("パス"),
    Node("ノード"),
}

@Composable
fun PropertiesPanel(
    editor: Editor,
    refresh: Int,
    onSaveSwatches: () -> Unit,
    onLoadSwatches: () -> Unit,
    customFontFamilies: List<String> = emptyList(),
    onShowFontManager: (() -> Unit)? = null,
    onReplaceColor: (() -> Unit)? = null,
) {
    val sel = editor.selection
    val hasSel = sel.isNotEmpty()
    val isPath = sel.any {
        dev.svgpro.core.path.PathData.parse(it.attr("d")) != null || dev.svgpro.core.model.Shapes.isPrimitive(it)
    }

    // ―――― ダイアログ状態（各タブから起動） ――――
    val colorDialogTarget = remember { mutableStateOf<String?>(null) } // "fill" | "stroke"
    val colorSwatchTarget = remember { mutableStateOf("fill") }
    var offsetDialog by remember { mutableStateOf(false) }
    var transformDialog by remember { mutableStateOf(false) }
    var handleDialog by remember { mutableStateOf(false) }
    val stopColorTarget = remember { mutableStateOf<Int?>(null) }
    val alignRelative = remember { mutableStateOf(AlignRelative.SELECTION) }
    var panelTabOrdinal by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        // スクロール可能なタブバー（狭いパネルでも全タブへ届く）
        ScrollableTabRow(
            selectedTabIndex = panelTabOrdinal,
            edgePadding = 4.dp,
            divider = {},
        ) {
            PropertyTab.values().forEach { tab ->
                Tab(
                    text = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                    selected = panelTabOrdinal == tab.ordinal,
                    onClick = { panelTabOrdinal = tab.ordinal },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(Modifier.weight(1f)) {
            when (PropertyTab.values()[panelTabOrdinal]) {
                PropertyTab.Fill -> FillTabContent(
                    editor, hasSel, colorDialogTarget, colorSwatchTarget, onSaveSwatches, onLoadSwatches, stopColorTarget, onReplaceColor,
                )
                PropertyTab.Stroke -> StrokeTabContent(editor, hasSel, colorDialogTarget)
                PropertyTab.Text -> TextTabContent(editor, hasSel, customFontFamilies, onShowFontManager)
                PropertyTab.Effects -> EffectsTabContent(editor, hasSel)
                PropertyTab.Transform -> TransformTabContent(editor, hasSel, refresh, onOpenTransformDialog = { transformDialog = true })
                PropertyTab.Align -> AlignTabContent(editor, hasSel, alignRelative)
                PropertyTab.Path -> PathTabContent(editor, hasSel, isPath, onOffsetDialog = { offsetDialog = true })
                PropertyTab.Node -> NodeTabContent(editor, onOpenHandleDialog = { handleDialog = true })
            }
        }
    }

    // ―――― ダイアログ ――――
    colorDialogTarget.value?.let { target ->
        val isFill = target == "fill"
        ColorPickerDialog(
            title = if (isFill) "塗りの色" else "線の色",
            initial = when {
                isFill -> (if (hasSel) editor.selectionFill() else editor.curFill).let { (it as? Paint.Solid)?.argb ?: 0xFF1473E6.toInt() }
                else -> (if (hasSel) editor.selectionStroke() else editor.curStroke).let { (it as? Paint.Solid)?.argb ?: 0xFF222222.toInt() }
            },
            onDismiss = { colorDialogTarget.value = null },
        ) { argb ->
            val paint = Paint.Solid(argb)
            if (isFill) {
                if (hasSel) editor.applyFillToSelection(paint) else { editor.curFill = paint; editor.onChanged() }
            } else {
                if (hasSel) editor.applyStrokeToSelection(paint) else { editor.curStroke = paint; editor.onChanged() }
            }
            colorDialogTarget.value = null
        }
    }

    if (offsetDialog) {
        NumberInputDialog(
            title = "オフセット量（+で拡張 / -で縮小）",
            initial = "4",
            onDismiss = { offsetDialog = false },
        ) { v ->
            dev.svgpro.core.util.Numbers.parse(v)?.let { editor.offsetPath(it) }
            offsetDialog = false
        }
    }

    if (transformDialog) {
        TransformDialog(editor = editor, onDismiss = { transformDialog = false })
    }

    if (handleDialog) {
        HandleNumericDialog(editor = editor, onDismiss = { handleDialog = false })
    }

    stopColorTarget.value?.let { idx ->
        val gm = editor.gradientModelOfSelection()
        val initial = gm?.stops?.getOrNull(idx)?.argb ?: 0xFF1473E6.toInt()
        ColorPickerDialog(
            title = "ストップ${idx + 1}の色",
            initial = initial,
            onDismiss = { stopColorTarget.value = null },
        ) { argb ->
            editor.setGradientStopColor(idx, argb)
            stopColorTarget.value = null
        }
    }
}

/** タブ既定値（初回表示は塗り） */

// ========================================================================
// 共通パーツ
// ========================================================================

@Composable
internal fun SectionTitle(text: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** 塗り/線のコンパクトボタン */
@Composable
internal fun CompactButton(label: String, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(UiMetrics.CornerSmall))
            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** トグル可能な選択チップ */
@Composable
private fun SelectChip(label: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(UiMetrics.CornerSmall))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 横スクロールで並べるチップ行 */
@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 数値入力フィールド（ラベル + 入力 + 適用） */
@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit,
    width: Dp = 64.dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(38.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.width(width),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        TextButton(onClick = onApply, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("適用", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 塗り/線のスウォッチ表示 */
@Composable
internal fun Swatch(paint: Paint) {
    val color = when (paint) {
        is Paint.None -> MaterialTheme.colorScheme.surfaceVariant
        is Paint.Solid -> Color(paint.argb)
        is Paint.Server -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
    )
}

/** スウォッチ1つ（クリックで適用・長押しで未対応） */
@Composable
private fun ColorTile(argb: Int, size: Int = 22, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(argb))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
    )
}

/** 選択中/現在の塗り・線を取得するヘルパ */
private fun currentPaint(editor: Editor, hasSel: Boolean, fill: Boolean): Paint =
    if (fill) (if (hasSel) editor.selectionFill() else editor.curFill) ?: Paint.None
    else (if (hasSel) editor.selectionStroke() else editor.curStroke) ?: Paint.None

// ========================================================================
// 塗りタブ
// ========================================================================

@Composable
private fun FillTabContent(
    editor: Editor,
    hasSel: Boolean,
    colorDialogTarget: MutableState<String?>,
    colorSwatchTarget: MutableState<String>,
    onSaveSwatches: () -> Unit,
    onLoadSwatches: () -> Unit,
    stopColorTarget: MutableState<Int?>,
    onReplaceColor: (() -> Unit)? = null,
) {
    fun applyPaint(paint: Paint) {
        if (hasSel) {
            if (colorSwatchTarget.value == "fill") editor.applyFillToSelection(paint)
            else editor.applyStrokeToSelection(paint)
        } else {
            if (colorSwatchTarget.value == "fill") { editor.curFill = paint; editor.onChanged() }
            else { editor.curStroke = paint; editor.onChanged() }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("塗り")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val fill = currentPaint(editor, hasSel, fill = true)
            Swatch(fill)
            Spacer(Modifier.width(4.dp))
            Column {
                CompactButton("なし") { applyPaint(Paint.None) }
                CompactButton("色…", filled = true) { colorDialogTarget.value = "fill" }
            }
            Spacer(Modifier.weight(1f))
            Column {
                CompactButton("線形") {
                    val id = ensureGradient(editor, radial = false)
                    if (hasSel) editor.applyFillToSelection(Paint.Server(id)) else { editor.curFill = Paint.Server(id); editor.onChanged() }
                }
                CompactButton("円形") {
                    val id = ensureGradient(editor, radial = true)
                    if (hasSel) editor.applyFillToSelection(Paint.Server(id)) else { editor.curFill = Paint.Server(id); editor.onChanged() }
                }
            }
        }

        // 不透明度（選択中のみ）
        if (hasSel) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("不透明度", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp))
                val opacity = editor.selectionOpacity()
                Slider(
                    value = opacity.toFloat(),
                    onValueChange = { editor.applyOpacityToSelection(it.toDouble()) },
                    modifier = Modifier.weight(1f),
                )
                Text("${(opacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(34.dp))
            }
        }

        // スウォッチ（対象切り替え）
        SectionTitle("スウォッチ")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = colorSwatchTarget.value == "fill",
                onClick = { colorSwatchTarget.value = "fill" },
                label = { Text("塗り", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = colorSwatchTarget.value == "stroke",
                onClick = { colorSwatchTarget.value = "stroke" },
                label = { Text("線", style = MaterialTheme.typography.labelSmall) },
            )
            Spacer(Modifier.weight(1f))
            CompactButton("＋登録") {
                val c = if (hasSel) editor.selectionFill() else editor.curFill
                (c as? Paint.Solid)?.let { editor.addColorSwatch(it.argb) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            editor.colorSwatches.forEachIndexed { _, argb ->
                ColorTile(argb) { applyPaint(Paint.Solid(argb)) }
            }
        }
        if (editor.recentColors.isNotEmpty()) {
            Text("最近使用した色", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                editor.recentColors.forEach { argb ->
                    ColorTile(argb) { applyPaint(Paint.Solid(argb)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactButton("パレット保存") { onSaveSwatches() }
            CompactButton("パレット読込") { onLoadSwatches() }
            CompactButton("ドキュメントから収集") { editor.addDocumentColorsToSwatches() }
            if (onReplaceColor != null) {
                CompactButton("色一括置換…") { onReplaceColor() }
            }
        }

        // グラデーションエディタ（選択の塗りがグラデーション時のみ）
        val gm = if (hasSel) editor.gradientModelOfSelection() else null
        if (gm != null) {
            SectionTitle("グラデーション")
            Text(
                "ストップ数: ${gm.stops.size}（キャンバスのハンドルで角度/範囲を編集）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            gm.stops.forEachIndexed { i, stop ->
                var offText by remember(stop.offset, i) { mutableStateOf(dev.svgpro.core.util.Numbers.format(stop.offset)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(stop.argb))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { stopColorTarget.value = i },
                    )
                    Text("${i + 1}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(14.dp))
                    OutlinedTextField(
                        value = offText,
                        onValueChange = { offText = it },
                        modifier = Modifier.width(58.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    TextButton(
                        onClick = {
                            dev.svgpro.core.util.Numbers.parse(offText)?.let { editor.moveGradientStop(i, it) }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { Text("位置", style = MaterialTheme.typography.labelSmall) }
                    Spacer(Modifier.weight(1f))
                    if (gm.stops.size > 2) {
                        TextButton(
                            onClick = { editor.removeGradientStop(i) },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text("削除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { editor.addGradientStop(0.5) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("ストップ追加", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        val handles = editor.gradientHandlePoints() ?: return@TextButton
                        val s = handles.second
                        val e = handles.third
                        editor.commit("gradient angle") { batch ->
                            batch.touch(gm.def)
                            val mid = dev.svgpro.core.geom.Vec((s.x + e.x) / 2, (s.y + e.y) / 2)
                            val half = dev.svgpro.core.geom.Vec((e.x - s.x) / 2, (e.y - s.y) / 2)
                            val rot = dev.svgpro.core.geom.Matrix().rotateDeg(90.0)
                            val q = rot.apply(half)
                            editor.updateGradientHandles(mid - q, mid + q)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) { Text("角度90°", style = MaterialTheme.typography.labelSmall) }
            }
            if (hasSel) {
                ChipRow {
                    SelectChip("選択へ再適用", active = false) {
                        if (gm.id.isNotBlank()) editor.applyGradientToSelection(gm.id)
                    }
                }
            }
        }
    }
}

// ========================================================================
// 線タブ
// ========================================================================

@Composable
private fun StrokeTabContent(
    editor: Editor,
    hasSel: Boolean,
    colorDialogTarget: MutableState<String?>,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("線")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val stroke = currentPaint(editor, hasSel, fill = false)
            Swatch(stroke)
            Spacer(Modifier.width(4.dp))
            Column {
                CompactButton("なし") {
                    if (hasSel) editor.applyStrokeToSelection(Paint.None)
                    else { editor.curStroke = Paint.None; editor.onChanged() }
                }
                CompactButton("色…", filled = true) { colorDialogTarget.value = "stroke" }
            }
            Spacer(Modifier.weight(1f))
            if (hasSel) {
                CompactButton("塗りと入替") { editor.swapFillStrokeSelection() }
            }
        }
        // 幅
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("幅", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(28.dp))
            val strokeWidth = if (hasSel) editor.selectionStrokeWidth() else editor.curStrokeWidth
            Slider(
                value = strokeWidth.toFloat().coerceIn(0.1f, 40f),
                onValueChange = {
                    val v = it.toDouble()
                    if (hasSel) editor.applyStrokeWidthToSelection(v) else { editor.curStrokeWidth = v; editor.onChanged() }
                },
                modifier = Modifier.weight(1f),
            )
            Text(String.format("%.1f", strokeWidth), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(34.dp))
        }
        // 線端 / 線継ぎ / 線位置
        ChipRow {
            listOf("butt" to "バット", "round" to "ラウンド", "square" to "スクエア").forEach { (v, label) ->
                SelectChip(label, active = false, enabled = hasSel) { editor.applyStrokeCapToSelection(v) }
            }
        }
        ChipRow {
            listOf("miter" to "マイター", "round" to "ラウンド", "bevel" to "ベベル").forEach { (v, label) ->
                SelectChip(label, active = false, enabled = hasSel) { editor.applyStrokeJoinToSelection(v) }
            }
        }
        ChipRow {
            listOf("center" to "中央", "inside" to "内側", "outside" to "外側").forEach { (v, label) ->
                val selected = hasSel && editor.selectionStrokeAlignment() == v
                SelectChip(label, active = selected, enabled = hasSel) { editor.applyStrokeAlignmentToSelection(v) }
            }
        }
        // ダッシュ
        ChipRow {
            listOf(
                null to "実線",
                listOf(6.0, 4.0) to "破線",
                listOf(2.0, 3.0) to "点線",
                listOf(10.0, 4.0, 2.0, 4.0) to "鎖線",
            ).forEach { (v, label) ->
                SelectChip(label, active = false, enabled = hasSel) { editor.applyDashToSelection(v) }
            }
        }
        // 不透明度
        if (hasSel) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("不透明度", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp))
                val opacity = editor.selectionOpacity()
                Slider(
                    value = opacity.toFloat(),
                    onValueChange = { editor.applyOpacityToSelection(it.toDouble()) },
                    modifier = Modifier.weight(1f),
                )
                Text("${(opacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(34.dp))
            }
        }
        if (!hasSel) {
            Text(
                "オブジェクトを選択すると選択中の線を編集できます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ========================================================================
// 文字タブ
// ========================================================================

@Composable
private fun TextTabContent(
    editor: Editor,
    hasSel: Boolean,
    customFontFamilies: List<String>,
    onShowFontManager: (() -> Unit)?,
) {
    val textEl = editor.selection.firstOrNull { it.tag == "text" }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("文字")
        if (textEl == null) {
            Text(
                "テキスト要素を選択してください（T ツールで作成 / ダブルタップで編集）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val el = textEl

        // 内容（ローカル状態 + 更新ボタンでUndo履歴を1件にまとめる）
        var content by remember(el) { mutableStateOf(el.textContent()) }
        LaunchedEffect(el) { content = el.textContent() }
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text("テキスト内容") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                editor.commit("text edit") { batch -> batch.touch(el); el.setTextContent(content) }
            }) { Text("更新", style = MaterialTheme.typography.labelSmall) }
        }

        // フォント
        val curFamily = StyleUtil.getProperty(el, "font-family") ?: "sans-serif"
        var familyText by remember(curFamily) { mutableStateOf(curFamily) }
        LaunchedEffect(curFamily) { familyText = curFamily }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("フォント", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
            OutlinedTextField(
                value = familyText,
                onValueChange = { familyText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                editor.commit("font-family") { batch ->
                    batch.touch(el)
                    StyleUtil.setProperty(el, "font-family", familyText.takeIf { it.isNotBlank() } ?: "sans-serif")
                }
            }) { Text("適用", style = MaterialTheme.typography.labelSmall) }
        }
        ChipRow {
            (listOf("sans-serif", "serif", "monospace") + customFontFamilies).forEach { family ->
                SelectChip(family, active = familyText == family) { familyText = family }
            }
            if (customFontFamilies.isNotEmpty() && onShowFontManager != null) {
                SelectChip("フォント管理…", active = false) { onShowFontManager() }
            }
        }

        // サイズ・ウェイト・斜体
        var sizeText by remember(el) {
            mutableStateOf((dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(el, "font-size")) ?: 16.0).toString())
        }
        NumberField("サイズ", sizeText, { sizeText = it }, {
            dev.svgpro.core.util.Numbers.parse(sizeText)?.let { v ->
                editor.commit("font-size") { batch ->
                    batch.touch(el)
                    StyleUtil.setProperty(el, "font-size", dev.svgpro.core.util.Numbers.format(v))
                }
            }
        })
        ChipRow {
            val curWeight = StyleUtil.getProperty(el, "font-weight") ?: "normal"
            listOf("normal" to "通常", "bold" to "太字", "600" to "600", "900" to "900").forEach { (v, label) ->
                SelectChip(label, active = curWeight == v) { editor.applyTextFontWeightSelection(v) }
            }
            SelectChip("斜体", active = (StyleUtil.getProperty(el, "font-style") ?: "normal") == "italic") {
                editor.commit("italic") { batch ->
                    batch.touch(el)
                    val cur = StyleUtil.getProperty(el, "font-style") ?: "normal"
                    StyleUtil.setProperty(el, "font-style", if (cur == "italic") "normal" else "italic")
                }
            }
        }
        // 水平揃え・書字方向
        ChipRow {
            val anchor = editor.selectionTextAnchor()
            listOf("start" to "左", "middle" to "中央", "end" to "右").forEach { (v, label) ->
                SelectChip(label, active = anchor == v) { editor.applyTextAnchorSelection(v) }
            }
        }
        ChipRow {
            val wm = editor.selectionWritingMode()
            listOf("horizontal-tb" to "横書き", "vertical-lr" to "縦書きLR", "vertical-rl" to "縦書きRL").forEach { (v, label) ->
                SelectChip(label, active = wm == v) { editor.applyTextWritingModeSelection(v) }
            }
        }
        // 装飾
        ChipRow {
            listOf("none" to "装飾なし", "underline" to "下線", "line-through" to "取り消し線").forEach { (v, label) ->
                SelectChip(label, active = false) { editor.applyTextDecorationSelection(v) }
            }
        }
        // 字間 / 単語間隔 / 行間
        var letterText by remember(el) { mutableStateOf(StyleUtil.getProperty(el, "letter-spacing") ?: "") }
        NumberField("字間", letterText, { letterText = it }, { editor.applyTextLetterSpacingSelection(letterText) })
        var wordText by remember(el) { mutableStateOf(StyleUtil.getProperty(el, "word-spacing") ?: "") }
        NumberField("単語間", wordText, { wordText = it }, { editor.applyTextWordSpacingSelection(wordText) })
        var lineText by remember(el) { mutableStateOf(StyleUtil.getProperty(el, "line-height") ?: "") }
        NumberField("行間", lineText, { lineText = it }, { editor.applyTextLineHeightSelection(lineText) })
        // 垂直揃え
        ChipRow {
            listOf("auto" to "自動", "alphabetic" to "アルファ", "central" to "中央", "hanging" to "ハング").forEach { (v, label) ->
                SelectChip(label, active = false) { editor.applyTextVerticalAlignSelection(v) }
            }
        }
        // 構造変換
        ChipRow {
            SelectChip("パスへ変換", active = false) { editor.convertToPath(listOf(el)) }
            val hasPath = editor.selection.any { it.tag == "path" }
            if (hasPath) {
                SelectChip("パスに沿わせる", active = false) { editor.makeTextPath() }
            }
        }
    }
}

// ========================================================================
// 効果タブ
// ========================================================================

@Composable
private fun EffectsTabContent(
    editor: Editor,
    hasSel: Boolean,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("効果")
        if (!hasSel) {
            Text(
                "オブジェクトを選択してください",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        // ぼかし
        var blurText by remember { mutableStateOf(editor.selectionEffectBlur().toString()) }
        NumberField("ぼかし", blurText, { blurText = it }, {
            dev.svgpro.core.util.Numbers.parse(blurText)?.let { editor.applyEffectBlurToSelection(it) }
        }, width = 56.dp)
        // ドロップシャドウ
        var shadowText by remember {
            mutableStateOf(
                editor.selectionEffectDropShadow()?.let { (dx, dy, blur) ->
                    "${dev.svgpro.core.util.Numbers.format(dx)} ${dev.svgpro.core.util.Numbers.format(dy)} ${dev.svgpro.core.util.Numbers.format(blur)}"
                } ?: "4 4 8",
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("影", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(38.dp))
            OutlinedTextField(
                value = shadowText,
                onValueChange = { shadowText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                val parts = shadowText.split(Regex("\\s+")).filter { it.isNotEmpty() }.mapNotNull { dev.svgpro.core.util.Numbers.parse(it) }
                if (parts.size >= 3) {
                    editor.applyEffectDropShadowToSelection(parts[0], parts[1], parts[2], 0xFF000000.toInt())
                }
            }) { Text("適用", style = MaterialTheme.typography.labelSmall) }
        }
        // 光彩
        var glowText by remember {
            mutableStateOf(
                editor.selectionEffectGlow()?.let { dev.svgpro.core.util.Numbers.format(it.first) } ?: "8",
            )
        }
        NumberField("光彩", glowText, { glowText = it }, {
            dev.svgpro.core.util.Numbers.parse(glowText)?.let { editor.applyEffectGlowToSelection(it, 0xFFFFFF80.toInt()) }
        }, width = 56.dp)
        // 内側シャドウ
        var innerShadowText by remember {
            mutableStateOf(
                editor.selectionEffectInnerShadow()?.let { (dx, dy, blur) ->
                    "${dev.svgpro.core.util.Numbers.format(dx)} ${dev.svgpro.core.util.Numbers.format(dy)} ${dev.svgpro.core.util.Numbers.format(blur)}"
                } ?: "4 4 8",
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("内影", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(38.dp))
            OutlinedTextField(
                value = innerShadowText,
                onValueChange = { innerShadowText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                val parts = innerShadowText.split(Regex("\\s+")).filter { it.isNotEmpty() }.mapNotNull { dev.svgpro.core.util.Numbers.parse(it) }
                if (parts.size >= 3) {
                    editor.applyEffectInnerShadowToSelection(parts[0], parts[1], parts[2], 0xFF000000.toInt())
                }
            }) { Text("適用", style = MaterialTheme.typography.labelSmall) }
        }
        // 色調プリセット
        SectionTitle("色調")
        ChipRow {
            val cur = editor.selectionEffectColorMatrix()
            SelectChip("なし", active = cur == null) { editor.applyEffectColorMatrixToSelection("") }
            SelectChip("グレー", active = cur == GRAYSCALE) { editor.applyEffectColorMatrixToSelection(GRAYSCALE) }
            SelectChip("セピア", active = cur == SEPIA) { editor.applyEffectColorMatrixToSelection(SEPIA) }
            SelectChip("反転", active = cur == INVERT) { editor.applyEffectColorMatrixToSelection(INVERT) }
        }
        // ラスタライズ
        SectionTitle("ラスタライズ")
        var scaleText by remember { mutableStateOf("1.0") }
        NumberField("倍率", scaleText, { scaleText = it }, {
            val s = dev.svgpro.core.util.Numbers.parse(scaleText) ?: 1.0
            editor.rasterizeSelection(s.coerceIn(0.1, 8.0))
        }, width = 50.dp)
        Text(
            "選択を埋め込み画像（data URI）へ変換します",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val GRAYSCALE =
    "0.2126 0.7152 0.0722 0 0 0.2126 0.7152 0.0722 0 0 0.2126 0.7152 0.0722 0 0 0 0 0 1 0"
private const val SEPIA =
    "0.393 0.769 0.189 0 0 0.349 0.686 0.168 0 0 0.272 0.534 0.131 0 0 0 0 0 1 0"
private const val INVERT =
    "-1 0 0 0 1 0 -1 0 0 1 0 0 -1 0 1 0 0 0 1 0"

// ========================================================================
// 変形タブ
// ========================================================================

@Composable
private fun TransformTabContent(
    editor: Editor,
    hasSel: Boolean,
    refresh: Int,
    onOpenTransformDialog: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("変形")
        if (!hasSel) {
            Text(
                "オブジェクトを選択してください",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val sb = editor.trace.boundsOf(editor.selection)
        if (sb != null) {
            var xText by remember(sb.x, refresh) { mutableStateOf(dev.svgpro.core.util.Numbers.format(sb.x, 1)) }
            var yText by remember(sb.y, refresh) { mutableStateOf(dev.svgpro.core.util.Numbers.format(sb.y, 1)) }
            var wText by remember(sb.width, refresh) { mutableStateOf(dev.svgpro.core.util.Numbers.format(sb.width, 1)) }
            var hText by remember(sb.height, refresh) { mutableStateOf(dev.svgpro.core.util.Numbers.format(sb.height, 1)) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = xText, onValueChange = { xText = it }, label = { Text("X") }, modifier = Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = yText, onValueChange = { yText = it }, label = { Text("Y") }, modifier = Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = wText, onValueChange = { wText = it }, label = { Text("W") }, modifier = Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = hText, onValueChange = { hText = it }, label = { Text("H") }, modifier = Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    val x = dev.svgpro.core.util.Numbers.parse(xText)
                    val y = dev.svgpro.core.util.Numbers.parse(yText)
                    val w = dev.svgpro.core.util.Numbers.parse(wText)
                    val h = dev.svgpro.core.util.Numbers.parse(hText)
                    if (x != null && y != null) {
                        editor.setSelectionPosition(x, y)
                        if (w != null && h != null && w > 0 && h > 0) editor.setSelectionSize(w, h)
                    }
                }) { Text("適用") }
            }
        }
        // クイック変形
        SectionTitle("クイック変形")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            CompactButton("-90°") { rotateSelectionAroundBboxCenter(editor, -90.0) }
            CompactButton("+90°") { rotateSelectionAroundBboxCenter(editor, 90.0) }
            CompactButton("左右反転") { editor.flipSelection(true) }
            CompactButton("上下反転") { editor.flipSelection(false) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            CompactButton("複製 (Ctrl+D)") { editor.duplicateSelection() }
            CompactButton("変形を繰り返し (Ctrl+J)") { editor.transformAgain() }
            CompactButton("数値変形…", filled = true) { onOpenTransformDialog() }
        }
    }
}

private fun rotateSelectionAroundBboxCenter(editor: Editor, deg: Double) {
    val b = editor.trace.boundsOf(editor.selection) ?: return
    editor.rotateSelection(deg, dev.svgpro.core.geom.Vec(b.centerX, b.centerY))
}

// ========================================================================
// 整列タブ
// ========================================================================

@Composable
private fun AlignTabContent(
    editor: Editor,
    hasSel: Boolean,
    alignRelative: MutableState<AlignRelative>,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("整列・配置")
        if (!hasSel) {
            Text(
                "2つ以上のオブジェクトを選択すると整列できます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val rel = alignRelative.value
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf(
                AlignRelative.SELECTION to "選択範囲",
                AlignRelative.CANVAS to "キャンバス",
                AlignRelative.KEY_OBJECT to "基準オブジェクト",
            ).forEach { (mode, label) ->
                SelectChip(label, active = rel == mode) { alignRelative.value = mode }
            }
        }
        if (rel == AlignRelative.KEY_OBJECT) {
            Text(
                if (editor.keyObject != null) "基準: ${editor.keyObject?.attr("id") ?: "（設定済み）"}"
                else "「基準に指定」で選択中の1つを基準にします",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CompactButton("基準に指定") { editor.keyObject = editor.selection.first() }
        }
        val alignFn: (String) -> Unit = { mode ->
            if (rel == AlignRelative.SELECTION) editor.alignSelection(mode) else editor.alignSelectionTo(mode, rel)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconAction(AppIcons.AlignLeft, "左揃え") { alignFn("left") }
            IconAction(AppIcons.AlignCenterH, "水平中央揃え") { alignFn("centerX") }
            IconAction(AppIcons.AlignRight, "右揃え") { alignFn("right") }
            IconAction(AppIcons.AlignTop, "上揃え") { alignFn("top") }
            IconAction(AppIcons.AlignCenterV, "垂直中央揃え") { alignFn("centerY") }
            IconAction(AppIcons.AlignBottom, "下揃え") { alignFn("bottom") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconAction(AppIcons.DistH, "横等間隔") {
                if (rel == AlignRelative.SELECTION) editor.distributeSelection(true) else editor.distributeSelectionEx(true)
            }
            IconAction(AppIcons.DistV, "縦等間隔") {
                if (rel == AlignRelative.SELECTION) editor.distributeSelection(false) else editor.distributeSelectionEx(false)
            }
            IconAction(AppIcons.FlipH, "左右反転") { editor.flipSelection(true) }
            IconAction(AppIcons.FlipV, "上下反転") { editor.flipSelection(false) }
        }
        // ギャップ指定の等間隔
        var gapText by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ギャップ", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
            OutlinedTextField(
                value = gapText,
                onValueChange = { gapText = it },
                modifier = Modifier.width(58.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            TextButton(onClick = {
                val gap = dev.svgpro.core.util.Numbers.parse(gapText) ?: 0.0
                editor.distributeSelectionEx(horizontal = true, useGap = true, gap = gap)
            }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("横に適用", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = {
                val gap = dev.svgpro.core.util.Numbers.parse(gapText) ?: 0.0
                editor.distributeSelectionEx(horizontal = false, useGap = true, gap = gap)
            }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("縦に適用", style = MaterialTheme.typography.labelSmall) }
        }
        ChipRow {
            SelectChip("グリッドに配置", active = false) { editor.snapSelectionToGrid() }
        }
        // 選択ヘルパ
        SectionTitle("選択")
        ChipRow {
            SelectChip("同じ塗り", active = false) { editor.selectSameFill() }
            SelectChip("同じ線", active = false) { editor.selectSameStroke() }
            SelectChip("同じ種類", active = false) { editor.selectSameKind() }
            SelectChip("選択反転", active = false) { editor.invertSelection() }
            if (editor.selection.size == 1 && editor.selection[0].tag == "g") {
                SelectChip("内部へ", active = false) { editor.selectChildren() }
            }
        }
    }
}

@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(UiMetrics.BarButton)
            .clip(RoundedCornerShape(UiMetrics.Corner))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(UiMetrics.Icon), tint = MaterialTheme.colorScheme.onSurface)
    }
}

// ========================================================================
// パスタブ
// ========================================================================

@Composable
private fun PathTabContent(
    editor: Editor,
    hasSel: Boolean,
    isPath: Boolean,
    onOffsetDialog: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("パス操作")
        if (!hasSel) {
            Text(
                "オブジェクトを選択してください",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        if (!isPath) {
            ChipRow {
                SelectChip("パスへ変換", active = false) { editor.convertToPath(editor.selection.toList()) }
            }
        }
        SectionTitle("ブーリアン")
        ChipRow {
            SelectChip("和集合", active = false) { editor.booleanOp(GeometryOpKind.UNION) }
            SelectChip("差集合", active = false) { editor.booleanOp(GeometryOpKind.DIFFERENCE) }
            SelectChip("共通部分", active = false) { editor.booleanOp(GeometryOpKind.INTERSECTION) }
            SelectChip("排他的和", active = false) { editor.booleanOp(GeometryOpKind.XOR) }
        }
        SectionTitle("パス編集")
        ChipRow {
            SelectChip("オフセット…", active = false, enabled = isPath) { onOffsetDialog() }
            SelectChip("単純化", active = false, enabled = isPath) { editor.simplifyPath(0.5) }
            SelectChip("平滑化", active = false, enabled = isPath) { editor.smoothPath() }
        }
        ChipRow {
            SelectChip("方向反転", active = false, enabled = isPath) { editor.reversePath() }
            SelectChip("結合", active = false, enabled = isPath) { editor.joinPaths() }
            SelectChip("線をパス化", active = false) { editor.strokeToPathSelection() }
        }
        ChipRow {
            SelectChip("複合パス化", active = false, enabled = isPath) { editor.compoundSelection() }
            SelectChip("複合パス解除", active = false) { editor.releaseCompound() }
        }
        val rule = if (editor.selection.isNotEmpty()) StyleUtil.fillRule(editor.selection.first()) else "nonzero"
        ChipRow {
            SelectChip(
                "塗り規則: $rule",
                active = rule == "evenodd",
                enabled = isPath,
            ) { editor.setFillRuleSelection(if (rule == "evenodd") "nonzero" else "evenodd") }
        }
        SectionTitle("クリッピング / マスク")
        ChipRow {
            SelectChip("クリッピング作成", active = false, enabled = editor.selection.size >= 2) { editor.createClipFromSelection() }
            SelectChip("クリッピング解除", active = false) { editor.releaseClipSelection() }
        }
        ChipRow {
            SelectChip("不透明マスク作成", active = false, enabled = editor.selection.size >= 2) { editor.createMaskFromSelection() }
            SelectChip("マスク解除", active = false) { editor.releaseMaskSelection() }
        }
        SectionTitle("シンボル")
        ChipRow {
            SelectChip("シンボル化", active = false) { editor.createSymbolFromSelection() }
            if (editor.selection.firstOrNull()?.tag == "use") {
                SelectChip("インスタンス解除", active = false) { editor.breakSymbolInstance() }
            }
        }
    }
}

// ========================================================================
// ノードタブ
// ========================================================================

@Composable
private fun NodeTabContent(
    editor: Editor,
    onOpenHandleDialog: () -> Unit,
) {
    val session = editor.nodeSession
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("ノード編集")
        if (editor.tool != ToolType.NODE || session == null) {
            Text(
                "ノードツール（A キー）でパスを選択すると編集できます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text(
            "${session.selected.size} ノード選択",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionTitle("ノード種別")
        ChipRow {
            SelectChip("スムーズ", active = false) { editor.commit("smooth") { batch -> batch.touch(session.el); session.convertSelected(NodeKind.SMOOTH) } }
            SelectChip("対称", active = false) { editor.commit("symmetric") { batch -> batch.touch(session.el); session.convertSelected(NodeKind.SYMMETRIC) } }
            SelectChip("コーナー", active = false) { editor.commit("corner") { batch -> batch.touch(session.el); session.convertSelected(NodeKind.CORNER) } }
            SelectChip("ノード削除", active = false) { editor.commit("delete nodes") { batch -> batch.touch(session.el); session.deleteSelected() } }
        }
        SectionTitle("パス")
        ChipRow {
            SelectChip("パスを閉じる", active = false) { editor.commit("close") { batch -> batch.touch(session.el); session.closeOpenSelected(true) } }
            SelectChip("パスを開く", active = false) { editor.commit("open") { batch -> batch.touch(session.el); session.closeOpenSelected(false) } }
            SelectChip("方向反転", active = false) { session.reverseAll(); editor.onChanged() }
        }
        SectionTitle("選択・整列")
        ChipRow {
            SelectChip("選択反転", active = false) { session.invertNodeSelection(); editor.onChanged() }
            SelectChip("全選択", active = false) { session.selectAllNodes(); editor.onChanged() }
            SelectChip("横等間隔", active = false) { editor.commit("distribute nodes") { b -> b.touch(session.el); session.distributeSelectedNodes(true) } }
            SelectChip("縦等間隔", active = false) { editor.commit("distribute nodes") { b -> b.touch(session.el); session.distributeSelectedNodes(false) } }
        }
        SectionTitle("セグメント")
        ChipRow {
            SelectChip("直線化", active = false) { editor.commit("segments line") { b -> b.touch(session.el); session.convertSelectedSegments(false) } }
            SelectChip("曲線化", active = false) { editor.commit("segments curve") { b -> b.touch(session.el); session.convertSelectedSegments(true) } }
            SelectChip("セグメント削除", active = false) { editor.commit("delete segments") { b -> b.touch(session.el); session.deleteSelectedSegments() } }
        }
        SectionTitle("切断・接続")
        ChipRow {
            SelectChip("端点を接続", active = false) { editor.commit("join ends") { b -> b.touch(session.el); session.joinSelectedEnds() } }
            SelectChip("ノードで切断", active = false) { editor.commit("split nodes") { b -> b.touch(session.el); session.splitAtSelectedNodes() } }
        }
        ChipRow {
            SelectChip("ノード削減", active = false) {
                editor.commit("reduce nodes") { b -> b.touch(session.el); session.reduceNodesInPath(0.5 / editor.zoom.coerceAtLeast(0.01)) }
            }
            SelectChip("ハンドル数値…", active = false) { onOpenHandleDialog() }
        }
        Text(
            "隣接する2ノードを選ぶとセグメント操作、端点2つで接続できます",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** グラデーション定義を作成/取得（既存grad_*があれば再利用） */
fun ensureGradient(editor: Editor, radial: Boolean): String {
    val defs = editor.doc.defsElement()
    val existing = defs.childElements().firstOrNull {
        (it.tag == (if (radial) "radialGradient" else "linearGradient")) &&
            (it.attr("id")?.startsWith("grad") == true)
    }
    if (existing != null) return existing.attr("id")!!
    val id = "grad_${System.currentTimeMillis() % 100000}"
    val g = dev.svgpro.core.svg.Element(if (radial) "radialGradient" else "linearGradient")
    g.setAttr("id", id)
    if (!radial) {
        g.setAttr("x1", "0"); g.setAttr("y1", "0"); g.setAttr("x2", "1"); g.setAttr("y2", "1")
    } else {
        g.setAttr("cx", "0.5"); g.setAttr("cy", "0.5"); g.setAttr("r", "0.5")
    }
    val s1 = dev.svgpro.core.svg.Element("stop")
    s1.setAttr("offset", "0")
    s1.setAttr("stop-color", "#42a5f5")
    val s2 = dev.svgpro.core.svg.Element("stop")
    s2.setAttr("offset", "1")
    s2.setAttr("stop-color", "#ffb74d")
    g.children.add(s1)
    g.children.add(s2)
    defs.children.add(g)
    editor.notifyChanged()
    return id
}
