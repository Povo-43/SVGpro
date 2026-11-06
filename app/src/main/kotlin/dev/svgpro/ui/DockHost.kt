package dev.svgpro.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.svgpro.editor.Editor
import kotlin.math.roundToInt

/**
 * ドック式パネルシステム（Figma / Illustrator 風）。
 *
 * - すべてのパネル（プロパティ/レイヤー/履歴/ナビゲーター）はドックに格納される
 * - パネル同士を重ねてタブ化できる（タブの長押しドラッグで別グループへ移動・分離）
 * - 各タブは閉じられ、メニュー（パネル > …）から再表示できる
 * - ドック幅（右ドック）とグループ高さ / 下部ドック高さはドラッグで変更できる
 */

/** ドックに格納できるパネルの定義 */
data class DockPanelSpec(val id: String, val label: String)

object DockPanels {
    val all = listOf(
        DockPanelSpec("props", "プロパティ"),
        DockPanelSpec("layers", "レイヤー"),
        DockPanelSpec("history", "履歴"),
        DockPanelSpec("navigator", "ナビゲーター"),
    )

    fun label(id: String): String = all.firstOrNull { it.id == id }?.label ?: id
}

/** 重ねてタブ化されたパネル1組 */
data class DockGroupState(val tabs: List<String>, val active: String)

/**
 * ドックレイアウト状態。
 * - groups: 縦に積まれるタブグループの列（expanded時の右ドック）
 * - open/toggle/close: メニューからの表示切替
 * - moveTab: タブを別グループへ移動 / 新グループとして分離
 * - weights: 各グループの高さ比（スプリッターでドラッグ変更）
 */
class DockState {
    val groups = mutableStateListOf<DockGroupState>()
    val weights = mutableStateListOf<Float>()

    /** 右ドックの幅（dp）。左端ハンドルで変更 */
    var widthDp by mutableFloatStateOf(272f)
    /** 下部ドックの高さ（dp）。ハンドルで変更 */
    var bottomHeightDp by mutableFloatStateOf(260f)

    /** 長押しドラッグ中のタブ（パネルid） */
    var draggingTab by mutableStateOf<String?>(null)
        private set
    /** ドラッグ中のポインタ位置（ルート座標） */
    var dragPosition by mutableStateOf(Offset.Zero)
    /** ドラッグ開始時のタブ内つかみオフセット */
    var dragGrabOffset by mutableStateOf(Offset.Zero)
    /** ドラッグ中にハイライト中のグループindex（範囲外=新グループ） */
    var dragHoverGroup by mutableStateOf(-1)
        private set

    /** 最後にアクティブになったパネル（下部ドックの表示用） */
    var activePanel by mutableStateOf("props")
        private set

    init {
        reset()
    }

    /** 既定レイアウト: 1グループに全パネルをタブ化 */
    fun reset() {
        groups.clear()
        groups.add(DockGroupState(listOf("props", "layers", "history", "navigator"), "props"))
        weights.clear()
        weights.add(1f)
        activePanel = "props"
    }

    fun isOpen(id: String): Boolean = groups.any { id in it.tabs }

    /** 開いているパネルを順序通りに並べる（下部ドックのタブ用） */
    fun openPanels(): List<String> = groups.flatMap { it.tabs }

    fun toggle(id: String) {
        if (isOpen(id)) close(id) else open(id)
    }

    /** パネルを開く（末尾グループのタブに追加。無ければ新グループ） */
    fun open(id: String) {
        if (isOpen(id)) {
            activate(id)
            return
        }
        val li = groups.lastIndex
        if (li >= 0) {
            val g = groups[li]
            groups[li] = g.copy(tabs = g.tabs + id, active = id)
        } else {
            groups.add(DockGroupState(listOf(id), id))
        }
        normalizeWeights()
        activePanel = id
    }

    /** パネルを閉じる（タブ削除。空グループは削除） */
    fun close(id: String) {
        val gi = groups.indexOfFirst { id in it.tabs }
        if (gi < 0) return
        val g = groups[gi]
        val tabs = g.tabs - id
        if (tabs.isEmpty()) {
            groups.removeAt(gi)
        } else {
            groups[gi] = g.copy(tabs = tabs, active = if (g.active == id) tabs.first() else g.active)
        }
        normalizeWeights()
        if (activePanel == id) {
            activePanel = openPanels().firstOrNull() ?: "props"
        }
    }

    fun activate(id: String) {
        val gi = groups.indexOfFirst { id in it.tabs }
        if (gi >= 0) {
            groups[gi] = groups[gi].copy(active = id)
        }
        activePanel = id
    }

    // ―――― タブの長押しドラッグ ――――

    fun beginTabDrag(id: String, pointer: Offset, grabOffset: Offset) {
        draggingTab = id
        dragPosition = pointer
        dragGrabOffset = grabOffset
    }

    fun updateTabDrag(pointer: Offset, hoveredGroup: Int) {
        dragPosition = pointer
        dragHoverGroup = hoveredGroup
    }

    /**
     * ドラッグ確定。targetGroup は groups.size のとき「新グループとして分離」。
     * insertBefore は挿入位置の目印タブ（null=末尾）。
     */
    fun endTabDrag(targetGroup: Int, insertBefore: String?) {
        val id = draggingTab ?: return
        draggingTab = null
        dragHoverGroup = -1
        moveTab(id, targetGroup, insertBefore)
    }

    fun cancelTabDrag() {
        draggingTab = null
        dragHoverGroup = -1
    }

    /** タブを別グループへ移動する */
    fun moveTab(id: String, targetGroup: Int, insertBefore: String?) {
        val from = groups.indexOfFirst { id in it.tabs }
        if (from < 0) return
        val old = groups[from]
        val remaining = old.tabs - id
        var removedSource = false
        if (remaining.isEmpty()) {
            groups.removeAt(from)
            removedSource = true
        } else {
            groups[from] = old.copy(tabs = remaining, active = if (old.active == id) remaining.first() else old.active)
        }
        // 移動元グループ削除で index が詰まる分を補正
        var t = targetGroup
        if (removedSource && from < t) t -= 1
        if (t < 0) t = 0
        if (t >= groups.size) {
            groups.add(DockGroupState(listOf(id), id))
        } else {
            val g = groups[t]
            val idx = insertBefore?.let { b -> g.tabs.indexOf(b) }?.takeIf { it >= 0 } ?: g.tabs.size
            val tabs = g.tabs.toMutableList()
            tabs.add(idx.coerceIn(0, tabs.size), id)
            groups[t] = g.copy(tabs = tabs, active = id)
        }
        normalizeWeights()
        activePanel = id
    }

    /** グループ間スプリッターのドラッグで高さ比を変更する */
    fun adjustWeights(topIndex: Int, deltaFraction: Float, minFraction: Float = 0.08f) {
        val bottom = topIndex + 1
        if (topIndex < 0 || bottom >= groups.size) return
        val t = weights.getOrElse(topIndex) { 1f } + deltaFraction
        val b = weights.getOrElse(bottom) { 1f } - deltaFraction
        if (t < minFraction || b < minFraction) return
        weights[topIndex] = t
        weights[bottom] = b
    }

    /** groups の数に合わせて weights を同期 */
    private fun normalizeWeights() {
        while (weights.size < groups.size) weights.add(1f)
        while (weights.size > groups.size) weights.removeAt(weights.lastIndex)
    }
}

// ========================================================================
// パネル内容の共通ディスパッチ
// ========================================================================

@Composable
fun DockPanelContent(
    editor: Editor,
    refresh: Int,
    panel: String,
    onSaveSwatches: () -> Unit,
    onLoadSwatches: () -> Unit,
    customFontFamilies: List<String>,
    onShowFontManager: () -> Unit,
    onReplaceColor: (() -> Unit)? = null,
) {
    when (panel) {
        "layers" -> LayersPanel(editor, refresh)
        "history" -> HistoryPanel(editor, refresh)
        "navigator" -> NavigatorPanel(editor, refresh)
        else -> PropertiesPanel(
            editor,
            refresh,
            onSaveSwatches,
            onLoadSwatches,
            customFontFamilies = customFontFamilies,
            onShowFontManager = onShowFontManager,
            onReplaceColor = onReplaceColor,
        )
    }
}

// ========================================================================
// 右ドック（expanded）
// ========================================================================

/** expanded時の右ドック。縦にタブグループを積み、幅は左ハンドルでリサイズ。 */
@Composable
fun DockHost(
    editor: Editor,
    refresh: Int,
    dock: DockState,
    animationMode: Boolean,
    onCloseAnimation: () -> Unit,
    onSaveSwatches: () -> Unit,
    onLoadSwatches: () -> Unit,
    customFontFamilies: List<String>,
    onShowFontManager: () -> Unit,
    onReplaceColor: (() -> Unit)? = null,
) {
    val groupRects = remember { mutableMapOf<Int, Rect>() }
    val tabRects = remember { mutableMapOf<String, Rect>() }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    var dockHeightPx by remember { mutableStateOf(0f) }

    // ポインタ位置（ルート座標）→ 対象グループindex。範囲外は新グループ（= groups.size）
    fun hoveredGroupAt(pointer: Offset): Int =
        groupRects.entries.firstOrNull { it.value.contains(pointer) }?.key ?: -1

    fun dropTargetAt(pointer: Offset): Int {
        val g = hoveredGroupAt(pointer)
        return if (g >= 0) g else dock.groups.size
    }

    // 挿入位置の目印タブ（ポインタより右にある最初のタブ）
    fun insertBeforeAt(target: Int, pointer: Offset, exclude: String): String? {
        if (target >= dock.groups.size) return null
        return dock.groups[target].tabs.firstOrNull { tid ->
            tid != exclude && (tabRects[tid]?.center?.x ?: Float.MAX_VALUE) > pointer.x
        }
    }

    Box(
        Modifier
            .fillMaxHeight()
            .onGloballyPositioned { hostOrigin = it.positionInRoot() },
    ) {
        Row {
            // 幅リサイズハンドル
            Box(
                Modifier
                    .width(7.dp)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, amount ->
                            change.consume()
                            dock.widthDp = (dock.widthDp + amount).coerceIn(180f, 480f)
                        }
                    }
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(dock.widthDp.dp).fillMaxHeight(),
            ) {
                if (animationMode) {
                    AnimationPanel(editor = editor, refresh = refresh, onClose = onCloseAnimation)
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { dockHeightPx = it.size.height.toFloat() },
                    ) {
                        if (dock.groups.isEmpty()) {
                            EmptyDockHint()
                        }
                        dock.groups.forEachIndexed { gi, group ->
                            if (gi > 0) GroupSplitter(dock, gi, dockHeightPx)
                            DockGroupPanel(
                                index = gi,
                                group = group,
                                dock = dock,
                                editor = editor,
                                refresh = refresh,
                                onSaveSwatches = onSaveSwatches,
                                onLoadSwatches = onLoadSwatches,
                                customFontFamilies = customFontFamilies,
                                onShowFontManager = onShowFontManager,
                                onReplaceColor = onReplaceColor,
                                hoverLookup = ::hoveredGroupAt,
                                dropTargetLookup = ::dropTargetAt,
                                insertBeforeLookup = ::insertBeforeAt,
                                registerTabRect = { tabId, rect -> tabRects[tabId] = rect },
                            )
                        }
                    }
                }
            }
        }

        // ドラッグ中のゴーストタブ
        dock.draggingTab?.let { id ->
            Box(
                Modifier
                    .offset {
                        val p = dock.dragPosition - hostOrigin - dock.dragGrabOffset
                        IntOffset(p.x.roundToInt(), p.y.roundToInt())
                    }
                    .shadow(6.dp, RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(DockPanels.label(id), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 右ドック内の1タブグループ（タブバー + パネル本体）。親Columnのweightで高さ比を持ち、スプリッターで変更される */
@Composable
private fun ColumnScope.DockGroupPanel(
    index: Int,
    group: DockGroupState,
    dock: DockState,
    editor: Editor,
    refresh: Int,
    onSaveSwatches: () -> Unit,
    onLoadSwatches: () -> Unit,
    customFontFamilies: List<String>,
    onShowFontManager: () -> Unit,
    onReplaceColor: (() -> Unit)?,
    hoverLookup: (Offset) -> Int,
    dropTargetLookup: (Offset) -> Int,
    insertBeforeLookup: (Int, Offset, String) -> String?,
    registerTabRect: (String, Rect) -> Unit,
) {
    val isHovered = dock.draggingTab != null && dock.dragHoverGroup == index
    Column(
        Modifier
            .weight(dock.weights.getOrElse(index) { 1f })
            .fillMaxWidth()
            .background(if (isHovered) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent),
    ) {
        // タブバー
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            group.tabs.forEach { id ->
                DockTab(
                    id = id,
                    active = id == group.active,
                    onClick = { dock.activate(id) },
                    onClose = { dock.close(id) },
                    onDragStart = { pointer, grab -> dock.beginTabDrag(id, pointer, grab) },
                    onDragMove = { pointer ->
                        dock.updateTabDrag(pointer, hoverLookup(pointer))
                    },
                    onDragEnd = {
                        val pos = dock.dragPosition
                        val target = dropTargetLookup(pos)
                        dock.endTabDrag(target, insertBeforeLookup(target, pos, id))
                    },
                    registerRect = { rect -> registerTabRect(id, rect) },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f)) {
            DockPanelContent(
                editor = editor,
                refresh = refresh,
                panel = group.active,
                onSaveSwatches = onSaveSwatches,
                onLoadSwatches = onLoadSwatches,
                customFontFamilies = customFontFamilies,
                onShowFontManager = onShowFontManager,
                onReplaceColor = onReplaceColor,
            )
        }
    }
}

/** ドックタブ（クリック=アクティブ化 / ×=閉じる / 長押しドラッグ=移動） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockTab(
    id: String,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onDragStart: (pointer: Offset, grab: Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    registerRect: (Rect) -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Row(
        Modifier
            .height(UiMetrics.PanelHeader)
            .onGloballyPositioned {
                origin = it.positionInRoot()
                registerRect(it.boundsInRoot())
            }
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .background(
                if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .combinedClickable(onClick = onClick)
            .pointerInput(id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { off -> onDragStart(origin + off, off) },
                    onDrag = { change, _ ->
                        change.consume()
                        onDragMove(origin + change.position)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            }
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            DockPanels.label(id),
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onClose, modifier = Modifier.size(22.dp)) {
            Icon(
                AppIcons.Close,
                contentDescription = "${DockPanels.label(id)}パネルを閉じる",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** グループ間の高さリサイズスプリッター */
@Composable
private fun GroupSplitter(dock: DockState, index: Int, dockHeightPx: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .pointerInput(index, dock.groups.size) {
                detectVerticalDragGestures { change, amount ->
                    change.consume()
                    val splitterPx = 8.dp.toPx() * (dock.groups.size - 1)
                    val usable = dockHeightPx - splitterPx
                    if (usable > 200f) {
                        dock.adjustWeights(index - 1, amount / usable)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(2.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** パネルが1つも無いときのプレースホルダ */
@Composable
private fun EmptyDockHint() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("パネルはありません", style = MaterialTheme.typography.labelLarge)
        Text(
            "メニュー → パネル から再表示できます",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ========================================================================
// 下部ドック（compact / medium）
// ========================================================================

/**
 * compact / medium時の下部ドック。開いている全パネルをタブ化し、
 * 上端ハンドルのドラッグで高さを変更できる。
 */
@Composable
fun BottomDock(
    editor: Editor,
    refresh: Int,
    dock: DockState,
    animationMode: Boolean,
    onCloseAnimation: () -> Unit,
    maxDp: Dp,
    onSaveSwatches: () -> Unit,
    onLoadSwatches: () -> Unit,
    customFontFamilies: List<String>,
    onShowFontManager: () -> Unit,
    onReplaceColor: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(minOf(dock.bottomHeightDp, maxDp.value).coerceAtLeast(100f).dp),
    ) {
        Column {
            // 高さリサイズハンドル（上へドラッグで高くなる）
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, amount ->
                                change.consume()
                                // 上へドラッグ（負）で高く。上限は親の制約、下限は100dp
                                dock.bottomHeightDp = (dock.bottomHeightDp - amount)
                                    .coerceAtMost(maxDp.value)
                                    .coerceAtLeast(100f)
                            }
                        },
                )
            }
            if (animationMode) {
                AnimationPanel(
                    editor = editor,
                    refresh = refresh,
                    onClose = onCloseAnimation,
                )
            } else {
                val open = dock.openPanels()
                if (open.isEmpty()) {
                    Box(Modifier.weight(1f)) { EmptyDockHint() }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        open.forEach { id ->
                            BottomDockTab(
                                id = id,
                                active = id == dock.activePanel,
                                onClick = { dock.activate(id) },
                                onClose = { dock.close(id) },
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f)) {
                        DockPanelContent(
                            editor = editor,
                            refresh = refresh,
                            panel = dock.activePanel,
                            onSaveSwatches = onSaveSwatches,
                            onLoadSwatches = onLoadSwatches,
                            customFontFamilies = customFontFamilies,
                            onShowFontManager = onShowFontManager,
                            onReplaceColor = onReplaceColor,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomDockTab(
    id: String,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .height(UiMetrics.PanelHeader)
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .combinedClickable(onClick = onClick)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            DockPanels.label(id),
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onClose, modifier = Modifier.size(22.dp)) {
            Icon(
                AppIcons.Close,
                contentDescription = "${DockPanels.label(id)}パネルを閉じる",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
