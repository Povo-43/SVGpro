package dev.svgpro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.svgpro.core.model.Paint
import dev.svgpro.editor.Editor
import dev.svgpro.editor.applyTransform
import dev.svgpro.editor.transformAgain
import dev.svgpro.editor.setHandleNumeric
import dev.svgpro.editor.replaceColorEverywhere

/**
 * 共通ダイアログ（テキスト入力・数値入力・カラーピッカー・ドキュメント設定）。
 */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    multiline: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = if (multiline) 3 else 1,
                maxLines = if (multiline) 6 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@Composable
fun NumberInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/**
 * カラーピッカー（HSV+α、HEX入力つき）
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var color by remember { mutableStateOf(initial) }
    val hsv = remember(initial) {
        val r = (initial shr 16 and 0xFF) / 255f
        val g = (initial shr 8 and 0xFF) / 255f
        val b = (initial and 0xFF) / 255f
        val out = FloatArray(3)
        android.graphics.Color.RGBToHSV((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt(), out)
        out
    }
    var hue by remember { mutableStateOf(hsv[0]) }
    var satVal by remember { mutableStateOf(androidx.compose.ui.geometry.Offset(hsv[1], hsv[2])) }
    var alpha by remember { mutableStateOf(((initial ushr 24) and 0xFF) / 255f) }
    var hex by remember { mutableStateOf(dev.svgpro.core.model.Color.toHex(initial)) }

    fun applyHsv() {
        val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, satVal.x.coerceIn(0f, 1f), satVal.y.coerceIn(0f, 1f)))
        color = ((alpha * 255).toInt() shl 24) or (c and 0x00FFFFFF)
        hex = dev.svgpro.core.model.Color.toHex(color)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // SVパッド
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { off ->
                                    satVal = Offset(
                                        (off.x / size.width.toFloat()).coerceIn(0f, 1f),
                                        (off.y / size.height.toFloat()).coerceIn(0f, 1f),
                                    )
                                    applyHsv()
                                },
                                onDrag = { change, _ ->
                                    satVal = Offset(
                                        (change.position.x / size.width.toFloat()).coerceIn(0f, 1f),
                                        (change.position.y / size.height.toFloat()).coerceIn(0f, 1f),
                                    )
                                    applyHsv()
                                },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        for (x in 0..50) {
                            for (y in 0..50) {
                                val sx = x / 50f
                                val sy = y / 50f
                                val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, sx, sy))
                                drawRect(
                                    color = Color(c),
                                    topLeft = Offset(sx * w, sy * h),
                                    size = androidx.compose.ui.geometry.Size(w / 50 + 1, h / 50 + 1),
                                )
                            }
                        }
                        // 選択マーカー
                        drawCircle(
                            Color.White,
                            radius = 7.dp.toPx(),
                            center = Offset(satVal.x * w, satVal.y * h),
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                }
                // Hueスライダー
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { off -> hue = (off.x / size.width) * 360f; applyHsv() },
                                onDrag = { change, _ -> hue = ((change.position.x / size.width) * 360f).coerceIn(0f, 359.9f); applyHsv() },
                            )
                        },
                ) {
                    val w = size.width
                    for (x in 0..120) {
                        val t = x / 120f
                        val c = android.graphics.Color.HSVToColor(floatArrayOf(t * 360f, 1f, 1f))
                        drawRect(Color(c), topLeft = Offset(t * w, 0f), size = androidx.compose.ui.geometry.Size(w / 120 + 1, size.height))
                    }
                    drawRect(Color.White, topLeft = Offset(hue / 360f * w - 2.dp.toPx(), 0f), size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height), style = Stroke(2.dp.toPx()))
                }
                // Alpha
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("α", style = MaterialTheme.typography.labelMedium)
                    Slider(value = alpha, onValueChange = { alpha = it; applyHsv() })
                }
                // HEX + 現在色
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(color))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                    )
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { v ->
                            hex = v
                            dev.svgpro.core.model.Color.parse(v)?.let { c ->
                                color = c
                                val r = (c shr 16 and 0xFF) / 255f
                                val g = (c shr 8 and 0xFF) / 255f
                                val b = (c and 0xFF) / 255f
                                val out = FloatArray(3)
                                android.graphics.Color.RGBToHSV((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt(), out)
                                hue = out[0]
                                satVal = Offset(out[1], out[2])
                            }
                        },
                        modifier = Modifier.width(130.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                // スウォッチ
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        0xFF000000, 0xFFFFFFFF, 0xFFF44336, 0xFFE91E63, 0xFF9C27B0,
                        0xFF673AB7, 0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4,
                    ).forEach { c ->
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape2)
                                .background(Color(c.toInt()))
                                .clickable { color = c.toInt(); hex = dev.svgpro.core.model.Color.toHex(c.toInt()) }
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape2),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0xFF009688, 0xFF4CAF50, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107,
                        0xFFFF9800, 0xFFFF5722, 0xFF795548, 0xFF607D8B, 0xFF9E9E9E).forEach { c ->
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape2)
                                .background(Color(c.toInt()))
                                .clickable { color = c.toInt(); hex = dev.svgpro.core.model.Color.toHex(c.toInt()) }
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape2),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(color) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

private val CircleShape2 = androidx.compose.foundation.shape.CircleShape

/**
 * 色の一括置換ダイアログ。
 */
@Composable
fun ReplaceColorDialog(editor: dev.svgpro.editor.Editor, onDismiss: () -> Unit) {
    var fromColor by remember { mutableStateOf(0xFFFF0000.toInt()) }
    var toColor by remember { mutableStateOf(0xFF1473E6.toInt()) }
    var picking by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("色の一括置換") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(fromColor))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                            .clickable { picking = "from" },
                    )
                    Text("→", style = MaterialTheme.typography.titleMedium)
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(toColor))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                            .clickable { picking = "to" },
                    )
                    Text("置換対象の色を変更します（塗り・線・グラデーションストップ）", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editor.replaceColorEverywhere(fromColor, toColor)
                onDismiss()
            }) { Text("置換") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
    picking?.let { target ->
        ColorPickerDialog(
            title = if (target == "from") "置換前の色" else "置換後の色",
            initial = if (target == "from") fromColor else toColor,
            onDismiss = { picking = null },
        ) { argb ->
            if (target == "from") fromColor = argb else toColor = argb
            picking = null
        }
    }
}

/**
 * PNG書き出しダイアログ（サイズ・透過・選択範囲のみ）。
 */
@Composable
fun ExportPngDialog(
    editor: dev.svgpro.editor.Editor,
    onDismiss: () -> Unit,
    onExport: (Pair<Int, Int>, Boolean, Boolean) -> Unit,
) {
    val b = editor.trace.boundsOf(editor.selection)
    val vb = editor.doc.viewBox
    val srcBounds = if (editor.selection.isNotEmpty() && b != null) b else dev.svgpro.core.geom.Bounds(vb.minX, vb.minY, vb.width, vb.height)
    var wText by remember { mutableStateOf("${(srcBounds.width * 2).toInt().coerceIn(16, 8192)}") }
    var hText by remember { mutableStateOf("${(srcBounds.height * 2).toInt().coerceIn(16, 8192)}") }
    var transparent by remember { mutableStateOf(true) }
    var selectionOnly by remember { mutableStateOf(editor.selection.isNotEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PNG書き出し") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = wText, onValueChange = { wText = it }, label = { Text("幅px") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = hText, onValueChange = { hText = it }, label = { Text("高さpx") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(transparent, onCheckedChange = { transparent = it })
                    Text("透過背景", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(16.dp))
                    Checkbox(selectionOnly, onCheckedChange = { selectionOnly = it })
                    Text("選択のみ", style = MaterialTheme.typography.bodySmall)
                }
                Text("解像度: 幅/高さのピクセルで出力します", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = dev.svgpro.core.util.Numbers.parse(wText)?.toInt()?.coerceIn(16, 8192) ?: 1024
                val h = dev.svgpro.core.util.Numbers.parse(hText)?.toInt()?.coerceIn(16, 8192) ?: 1024
                onExport(w to h, transparent, selectionOnly)
            }) { Text("書き出し") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/**
 * 数値変形ダイアログ（移動・拡縮・回転・傾斜・変形の繰り返し/複製）。
 */
@Composable
fun TransformDialog(editor: dev.svgpro.editor.Editor, onDismiss: () -> Unit) {
    val b = editor.trace.boundsOf(editor.selection)
    var dx by remember { mutableStateOf("10") }
    var dy by remember { mutableStateOf("0") }
    var sx by remember { mutableStateOf("100") }
    var sy by remember { mutableStateOf("100") }
    var rot by remember { mutableStateOf("0") }
    var skewX by remember { mutableStateOf("0") }
    var skewY by remember { mutableStateOf("0") }
    val cx = b?.centerX ?: 0.0
    val cy = b?.centerY ?: 0.0

    fun applyOp(duplicate: Boolean) {
        val dxv = dev.svgpro.core.util.Numbers.parse(dx) ?: 0.0
        val dyv = dev.svgpro.core.util.Numbers.parse(dy) ?: 0.0
        val sxv = (dev.svgpro.core.util.Numbers.parse(sx) ?: 100.0) / 100.0
        val syv = (dev.svgpro.core.util.Numbers.parse(sy) ?: 100.0) / 100.0
        val rotv = dev.svgpro.core.util.Numbers.parse(rot) ?: 0.0
        val skx = dev.svgpro.core.util.Numbers.parse(skewX) ?: 0.0
        val sky = dev.svgpro.core.util.Numbers.parse(skewY) ?: 0.0
        when {
            rotv != 0.0 -> editor.applyTransform(
                dev.svgpro.editor.TransformOp.Rotate(rotv, cx, cy),
                duplicate,
            )
            skx != 0.0 || sky != 0.0 -> editor.applyTransform(
                dev.svgpro.editor.TransformOp.Skew(skx, sky, cx, cy),
                duplicate,
            )
            sxv != 1.0 || syv != 1.0 -> editor.applyTransform(
                dev.svgpro.editor.TransformOp.Scale(sxv, syv, b?.x ?: 0.0, b?.y ?: 0.0),
                duplicate,
            )
            else -> editor.applyTransform(
                dev.svgpro.editor.TransformOp.Move(dxv, dyv),
                duplicate,
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("数値変形") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "中心 (${dev.svgpro.core.util.Numbers.format(cx)}, ${dev.svgpro.core.util.Numbers.format(cy)})",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = dx, onValueChange = { dx = it }, label = { Text("移動X") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = dy, onValueChange = { dy = it }, label = { Text("移動Y") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sx, onValueChange = { sx = it }, label = { Text("拡縮X%") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = sy, onValueChange = { sy = it }, label = { Text("拡縮Y%") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = rot, onValueChange = { rot = it }, label = { Text("回転°") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = skewX, onValueChange = { skewX = it }, label = { Text("傾斜X°") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = skewY, onValueChange = { skewY = it }, label = { Text("傾斜Y°") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { applyOp(false); onDismiss() }) { Text("適用") }
                TextButton(onClick = { applyOp(true); onDismiss() }) { Text("複製して適用") }
                TextButton(onClick = { editor.transformAgain(); onDismiss() }) { Text("繰り返し") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/**
 * ハンドルの数値入力（選択ノードの出力ハンドルの長さ・角度）。
 */
@Composable
fun HandleNumericDialog(editor: dev.svgpro.editor.Editor, onDismiss: () -> Unit) {
    val session = editor.nodeSession
    var length by remember { mutableStateOf("10") }
    var angle by remember { mutableStateOf("0") }
    var isIn by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ハンドル数値") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("対象", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !isIn, onClick = { isIn = false }, label = { Text("出力", style = MaterialTheme.typography.labelSmall) })
                    Spacer(Modifier.width(4.dp))
                    FilterChip(selected = isIn, onClick = { isIn = true }, label = { Text("入力", style = MaterialTheme.typography.labelSmall) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = length, onValueChange = { length = it }, label = { Text("長さ") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = angle, onValueChange = { angle = it }, label = { Text("角度°") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val session0 = editor.nodeSession
                val len = dev.svgpro.core.util.Numbers.parse(length)
                val ang = dev.svgpro.core.util.Numbers.parse(angle)
                if (session0 != null && len != null && ang != null) {
                    editor.commit("handle numeric") { batch ->
                        batch.touch(session0.el)
                        session0.setHandleNumeric(isIn, len, ang)
                    }
                }
                onDismiss()
            }) { Text("適用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/**
 * ドキュメント設定（サイズ・背景）
 */
@Composable
fun DocumentSettingsDialog(
    editor: Editor,
    onDismiss: () -> Unit,
) {
    val vb = editor.doc.viewBox
    var wText by remember { mutableStateOf(dev.svgpro.core.util.Numbers.format(vb.width)) }
    var hText by remember { mutableStateOf(dev.svgpro.core.util.Numbers.format(vb.height)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ドキュメント設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = wText, onValueChange = { wText = it },
                        label = { Text("幅") },
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = hText, onValueChange = { hText = it },
                        label = { Text("高さ") },
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("変更するとviewBoxとページサイズが更新されます", style = MaterialTheme.typography.bodySmall)
                // グリッド設定
                var gridOn by remember { mutableStateOf(editor.showGrid) }
                var snapOn by remember { mutableStateOf(editor.snapToGrid) }
                var smartOn by remember { mutableStateOf(editor.smartGuides) }
                var snapNodeOn by remember { mutableStateOf(editor.snapToNode) }
                var snapCenterOn by remember { mutableStateOf(editor.snapToCenter) }
                var snapGuideOn by remember { mutableStateOf(editor.snapToGuides) }
                var rulerOn by remember { mutableStateOf(editor.showRulers) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(gridOn, onCheckedChange = { gridOn = it; editor.showGrid = it; editor.onChanged() })
                        Text("グリッド", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(snapOn, onCheckedChange = { snapOn = it; editor.snapToGrid = it })
                        Text("グリッドスナップ", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(smartOn, onCheckedChange = { smartOn = it; editor.smartGuides = it })
                        Text("スマートガイド", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rulerOn, onCheckedChange = { rulerOn = it; editor.showRulers = it; editor.onChanged() })
                        Text("ルーラー", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(snapNodeOn, onCheckedChange = { snapNodeOn = it; editor.snapToNode = it })
                        Text("ノードにスナップ", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(snapCenterOn, onCheckedChange = { snapCenterOn = it; editor.snapToCenter = it })
                        Text("中心にスナップ", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(snapGuideOn, onCheckedChange = { snapGuideOn = it; editor.snapToGuides = it })
                        Text("ガイドにスナップ", style = MaterialTheme.typography.bodySmall)
                    }
                }
                // ガイド一覧（ロック・表示・削除）
                Text("ガイド (${editor.guides.size})", style = MaterialTheme.typography.labelMedium)
                if (editor.guides.isEmpty()) {
                    Text("ルーラーからドラッグしてガイドを作成できます", style = MaterialTheme.typography.bodySmall)
                }
                editor.guides.forEachIndexed { i, g ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (g.horizontal) "H ${dev.svgpro.core.util.Numbers.format(g.pos, 1)}" else "V ${dev.svgpro.core.util.Numbers.format(g.pos, 1)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(72.dp),
                        )
                        Text(
                            if (g.locked) "ロック中" else "編集可",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable { editor.updateGuide(i, g.copy(locked = !g.locked)) },
                            color = if (g.locked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (g.visible) "表示" else "非表示",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable { editor.updateGuide(i, g.copy(visible = !g.visible)); },
                            color = if (g.visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "削除",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { editor.removeGuide(i) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = dev.svgpro.core.util.Numbers.parse(wText)
                val h = dev.svgpro.core.util.Numbers.parse(hText)
                if (w != null && h != null && w > 0 && h > 0) {
                    editor.commit("document size") { batch ->
                        batch.touch(editor.doc.root)
                        editor.doc.setViewBox(vb.minX, vb.minY, w, h)
                        editor.doc.setPageSize(w, h)
                    }
                    editor.fitToPage()
                }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
