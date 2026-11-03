package dev.svgpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.svgpro.core.animation.AnimationTrack
import dev.svgpro.core.animation.EaseType
import dev.svgpro.core.animation.Keyframe
import dev.svgpro.editor.Editor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * アニメーションタイムラインパネル（キーフレームベース）。
 *
 * アニメーションモード時に右ドック/ボトムシートに表示される。
 *
 * 機能:
 *  - トラック一覧（オブジェクト × キーフレーム）
 *  - 時間軸ルーラー + キーフレームマーカー表示
 *  - 再生ヘッドによる再生位置指示
 *  - 再生/一時停止/停止コントロール
 *  - 選択オブジェクトへのキーフレーム追加（現在値スナップショット）
 *  - トラックの有効/無効・ループ切替
 */
@Composable
fun AnimationPanel(
    editor: Editor,
    refresh: Int,
    onClose: () -> Unit,
) {
    val animating = editor.isKeyframePlaying
    val tracks = editor.animationTracks
    val durationMs = editor.keyframeDurationMs
    val currentTimeMs = editor.keyframeTimeMs

    var selectedTrackId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showMotionPathDialog by remember { mutableStateOf(false) }

    // 再生ループ（30fps）
    val scope = rememberCoroutineScope()
    LaunchedEffect(animating) {
        if (animating) {
            scope.launch {
                while (editor.isKeyframePlaying) {
                    val next = (editor.keyframeTimeMs + 33).coerceAtMost(durationMs.coerceAtLeast(1))
                    editor.seekKeyframeAnimation(next)
                    if (next >= durationMs) {
                        if (tracks.any { it.loop }) {
                            editor.seekKeyframeAnimation(0)
                        } else {
                            editor.stopKeyframeAnimation()
                        }
                    }
                    delay(33)
                }
            }
        }
    }

    // ピクセル/ms 変換（タイムライン描画用）
    val pxPerMs = derivePxPerMs(durationMs)

    Column(Modifier.fillMaxSize()) {
        // ヘッダー
        Row(
            Modifier.fillMaxWidth().height(UiMetrics.PanelHeader).padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "アニメーションタイムライン",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(UiMetrics.MiniButton)) {
                Icon(AppIcons.Close, "パネルを閉じる", modifier = Modifier.size(14.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 再生コントロール
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { editor.toggleKeyframeAnimation() },
                modifier = Modifier.size(UiMetrics.MiniButton),
                enabled = tracks.isNotEmpty(),
            ) {
                Icon(
                    if (animating) AppIcons.StopIcon else AppIcons.PlayIcon,
                    contentDescription = if (animating) "停止" else "再生",
                    modifier = Modifier.size(UiMetrics.IconSmall),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = { editor.stopKeyframeAnimation() },
                modifier = Modifier.size(UiMetrics.MiniButton),
                enabled = animating,
            ) {
                Icon(AppIcons.StopIcon, contentDescription = "停止",
                    modifier = Modifier.size(UiMetrics.IconSmall), tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                "トラック数: ${tracks.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.size(UiMetrics.MiniButton),
                enabled = editor.selection.isNotEmpty(),
            ) {
                Icon(AppIcons.Plus, contentDescription = "キーフレーム追加",
                    modifier = Modifier.size(UiMetrics.IconSmall), tint = MaterialTheme.colorScheme.onSurface)
            }
            TextButton(
                onClick = {
                    editor.addKeyframeForSelection(editor.keyframeTimeMs)
                },
                enabled = editor.selection.isNotEmpty(),
            ) {
                Text("現在値を記録", style = MaterialTheme.typography.labelSmall)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // タイムライン本体
        TimelineCanvas(
            editor = editor,
            tracks = tracks,
            durationMs = durationMs,
            currentTimeMs = currentTimeMs,
            pxPerMs = pxPerMs,
            selectedTrackId = selectedTrackId,
            onTrackClick = { selectedTrackId = it },
            onKeyframeClick = { trackId, timeMs ->
                selectedTrackId = trackId
                editor.seekKeyframeAnimation(timeMs)
            },
            onAddKeyframe = { trackId, timeMs ->
                val track = tracks.firstOrNull { it.id == trackId }
                val target = track?.let { editor.doc.root.findById(it.targetId) }
                if (track != null && target != null) {
                    val props = LinkedHashMap<String, String>()
                    props["x"] = target.attr("x") ?: "0"
                    props["y"] = target.attr("y") ?: "0"
                    props["transform"] = target.attr("transform") ?: ""
                    props["fill"] = target.attr("fill") ?: ""
                    props["opacity"] = target.attr("opacity") ?: "1"
                    props["stroke-width"] = target.attr("stroke-width") ?: "1"
                    val tLong = timeMs
                    val existing = track.keyframes.firstOrNull { it.timeMs == tLong }
                    if (existing != null) {
                        val idx = track.keyframes.indexOf(existing)
                        track.keyframes[idx] = Keyframe(timeMs = tLong, properties = props, easeType = existing.easeType, bezier = existing.bezier)
                    } else {
                        track.keyframes.add(Keyframe(timeMs = tLong, properties = props, easeType = EaseType.LINEAR))
                        track.keyframes.sortBy { it.timeMs }
                    }
                    editor.recomputeKeyframeDuration()
                    editor.saveKeyframesToDocument()
                    editor.notifyChanged()
                }
            },
            onRemoveKeyframe = { trackId, timeMs ->
                editor.removeKeyframe(trackId, timeMs)
            },
            onEditEasing = { trackId, timeMs -> },
        )

        // 選択トラック詳細
        val selectedTrack = tracks.firstOrNull { it.id == selectedTrackId }
        selectedTrack?.let { track ->
            Column(Modifier.padding(10.dp)) {
                Text(
                    "トラック詳細: ${track.targetName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("対象ID", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
                    Text(track.targetId, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("再生時間(ms)", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
                    OutlinedTextField(
                        value = track.durationMs.toString(),
                        onValueChange = {
                            val v = it.toLongOrNull() ?: 2000L
                            val idx = tracks.indexOf(track)
                            tracks[idx] = track.copy(durationMs = v)
                            editor.recomputeKeyframeDuration()
                            editor.saveKeyframesToDocument()
                            editor.notifyChanged()
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = { editor.toggleKeyframeTrackEnabled(track.id) },
                    ) {
                        Icon(
                            if (track.enabled) AppIcons.EyeOpen else AppIcons.EyeClosed,
                            contentDescription = if (track.enabled) "無効化" else "有効化",
                            modifier = Modifier.size(UiMetrics.IconSmall),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = { editor.toggleKeyframeTrackLoop(track.id) },
                    ) {
                        Icon(
                            if (track.loop) AppIcons.RepeatIcon else AppIcons.RepeatOffIcon,
                            contentDescription = if (track.loop) "ループ解除" else "ループ設定",
                            modifier = Modifier.size(UiMetrics.IconSmall),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = { editor.removeKeyframeTrack(track.id) },
                    ) {
                        Icon(AppIcons.Delete, contentDescription = "トラック削除",
                            modifier = Modifier.size(UiMetrics.IconSmall),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(8.dp))

                // モーションブラー / タイムリマッピング
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("モーションブラー", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                    IconButton(
                        onClick = { editor.toggleKeyframeTrackMotionBlur(track.id) },
                    ) {
                        Icon(
                            if (track.motionBlur) AppIcons.RepeatIcon else AppIcons.RepeatOffIcon,
                            contentDescription = if (track.motionBlur) "モーションブラー無効" else "モーションブラー有効",
                            modifier = Modifier.size(UiMetrics.IconSmall),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text("シャッター角度", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                    OutlinedTextField(
                        value = track.shutterAngle.toString(),
                        onValueChange = {
                            editor.setKeyframeTrackShutterAngle(track.id, it.toDoubleOrNull() ?: 180.0)
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))

                // タイムリマッピング
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("再生速度", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                    OutlinedTextField(
                        value = track.timeRemap.toString(),
                        onValueChange = {
                            editor.setKeyframeTrackTimeRemap(track.id, it.toDoubleOrNull() ?: 1.0)
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))

                // 親子関係
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("親トラック", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                    val parentTracks = tracks.filter { it.id != track.id }
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = {},
                        modifier = Modifier.weight(1f),
                    ) {
                        DropdownMenuItem(
                            text = { Text("なし") },
                            onClick = { editor.setKeyframeTrackParent(track.id, null) },
                        )
                        parentTracks.forEach { pt ->
                            DropdownMenuItem(
                                text = { Text(pt.targetName.ifEmpty { "トラック${tracks.indexOf(pt) + 1}" }) },
                                onClick = { editor.setKeyframeTrackParent(track.id, pt.id) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // キーフレーム・イージング編集（現在時刻に最も近いキーフレーム）
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("イージング", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                    val nearestKf = track.keyframes.minByOrNull { Math.abs(it.timeMs - currentTimeMs) }
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = {},
                        modifier = Modifier.weight(1f),
                    ) {
                        listOf(EaseType.LINEAR, EaseType.EASE_IN, EaseType.EASE_OUT, EaseType.EASE_IN_OUT, EaseType.BEZIER).forEach { et ->
                            DropdownMenuItem(
                                text = { Text(et.name) },
                                onClick = {
                                    val kfIdx = track.keyframes.indexOfFirst { it.timeMs == nearestKf?.timeMs }
                                    if (kfIdx >= 0) {
                                        val newKf = track.keyframes[kfIdx].copy(easeType = et)
                                        track.keyframes[kfIdx] = newKf
                                        editor.saveKeyframesToDocument()
                                        editor.notifyChanged()
                                    }
                                },
                            )
                        }
                    }
                    Text("(${nearestKf?.timeMs?.let { it / 1000.0 }?.toString() ?: "-"}s)", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))

                // モーションパス設定
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("モーションパス", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                    Text(track.motionPath?.pathData?.take(20) ?: "なし", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showMotionPathDialog = true }) {
                        Icon(AppIcons.Edit, contentDescription = "モーションパス編集",
                            modifier = Modifier.size(UiMetrics.IconSmall), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { selectedTrackId = null }) {
                        Text("閉じる")
                    }
                }
            }
        }
    }

    // キーフレーム追加ダイアログ（時間指定）
    if (showAddDialog) {
        AddKeyframeDialog(
            editor = editor,
            tracks = tracks,
            onDismiss = { showAddDialog = false },
        )
    }

    // モーションパス設定ダイアログ
    MotionPathDialogSection(
        editor = editor,
        tracks = tracks,
        selectedTrackId = selectedTrackId,
        showMotionPathDialog = showMotionPathDialog,
        onShowMotionPathDialog = { showMotionPathDialog = it },
    )
}

/** タイムライン本体（レイアウトベース描画） */
@Composable
private fun TimelineCanvas(
    editor: Editor,
    tracks: List<AnimationTrack>,
    durationMs: Long,
    currentTimeMs: Long,
    pxPerMs: Float,
    selectedTrackId: String?,
    onTrackClick: (String) -> Unit,
    onKeyframeClick: (String, Long) -> Unit,
    onAddKeyframe: (String, Long) -> Unit,
    onRemoveKeyframe: (String, Long) -> Unit,
    onEditEasing: (String, Long) -> Unit,
) {
    val trackHeight = 44f
    val headerHeight = 32f
    val trackLabelWidth = 120f
    val minTimelineWidth = 600f
    val contentWidth = (durationMs.coerceAtLeast(1).toFloat() * pxPerMs).coerceAtLeast(minTimelineWidth)

    Column(Modifier.fillMaxWidth()) {
        // 時間ルーラー（ヘッダー）
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(headerHeight.dp),
        ) {
            var t = 0L
            val stepMs = deriveStepMs(durationMs)
            while (t <= durationMs) {
                val xPx = t.toFloat() * pxPerMs
                Surface(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .width(1.dp)
                        .heightIn((headerHeight - 4).dp)
                        .offset(x = xPx.dp),
                ) {
                    Box(Modifier.fillMaxSize())
                }
                Text(
                    text = "${t / 1000.0}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(x = (xPx + 4).dp, y = 4.dp),
                )
                t += stepMs
            }
        }

        // トラック行
        tracks.forEachIndexed { index, track ->
            TrackRow(
                editor = editor,
                track = track,
                index = index,
                trackHeight = trackHeight,
                trackLabelWidth = trackLabelWidth,
                durationMs = durationMs,
                currentTimeMs = currentTimeMs,
                pxPerMs = pxPerMs,
                isSelected = selectedTrackId == track.id,
                onClick = { onTrackClick(track.id) },
                onKeyframeClick = { timeMs -> onKeyframeClick(track.id, timeMs) },
                onAddKeyframe = { timeMs -> onAddKeyframe(track.id, timeMs) },
                onRemoveKeyframe = { timeMs -> onRemoveKeyframe(track.id, timeMs) },
                onEditEasing = { timeMs -> onEditEasing(track.id, timeMs) },
            )
        }

        // コンテンツ幅確保（横スクロール用のダミー）
        Spacer(Modifier.width(contentWidth.dp).height(1.dp))
    }
}

/** 1トラックの描画行 */
@Composable
private fun TrackRow(
    editor: Editor,
    track: AnimationTrack,
    index: Int,
    trackHeight: Float,
    trackLabelWidth: Float,
    durationMs: Long,
    currentTimeMs: Long,
    pxPerMs: Float,
    isSelected: Boolean,
    onClick: () -> Unit,
    onKeyframeClick: (Long) -> Unit,
    onAddKeyframe: (Long) -> Unit,
    onRemoveKeyframe: (Long) -> Unit,
    onEditEasing: (Long) -> Unit,
) {
    val rowBg = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else if (index % 2 == 0) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        Color.Transparent
    }

    Surface(
        color = rowBg,
        modifier = Modifier
            .fillMaxWidth()
            .height((trackHeight + 1).dp)
            .clickable { onClick() },
    ) {
        // トラックラベル（左固定）
        Box(
            Modifier
                .width(trackLabelWidth.dp)
                .height(trackHeight.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = track.targetName.ifEmpty { "トラック${index + 1}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = "#${track.targetId.takeLast(4)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        // 再生ヘッド（縦線）
        val playheadX = currentTimeMs.toFloat() * pxPerMs
        Surface(
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .width(1.dp)
                .height(trackHeight.dp)
                .offset(x = playheadX.dp),
        ) {
            Box(Modifier.fillMaxSize())
        }
        Surface(
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .size(8.dp)
                .offset(x = (playheadX - 4).dp, y = (trackHeight / 2 - 4).dp),
        ) {
            Box(Modifier.fillMaxSize())
        }

        // キーフレームマーカー
        for (kf in track.keyframes) {
            val x = kf.timeMs.toFloat() * pxPerMs
            val isCurrent = kf.timeMs == currentTimeMs
            val markerColor = if (isCurrent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            val markerSize = if (isCurrent) 10f else 7f
            Surface(
                color = markerColor,
                modifier = Modifier
                    .size(markerSize.dp)
                    .offset(
                        x = (x - markerSize / 2).dp,
                        y = (trackHeight / 2 - markerSize / 2).dp,
                    ),
            ) {
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

/** 時間ステップ（ルーラー目盛間隔）を導出 */
private fun deriveStepMs(durationMs: Long): Long {
    val seconds = durationMs / 1000.0
    return when {
        seconds <= 2 -> 250
        seconds <= 5 -> 500
        seconds <= 15 -> 1000
        seconds <= 60 -> 5000
        else -> 10000
    }
}

/** ピクセル/ms 比を導出（画面幅に応じて適切なスケール） */
private fun derivePxPerMs(durationMs: Long): Float {
    val seconds = durationMs / 1000.0
    return when {
        seconds <= 2 -> 120f
        seconds <= 5 -> 80f
        seconds <= 15 -> 40f
        seconds <= 60 -> 20f
        else -> 10f
    }
}

/** キーフレーム追加ダイアログ（時間指定） */
@Composable
private fun AddKeyframeDialog(
    editor: Editor,
    tracks: List<AnimationTrack>,
    onDismiss: () -> Unit,
) {
    var trackId by remember { mutableStateOf(tracks.firstOrNull()?.id ?: "") }
    var timeSec by remember { mutableStateOf("1.0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("キーフレーム追加") },
        text = {
            Column(Modifier.width(280.dp)) {
                Text("トラック", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = {},
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tracks.forEach { track ->
                        DropdownMenuItem(
                            text = { Text(track.targetName.ifEmpty { "トラック${tracks.indexOf(track) + 1}" }) },
                            onClick = { trackId = track.id },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("時刻(秒)", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = timeSec,
                    onValueChange = { timeSec = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val track = tracks.firstOrNull { it.id == trackId }
                val target = track?.let { editor.doc.root.findById(it.targetId) }
                if (track != null && target != null) {
                    val t = (timeSec.toDoubleOrNull() ?: 1.0) * 1000L
                    val tLong = t.toLong()
                    val props = LinkedHashMap<String, String>()
                    props["x"] = target.attr("x") ?: "0"
                    props["y"] = target.attr("y") ?: "0"
                    props["transform"] = target.attr("transform") ?: ""
                    props["fill"] = target.attr("fill") ?: ""
                    props["opacity"] = target.attr("opacity") ?: "1"
                    props["stroke-width"] = target.attr("stroke-width") ?: "1"
                    val existing = track.keyframes.firstOrNull { it.timeMs == tLong }
                    if (existing != null) {
                        val idx = track.keyframes.indexOf(existing)
                        track.keyframes[idx] = Keyframe(timeMs = tLong, properties = props, easeType = existing.easeType, bezier = existing.bezier)
                    } else {
                        track.keyframes.add(Keyframe(timeMs = tLong, properties = props, easeType = EaseType.LINEAR))
                        track.keyframes.sortBy { it.timeMs }
                    }
                    editor.recomputeKeyframeDuration()
                    editor.saveKeyframesToDocument()
                    editor.notifyChanged()
                    onDismiss()
                }
            }) {
                Text("追加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

/** モーションパス編集ダイアログ */
@Composable
private fun MotionPathDialog(
    editor: Editor,
    track: AnimationTrack,
    onDismiss: () -> Unit,
) {
    var pathData by remember { mutableStateOf(track.motionPath?.pathData ?: "") }
    var durationSec by remember { mutableStateOf((track.motionPath?.durationMs ?: 2000) / 1000.0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("モーションパス設定") },
        text = {
            Column(Modifier.width(320.dp)) {
                Text("パスデータ (SVG path 形式)", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = pathData,
                    onValueChange = { pathData = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("所要時間(秒)", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = durationSec.toString(),
                    onValueChange = { durationSec = it.toFloatOrNull() ?: 2.0f },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("例: M 0 0 L 100 100 L 200 0", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val durMs = (durationSec * 1000).toLong()
                editor.setKeyframeTrackMotionPath(track.id, pathData, durMs)
                onDismiss()
            }) {
                Text("適用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

/** モーションパス設定ダイアログ表示（AnimationPanel 内で制御） */
@Composable
private fun MotionPathDialogSection(
    editor: Editor,
    tracks: List<AnimationTrack>,
    selectedTrackId: String?,
    showMotionPathDialog: Boolean,
    onShowMotionPathDialog: (Boolean) -> Unit,
) {
    if (showMotionPathDialog) {
        val trackForDialog = tracks.firstOrNull { it.id == selectedTrackId }
        if (trackForDialog != null) {
            MotionPathDialog(
                editor = editor,
                track = trackForDialog,
                onDismiss = { onShowMotionPathDialog(false) },
            )
        }
    }
}
