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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(gridOn, onCheckedChange = { gridOn = it; editor.showGrid = it; editor.onChanged() })
                        Text("グリッド", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(snapOn, onCheckedChange = { snapOn = it; editor.snapToGrid = it })
                        Text("スナップ", style = MaterialTheme.typography.bodySmall)
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
