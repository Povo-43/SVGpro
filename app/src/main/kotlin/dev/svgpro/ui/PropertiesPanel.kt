package dev.svgpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
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
import dev.svgpro.editor.alignSelectionTo
import dev.svgpro.editor.gradientHandlePoints
import dev.svgpro.editor.updateGradientHandles
import dev.svgpro.editor.distributeSelectionEx
import dev.svgpro.editor.applyGradientToSelection
import dev.svgpro.editor.addGradientStop
import dev.svgpro.editor.compoundSelection
import dev.svgpro.editor.createClipFromSelection
import dev.svgpro.editor.createMaskFromSelection
import dev.svgpro.editor.createGradientForSelection
import dev.svgpro.editor.createSymbolFromSelection
import dev.svgpro.editor.breakSymbolInstance
import dev.svgpro.editor.gradientModelOfSelection
import dev.svgpro.editor.invertSelection
import dev.svgpro.editor.invertNodeSelection
import dev.svgpro.editor.selectAllNodes
import dev.svgpro.editor.distributeSelectedNodes
import dev.svgpro.editor.convertSelectedSegments
import dev.svgpro.editor.deleteSelectedSegments
import dev.svgpro.editor.joinSelectedEnds
import dev.svgpro.editor.splitAtSelectedNodes
import dev.svgpro.editor.reduceNodesInPath
import dev.svgpro.editor.releaseClipSelection
import dev.svgpro.editor.releaseCompound
import dev.svgpro.editor.releaseMaskSelection
import dev.svgpro.editor.removeGradientStop
import dev.svgpro.editor.selectChildren
import dev.svgpro.editor.selectSameFill
import dev.svgpro.editor.selectSameKind
import dev.svgpro.editor.selectSameStroke
import dev.svgpro.editor.setFillRuleSelection
import dev.svgpro.editor.setSelectionPosition
import dev.svgpro.editor.setSelectionSize
import dev.svgpro.editor.snapSelectionToGrid
import dev.svgpro.editor.strokeToPathSelection
import dev.svgpro.editor.makeTextPath
import dev.svgpro.editor.moveGradientStop
import dev.svgpro.editor.setGradientStopColor

/**
 * プロパティパネル（塗り・線・整列・パス操作）。
 */

/** プロパティタブ種別。 */
enum class PropertyTab(val label: String) {
    Fill("塗り"),
    Stroke("線"),
    Text("テキスト"),
    Effects("エフェクト"),
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
) {
    val sel = editor.selection
    val hasSel = sel.isNotEmpty()
    val isPath = sel.any { dev.svgpro.core.path.PathData.parse(it.attr("d")) != null || dev.svgpro.core.model.Shapes.isPrimitive(it) }

    val colorDialogTargetState = remember { mutableStateOf<String?>(null) }
    var colorDialogTarget by colorDialogTargetState
    val colorSwatchTargetState = remember { mutableStateOf("fill") }
    var colorSwatchTarget by colorSwatchTargetState
    var offsetDialog by remember { mutableStateOf(false) }

    fun onColorSwatch(argb: Int) {
        val paint = Paint.Solid(argb)
        if (hasSel) {
            if (colorSwatchTarget == "fill") editor.applyFillToSelection(paint)
            else editor.applyStrokeToSelection(paint)
        } else {
            if (colorSwatchTarget == "fill") { editor.curFill = paint; editor.onChanged() }
            else { editor.curStroke = paint; editor.onChanged() }
        }
    }

    var textDialog by remember { mutableStateOf(false) }
    val transformDialogState = remember { mutableStateOf(false) }
    var transformDialog by transformDialogState
    val alignRelativeState = remember { mutableStateOf(AlignRelative.SELECTION) }
    var alignRelative by alignRelativeState
    val handleDialogState = remember { mutableStateOf(false) }
    var handleDialog by handleDialogState
    var stopColorTarget by remember { mutableStateOf<Int?>(null) }

    /** プロパティパネルのタブ選択状態。 */
    var panelTab by remember { mutableStateOf(PropertyTab.Fill) }


    Column(
        Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // タブバー
        TabRow(
            selectedTabIndex = panelTab.ordinal,
            modifier = Modifier.fillMaxWidth(),
        ) {
            PropertyTab.values().forEach { tab ->
                Tab(
                    text = { Text(tab.label) },
                    selected = panelTab == tab,
                    onClick = { panelTab = tab },
                )
            }
        }
        Divider()

        when (panelTab) {
            PropertyTab.Fill -> FillTabContent(editor, hasSel, colorDialogTargetState, colorSwatchTargetState, ::onColorSwatch, onSaveSwatches, onLoadSwatches)
            PropertyTab.Stroke -> StrokeTabContent(editor, hasSel, colorDialogTargetState, colorSwatchTargetState)
            PropertyTab.Text -> TextTabContent(editor, hasSel, customFontFamilies, onShowFontManager)
            PropertyTab.Effects -> EffectsTabContent(editor, hasSel)
            PropertyTab.Transform -> TransformTabContent(editor, hasSel, transformDialogState, alignRelativeState)
            PropertyTab.Align -> AlignTabContent(editor, hasSel, alignRelativeState)
            PropertyTab.Path -> PathTabContent(editor, hasSel)
            PropertyTab.Node -> NodeTabContent(editor, hasSel, handleDialogState)
        }
    }
}

// ========================================================================
// タブごとのコンテンツ
// ========================================================================

@Composable
internal fun SectionTitle(text: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** 塗りタブコンテンツ。 */
@Composable
private fun FillTabContent(
    editor: Editor,
    hasSel: Boolean,
    colorDialogTarget: MutableState<String?>,
    colorSwatchTarget: MutableState<String>,
    onColorSwatch: (Int) -> Unit,
    onSaveSwatches: () -> Unit,
    onLoadSwatches: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 塗り
        SectionTitle("塗り")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val fill = if (hasSel) editor.selectionFill() ?: Paint.None else editor.curFill
            Swatch(fill)
            Spacer(Modifier.width(4.dp))
            Column {
                CompactButton("なし") {
                    if (hasSel) {
                        editor.applyFillToSelection(Paint.None)
                    } else {
                        editor.curFill = Paint.None
                        editor.onChanged()
                    }
                }
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
        // スウォッチ
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("スウォッチ", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = colorSwatchTarget.value == "fill", onClick = { colorSwatchTarget.value = "fill" }, label = { Text("塗り", style = MaterialTheme.typography.labelSmall) })
                FilterChip(selected = colorSwatchTarget.value == "stroke", onClick = { colorSwatchTarget.value = "stroke" }, label = { Text("線", style = MaterialTheme.typography.labelSmall) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            editor.colorSwatches.toList().forEachIndexed { i: Int, argb: Int ->
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(androidx.compose.ui.graphics.Color(argb))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                        .clickable { onColorSwatch(argb) },
                )
            }
            CompactButton("＋登録", filled = false) {
                val c = if (hasSel) editor.selectionFill() else editor.curFill
                (c as? Paint.Solid)?.let { editor.addColorSwatch(it.argb) }
            }
            Spacer(Modifier.weight(1f))
            Column {
                CompactButton("パレット保存", filled = false) { onSaveSwatches() }
                CompactButton("パレット読込", filled = false) { onLoadSwatches() }
            }
        }
    }
}

/** 線タブコンテンツ。 */
@Composable
private fun StrokeTabContent(
    editor: Editor,
    hasSel: Boolean,
    colorDialogTargetState: MutableState<String?>,
    colorSwatchTargetState: MutableState<String>,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("線")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val stroke = if (hasSel) editor.selectionStroke() ?: Paint.None else editor.curStroke
            Swatch(stroke)
            Spacer(Modifier.width(4.dp))
            Column {
                CompactButton("なし") {
                    if (hasSel) editor.applyStrokeToSelection(Paint.None)
                    else { editor.curStroke = Paint.None; editor.onChanged() }
                }
                CompactButton("色…", filled = true) { colorDialogTargetState.value = "stroke" }
            }
            Spacer(Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("幅", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(40.dp))
            val strokeWidth = if (hasSel) editor.selectionStrokeWidth() else editor.curStrokeWidth
            Slider(
                value = strokeWidth.toFloat().coerceIn(0.1f, 40f),
                onValueChange = {
                    val v = it.toDouble()
                    if (hasSel) editor.applyStrokeWidthToSelection(v) else { editor.curStrokeWidth = v; editor.onChanged() }
                },
                modifier = Modifier.weight(1f),
            )
            Text(String.format("%.1f", strokeWidth), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
        }
    }
}

/** テキストタブコンテンツ。 */
@Composable
private fun TextTabContent(
    editor: Editor,
    hasSel: Boolean,
    customFontFamilies: List<String>,
    onShowFontManager: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("テキスト")
        if (hasSel) {
            val textEl = editor.selection.firstOrNull { it.tag == "text" } ?: return@Column
            var textContent by remember(textEl) { mutableStateOf(textEl.textContent()) }
            LaunchedEffect(textEl) { textContent = textEl.textContent() }
            OutlinedTextField(
                value = textContent,
                onValueChange = { newText ->
                    editor.commit("text") { batch -> batch.touch(textEl); textEl.setTextContent(newText) }
                },
                label = { Text("テキスト内容") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text("テキスト要素を選択してください", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** エフェクトタブコンテンツ。 */
@Composable
private fun EffectsTabContent(
    editor: Editor,
    hasSel: Boolean,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("エフェクト")
        if (hasSel) {
            var blurText by remember { mutableStateOf(editor.selectionEffectBlur().toString()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ぼかし", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                OutlinedTextField(
                    value = blurText,
                    onValueChange = { blurText = it },
                    modifier = Modifier.width(70.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = {
                    dev.svgpro.core.util.Numbers.parse(blurText)?.let { editor.applyEffectBlurToSelection(it) }
                }) { Text("適用", style = MaterialTheme.typography.labelSmall) }
            }
        } else {
            Text("オブジェクトを選択してください", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 変形タブコンテンツ。 */
@Composable
private fun TransformTabContent(
    editor: Editor,
    hasSel: Boolean,
    transformDialog: MutableState<Boolean>,
    alignRelative: MutableState<dev.svgpro.editor.Editor.AlignRelative>,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("変形")
        if (hasSel) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { transformDialog.value = true }) { Text("数値変形ダイアログ", style = MaterialTheme.typography.labelSmall) }
            }
        } else {
            Text("オブジェクトを選択してください", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 整列タブコンテンツ。 */
@Composable
private fun AlignTabContent(
    editor: Editor,
    hasSel: Boolean,
    alignRelative: MutableState<dev.svgpro.editor.Editor.AlignRelative>,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("整列")
        if (hasSel) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                dev.svgpro.editor.Editor.AlignRelative.values().forEach { rel ->
                    val selected = alignRelative.value == rel
                    AssistChip(
                        onClick = { alignRelative.value = rel },
                        label = { Text(rel.name, style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        } else {
            Text("オブジェクトを選択してください", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** パスタブコンテンツ。 */
@Composable
private fun PathTabContent(
    editor: Editor,
    hasSel: Boolean,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("パス")
        if (hasSel) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                dev.svgpro.core.path.PathData.parse(editor.selection.first().attr("d"))?.let {
                    AssistChip(onClick = { editor.convertToPath(editor.selection.toList()) }, label = { Text("パスへ変換", style = MaterialTheme.typography.labelSmall) })
                } ?: run {
                    Text("パス要素を選択してください", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Text("オブジェクトを選択してください", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** ノードタブコンテンツ。 */
@Composable
private fun NodeTabContent(
    editor: Editor,
    hasSel: Boolean,
    handleDialog: MutableState<Boolean>,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("ノード")
        if (editor.tool == dev.svgpro.editor.ToolType.NODE && editor.nodeSession != null) {
            val session = editor.nodeSession!!
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { editor.commit("smooth") { batch -> batch.touch(session.el); session.convertSelected(dev.svgpro.core.path.NodeKind.SMOOTH) } },
                    label = { Text("スムーズ", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("symmetric") { batch -> batch.touch(session.el); session.convertSelected(dev.svgpro.core.path.NodeKind.SYMMETRIC) } },
                    label = { Text("対称", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("corner") { batch -> batch.touch(session.el); session.convertSelected(dev.svgpro.core.path.NodeKind.CORNER) } },
                    label = { Text("コーナー", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("delete nodes") { batch -> batch.touch(session.el); session.deleteSelected() } },
                    label = { Text("ノード削除", style = MaterialTheme.typography.labelSmall) })
            }
        } else {
            Text("ノードツールでパスを選択してください", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

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

/** 塗り/線のスウォッチ表示 */
@Composable
internal fun Swatch(paint: dev.svgpro.core.model.Paint) {
    val color = when (paint) {
        is dev.svgpro.core.model.Paint.None -> MaterialTheme.colorScheme.surfaceVariant
        is dev.svgpro.core.model.Paint.Solid -> androidx.compose.ui.graphics.Color(paint.argb)
        is dev.svgpro.core.model.Paint.Server -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
    )
}
