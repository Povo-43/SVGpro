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
import dev.svgpro.editor.GeometryOpKind
import dev.svgpro.core.path.NodeKind
import dev.svgpro.editor.ToolType

/**
 * プロパティパネル（塗り・線・整列・パス操作）。
 */
@Composable
fun PropertiesPanel(editor: Editor, refresh: Int) {
    var colorDialogTarget by remember { mutableStateOf<String?>(null) } // "fill" | "stroke"
    var offsetDialog by remember { mutableStateOf(false) }
    var textDialog by remember { mutableStateOf(false) }

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
            Button(onClick = {
                val cur = (if (hasSel) editor.selectionFill() else editor.curFill)
                if (hasSel) {
                    editor.applyFillToSelection(Paint.None)
                } else {
                    editor.curFill = Paint.None
                    editor.onChanged()
                }
            }) { Text("なし") }
            Button(onClick = { colorDialogTarget = "fill" }) { Text("色…") }
            Button(onClick = {
                val id = ensureGradient(editor, radial = false)
                if (hasSel) editor.applyFillToSelection(Paint.Server(id)) else editor.curFill = Paint.Server(id)
            }) { Text("線形") }
            Button(onClick = {
                val id = ensureGradient(editor, radial = true)
                if (hasSel) editor.applyFillToSelection(Paint.Server(id)) else editor.curFill = Paint.Server(id)
            }) { Text("円形") }
        }

        // ―――― 線 ――――
        SectionTitle("線")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val stroke = if (hasSel) editor.selectionStroke() else editor.curStroke
            Swatch(stroke)
            Button(onClick = {
                if (hasSel) editor.applyStrokeToSelection(Paint.None)
                else { editor.curStroke = Paint.None; editor.onChanged() }
            }) { Text("なし") }
            Button(onClick = { colorDialogTarget = "stroke" }) { Text("色…") }
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

        // ―――― 整列 ――――
        SectionTitle("整列・配置")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            AssistChip(onClick = { editor.alignSelection("left") }, label = { Text("左", style = MaterialTheme.typography.labelSmall) })
            AssistChip(onClick = { editor.alignSelection("centerX") }, label = { Text("左右中央", style = MaterialTheme.typography.labelSmall) })
            AssistChip(onClick = { editor.alignSelection("right") }, label = { Text("右", style = MaterialTheme.typography.labelSmall) })
            AssistChip(onClick = { editor.alignSelection("top") }, label = { Text("上", style = MaterialTheme.typography.labelSmall) })
            AssistChip(onClick = { editor.alignSelection("centerY") }, label = { Text("上下中央", style = MaterialTheme.typography.labelSmall) })
            AssistChip(onClick = { editor.alignSelection("bottom") }, label = { Text("下", style = MaterialTheme.typography.labelSmall) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            AssistChip(onClick = { editor.distributeSelection(true) }, label = { Text("横均等", style = MaterialTheme.typography.labelSmall) })
            AssistChip(onClick = { editor.distributeSelection(false) }, label = { Text("縦均等", style = MaterialTheme.typography.labelSmall) })
            AssistChip(onClick = { editor.flipSelection(true) }, label = { Text("左右反転", style = MaterialTheme.typography.labelSmall) })
            AssistChip(onClick = { editor.flipSelection(false) }, label = { Text("上下反転", style = MaterialTheme.typography.labelSmall) })
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
                AssistChip(onClick = { outlineStroke(editor) }, label = { Text("線をパス化", style = MaterialTheme.typography.labelSmall) })
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
                "fill" -> (if (hasSel) editor.selectionFill() else editor.curFill).let { (it as? Paint.Solid)?.argb ?: 0xFF42A5F5.toInt() }
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
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
                        listOf(Color(0xFF42A5F5), Color(0xFFFFB74D)),
                    )
                } else {
                    SolidColor(color)
                },
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

/** ストロークのアウトライン化（Paint.getFillPathを使用） */
fun outlineStroke(editor: Editor) {
    val el = editor.selection.firstOrNull() ?: return
    val pd = dev.svgpro.core.path.PathData.parse(el.attr("d")) ?: return
    val androidPath = dev.svgpro.canvas.SvgRenderer.toAndroidPath(pd)
    val paint = android.graphics.Paint()
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = editor.selectionStrokeWidth().toFloat()
    paint.strokeCap = when (StyleUtil.getProperty(el, "stroke-linecap")) {
        "round" -> android.graphics.Paint.Cap.ROUND
        "square" -> android.graphics.Paint.Cap.SQUARE
        else -> android.graphics.Paint.Cap.BUTT
    }
    paint.strokeJoin = when (StyleUtil.getProperty(el, "stroke-linejoin")) {
        "round" -> android.graphics.Paint.Join.ROUND
        "bevel" -> android.graphics.Paint.Join.BEVEL
        else -> android.graphics.Paint.Join.MITER
    }
    val dst = android.graphics.Path()
    paint.getFillPath(androidPath, dst)
    val out = outlinePathToData(dst) ?: return
    val abs = editor.trace.absOf(el)
    out.transform(abs)
    editor.commit("stroke to path") { batch ->
        batch.touch(el)
        el.setAttr("d", out.write())
        el.setAttr("fill-rule", "evenodd")
        StyleUtil.setProperty(el, "fill", StyleUtil.getProperty(el, "stroke") ?: "#000000")
        StyleUtil.setProperty(el, "stroke", "none")
    }
}

private fun outlinePathToData(src: android.graphics.Path): dev.svgpro.core.path.PathData? {
    val segs = ArrayList<dev.svgpro.core.path.Seg>()
    val measure = android.graphics.PathMeasure(src, false)
    while (true) {
        val len = measure.length
        if (len > 0.01f) {
            val steps = (len / 0.7f).toInt().coerceIn(8, 600)
            var first = true
            for (i in 0..steps) {
                val pos = FloatArray(2)
                measure.getPosTan(len * i / steps, pos, null)
                if (first) {
                    segs.add(dev.svgpro.core.path.Seg.Move(pos[0].toDouble(), pos[1].toDouble()))
                    first = false
                } else {
                    segs.add(dev.svgpro.core.path.Seg.Line(pos[0].toDouble(), pos[1].toDouble()))
                }
            }
            segs.add(dev.svgpro.core.path.Seg.Close)
        }
        if (!measure.nextContour()) break
    }
    if (segs.isEmpty()) return null
    return dev.svgpro.core.path.PathData(segs)
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
