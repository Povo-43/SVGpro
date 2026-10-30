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
@Composable
fun PropertiesPanel(editor: Editor, refresh: Int) {
    var colorDialogTarget by remember { mutableStateOf<String?>(null) } // "fill" | "stroke"
    var offsetDialog by remember { mutableStateOf(false) }
    var textDialog by remember { mutableStateOf(false) }
    var transformDialog by remember { mutableStateOf(false) }
    var alignRelative by remember { mutableStateOf(AlignRelative.SELECTION) }
    var handleDialog by remember { mutableStateOf(false) }
    var stopColorTarget by remember { mutableStateOf<Int?>(null) }

    val sel = editor.selection
    val hasSel = sel.isNotEmpty()
    val isPath = sel.any { dev.svgpro.core.path.PathData.parse(it.attr("d")) != null || dev.svgpro.core.model.Shapes.isPrimitive(it) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ―――― 塗り ――――
        SectionTitle("塗り")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val fill = if (hasSel) editor.selectionFill() else editor.curFill
            Swatch(fill)
            CompactButton("なし") {
                if (hasSel) {
                    editor.applyFillToSelection(Paint.None)
                } else {
                    editor.curFill = Paint.None
                    editor.onChanged()
                }
            }
            CompactButton("色…", filled = true) { colorDialogTarget = "fill" }
            CompactButton("線形") {
                val id = ensureGradient(editor, radial = false)
                if (hasSel) editor.applyFillToSelection(Paint.Server(id)) else editor.curFill = Paint.Server(id)
            }
            CompactButton("円形") {
                val id = ensureGradient(editor, radial = true)
                if (hasSel) editor.applyFillToSelection(Paint.Server(id)) else editor.curFill = Paint.Server(id)
            }
        }

        // ―――― 線 ――――
        SectionTitle("線")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val stroke = if (hasSel) editor.selectionStroke() else editor.curStroke
            Swatch(stroke)
            CompactButton("なし") {
                if (hasSel) editor.applyStrokeToSelection(Paint.None)
                else { editor.curStroke = Paint.None; editor.onChanged() }
            }
            CompactButton("色…", filled = true) { colorDialogTarget = "stroke" }
        }
        val strokeWidth = if (hasSel) editor.selectionStrokeWidth() else editor.curStrokeWidth
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("幅", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = strokeWidth.toFloat().coerceIn(0.1f, 40f),
                onValueChange = {
                    val v = it.toDouble()
                    if (hasSel) editor.applyStrokeWidthToSelection(v) else { editor.curStrokeWidth = v; editor.onChanged() }
                },
                modifier = Modifier.weight(1f),
            )
            Text(String.format("%.1f", strokeWidth), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(38.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("butt" to "バット", "round" to "ラウンド", "square" to "スクエア").forEach { (v, label) ->
                AssistChip(onClick = { if (hasSel) editor.applyStrokeCapToSelection(v) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
            }
            Spacer(Modifier.width(8.dp))
            listOf("miter" to "マイター", "round" to "ラウンド", "bevel" to "ベベル").forEach { (v, label) ->
                AssistChip(onClick = { if (hasSel) editor.applyStrokeJoinToSelection(v) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf(
                null to "実線",
                listOf(6.0, 4.0) to "破線",
                listOf(2.0, 3.0) to "点線",
                listOf(10.0, 4.0, 2.0, 4.0) to "鎖線",
            ).forEach { (v, label) ->
                AssistChip(onClick = { if (hasSel) editor.applyDashToSelection(v) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
            }
        }
        // 不透明度
        val opacity = if (hasSel) editor.selectionOpacity() else 1.0
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("不透明度", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = opacity.toFloat(),
                onValueChange = {
                    if (hasSel) editor.applyOpacityToSelection(it.toDouble()) else editor.onChanged()
                },
                modifier = Modifier.weight(1f),
            )
            Text("${(opacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(38.dp))
        }

        // ―――― 選択 ――――
        if (hasSel) {
            SectionTitle("選択")
            // 位置・サイズの数値指定
            val sb = editor.trace.boundsOf(sel)
            if (sb != null) {
                var xText by remember(sb.x, refresh) { mutableStateOf(dev.svgpro.core.util.Numbers.format(sb.x, 1)) }
                var yText by remember(sb.y, refresh) { mutableStateOf(dev.svgpro.core.util.Numbers.format(sb.y, 1)) }
                var wText by remember(sb.width, refresh) { mutableStateOf(dev.svgpro.core.util.Numbers.format(sb.width, 1)) }
                var hText by remember(sb.height, refresh) { mutableStateOf(dev.svgpro.core.util.Numbers.format(sb.height, 1)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = xText, onValueChange = { xText = it }, label = { Text("X") }, modifier = Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = yText, onValueChange = { yText = it }, label = { Text("Y") }, modifier = Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { editor.selectSameFill() }, label = { Text("同じ塗り", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.selectSameStroke() }, label = { Text("同じ線", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.selectSameKind() }, label = { Text("同じ種類", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.invertSelection() }, label = { Text("選択反転", style = MaterialTheme.typography.labelSmall) })
                if (sel.size == 1 && sel[0].tag == "g") {
                    AssistChip(onClick = { editor.selectChildren() }, label = { Text("内部へ", style = MaterialTheme.typography.labelSmall) })
                }
            }
        }

        // ―――― 整列 ――――
        SectionTitle("整列・配置")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf(
                AlignRelative.SELECTION to "選択範囲",
                AlignRelative.CANVAS to "キャンバス",
                AlignRelative.KEY_OBJECT to "キーオブジェクト",
            ).forEach { (mode, label) ->
                AssistChip(
                    onClick = { alignRelative = mode },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    colors = if (alignRelative == mode) {
                        AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    } else {
                        AssistChipDefaults.assistChipColors()
                    },
                )
            }
            if (alignRelative == AlignRelative.KEY_OBJECT && hasSel) {
                AssistChip(onClick = { editor.keyObject = sel.first() }, label = { Text("基準に指定", style = MaterialTheme.typography.labelSmall) })
            }
        }
        val alignFn: (String) -> Unit = { mode ->
            if (alignRelative == AlignRelative.SELECTION) editor.alignSelection(mode) else editor.alignSelectionTo(mode, alignRelative)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconAction(AppIcons.AlignLeft, "左揃え") { alignFn("left") }
            IconAction(AppIcons.AlignCenterH, "左右中央揃え") { alignFn("centerX") }
            IconAction(AppIcons.AlignRight, "右揃え") { alignFn("right") }
            IconAction(AppIcons.AlignTop, "上揃え") { alignFn("top") }
            IconAction(AppIcons.AlignCenterV, "上下中央揃え") { alignFn("centerY") }
            IconAction(AppIcons.AlignBottom, "下揃え") { alignFn("bottom") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconAction(AppIcons.DistH, "横に等間隔") {
                if (alignRelative == AlignRelative.SELECTION) editor.distributeSelection(true) else editor.distributeSelectionEx(true)
            }
            IconAction(AppIcons.DistV, "縦に等間隔") {
                if (alignRelative == AlignRelative.SELECTION) editor.distributeSelection(false) else editor.distributeSelectionEx(false)
            }
            IconAction(AppIcons.FlipH, "左右反転") { editor.flipSelection(true) }
            IconAction(AppIcons.FlipV, "上下反転") { editor.flipSelection(false) }
        }
        if (hasSel) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { editor.snapSelectionToGrid() }, label = { Text("グリッドに配置", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { transformDialog = true }, label = { Text("変形…", style = MaterialTheme.typography.labelSmall) })
            }
        }

        // ―――― パス操作 ――――
        if (isPath && sel.isNotEmpty()) {
            SectionTitle("パス操作")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { editor.booleanOp(GeometryOpKind.UNION) }, label = { Text("合成", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.booleanOp(GeometryOpKind.DIFFERENCE) }, label = { Text("型抜き", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.booleanOp(GeometryOpKind.INTERSECTION) }, label = { Text("交差", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.booleanOp(GeometryOpKind.XOR) }, label = { Text("排他", style = MaterialTheme.typography.labelSmall) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { offsetDialog = true }, label = { Text("オフセット…", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.simplifyPath(0.5) }, label = { Text("単純化", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.smoothPath() }, label = { Text("滑らか", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.convertToPath(editor.selection.toList()) }, label = { Text("パスへ変換", style = MaterialTheme.typography.labelSmall) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { editor.reversePath() }, label = { Text("方向反転", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.joinPaths() }, label = { Text("結合", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.strokeToPathSelection() }, label = { Text("線をパス化", style = MaterialTheme.typography.labelSmall) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { editor.compoundSelection() }, label = { Text("複合パス", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.releaseCompound() }, label = { Text("複合解除", style = MaterialTheme.typography.labelSmall) })
                val rule = StyleUtil.fillRule(sel.first())
                AssistChip(
                    onClick = { editor.setFillRuleSelection(if (rule == "evenodd") "nonzero" else "evenodd") },
                    label = { Text("塗り規則: $rule", style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        // ―――― クリッピング / マスク ――――
        if (sel.isNotEmpty()) {
            SectionTitle("クリッピング・マスク")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(
                    onClick = { editor.createClipFromSelection() },
                    enabled = sel.size >= 2,
                    label = { Text("クリッピング作成", style = MaterialTheme.typography.labelSmall) },
                )
                AssistChip(onClick = { editor.releaseClipSelection() }, label = { Text("クリップ解除", style = MaterialTheme.typography.labelSmall) })
                AssistChip(
                    onClick = { editor.createMaskFromSelection() },
                    enabled = sel.size >= 2,
                    label = { Text("マスク作成", style = MaterialTheme.typography.labelSmall) },
                )
                AssistChip(onClick = { editor.releaseMaskSelection() }, label = { Text("マスク解除", style = MaterialTheme.typography.labelSmall) })
            }
            Text(
                "最前面の図形で、それ以外の選択を非破壊で切り抜きます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ―――― シンボル ――――
        if (sel.isNotEmpty()) {
            SectionTitle("シンボル")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { editor.createSymbolFromSelection() }, label = { Text("シンボル化", style = MaterialTheme.typography.labelSmall) })
                val isUse = sel.firstOrNull()?.tag == "use"
                if (isUse) {
                    AssistChip(onClick = { editor.breakSymbolInstance() }, label = { Text("インスタンス解除", style = MaterialTheme.typography.labelSmall) })
                }
            }
        }

        // ―――― グラデーションエディタ ――――
        if (hasSel) {
            val gm = editor.gradientModelOfSelection()
            if (gm != null) {
                SectionTitle("グラデーション")
                Text(
                    "ストップ ${gm.stops.size}個（canvasでハンドル編集可）",
                    style = MaterialTheme.typography.bodySmall,
                )
                gm.stops.forEachIndexed { i, stop ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(stop.argb))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { stopColorTarget = i },
                        )
                        var offText by remember(stop.offset, refresh) {
                            mutableStateOf(dev.svgpro.core.util.Numbers.format(stop.offset))
                        }
                        OutlinedTextField(
                            value = offText,
                            onValueChange = { offText = it },
                            modifier = Modifier.width(64.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            dev.svgpro.core.util.Numbers.parse(offText)?.let { editor.moveGradientStop(i, it) }
                        }) { Text("位置", style = MaterialTheme.typography.labelSmall) }
                        if (gm.stops.size > 2) {
                            TextButton(onClick = { editor.removeGradientStop(i) }) {
                                Text("削除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { editor.addGradientStop(0.5) }) { Text("ストップ追加", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = {
                        val handles = editor.gradientHandlePoints() ?: return@TextButton
                        val model = handles.first
                        val s = handles.second
                        val e = handles.third
                        editor.commit("gradient angle") { batch ->
                            batch.touch(model.def)
                            val mid = dev.svgpro.core.geom.Vec((s.x + e.x) / 2, (s.y + e.y) / 2)
                            val half = dev.svgpro.core.geom.Vec((e.x - s.x) / 2, (e.y - s.y) / 2)
                            val rot = dev.svgpro.core.geom.Matrix().rotateDeg(90.0)
                            val q = rot.apply(half)
                            editor.updateGradientHandles(mid - q, mid + q)
                        }
                    }) { Text("角度90°", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        // ―――― テキスト拡張 ――――
        if (sel.any { it.tag == "text" } && sel.any { it.tag == "path" }) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { editor.makeTextPath() }, label = { Text("パスに沿わせる", style = MaterialTheme.typography.labelSmall) })
            }
        }

        // ―――― テキスト ――――
        if (sel.any { it.tag == "text" }) {
            SectionTitle("テキスト")
            Button(onClick = { textDialog = true }) { Text("テキストを編集…") }
            val textEl = sel.first { it.tag == "text" }
            TextAttrEditors(editor, textEl)
        }

        // ―――― ノード編集 ――――
        if (editor.tool == ToolType.NODE && editor.nodeSession != null) {
            val session = editor.nodeSession!!
            SectionTitle("ノード (${session.selected.size}選択)")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { editor.commit("smooth") { batch -> batch.touch(session.el); session.convertSelected(NodeKind.SMOOTH) } },
                    label = { Text("スムーズ", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("symmetric") { batch -> batch.touch(session.el); session.convertSelected(NodeKind.SYMMETRIC) } },
                    label = { Text("対称", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("corner") { batch -> batch.touch(session.el); session.convertSelected(NodeKind.CORNER) } },
                    label = { Text("コーナー", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("delete nodes") { batch -> batch.touch(session.el); session.deleteSelected() } },
                    label = { Text("ノード削除", style = MaterialTheme.typography.labelSmall) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { editor.commit("close") { batch -> batch.touch(session.el); session.closeOpenSelected(true) } },
                    label = { Text("パスを閉じる", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("open") { batch -> batch.touch(session.el); session.closeOpenSelected(false) } },
                    label = { Text("パスを開く", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { session.reverseAll(); editor.onChanged() },
                    label = { Text("方向反転", style = MaterialTheme.typography.labelSmall) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { session.invertNodeSelection(); editor.onChanged() },
                    label = { Text("選択反転", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { session.selectAllNodes(); editor.onChanged() },
                    label = { Text("全選択", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("distribute nodes") { b -> b.touch(session.el); session.distributeSelectedNodes(true) } },
                    label = { Text("横等間隔", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("distribute nodes") { b -> b.touch(session.el); session.distributeSelectedNodes(false) } },
                    label = { Text("縦等間隔", style = MaterialTheme.typography.labelSmall) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { editor.commit("segments line") { b -> b.touch(session.el); session.convertSelectedSegments(false) } },
                    label = { Text("セグメント直線化", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("segments curve") { b -> b.touch(session.el); session.convertSelectedSegments(true) } },
                    label = { Text("セグメント曲線化", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("delete segments") { b -> b.touch(session.el); session.deleteSelectedSegments() } },
                    label = { Text("セグメント削除", style = MaterialTheme.typography.labelSmall) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { editor.commit("join ends") { b -> b.touch(session.el); session.joinSelectedEnds() } },
                    label = { Text("端点を接続", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("split nodes") { b -> b.touch(session.el); session.splitAtSelectedNodes() } },
                    label = { Text("ノードで切断", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { editor.commit("reduce nodes") { b -> b.touch(session.el); session.reduceNodesInPath(0.5 / editor.zoom.coerceAtLeast(0.01)) } },
                    label = { Text("ノード削減", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { handleDialog = true },
                    label = { Text("ハンドル数値…", style = MaterialTheme.typography.labelSmall) })
            }
            Text(
                "隣接する2ノードを選ぶとセグメント操作、端点2つで接続できます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!hasSel) {
            Text(
                "オブジェクトを選択するとプロパティを編集できます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ―――― ダイアログ ――――
    colorDialogTarget?.let { target ->
        ColorPickerDialog(
            title = if (target == "fill") "塗りの色" else "線の色",
            initial = when (target) {
                "fill" -> (if (hasSel) editor.selectionFill() else editor.curFill).let { (it as? Paint.Solid)?.argb ?: 0xFF1473E6.toInt() }
                else -> (if (hasSel) editor.selectionStroke() else editor.curStroke).let { (it as? Paint.Solid)?.argb ?: 0xFF222222.toInt() }
            },
            onDismiss = { colorDialogTarget = null },
        ) { argb ->
            if (target == "fill") {
                if (hasSel) editor.applyFillToSelection(Paint.Solid(argb)) else { editor.curFill = Paint.Solid(argb); editor.onChanged() }
            } else {
                if (hasSel) editor.applyStrokeToSelection(Paint.Solid(argb)) else { editor.curStroke = Paint.Solid(argb); editor.onChanged() }
            }
            colorDialogTarget = null
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

    stopColorTarget?.let { idx ->
        val gm = editor.gradientModelOfSelection()
        val initial = gm?.stops?.getOrNull(idx)?.argb ?: 0xFF1473E6.toInt()
        ColorPickerDialog(
            title = "ストップ${idx + 1}の色",
            initial = initial,
            onDismiss = { stopColorTarget = null },
        ) { argb ->
            editor.setGradientStopColor(idx, argb)
            stopColorTarget = null
        }
    }

    if (textDialog) {
        val textEl = sel.firstOrNull { it.tag == "text" }
        TextInputDialog(
            title = "テキスト",
            initial = textEl?.textContent() ?: "",
            multiline = true,
            onDismiss = { textDialog = false },
        ) { value ->
            if (textEl != null) {
                editor.commit("text") { batch -> batch.touch(textEl); textEl.setTextContent(value) }
            }
            textDialog = false
        }
    }
}

@Composable
private fun TextAttrEditors(editor: Editor, el: Element) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        AssistChip(onClick = {
            editor.commit("bold") { batch ->
                batch.touch(el)
                val cur = StyleUtil.getProperty(el, "font-weight") ?: "normal"
                StyleUtil.setProperty(el, "font-weight", if (cur == "bold") "normal" else "bold")
            }
        }, label = { Text("太字", style = MaterialTheme.typography.labelSmall) })
        AssistChip(onClick = {
            editor.commit("italic") { batch ->
                batch.touch(el)
                val cur = StyleUtil.getProperty(el, "font-style") ?: "normal"
                StyleUtil.setProperty(el, "font-style", if (cur == "italic") "normal" else "italic")
            }
        }, label = { Text("斜体", style = MaterialTheme.typography.labelSmall) })
        AssistChip(onClick = { editor.convertToPath(listOf(el)) }, label = { Text("パスへ変換", style = MaterialTheme.typography.labelSmall) })
    }
    var sizeText by remember(el) { mutableStateOf((dev.svgpro.core.util.Numbers.parse(StyleUtil.getProperty(el, "font-size")) ?: 16.0).toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("サイズ", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = sizeText,
            onValueChange = { sizeText = it },
            modifier = Modifier.width(90.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = {
            dev.svgpro.core.util.Numbers.parse(sizeText)?.let { v ->
                editor.commit("font-size") { batch ->
                    batch.touch(el)
                    StyleUtil.setProperty(el, "font-size", dev.svgpro.core.util.Numbers.format(v))
                }
            }
        }) { Text("適用") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** 塗り/線のコンパクトボタン */
@Composable
private fun CompactButton(label: String, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 整列等のアイコンボタン */
@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Swatch(paint: Paint?) {
    val color = when (paint) {
        is Paint.Solid -> Color(paint.argb)
        is Paint.Server -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(
                if (paint is Paint.Server) {
                    Brush.linearGradient(
                        listOf(Color(0xFF1473E6), Color(0xFFE8A33D)),
                    )
                } else {
                    SolidColor(color)
                },
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

/** ストロークのアウトライン化（コア実装・開パス対応）は editor.strokeToPathSelection() へ移行 */
@Deprecated("editor.strokeToPathSelection() を使用")
fun outlineStroke(editor: Editor) {
    editor.strokeToPathSelection()
}

/** グラデーション定義を作成/取得 */
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
