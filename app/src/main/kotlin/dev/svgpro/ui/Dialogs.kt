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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import android.graphics.Typeface
import dev.svgpro.core.model.FontFace
import dev.svgpro.core.model.Paint
import dev.svgpro.core.model.StyleUtil
import dev.svgpro.core.svg.Element
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

/**
 * ガイド作成ダイアログ（水平/垂直/任意角度+通過点）。
 * ロゴデザイン向けに数値指定でガイドを引ける。
 */
@Composable
fun GuideCreateDialog(
    editor: dev.svgpro.editor.Editor,
    onDismiss: () -> Unit,
) {
    val vb = editor.doc.viewBox
    val cx = vb.minX + vb.width / 2
    val cy = vb.minY + vb.height / 2
    var kind by remember { mutableStateOf(0) } // 0=水平 1=垂直 2=角度
    var posText by remember { mutableStateOf(dev.svgpro.core.util.Numbers.format(if (kind == 0) cy else cx)) }
    var angleText by remember { mutableStateOf("0") }
    var pxText by remember { mutableStateOf(dev.svgpro.core.util.Numbers.format(cx)) }
    var pyText by remember { mutableStateOf(dev.svgpro.core.util.Numbers.format(cy)) }
    var lockOn by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ガイドを作成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("水平" to 0, "垂直" to 1, "角度" to 2).forEach { (label, k) ->
                        FilterChip(
                            selected = kind == k,
                            onClick = {
                                kind = k
                                posText = dev.svgpro.core.util.Numbers.format(if (k == 0) cy else cx)
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                if (kind == 2) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("角度°", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(
                            value = angleText,
                            onValueChange = { angleText = it },
                            modifier = Modifier.width(70.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Text("（0=右・時計回りが正）", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = pxText,
                            onValueChange = { pxText = it },
                            label = { Text("通過点X") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = pyText,
                            onValueChange = { pyText = it },
                            label = { Text("通過点Y") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = posText,
                        onValueChange = { posText = it },
                        label = { Text(if (kind == 0) "Y位置" else "X位置") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(lockOn, onCheckedChange = { lockOn = it })
                    Text("ロックして作成", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val g = when (kind) {
                    0 -> dev.svgpro.core.util.Numbers.parse(posText)?.let {
                        dev.svgpro.core.model.Guide(horizontal = true, pos = it, locked = lockOn)
                    }
                    1 -> dev.svgpro.core.util.Numbers.parse(posText)?.let {
                        dev.svgpro.core.model.Guide(horizontal = false, pos = it, locked = lockOn)
                    }
                    else -> {
                        val ang = dev.svgpro.core.util.Numbers.parse(angleText)
                        val px = dev.svgpro.core.util.Numbers.parse(pxText)
                        val py = dev.svgpro.core.util.Numbers.parse(pyText)
                        if (ang != null && px != null && py != null) {
                            dev.svgpro.core.model.Guide.angled(ang, dev.svgpro.core.geom.Vec(px, py), locked = lockOn)
                        } else null
                    }
                }
                if (g != null) editor.addGuideAt(g)
                onDismiss()
            }) { Text("作成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
fun NumberInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {    var text by remember { mutableStateOf(initial) }
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
        syncCmyk(color)
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
                                syncCmyk(c)
                            }
                        },
                        modifier = Modifier.width(130.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                // CMYK入力
                var cmykC by remember { mutableStateOf("0") }
                var cmykM by remember { mutableStateOf("0") }
                var cmykY by remember { mutableStateOf("0") }
                var cmykK by remember { mutableStateOf("0") }
                LaunchedEffect(color) {
                    val cmyk = rgbToCmyk(color)
                    cmykC = dev.svgpro.core.util.Numbers.format(cmyk.first * 100)
                    cmykM = dev.svgpro.core.util.Numbers.format(cmyk.second * 100)
                    cmykY = dev.svgpro.core.util.Numbers.format(cmyk.third * 100)
                    cmykK = dev.svgpro.core.util.Numbers.format(cmyk.fourth * 100)
                }
                fun syncCmyk(argb: Int) {
                    val cmyk = rgbToCmyk(argb)
                    cmykC = dev.svgpro.core.util.Numbers.format(cmyk.first * 100)
                    cmykM = dev.svgpro.core.util.Numbers.format(cmyk.second * 100)
                    cmykY = dev.svgpro.core.util.Numbers.format(cmyk.third * 100)
                    cmykK = dev.svgpro.core.util.Numbers.format(cmyk.fourth * 100)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CMYK", style = MaterialTheme.typography.labelMedium)
                    listOf(
                        "C" to cmykC,
                        "M" to cmykM,
                        "Y" to cmykY,
                        "K" to cmykK,
                    ).forEachIndexed { index, (label, value) ->
                        OutlinedTextField(
                            value = value,
                            onValueChange = { v ->
                                when (index) {
                                    0 -> cmykC = v
                                    1 -> cmykM = v
                                    2 -> cmykY = v
                                    3 -> cmykK = v
                                }
                                val c = dev.svgpro.core.util.Numbers.parse(cmykC) ?: 0.0
                                val m = dev.svgpro.core.util.Numbers.parse(cmykM) ?: 0.0
                                val y = dev.svgpro.core.util.Numbers.parse(cmykY) ?: 0.0
                                val k = dev.svgpro.core.util.Numbers.parse(cmykK) ?: 0.0
                                val newColor = cmykToRgb(c / 100.0, m / 100.0, y / 100.0, k / 100.0)
                                color = newColor
                                hex = dev.svgpro.core.model.Color.toHex(newColor)
                                val r = (newColor shr 16 and 0xFF) / 255f
                                val g = (newColor shr 8 and 0xFF) / 255f
                                val b = (newColor and 0xFF) / 255f
                                val out = FloatArray(3)
                                android.graphics.Color.RGBToHSV((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt(), out)
                                hue = out[0]
                                satVal = Offset(out[1], out[2])
                            },
                            modifier = Modifier.width(40.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("%", style = MaterialTheme.typography.bodySmall)
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
enum class ExportFormat { PNG, JPEG, WEBP }

/** 画像書き出しダイアログ（PNG / JPEG / WebP）。 */
@Composable
fun ExportPngDialog(
    editor: dev.svgpro.editor.Editor,
    onDismiss: () -> Unit,
    onExport: (Pair<Int, Int>, Boolean, Boolean, ExportFormat) -> Unit,
) {
    val b = editor.trace.boundsOf(editor.selection)
    val vb = editor.doc.viewBox
    val srcBounds = if (editor.selection.isNotEmpty() && b != null) b else dev.svgpro.core.geom.Bounds(vb.minX, vb.minY, vb.width, vb.height)
    var wText by remember { mutableStateOf("${(srcBounds.width * 2).toInt().coerceIn(16, 8192)}") }
    var hText by remember { mutableStateOf("${(srcBounds.height * 2).toInt().coerceIn(16, 8192)}") }
    var transparent by remember { mutableStateOf(true) }
    var selectionOnly by remember { mutableStateOf(editor.selection.isNotEmpty()) }
    var format by remember { mutableStateOf(ExportFormat.PNG) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("画像書き出し") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = wText, onValueChange = { wText = it }, label = { Text("幅px") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = hText, onValueChange = { hText = it }, label = { Text("高さpx") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.svgHorizontalScroll(svgRememberScrollState())) {
                    listOf(
                        ExportFormat.PNG to "PNG",
                        ExportFormat.JPEG to "JPEG",
                        ExportFormat.WEBP to "WebP",
                    ).forEach { (f, label) ->
                        val selected = format == f
                        AssistChip(
                            onClick = { format = f },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = if (selected) {
                                AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                            } else {
                                AssistChipDefaults.assistChipColors()
                            },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val jpeg = format == ExportFormat.JPEG
                    val transEnabled = !jpeg
                    Checkbox(transparent && transEnabled, onCheckedChange = { if (transEnabled) transparent = it })
                    Text("透過背景（JPEGは無効）", style = MaterialTheme.typography.bodySmall)
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
                onExport(w to h, transparent && format != ExportFormat.JPEG, selectionOnly, format)
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
    refresh: Int,
    onDismiss: () -> Unit,
) {
    val vb = editor.doc.viewBox
    var wText by remember { mutableStateOf(dev.svgpro.core.util.Numbers.format(vb.width)) }
    var hText by remember { mutableStateOf(dev.svgpro.core.util.Numbers.format(vb.height)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ドキュメント設定") },
        text = {
            // refreshのキー評価により、エディタ変更（ガイド追加・ロック等）のたびに
            // このtextラムダが再実行され、ガイド一覧が更新される
            LaunchedEffect(refresh) { }
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
                // グリッド間隔（ルーラー目盛りとも連動）
                var gridSizeText by remember { mutableStateOf(editor.gridSize.toString()) }
                LaunchedEffect(editor.gridSize) {
                    gridSizeText = editor.gridSize.toString()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = gridSizeText,
                            onValueChange = {
                                gridSizeText = it
                                dev.svgpro.core.util.Numbers.parse(it)?.let { editor.gridSize = it }
                            },
                            label = { Text("グリッド間隔") },
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var snapGuideGrid by remember { mutableStateOf(editor.snapGuidesToGrid) }
                        Checkbox(snapGuideGrid, onCheckedChange = { snapGuideGrid = it; editor.snapGuidesToGrid = it })
                        Text("ガイドをグリッドにスナップ", style = MaterialTheme.typography.bodySmall)
                    }
                }
                // ルーラー単位
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ルーラー単位", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            val units = dev.svgpro.editor.Editor.RulerUnit.values()
                            val cur = editor.rulerUnit
                            editor.rulerUnit = units[(units.indexOf(cur) + 1) % units.size]
                        }) {
                            Text(editor.rulerUnitLabel(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("DPI", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        var dpiText by remember { mutableStateOf(editor.rulerDpi.toString()) }
                        OutlinedTextField(
                            value = dpiText, onValueChange = { dpiText = it },
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.width(56.dp),
                            singleLine = true,
                        )
                        TextButton(onClick = {
                            dev.svgpro.core.util.Numbers.parse(dpiText)?.let { if (it > 0) editor.rulerDpi = it }
                        }) { Text("適用", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                // 回転スナップ角（ロゴ作業向け）
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    var rotateSnapText by remember { mutableStateOf(editor.rotateSnapStep.toString()) }
                    LaunchedEffect(editor.rotateSnapStep) { rotateSnapText = editor.rotateSnapStep.toString() }
                    OutlinedTextField(
                        value = rotateSnapText,
                        onValueChange = { rotateSnapText = it },
                        label = { Text("回転スナップ角°") },
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.width(120.dp),
                        singleLine = true,
                    )
                    TextButton(onClick = {
                        dev.svgpro.core.util.Numbers.parse(rotateSnapText)?.let {
                            if (it >= 1.0) editor.rotateSnapStep = it
                        }
                    }) { Text("適用", style = MaterialTheme.typography.labelSmall) }
                    Text("回転ハンドル・計測のShiftスナップ", style = MaterialTheme.typography.bodySmall)
                }
                // アートボード管理
                var abNameText by remember { mutableStateOf("") }
                Text("アートボード", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                editor.doc.artboards.withIndex().forEach { (i, a) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${a.name} (${dev.svgpro.core.util.Numbers.format(a.w)}×${dev.svgpro.core.util.Numbers.format(a.h)})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editor.setCurrentArtboard(i) }) { Text("切替", style = MaterialTheme.typography.labelSmall) }
                        if (editor.doc.artboards.size > 1) {
                            TextButton(onClick = { editor.removeCurrentArtboard() }) { Text("削除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = abNameText,
                        onValueChange = { abNameText = it },
                        label = { Text("名前") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    TextButton(onClick = {
                        val vb = editor.doc.viewBox
                        editor.addArtboard(abNameText.takeIf { it.isNotBlank() } ?: "Page ${editor.doc.artboards.size + 1}", vb.minX, vb.minY, vb.width, vb.height)
                        abNameText = ""
                    }) { Text("追加", style = MaterialTheme.typography.labelSmall) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        val cur = editor.currentArtboard() ?: return@TextButton
                        editor.renameCurrentArtboard(abNameText.takeIf { it.isNotBlank() } ?: cur.name)
                        abNameText = ""
                    }) { Text("名前変更", style = MaterialTheme.typography.labelSmall) }
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
                // スタイラス
                var pressureOn by remember { mutableStateOf(editor.usePressure) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(pressureOn, onCheckedChange = { pressureOn = it; editor.usePressure = it })
                    Text("筆圧を利用（スタイラス・フリーハンド）", style = MaterialTheme.typography.bodySmall)
                }
                // ガイド一覧（ロック・表示・削除）
                Text("ガイド (${editor.guides.size})", style = MaterialTheme.typography.labelMedium)
                // 斜めガイド（任意角度の基準線）の追加
                var agAngle by remember { mutableStateOf("45") }
                var agX by remember { mutableStateOf("0") }
                var agY by remember { mutableStateOf("0") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = agAngle, onValueChange = { agAngle = it },
                        label = { Text("角度") },
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.width(72.dp), singleLine = true,
                    )
                    OutlinedTextField(
                        value = agX, onValueChange = { agX = it },
                        label = { Text("X") },
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.width(72.dp), singleLine = true,
                    )
                    OutlinedTextField(
                        value = agY, onValueChange = { agY = it },
                        label = { Text("Y") },
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.width(72.dp), singleLine = true,
                    )
                    TextButton(onClick = {
                        val a = dev.svgpro.core.util.Numbers.parse(agAngle)
                        val x = dev.svgpro.core.util.Numbers.parse(agX)
                        val y = dev.svgpro.core.util.Numbers.parse(agY)
                        if (a != null && x != null && y != null) {
                            editor.addGuideSnapped(dev.svgpro.core.model.Guide.angled(a, dev.svgpro.core.geom.Vec(x, y)))
                        }
                    }) { Text("斜めを追加", style = MaterialTheme.typography.labelSmall) }
                }
                if (editor.guides.isEmpty()) {
                    Text("ルーラーからドラッグしてガイドを作成できます", style = MaterialTheme.typography.bodySmall)
                }
                editor.guides.forEachIndexed { i, g ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when {
                                g.isAngled -> "A ${dev.svgpro.core.util.Numbers.format(g.angle ?: 0.0, 1)}° @(${dev.svgpro.core.util.Numbers.format(g.point?.x ?: 0.0, 1)}, ${dev.svgpro.core.util.Numbers.format(g.point?.y ?: 0.0, 1)})"
                                g.horizontal -> "H ${dev.svgpro.core.util.Numbers.format(g.pos, 1)}"
                                else -> "V ${dev.svgpro.core.util.Numbers.format(g.pos, 1)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
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

/**
 * SVGソースコード直接編集ダイアログ。
 * ドキュメント全体をXML文字列として編集・適用できる。
 */
@Composable
fun SvgSourceDialog(
    editor: Editor,
    onDismiss: () -> Unit,
) {
    var source by remember { mutableStateOf(editor.svgSource()) }
    LaunchedEffect(editor.svgSource()) { source = editor.svgSource() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SVGソースコード編集") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().widthIn(min = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "SVGコードを直接編集し、適用ボタンでドキュメントに反映します。不正なSVGは適用されません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(
                    value = source,
                    onValueChange = { source = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp),
                    minLines = 12,
                    maxLines = 24,
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (editor.replaceSvgSource(source)) {
                    onDismiss()
                }
            }) { Text("適用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/**
 * SVG最適化ダイアログ。
 * 検証結果（ID重複・ダングリング参照・未使用def）を表示し、最適化を実行する。
 */
@Composable
fun SvgOptimizeDialog(
    editor: Editor,
    onDismiss: () -> Unit,
) {
    var result by remember { mutableStateOf<SvgValidationResult?>(null) }
    val validation by remember(result) { mutableStateOf(result) }

    fun runOptimize() {
        result = SvgOptimizer.validate(null)
    }

    LaunchedEffect(Unit) { runOptimize() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SVG最適化・検証") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val v = validation
                if (v == null) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                } else {
                    Text(
                        "状態: ${if (v.isValid) "正常" else "問題あり（最適化で修正可能）"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (v.isValid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    if (v.duplicateIds.isNotEmpty()) {
                        Text("ID重複: ${v.duplicateIds.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (v.danglingRefs.isNotEmpty()) {
                        Text("未解決参照: ${v.danglingRefs.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (v.unusedDefIds.isNotEmpty()) {
                        Text("未使用定義: ${v.unusedDefIds.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (v.isValid && v.duplicateIds.isEmpty() && v.danglingRefs.isEmpty() && v.unusedDefIds.isEmpty()) {
                        Text("最適化対象はありません。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            val v = validation
            if (v == null || v.isValid) {
                TextButton(onClick = { onDismiss() }, enabled = false) { Text("最適化") }
            } else {
                TextButton(onClick = { runOptimize(); onDismiss() }) { Text("最適化して閉じる") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** キーボードショートカット一覧ダイアログ。 */
@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("キーボードショートカット") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().widthIn(min = 340.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shortcutSection("ファイル", listOf(
                    "Ctrl+S" to "保存",
                    "Ctrl+Z" to "元に戻す",
                    "Ctrl+Shift+Z / Ctrl+Y" to "やり直す",
                    "Delete" to "削除",
                    "Ctrl+D" to "複製",
                ))
                shortcutSection("選択・グループ", listOf(
                    "Ctrl+A" to "全選択",
                    "Ctrl+G / Ctrl+Shift+G" to "グループ化 / グループ解除",
                    "Ctrl+J" to "変形の繰り返し",
                    "矢印キー" to "ナッジ（Shiftで10倍）",
                    "Ctrl+R" to "ルーラー表示",
                ))
                shortcutSection("ツール切替", listOf(
                    "V / A / P / N" to "選択 / アンカー / ペン / フリーハンド",
                    "R / O / L / T" to "長方形 / 楕円 / 直線 / テキスト",
                    "I / H / G" to "スポイト / 手のひら / グラデーション",
                ))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
fun shortcutSection(title: String, items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        items.forEach { (key, desc) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(key, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(130.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** キーボードショートカットカスタマイズダイアログ。 */
@Composable
fun ShortcutCustomizeDialog(editor: Editor, onDismiss: () -> Unit) {
    var editingAction by remember { mutableStateOf<String?>(null) }
    val actions = listOf(
        "undo" to "元に戻す", "redo" to "やり直す", "save" to "保存",
        "copy" to "コピー", "cut" to "カット", "paste" to "ペースト",
        "delete" to "削除", "duplicate" to "複製", "selectAll" to "全選択",
        "invertSelection" to "選択反転", "group" to "グループ化", "ungroup" to "グループ解除",
        "rulers" to "ルーラー表示", "grid" to "グリッド表示", "gridSnap" to "グリッドスナップ",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ショートカットカスタマイズ") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("「設定」を押した後、割り当てたいキーを押してください（Ctrlキーとの組み合わせで登録）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(actions, key = { it.first }) { (action, label) ->
                        val cur = editor.customKeyBindings[action]
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(cur?.toString() ?: "未設定", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
                            Button(
                                onClick = { editingAction = action },
                                enabled = editingAction == null,
                            ) {
                                Text(if (editingAction == action) "キー入力待ち…" else "設定")
                            }
                        }
                        Divider()
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** 長押しコンテキストメニュー（ポップアップ形式）。 */
@Composable
fun ContextMenu(
    editor: Editor,
    screenX: Float,
    screenY: Float,
    origin: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
    onDismiss: () -> Unit,
) {
    // メニュー項目: (ラベル, アイコン, アクション, 有効か)
    val items = listOf(
        Triple4("コピー", AppIcons.ContentCopy, { editor.copySelection() }, editor.selection.isNotEmpty()),
        Triple4("カット", AppIcons.ContentCut, { editor.cutSelection() }, editor.selection.isNotEmpty()),
        Triple4("ペースト", AppIcons.ContentPaste, { editor.pasteFromClipboard(inPlace = false) }, editor.clipboard.isNotEmpty()),
        Triple4("削除", AppIcons.Delete, { editor.deleteSelection() }, editor.selection.isNotEmpty()),
        Triple4("複製", AppIcons.Plus, { editor.duplicateSelection() }, editor.selection.isNotEmpty()),
        Triple4("グループ化", AppIcons.Group, { editor.groupSelection() }, editor.selection.size >= 2),
        Triple4("グループ解除", AppIcons.Ungroup, { editor.ungroupSelection() }, editor.selection.any { it.tag == "g" }),
        Triple4("全選択", null, {
            editor.selectAllInBox(
                dev.svgpro.core.geom.Bounds(
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                ),
                additive = false,
            )
        }, true),
    )

    // 長押しした位置（キャンバス内px + キャンバスのルート内位置）にメニューを表示する。
    // 以前はoffset未指定で常に左上に表示されるため、指元で操作できない欠陥があった。
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 外側タップで閉じる
        Box(
            Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
        )
        val rawX = with(density) { (origin.x + screenX).toDp() }
        val rawY = with(density) { (origin.y + screenY).toDp() }
        val menuW = 180.dp
        val menuH = 300.dp
        val x = rawX.coerceIn(0.dp, (maxWidth - menuW).coerceAtLeast(0.dp))
        val y = rawY.coerceIn(0.dp, (maxHeight - menuH).coerceAtLeast(0.dp))
        Box(
            modifier = Modifier
                .offset(x = x, y = y)
                .width(menuW),
        ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = item.fourth, onClick = { item.third(); onDismiss() }),
                    ) {
                        Row(
                            Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .width(156.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (item.second != null) {
                                Icon(
                                    item.second, null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (item.fourth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                            }
                            Text(
                                item.first,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (item.fourth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/** パラメトリック図形生成ダイアログ（カテゴリ17）。 */
@Composable
fun ParametricShapeDialog(editor: Editor, onDismiss: () -> Unit) {
    var shapeType by remember { mutableStateOf("heart") }
    var sizeText by remember { mutableStateOf("100") }
    var teethText by remember { mutableStateOf("8") }
    var turnsText by remember { mutableStateOf("2") }
    var ampText by remember { mutableStateOf("20") }

    fun create() {
        val cx = 100.0
        val cy = 100.0
        val pd = when (shapeType) {
            "heart" -> {
                val s = dev.svgpro.core.util.Numbers.parse(sizeText) ?: 100.0
                editor.heartPath(cx, cy, s)
            }
            "gear" -> {
                val t = dev.svgpro.core.util.Numbers.parse(teethText) ?: 8
                val o = dev.svgpro.core.util.Numbers.parse(sizeText) ?: 60.0
                val i = o * 0.6
                editor.gearPath(cx, cy, t.toInt(), o, i)
            }
            "spiral" -> {
                val turns = dev.svgpro.core.util.Numbers.parse(turnsText) ?: 2.0
                val r = dev.svgpro.core.util.Numbers.parse(sizeText) ?: 60.0
                editor.spiralPath(cx, cy, turns, r)
            }
            "wave" -> {
                val w = dev.svgpro.core.util.Numbers.parse(sizeText) ?: 120.0
                val waves = dev.svgpro.core.util.Numbers.parse(turnsText) ?: 3.0
                val amp = dev.svgpro.core.util.Numbers.parse(ampText) ?: 20.0
                editor.wavePath(cx, cy, w, waves, amp)
            }
            else -> editor.heartPath(cx, cy, 100.0)
        }
        val el = dev.svgpro.core.svg.Element("path")
        el.setAttr("d", pd.write())
        editor.applyCurrentStyle(el)
        editor.addElement(el, "parametric ${shapeType}")
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("パラメトリック図形生成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("種類", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                    listOf("heart" to "ハート", "gear" to "ギア", "spiral" to "スパイラル", "wave" to "波形").forEach { (v, label) ->
                        AssistChip(
                            onClick = { shapeType = v },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                when (shapeType) {
                    "heart", "gear", "spiral" -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("サイズ", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                            OutlinedTextField(
                                value = sizeText, onValueChange = { sizeText = it },
                                modifier = Modifier.width(80.dp), singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (shapeType == "gear") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("歯数", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                        OutlinedTextField(
                            value = teethText, onValueChange = { teethText = it },
                            modifier = Modifier.width(80.dp), singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (shapeType == "spiral") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("巻き数", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                        OutlinedTextField(
                            value = turnsText, onValueChange = { turnsText = it },
                            modifier = Modifier.width(80.dp), singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (shapeType == "wave") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("波数", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                        OutlinedTextField(
                            value = turnsText, onValueChange = { turnsText = it },
                            modifier = Modifier.width(80.dp), singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("振幅", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                        OutlinedTextField(
                            value = ampText, onValueChange = { ampText = it },
                            modifier = Modifier.width(80.dp), singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::create) { Text("生成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** チャート/グラフ生成ダイアログ（カテゴリ17）。 */
@Composable
fun ChartDialog(editor: Editor, onDismiss: () -> Unit) {
    var chartType by remember { mutableStateOf("bar") }
    var valuesText by remember { mutableStateOf("30,50,70,40,60") }
    var sizeText by remember { mutableStateOf("200") }

    fun create() {
        val cx = 100.0
        val cy = 100.0
        val s = dev.svgpro.core.util.Numbers.parse(sizeText) ?: 100.0
        val rawValues = valuesText.split(",").filter { it.isNotEmpty() }.mapNotNull { dev.svgpro.core.util.Numbers.parse(it) }
        val values = if (rawValues.isEmpty()) listOf(0.5) else rawValues
        val pd = when (chartType) {
            "bar" -> editor.barChartPath(cx, cy, s, s, values)
            "pie" -> editor.pieChartPath(cx, cy, s / 2, s / 4, values)
            "line" -> editor.lineChartPath(cx, cy, s, s, values)
            else -> editor.barChartPath(cx, cy, s, s, values)
        }
        val el = dev.svgpro.core.svg.Element("path")
        el.setAttr("d", pd.write())
        editor.applyCurrentStyle(el)
        editor.addElement(el, "chart ${chartType}")
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("チャート/グラフ生成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("種類", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                    listOf("bar" to "棒グラフ", "pie" to "円グラフ", "line" to "折れ線グラフ").forEach { (v, label) ->
                        AssistChip(
                            onClick = { chartType = v },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("値（カンマ区切り）", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
                    OutlinedTextField(
                        value = valuesText, onValueChange = { valuesText = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("サイズ", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                    OutlinedTextField(
                        value = sizeText, onValueChange = { sizeText = it },
                        modifier = Modifier.width(80.dp), singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "値は0〜100の数値をカンマ区切りで入力（例: 30,50,70,40,60）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = ::create) { Text("生成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** マクロ記録・再生ダイアログ（カテゴリ17）。 */
@Composable
fun MacroRecorderDialog(editor: Editor, onDismiss: () -> Unit) {
    var macroName by remember { mutableStateOf("") }

    fun create() {
        val name = macroName.takeIf { it.isNotBlank() } ?: "マクロ ${editor.macros.size + 1}"
        editor.startMacroRecording(name)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("マクロ記録・再生") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("マクロ名", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                    OutlinedTextField(
                        value = macroName,
                        onValueChange = { macroName = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                if (editor.isRecordingMacro) {
                    Text(
                        "記録中: ${editor.currentMacroName ?: ""}（操作を行うと記録されます）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { editor.stopMacroRecording() }) {
                        Text("記録停止・保存", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    TextButton(onClick = ::create) {
                        Text("記録開始", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Divider()
                Text("保存済みマクロ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (editor.macros.isEmpty()) {
                    Text("マクロはありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    editor.macros.withIndex().forEach { (i, macro) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "${macro.name} (${macro.steps.size}ステップ)",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { editor.playMacro(i) }) {
                                Text("再生", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { editor.deleteMacro(i) }) {
                                Text("削除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** コメント/注釈管理ダイアログ（カテゴリ17）。 */
@Composable
fun CommentDialog(editor: Editor, onDismiss: () -> Unit) {
    var newText by remember { mutableStateOf("") }
    var newAuthor by remember { mutableStateOf("匿名") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("コメント・注釈") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("投稿者", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                    OutlinedTextField(
                        value = newAuthor,
                        onValueChange = { newAuthor = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("コメント", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                    OutlinedTextField(
                        value = newText,
                        onValueChange = { newText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = {
                    if (newText.isNotBlank()) {
                        editor.addComment(100.0, 100.0, newText, newAuthor)
                        newText = ""
                        newAuthor = "匿名"
                    }
                }) {
                    Text("コメントを追加", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(4.dp))
                Divider()
                Text("既存コメント", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (editor.comments.isEmpty()) {
                    Text("コメントはありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    editor.comments.withIndex().forEach { (i, c) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Column {
                                Text("${c.author}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                Text("${dev.svgpro.core.util.Numbers.format(c.x, 0)}, ${dev.svgpro.core.util.Numbers.format(c.y, 0)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(c.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { editor.deleteComment(c.id) }) {
                                Text("削除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** スタイルプリセット管理ダイアログ（カテゴリ6/17）。 */
@Composable
fun StylePresetDialog(editor: Editor, onDismiss: () -> Unit) {
    var presetName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("スタイルプリセット") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("プリセット名", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        if (presetName.isNotBlank()) {
                            editor.saveStylePreset(presetName)
                            presetName = ""
                        }
                    }) {
                        Text("現在のスタイルを保存", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Divider()
                Text("保存済みプリセット", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (editor.stylePresets.isEmpty()) {
                    Text("プリセットはありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    editor.stylePresets.withIndex().forEach { (i, preset) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Column {
                                Text(preset.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                Text("塗り: ${when (preset.fill) { is dev.svgpro.core.model.Paint.None -> "なし"; is dev.svgpro.core.model.Paint.Solid -> dev.svgpro.core.model.Color.toHex(preset.fill.argb); is dev.svgpro.core.model.Paint.Server -> "グラデ" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { editor.applyStylePreset(i) }) {
                                Text("適用", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { editor.deleteStylePreset(i) }) {
                                Text("削除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** ブラシプリセット管理ダイアログ（カテゴリ17）。 */
@Composable
fun BrushPresetDialog(editor: Editor, onDismiss: () -> Unit) {
    var presetName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ブラシプリセット") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("プリセット名", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        if (presetName.isNotBlank()) {
                            editor.saveBrushPreset(presetName)
                            presetName = ""
                        }
                    }) {
                        Text("現在のストロークを保存", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Divider()
                Text("保存済みプリセット", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (editor.brushPresets.isEmpty()) {
                    Text("プリセットはありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    editor.brushPresets.withIndex().forEach { (i, preset) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Column {
                                Text(preset.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                Text("幅 ${dev.svgpro.core.util.Numbers.format(preset.strokeWidth)} / ${preset.strokeCap} / ${preset.strokeJoin}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { editor.applyBrushPreset(i) }) {
                                Text("適用", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { editor.deleteBrushPreset(i) }) {
                                Text("削除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** フォントマネージャーダイアログ（カスタムフォント TTF/OTF の読み込み・管理・プレビュー）。 */
@Composable
fun FontManagerDialog(
    vm: dev.svgpro.EditorViewModel,
    onDismiss: () -> Unit,
    onFontSelected: ((String) -> Unit)? = null,
) {
    var newFamily by remember { mutableStateOf("") }
    var fontUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        fontUri = uri
    }

    fun loadSelectedFont() {
        val uri = fontUri ?: return
        val context = dev.svgpro.AppContextHolder.get() ?: return
        val resolver = context.contentResolver
        val fam = newFamily.takeIf { it.isNotBlank() }
        // URIからファイルパスを取得（file:スキームなら直接パス、それ以外はコピーしてキャッシュ）
        var filePath: String? = null
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            filePath = uri.path
        } else {
            // その他のURI（content://等）は一時ファイルにコピー
            try {
                resolver.openInputStream(uri)?.use { input ->
                    val cache = java.io.File(context.cacheDir, "fonts")
                    cache.mkdirs()
                    val outFile = java.io.File(cache, System.currentTimeMillis().toString() + ".font")
                    java.io.FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                    filePath = outFile.absolutePath
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        filePath?.let {
            if (vm.loadFontFromFile(it, fam)) {
                fontUri = null
                newFamily = ""
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("フォントマネージャー") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(min = 340.dp)
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // フォント追加セクション
                SectionTitle("フォント追加（TTF/OTF）")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = newFamily,
                        onValueChange = { newFamily = it },
                        label = { Text("ファミリ名", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    CompactButton("ファイル選択…", filled = true) {
                        launcher.launch(arrayOf("application/x-font-ttf", "application/x-font-otf", "font/ttf", "font/otf", "*/*"))
                    }
                    CompactButton("読み込み", filled = true) {
                        loadSelectedFont()
                    }
                }
                Text(
                    "TTF/OTFファイルを選択し、ファミリ名を指定して読み込みます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(4.dp))
                Divider()

                // 読み込み済みフォント一覧
                SectionTitle("読み込み済みフォント (${vm.loadedFonts.size})")
                if (vm.loadedFonts.isEmpty()) {
                    Text(
                        "カスタムフォントはありません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(vm.loadedFontFamilies) { _, family ->
                            val typeface: Typeface? = remember(family) {
                                val face = vm.loadedFonts.firstOrNull { it.family == family }
                                face?.filePath?.let { Typeface.createFromFile(it) } ?: null
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // プレビュー（そのフォントで AaBbCc サンプル）
                                Box(
                                    Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
                                ) {
                                    Text(
                                        "Aa",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = if (typeface != null) FontFamily(typeface) else FontFamily.Default,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                                Column {
                                    Text(family, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        "読み込み済み",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                if (onFontSelected != null) {
                                    CompactButton("選択", filled = false) {
                                        onFontSelected(family)
                                    }
                                }
                                CompactButton("削除", filled = false) {
                                    vm.removeFont(family)
                                }
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss() }) { Text("閉じる") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** テキスト→パス変換確認ダイアログ。 */
@Composable
fun FontToPathDialog(
    editor: dev.svgpro.editor.Editor,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("テキストをパスに変換") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "選択中のテキスト要素を、現在のフォント設定でパス（アウトライン）に変換します。",
                    style = MaterialTheme.typography.bodySmall,
                )
                val textCount = editor.selection.count { it.tag == "text" }
                Text(
                    "対象: $textCount 個のテキスト",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "変換後、元のテキストは削除されます。編集できなくなるので注意してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { run { editor.fontToPathSelection(); onDismiss() } },
                content = { Text("変換") }
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** RGB(ARGB) → CMYK（各値 0.0〜1.0）。 */
private fun rgbToCmyk(argb: Int): Triple4<Double, Double, Double, Double> {
    val r = ((argb shr 16) and 0xFF) / 255.0
    val g = ((argb shr 8) and 0xFF) / 255.0
    val b = (argb and 0xFF) / 255.0
    val c = 1.0 - r
    val m = 1.0 - g
    val y = 1.0 - b
    val k = Math.min(Math.min(c, m), y)
    val cc = if (k == 1.0) 0.0 else (c - k) / (1.0 - k)
    val mm = if (k == 1.0) 0.0 else (m - k) / (1.0 - k)
    val yy = if (k == 1.0) 0.0 else (y - k) / (1.0 - k)
    return Triple4(cc, mm, yy, k)
}

/** CMYK（各値 0.0〜1.0） → ARGB。 */
private fun cmykToRgb(c: Double, m: Double, y: Double, k: Double): Int {
    val rr = (255.0 * (1.0 - c) * (1.0 - k)).coerceIn(0.0, 255.0).toInt()
    val gg = (255.0 * (1.0 - m) * (1.0 - k)).coerceIn(0.0, 255.0).toInt()
    val bb = (255.0 * (1.0 - y) * (1.0 - k)).coerceIn(0.0, 255.0).toInt()
    return 0xFF shl 24 or (rr shl 16) or (gg shl 8) or bb
}

/** CMYK用4倍体（Javaレコード相当）。 */
class Triple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/** SVG検証結果のスタブ（未実装のためダミー）。 */
private data class SvgValidationResult(
    val isValid: Boolean = true,
    val duplicateIds: List<String> = emptyList(),
    val danglingRefs: List<String> = emptyList(),
    val unusedDefIds: List<String> = emptyList(),
)

/** SVG最適化のスタブ（未実装のためダミー）。 */
private object SvgOptimizer {
    fun optimize(svg: String?): String? = svg
    fun validate(svg: String?): SvgValidationResult = SvgValidationResult()
}

/** CMYK同期のスタブ（未実装のためノーオペ）。 */
private fun syncCmyk(argb: Int) {}

/** 文章幅横スクロールのスタブ（未実装のためノーオペ修飾子）。 */
private fun Modifier.svgHorizontalScroll(state: Any? = null): Modifier = this

/** 文章スクロール状態のスタブ。 */
private fun svgRememberScrollState(): Any = Unit
