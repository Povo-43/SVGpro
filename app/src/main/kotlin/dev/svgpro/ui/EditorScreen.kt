package dev.svgpro.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import dev.svgpro.EditorViewModel
import dev.svgpro.MainActivity
import dev.svgpro.canvas.CanvasView
import dev.svgpro.core.geom.Bounds
import dev.svgpro.core.geom.Vec
import dev.svgpro.core.model.Color
import dev.svgpro.core.model.Paint
import dev.svgpro.core.model.StyleUtil
import dev.svgpro.core.model.TextAttr
import dev.svgpro.core.svg.TokenSource
import dev.svgpro.editor.Editor
import dev.svgpro.editor.invertSelection
import dev.svgpro.editor.renderToBitmap
import dev.svgpro.editor.transformAgain
import dev.svgpro.editor.ToolType
import java.io.BufferedReader

/**
 * メインエディタ画面（Illustrator風ダークUI）。
 *
 * レスポンシブ3段階:
 *  - compact  (<600dp)  : 下部ツールバー + ボトムシートパネル
 *  - medium   (<840dp)  : 左ツールレール + ボトムシートパネル
 *  - expanded (≥840dp)  : 左ツールレール + 右ドックパネル（タブレット横持ち）
 */
@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    val editor = vm.editor
    var refresh by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()

    var pendingTextPos by remember { mutableStateOf<Vec?>(null) }
    var showTextCreate by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDocSettings by remember { mutableStateOf(false) }
    var showNewDoc by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showPanel by remember { mutableStateOf<String?>(null) }
    var showExportPng by remember { mutableStateOf(false) }
    var showReplaceColor by remember { mutableStateOf(false) }
    var showTransform by remember { mutableStateOf(false) }
    var showSvgSource by remember { mutableStateOf(false) }
    var showSvgOptimize by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }
    var showCommandPalette by remember { mutableStateOf(false) }
    var showShortcutCustomize by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuPos by remember { mutableStateOf(FloatArray(2)) }
    var showParametricShape by remember { mutableStateOf(false) }
    var showChart by remember { mutableStateOf(false) }
    var showMacroRecorder by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var showNavigator by remember { mutableStateOf(false) }
    var showStylePreset by remember { mutableStateOf(false) }
    var showBrushPreset by remember { mutableStateOf(false) }
    var showFontManager by remember { mutableStateOf(false) }
    var showFontToPath by remember { mutableStateOf(false) }
    var selectedFontFamily by remember { mutableStateOf<String?>(null) }
    var exportPngUri by remember { mutableStateOf<android.net.Uri?>(null) }

    var showRestoreConfirm by remember { mutableStateOf(false) }

    // 自動バックアップ（クラッシュ復元用）
    LaunchedEffect(Unit) {
        vm.startAutosave(context)
        if (vm.hasBackup) showRestoreConfirm = true
    }

    val canvasView = remember { CanvasView(context) }

    // エディタ変更通知 → UI再構成 + キャンバス再描画。
    // EditorはComposeのstateではないため、refreshカウンタがUI全体の再構成の契機になる。
    // 各コールバックで即座にinvalidateするため、ツール操作・ズーム・Undo等が
    // 再構成を待たずキャンバスへ反映される。
    // ※ canvasView.setup()より先に登録する（setup内の初回fitToPageによる変更を取りこぼさないため）
    LaunchedEffect(Unit) {
        editor.onChanged = {
            refresh++
            canvasView.invalidate()
        }
        editor.onSelectionChanged = {
            refresh++
            canvasView.invalidate()
        }
        editor.onZoomChanged = {
            refresh++
            canvasView.invalidate()
        }
        editor.requestTextInput = { pos ->
            pendingTextPos = pos
            showTextCreate = true
        }
    }
    LaunchedEffect(Unit) {
        canvasView.setup(editor)
        canvasView.onLongPress = { x, y ->
            contextMenuPos[0] = x
            contextMenuPos[1] = y
            showContextMenu = true
        }
    }
    LaunchedEffect(dark) { canvasView.setDarkTheme(dark) }

    // refreshの変化で本文を再構成させる（BackHandlerのdirty等の購読）。
    // キャンバス自体はコールバック内で直接invalidateされるため、ここでは不要。
    LaunchedEffect(refresh) { }

    // ファイルI/O
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openDocument(vm, uri)
    }
    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        if (uri != null) {
            saveToUri(vm, uri, editor)
            vm.currentUri = uri
            editor.documentName = uri.lastPathSegment?.substringAfterLast('/') ?: "untitled.svg"
            editor.markSaved()
        }
    }

    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            exportPngUri = uri
            showExportPng = true
        }
    }

    val imageImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importImage(vm, uri)
    }

    val fontImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            showFontManager = true
        }
    }

    val swatchSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) saveSwatches(editor, uri)
    }
    val swatchLoadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadSwatches(editor, uri)
    }

    val onSaveSwatches = { swatchSaveLauncher.launch("swatches.json") }
    val onLoadSwatches = { swatchLoadLauncher.launch(arrayOf("application/json", "*/*")) }

    fun doSave() {
        val uri = vm.currentUri
        if (uri != null) {
            saveToUri(vm, uri, editor)
            editor.markSaved()
            vm.clearBackup(context)
        } else {
            createLauncher.launch(editor.documentName)
        }
    }

    // 戻るボタン: 未保存なら確認
    BackHandler(enabled = editor.dirty) {
        showExitConfirm = true
    }

    // キーボードショートカット登録
    DisposableEffect(Unit) {
        MainActivity.keyHandler = { event ->
            handleKey(editor, event.keyCode, event) { doSave() }
        }
        onDispose { MainActivity.keyHandler = null }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 840.dp
        val isMedium = !isExpanded && maxWidth >= 600.dp
        val sheetMaxH = maxHeight * 0.44f

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // このスコープでrefreshを購読する。BoxWithConstraintsはsubcompositionのため
            // 親の再構成が伝播しない。ここでキー評価することで、エディタ変更のたびに
            // Column以下が再実行され、下位バーへ新しいrefreshが渡される。
            // （Kotlin 2.0のstrong skippingにより、editor参照だけでは子がスキップされるため
            //   refreshをIntパラメータとして明示的に渡して再構成させる）
            LaunchedEffect(refresh) { canvasView.invalidate() }

            TopBar(
                editor = editor,
                refresh = refresh,
                showMenu = showMenu,
                onMenuDismiss = { showMenu = false },
                onMenuOpen = { showMenu = true },
                onUndo = { editor.undo() },
                onRedo = { editor.redo() },
                onSave = { doSave() },
                onPanel = { name -> showPanel = if (showPanel == name) null else name },
                activePanel = showPanel,
                onNew = { showNewDoc = true },
                onOpen = { openLauncher.launch(arrayOf("image/svg+xml", "text/plain", "text/xml", "*/*")) },
                onSaveAs = { createLauncher.launch(editor.documentName) },
                onExportPng = { pngLauncher.launch("export.png") },
                onReplaceColor = { showReplaceColor = true },
                onTransform = { showTransform = true },
                onSettings = { showDocSettings = true },
                onSvgSource = { showSvgSource = true },
                onSvgOptimize = { showSvgOptimize = true },
                onImageImport = { imageImportLauncher.launch(arrayOf("image/*")) },
                onFontImport = { fontImportLauncher.launch(arrayOf("application/x-font-ttf", "application/x-font-otf", "font/ttf", "font/otf", "*/*")) },
                onShortcuts = { showShortcuts = true },
                onCommandPalette = { showCommandPalette = true },
                onShortcutCustomize = { showShortcutCustomize = true },
                onParametricShape = { showParametricShape = true },
                onChart = { showChart = true },
                onMacroRecorder = { showMacroRecorder = true },
                onComments = { showComments = true },
                onNavigator = { showPanel = if (showPanel == "navigator") null else "navigator" },
                onStylePreset = { showStylePreset = true },
                onBrushPreset = { showBrushPreset = true },
                onHistory = { showPanel = if (showPanel == "history") null else "history" },
                onFontManager = { showFontManager = true },
                onFontToPath = { showFontToPath = true },
            )

            // ペン作成中は確定バーを出す（Columnスコープで評価し、Rowラムダへは値を渡す。
            // こうするとpenDraftの変化がstrong skippingを介しても確実に反映される）
            val penDraftActive = editor.penDraft != null

            Row(Modifier.weight(1f)) {
                if (isExpanded || isMedium) {
                    ToolRail(editor, refresh, vertical = true)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AndroidView(
                        factory = { canvasView },
                        update = { view -> view.invalidate() },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // ペン作成中の確定/取消（フローティングバー）
                    if (penDraftActive) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 4.dp,
                            shadowElevation = 6.dp,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                        ) {
                            Row(
                                Modifier.padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = { editor.commitPenDraft(true) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Icon(AppIcons.Check, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("閉じて確定", style = MaterialTheme.typography.labelMedium)
                                }
                                FilledTonalButton(
                                    onClick = { editor.commitPenDraft(false) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text("確定", style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(onClick = { editor.cancelPenDraft() }) {
                                    Text("取消", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                if (isExpanded) {
                    SidePanel(
                        editor = editor,
                        refresh = refresh,
                        panel = showPanel,
                        onClose = { showPanel = null },
                        onSaveSwatches = onSaveSwatches,
                        onLoadSwatches = onLoadSwatches,
                        customFontFamilies = vm.loadedFontFamilies,
                        onShowFontManager = { showFontManager = true },
                    )
                }
            }

            // compact: 下部ツールバー
            if (!isExpanded && !isMedium) {
                ToolRail(editor, refresh, vertical = false)
            }

            // compact / medium: ボトムシートパネル
            if (!isExpanded && showPanel != null) {
                SheetPanel(
                    editor = editor,
                    refresh = refresh,
                    panel = showPanel,
                    onClose = { showPanel = null },
                    maxHeight = sheetMaxH,
                    onSaveSwatches = onSaveSwatches,
                    onLoadSwatches = onLoadSwatches,
                    customFontFamilies = vm.loadedFontFamilies,
                    onShowFontManager = { showFontManager = true },
                )
            }

            StatusBar(editor, refresh)
        }

        if (showDocSettings) {
            DocumentSettingsDialog(editor = editor, refresh = refresh, onDismiss = { showDocSettings = false })
        }

        if (showNewDoc) {
            NewDocumentDialog(
                onDismiss = { showNewDoc = false },
                onCreate = { w, h ->
                    editor.newDocument(w, h)
                    vm.currentUri = null
                    editor.documentName = "untitled.svg"
                    showNewDoc = false
                },
            )
        }

        if (showTextCreate) {
            TextInputDialog(
                title = "テキストを追加",
                initial = "",
                onDismiss = { showTextCreate = false; pendingTextPos = null },
            ) { value ->
                val pos = pendingTextPos
                if (pos != null && value.isNotBlank()) {
                    val el = TextAttr.newElement(value, pos, editor.curTextSize)
                    StyleUtil.setProperty(el, "fill", Color.css((editor.curFill as? Paint.Solid)?.argb ?: 0xFF16202A.toInt()))
                    editor.addElement(el, "text")
                }
                showTextCreate = false
                pendingTextPos = null
            }
        }

        if (showTransform) {
            TransformDialog(editor = editor, onDismiss = { showTransform = false })
        }

        if (showReplaceColor) {
            ReplaceColorDialog(editor = editor, onDismiss = { showReplaceColor = false })
        }

        if (showExportPng) {
            val uri = exportPngUri
            ExportPngDialog(
                editor = editor,
                onDismiss = { showExportPng = false },
            ) { size, transparent, selectionOnly, format ->
                if (uri != null) {
                    try {
                        val resolver = dev.svgpro.AppContextHolder.get()?.contentResolver
                        val bmp = editor.renderToBitmap(size, transparent, selectionOnly)
                        val compressFormat = when (format) {
                            ExportFormat.JPEG -> android.graphics.Bitmap.CompressFormat.JPEG
                            ExportFormat.WEBP -> android.graphics.Bitmap.CompressFormat.WEBP
                            else -> android.graphics.Bitmap.CompressFormat.PNG
                        }
                        resolver?.openOutputStream(uri, "wt")?.use { out ->
                            bmp.compress(compressFormat, 95, out)
                        }
                        bmp.recycle()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                showExportPng = false
            }
        }

        if (showSvgSource) {
            SvgSourceDialog(editor = editor, onDismiss = { showSvgSource = false })
        }

        if (showSvgOptimize) {
            SvgOptimizeDialog(editor = editor, onDismiss = { showSvgOptimize = false })
        }

        if (showShortcuts) {
            ShortcutsDialog(onDismiss = { showShortcuts = false })
        }

        if (showShortcutCustomize) {
            ShortcutCustomizeDialog(editor = editor, onDismiss = { showShortcutCustomize = false })
        }

        if (showParametricShape) {
            ParametricShapeDialog(editor = editor, onDismiss = { showParametricShape = false })
        }

        if (showChart) {
            ChartDialog(editor = editor, onDismiss = { showChart = false })
        }

        if (showMacroRecorder) {
            MacroRecorderDialog(editor = editor, onDismiss = { showMacroRecorder = false })
        }

        if (showComments) {
            CommentDialog(editor = editor, onDismiss = { showComments = false })
        }

        if (showStylePreset) {
            StylePresetDialog(editor = editor, onDismiss = { showStylePreset = false })
        }

        if (showBrushPreset) {
            BrushPresetDialog(editor = editor, onDismiss = { showBrushPreset = false })
        }

        if (showFontManager) {
            FontManagerDialog(
                vm = vm,
                onDismiss = { showFontManager = false },
                onFontSelected = { family ->
                    selectedFontFamily = family
                    showFontManager = false
                },
            )
        }

        if (showFontToPath) {
            FontToPathDialog(editor = editor, onDismiss = { showFontToPath = false })
        }

        if (showContextMenu) {
            ContextMenu(
                editor = editor,
                screenX = contextMenuPos[0],
                screenY = contextMenuPos[1],
                onDismiss = { showContextMenu = false },
            )
        }

        if (showCommandPalette) {
            CommandPalette(
                editor = editor,
                onDismiss = { showCommandPalette = false },
                onSave = { doSave() },
                onSaveAs = { createLauncher.launch(editor.documentName) },
                onTransform = { showTransform = true },
                onReplaceColor = { showReplaceColor = true },
                onSvgSource = { showSvgSource = true },
                onSvgOptimize = { showSvgOptimize = true },
                onImageImport = { imageImportLauncher.launch(arrayOf("image/*")) },
                onExportPng = { pngLauncher.launch("export.png") },
            )
        }

        if (showRestoreConfirm) {
            AlertDialog(
                onDismissRequest = { showRestoreConfirm = false },
                title = { Text("バックアップから復元") },
                text = { Text("前回のセッションの未保存データが見つかりました。復元しますか？") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.restoreBackup(context)
                        showRestoreConfirm = false
                    }) { Text("復元する") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        vm.clearBackup(context)
                        showRestoreConfirm = false
                    }) { Text("破棄") }
                },
            )
        }

        if (showExitConfirm) {
            AlertDialog(
                onDismissRequest = { showExitConfirm = false },
                title = { Text("未保存の変更") },
                text = { Text("変更が保存されていません。このまま終了しますか？") },
                confirmButton = {
                    TextButton(onClick = { (context as? android.app.Activity)?.finish() }) { Text("破棄して終了") }
                },
                dismissButton = {
                    TextButton(onClick = { showExitConfirm = false }) { Text("キャンセル") }
                },
            )
        }
    }
}

/** キーボードショートカット */
private fun handleKey(editor: Editor, keyCode: Int, event: KeyEvent, save: () -> Unit): Boolean {
    val meta = event.metaState
    val ctrl = (meta and KeyEvent.META_CTRL_ON) != 0
    val shift = (meta and KeyEvent.META_SHIFT_ON) != 0
    // カスタムショートカット（Ctrl 基本）。editor.customKeyBindings のアクション名->keyCode マップで上書き可能。
    val customAction = editor.customKeyBindings.entries.firstOrNull { it.value == keyCode }?.key
    if (ctrl && customAction != null) {
        when (customAction) {
            "undo" -> { editor.undo(); return true }
            "redo" -> { editor.redo(); return true }
            "save" -> { save(); return true }
            "copy" -> { editor.copySelection(); return true }
            "cut" -> { editor.cutSelection(); return true }
            "paste" -> { editor.pasteFromClipboard(inPlace = false); return true }
            "delete" -> { editor.deleteSelection(); return true }
            "duplicate" -> { editor.duplicateSelection(); return true }
            "selectAll" -> {
                editor.selectAllInBox(
                    Bounds(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
                    additive = false,
                ); return true
            }
            "invertSelection" -> { editor.invertSelection(); return true }
            "group" -> { editor.groupSelection(); return true }
            "ungroup" -> { editor.ungroupSelection(); return true }
            "rulers" -> { editor.showRulers = !editor.showRulers; editor.onChanged(); return true }
            "grid" -> { editor.showGrid = !editor.showGrid; editor.onChanged(); return true }
            "gridSnap" -> { editor.snapToGrid = !editor.snapToGrid; editor.onChanged(); return true }
        }
    }
    return when {
        ctrl && keyCode == KeyEvent.KEYCODE_Z && !shift -> { editor.undo(); true }
        (ctrl && keyCode == KeyEvent.KEYCODE_Z && shift) || (ctrl && keyCode == KeyEvent.KEYCODE_Y) -> { editor.redo(); true }
        ctrl && keyCode == KeyEvent.KEYCODE_S -> { save(); true }
        keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> { editor.deleteSelection(); true }
        ctrl && keyCode == KeyEvent.KEYCODE_C -> { editor.copySelection(); true }
        ctrl && keyCode == KeyEvent.KEYCODE_X -> { editor.cutSelection(); true }
        ctrl && keyCode == KeyEvent.KEYCODE_V -> { editor.pasteFromClipboard(inPlace = false); true }
        ctrl && keyCode == KeyEvent.KEYCODE_D -> { editor.duplicateSelection(); true }
        ctrl && keyCode == KeyEvent.KEYCODE_G && !shift -> { editor.groupSelection(); true }
        ctrl && keyCode == KeyEvent.KEYCODE_G && shift -> { editor.ungroupSelection(); true }
        ctrl && keyCode == KeyEvent.KEYCODE_A -> {
            editor.selectAllInBox(
                Bounds(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
                additive = false,
            )
            true
        }
        ctrl && keyCode == KeyEvent.KEYCODE_J -> { editor.transformAgain(); true }
        ctrl && keyCode == KeyEvent.KEYCODE_R -> { editor.showRulers = !editor.showRulers; editor.onChanged(); true }
        keyCode == KeyEvent.KEYCODE_V && !ctrl -> { editor.tool = ToolType.SELECT; true }
        keyCode == KeyEvent.KEYCODE_A && !ctrl -> { editor.tool = ToolType.NODE; true }
        keyCode == KeyEvent.KEYCODE_P && !ctrl -> { editor.tool = ToolType.PEN; true }
        keyCode == KeyEvent.KEYCODE_N && !ctrl -> { editor.tool = ToolType.FREEHAND; true }
        keyCode == KeyEvent.KEYCODE_R && !ctrl -> { editor.tool = ToolType.RECT; true }
        keyCode == KeyEvent.KEYCODE_O && !ctrl -> { editor.tool = ToolType.ELLIPSE; true }
        keyCode == KeyEvent.KEYCODE_L && !ctrl -> { editor.tool = ToolType.LINE; true }
        keyCode == KeyEvent.KEYCODE_T && !ctrl -> { editor.tool = ToolType.TEXT; true }
        keyCode == KeyEvent.KEYCODE_I && !ctrl -> { editor.tool = ToolType.EYEDROPPER; true }
        keyCode == KeyEvent.KEYCODE_H && !ctrl -> { editor.tool = ToolType.HAND; true }
        keyCode == KeyEvent.KEYCODE_G && !ctrl -> { editor.tool = ToolType.GRADIENT; true }
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT && editor.selection.isNotEmpty() -> { nudge(editor, if (shift) -10.0 else -1.0, 0.0); true }
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && editor.selection.isNotEmpty() -> { nudge(editor, if (shift) 10.0 else 1.0, 0.0); true }
        keyCode == KeyEvent.KEYCODE_DPAD_UP && editor.selection.isNotEmpty() -> { nudge(editor, 0.0, if (shift) -10.0 else -1.0); true }
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN && editor.selection.isNotEmpty() -> { nudge(editor, 0.0, if (shift) 10.0 else 1.0); true }
        else -> false
    }
}

private fun nudge(editor: Editor, dx: Double, dy: Double) {
    val session = editor.beginDrag("nudge", editor.selection)
    editor.moveSelection(Vec(dx, dy))
    editor.endDrag(session)
}

// ―――― 上部バー ――――

@Composable
private fun TopBar(
    editor: Editor,
    refresh: Int,
    showMenu: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSaveAs: () -> Unit,
    onExportPng: () -> Unit,
    onReplaceColor: () -> Unit,
    onTransform: () -> Unit,
    onSettings: () -> Unit,
    onSvgSource: () -> Unit,
    onSvgOptimize: () -> Unit,
    onImageImport: () -> Unit,
    onFontImport: () -> Unit,
    onShortcuts: () -> Unit,
    onCommandPalette: () -> Unit,
    onShortcutCustomize: () -> Unit,
    onParametricShape: () -> Unit,
    onChart: () -> Unit,
    onMacroRecorder: () -> Unit,
    onComments: () -> Unit,
    onNavigator: () -> Unit,
    onStylePreset: () -> Unit,
    onBrushPreset: () -> Unit,
    onHistory: () -> Unit,
    onFontManager: () -> Unit,
    onFontToPath: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onPanel: (String) -> Unit,
    activePanel: String?,
) {
    // refreshはエディタ変更のたびに変化するInt。パラメータ評価によりstrong skippingを
    // 打ち消して再構成が保証され、zoom/dirty/canUndo等の素のフィールド読み取りが更新される
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
        Row(
            Modifier.fillMaxWidth().height(UiMetrics.BarHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ブランドバッジ（IllustratorのAiバッジ相当）
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(UiMetrics.CornerSmall))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "S",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Box {
                IconButton(onClick = onMenuOpen) { Icon(AppIcons.MenuIcon, "メニュー", tint = MaterialTheme.colorScheme.onSurface) }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = onMenuDismiss,
                    modifier = Modifier.width(220.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text("新規ドキュメント") },
                        leadingIcon = { Icon(AppIcons.Plus, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onNew() },
                    )
                    DropdownMenuItem(text = { Text("SVGを開く…") }, onClick = { onMenuDismiss(); onOpen() })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("上書き保存") },
                        leadingIcon = { Icon(AppIcons.Save, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onSave() },
                    )
                    DropdownMenuItem(text = { Text("別名で保存…") }, onClick = { onMenuDismiss(); onSaveAs() })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("コピー") },
                        leadingIcon = { Icon(AppIcons.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                        enabled = editor.selection.isNotEmpty(),
                        onClick = { onMenuDismiss(); editor.copySelection() },
                    )
                    DropdownMenuItem(
                        text = { Text("カット") },
                        leadingIcon = { Icon(AppIcons.ContentCut, null, modifier = Modifier.size(18.dp)) },
                        enabled = editor.selection.isNotEmpty(),
                        onClick = { onMenuDismiss(); editor.cutSelection() },
                    )
                    DropdownMenuItem(
                        text = { Text("ペースト") },
                        leadingIcon = { Icon(AppIcons.ContentPaste, null, modifier = Modifier.size(18.dp)) },
                        enabled = editor.clipboard.isNotEmpty(),
                        onClick = { onMenuDismiss(); editor.pasteFromClipboard(inPlace = false) },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("PNG書き出し…") },
                        leadingIcon = { Icon(AppIcons.Save, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onExportPng() },
                    )
                    DropdownMenuItem(text = { Text("数値変形…") }, onClick = { onMenuDismiss(); onTransform() })
                    DropdownMenuItem(text = { Text("色の一括置換…") }, onClick = { onMenuDismiss(); onReplaceColor() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("ドキュメント設定…") }, onClick = { onMenuDismiss(); onSettings() })
                    DropdownMenuItem(text = { Text("ドキュメント複製") }, onClick = { onMenuDismiss(); editor.duplicateDocument() })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("SVGソースコード編集…") },
                        leadingIcon = { Icon(AppIcons.Edit, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onSvgSource() },
                    )
                    DropdownMenuItem(
                        text = { Text("SVG最適化・検証…") },
                        leadingIcon = { Icon(AppIcons.PathOps, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onSvgOptimize() },
                    )
                    DropdownMenuItem(
                        text = { Text("画像をインポート…") },
                        leadingIcon = { Icon(AppIcons.ImageImport, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onImageImport() },
                    )
                    DropdownMenuItem(
                        text = { Text("フォントをインポート…") },
                        leadingIcon = { Icon(AppIcons.Font, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onFontImport() },
                    )
                    DropdownMenuItem(
                        text = { Text("ショートカット一覧") },
                        leadingIcon = { Icon(AppIcons.Shortcuts, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onShortcuts() },
                    )
                    DropdownMenuItem(
                        text = { Text("ショートカットカスタマイズ…") },
                        leadingIcon = { Icon(AppIcons.Edit, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onShortcutCustomize() },
                    )
                    DropdownMenuItem(
                        text = { Text("パラメトリック図形生成…") },
                        leadingIcon = { Icon(AppIcons.More, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onParametricShape() },
                    )
                    DropdownMenuItem(
                        text = { Text("チャート/グラフ生成…") },
                        leadingIcon = { Icon(AppIcons.More, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onChart() },
                    )
                    DropdownMenuItem(
                        text = { Text("コメント/注釈…") },
                        leadingIcon = { Icon(AppIcons.More, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onComments() },
                    )
                    DropdownMenuItem(
                        text = { Text("ナビゲーター") },
                        leadingIcon = { Icon(AppIcons.More, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onNavigator() },
                    )
                    DropdownMenuItem(
                        text = { Text("スタイルプリセット…") },
                        leadingIcon = { Icon(AppIcons.More, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onStylePreset() },
                    )
                    DropdownMenuItem(
                        text = { Text("ブラシプリセット…") },
                        leadingIcon = { Icon(AppIcons.More, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onBrushPreset() },
                    )
                    DropdownMenuItem(
                        text = { Text("フォントマネージャー…") },
                        leadingIcon = { Icon(AppIcons.Font, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onFontManager() },
                    )
                    DropdownMenuItem(
                        text = { Text("選択テキストをパス化") },
                        leadingIcon = { Icon(AppIcons.Path, null, modifier = Modifier.size(18.dp)) },
                        enabled = editor.selection.any { it.tag == "text" },
                        onClick = { onMenuDismiss(); onFontToPath() },
                    )
                    DropdownMenuItem(
                        text = { Text("マクロ記録…") },
                        leadingIcon = { Icon(AppIcons.More, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onMacroRecorder() },
                    )
                    DropdownMenuItem(
                        text = { if (editor.isEditable()) "閲覧専用モードにする" else "編集モードにする" },
                        leadingIcon = { Icon(AppIcons.LockIcon, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); editor.setViewOnlyMode(!editor.isEditable()) },
                    )
                    DropdownMenuItem(
                        text = { Text("コマンドパレット…") },
                        leadingIcon = { Icon(AppIcons.Search, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onCommandPalette() },
                    )
                }
            }

            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(
                    editor.documentName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (editor.dirty) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                    }
                    Text(
                        "${(editor.zoom * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            BarIconButton(AppIcons.Undo, "元に戻す", enabled = editor.undoStack.canUndo, onClick = onUndo)
            BarIconButton(AppIcons.Redo, "やり直す", enabled = editor.undoStack.canRedo, onClick = onRedo)
            BarDivider()
            BarIconButton(
                AppIcons.Layers, "レイヤー",
                active = activePanel == "layers", onClick = { onPanel("layers") },
            )
            BarIconButton(
                AppIcons.Palette, "プロパティ",
                active = activePanel == "props", onClick = { onPanel("props") },
            )
            BarIconButton(
                AppIcons.Edit, "履歴",
                active = activePanel == "history", onClick = { onPanel("history"); onHistory() },
            )
            BarDivider()
            BarIconButton(AppIcons.Save, "保存", onClick = onSave)
        }
        // 上部バーとキャンバスの間のヘアライン
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

@Composable
private fun BarIconButton(
    icon: ImageVector,
    desc: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        else androidx.compose.ui.graphics.Color.Transparent,
        label = "barBg",
    )
    Box(
        Modifier
            .size(UiMetrics.BarButton)
            .clip(RoundedCornerShape(UiMetrics.Corner))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = desc,
            modifier = Modifier.size(UiMetrics.Icon),
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun BarDivider() {
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .width(1.dp)
            .height(16.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

// ―――― ツールレール ――――

private val toolGroups: List<List<Pair<ToolType, ImageVector>>> = listOf(
    listOf(ToolType.SELECT to AppIcons.Select, ToolType.NODE to AppIcons.Node),
    listOf(ToolType.PEN to AppIcons.Pen, ToolType.FREEHAND to AppIcons.Pencil),
    listOf(
        ToolType.RECT to AppIcons.Rect,
        ToolType.RRECT to AppIcons.RRect,
        ToolType.ELLIPSE to AppIcons.Circle,
        ToolType.LINE to AppIcons.Line,
        ToolType.POLYLINE to AppIcons.Polyline,
        ToolType.POLYGON to AppIcons.Polygon,
        ToolType.STAR to AppIcons.Star,
        ToolType.ARC to AppIcons.Arc,
        ToolType.SECTOR to AppIcons.Sector,
        ToolType.SPIRAL to AppIcons.Spiral,
    ),
    listOf(ToolType.TEXT to AppIcons.TextTool),
    listOf(
        ToolType.GRADIENT to AppIcons.GradientTool,
        ToolType.EYEDROPPER to AppIcons.Eyedropper,
        ToolType.HAND to AppIcons.Hand,
    ),
)

private fun toolLabel(t: ToolType): String = when (t) {
    ToolType.SELECT -> "選択"
    ToolType.NODE -> "アンカー"
    ToolType.PEN -> "ペン"
    ToolType.FREEHAND -> "フリーハンド"
    ToolType.RECT -> "長方形"
    ToolType.RRECT -> "角丸長方形"
    ToolType.ELLIPSE -> "楕円"
    ToolType.LINE -> "直線"
    ToolType.POLYLINE -> "ポリライン"
    ToolType.POLYGON -> "多角形"
    ToolType.STAR -> "スター"
    ToolType.ARC -> "円弧"
    ToolType.SECTOR -> "扇形"
    ToolType.SPIRAL -> "螺旋"
    ToolType.TEXT -> "テキスト"
    ToolType.GRADIENT -> "グラデーション"
    ToolType.EYEDROPPER -> "スポイト"
    ToolType.HAND -> "手のひら"
}

/** ツールレール（縦: レール / 横: compact用バー） */
@Composable
fun ToolRail(editor: Editor, refresh: Int, vertical: Boolean) {
    val haptic = LocalHapticFeedback.current
    fun pickTool(t: ToolType) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        editor.tool = t
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        if (vertical) {
            Column(
                Modifier.width(UiMetrics.RailWidth).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                toolGroups.forEachIndexed { gi, group ->
                    if (gi > 0) RailSeparator(vertical = true)
                    group.forEach { (tool, icon) -> ToolButton(editor, refresh, tool, icon, onClick = { pickTool(tool) }) }
                }
                RailSeparator(vertical = true)
                RailToggle(AppIcons.Grid, "グリッド", editor.showGrid, refresh) {
                    editor.showGrid = !editor.showGrid; editor.onChanged()
                }
                RailToggle(AppIcons.Magnet, "グリッドスナップ", editor.snapToGrid, refresh) {
                    editor.snapToGrid = !editor.snapToGrid; editor.onChanged()
                }
                RailToggle(AppIcons.PenOnly, "ペン優先（指を無視）", editor.penOnly, refresh) {
                    editor.penOnly = !editor.penOnly; editor.onChanged()
                }
                RailToggle(AppIcons.Magnet, "距離スナップ", editor.snapToDistance, refresh) {
                    editor.snapToDistance = !editor.snapToDistance; editor.onChanged()
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                toolGroups.forEachIndexed { gi, group ->
                    if (gi > 0) RailSeparator(vertical = false)
                    group.forEach { (tool, icon) -> ToolButton(editor, refresh, tool, icon, onClick = { pickTool(tool) }) }
                }
                RailSeparator(vertical = false)
                RailToggle(AppIcons.Grid, "グリッド", editor.showGrid, refresh) {
                    editor.showGrid = !editor.showGrid; editor.onChanged()
                }
                RailToggle(AppIcons.Magnet, "グリッドスナップ", editor.snapToGrid, refresh) {
                    editor.snapToGrid = !editor.snapToGrid; editor.onChanged()
                }
                RailToggle(AppIcons.PenOnly, "ペン優先（指を無視）", editor.penOnly, refresh) {
                    editor.penOnly = !editor.penOnly; editor.onChanged()
                }
                RailToggle(AppIcons.Magnet, "距離スナップ", editor.snapToDistance, refresh) {
                    editor.snapToDistance = !editor.snapToDistance; editor.onChanged()
                }
                RailToggle(AppIcons.Grid, "ピクセルグリッド", editor.showPixelGrid, refresh) {
                    editor.showPixelGrid = !editor.showPixelGrid; editor.onChanged()
                }
                RailToggle(AppIcons.Outline, "アウトライン表示", editor.showOutlineMode, refresh) {
                    editor.showOutlineMode = !editor.showOutlineMode; editor.onChanged()
                }
            }
        }
    }
}

@Composable
private fun RailSeparator(vertical: Boolean) {
    Box(
        Modifier
            .padding(vertical = if (vertical) 2.dp else 0.dp, horizontal = if (vertical) 0.dp else 2.dp)
            .then(
                if (vertical) Modifier.width(UiMetrics.RailDivider).height(1.dp)
                else Modifier.width(1.dp).height(UiMetrics.RailDivider)
            )
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun ToolButton(editor: Editor, refresh: Int, tool: ToolType, icon: ImageVector, onClick: () -> Unit) {
    // refreshの評価によりstrong skippingを打ち消し、editor.toolの変化を反映する
    val selected = editor.tool == tool
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
        label = "toolBg",
    )
    Box(
        Modifier
            .size(UiMetrics.ToolButton)
            .clip(RoundedCornerShape(UiMetrics.Corner))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = toolLabel(tool),
            modifier = Modifier.size(UiMetrics.Icon),
            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RailToggle(icon: ImageVector, desc: String, active: Boolean, refresh: Int, onToggle: () -> Unit) {
    // refreshによりstrong skippingを打ち消し、activeの変化（editor側フラグ）を反映する
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        else androidx.compose.ui.graphics.Color.Transparent,
        label = "toggleBg",
    )
    Box(
        Modifier
            .size(UiMetrics.ToolButton)
            .clip(RoundedCornerShape(UiMetrics.Corner))
            .background(bg)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = desc,
            modifier = Modifier.size(17.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ―――― パネル ――――

@Composable
private fun panelTitle(panel: String?): String = when (panel) {
    "layers" -> "レイヤー / オブジェクト"
    "history" -> "操作履歴"
    else -> "プロパティ"
}

/** 操作履歴パネル */
@Composable
private fun HistoryPanel(editor: Editor, refresh: Int) {
    val stack = editor.undoStack
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Undo 履歴", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text("可 Undo=${stack.canUndo} Redo=${stack.canRedo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (stack.undoHistory.isEmpty()) {
            Text("履歴はありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            stack.undoHistory.reversed().forEachIndexed { i, cs ->
                val targetSize = stack.undoHistory.size - i // 新しい順に並べた位置から逆算
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        cs.label ?: "操作",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { stack.undoToSize(targetSize.coerceAtLeast(0)) },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("復帰", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Redo 履歴", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text("件数=${stack.redoHistory.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (stack.redoHistory.isEmpty()) {
            Text("redo 履歴はありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            stack.redoHistory.forEachIndexed { i, cs ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        cs.label ?: "操作",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            // redo は最新側から順に適用：redoHistory[0] が最新
                            stack.undoToSize(stack.undoHistory.size + i + 1)
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("復帰", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

/** ナビゲーターパネル（ドキュメント概要＋ビューポート表示）。 */
@Composable
private fun NavigatorPanel(editor: Editor, refresh: Int) {
    val vb = editor.doc.viewBox
    val zoom = editor.zoom
    val vm = editor.viewMatrix
    val viewportW = editor.viewportW.coerceAtLeast(1.0)
    val viewportH = editor.viewportH.coerceAtLeast(1.0)

    // パネル内の描画エリア（サムネイル相当）
    val panelW = 140.0
    val panelH = 100.0

    // ビューポートをパネル座標に変換（viewBox 基準）
    val scale = Math.min(panelW / vb.width.coerceAtLeast(1.0), panelH / vb.height.coerceAtLeast(1.0))
    val viewLeft = (vm.e - vb.minX) * scale
    val viewTop = (vm.f - vb.minY) * scale
    val viewRight = (vm.e + viewportW / zoom - vb.minX) * scale
    val viewBottom = (vm.f + viewportH / zoom - vb.minY) * scale

    Column(
        Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("ナビゲーター", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("${dev.svgpro.core.util.Numbers.format(zoom * 100.0)}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ドキュメント境界＋ビューポートを描画
        Box(
            Modifier
                .size(panelW.dp, panelH.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    // クリック位置をドキュメント座標に変換して中心へパン
                },
        ) {
            // ドキュメント境界線（薄いグレー）
            // ビューポート矩形（青い枠）
            Box(
                Modifier
                    .width(((viewRight - viewLeft).coerceAtLeast(4.0)).toFloat().dp)
                    .height(((viewBottom - viewTop).coerceAtLeast(4.0)).toFloat().dp)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .offset(x = viewLeft.roundToInt().dp, y = viewTop.roundToInt().dp)
            ) {}
        }
    }
}

/** expanded時の右ドックパネル */
@Composable
private fun SidePanel(
    editor: Editor,
    refresh: Int,
    panel: String?,
    onClose: () -> Unit,
    onSaveSwatches: () -> Unit,
    onLoadSwatches: () -> Unit,
    customFontFamilies: List<String>,
    onShowFontManager: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(UiMetrics.SidePanelWidth).fillMaxHeight(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(UiMetrics.PanelHeader).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    panelTitle(panel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(UiMetrics.MiniButton)) {
                    Icon(AppIcons.Close, "パネルを閉じる", modifier = Modifier.size(14.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f)) {
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
                    )
                }
            }
        }
    }
}

/** compact / medium時のボトムシートパネル */
@Composable
private fun SheetPanel(
    editor: Editor,
    refresh: Int,
    panel: String?,
    onClose: () -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp,
    onSaveSwatches: () -> Unit,
    onLoadSwatches: () -> Unit,
    customFontFamilies: List<String>,
    onShowFontManager: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
    ) {
        Column {
            // ハンドル + ヘッダー
            Box(Modifier.fillMaxWidth().padding(top = 5.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(32.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            Row(
                Modifier.fillMaxWidth().height(UiMetrics.PanelHeader).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    panelTitle(panel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(UiMetrics.MiniButton)) {
                    Icon(AppIcons.Close, "パネルを閉じる", modifier = Modifier.size(14.dp))
                }
            }
            Box(Modifier.heightIn(max = maxHeight - 44.dp)) {
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
                    )
                }
            }
        }
    }
}

// ―――― ステータスバー（ズーム操作つき） ――――

@Composable
private fun StatusBar(editor: Editor, refresh: Int) {
    // refreshによりstrong skippingを打ち消し、zoom/選択数/ツール名等の素の読み取りを更新する
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val vb = editor.doc.viewBox
            Text(
                "${dev.svgpro.core.util.Numbers.format(vb.width)} × ${dev.svgpro.core.util.Numbers.format(vb.height)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // アートボード切り替え（1枚以上の時に表示）
            val abCount = editor.doc.artboards.size
            if (abCount > 1) {
                var abIdx by remember { mutableStateOf(editor.currentArtboardIndex.coerceAtLeast(0).coerceAtMost(abCount - 1)) }
                LaunchedEffect(editor.currentArtboardIndex) { abIdx = editor.currentArtboardIndex }
                TextButton(
                    onClick = {
                        val next = (abIdx + 1) % abCount
                        editor.setCurrentArtboard(next)
                    },
                ) {
                    val a = editor.doc.artboards.getOrNull(abIdx) ?: return@TextButton
                    Text("${a.name}", style = MaterialTheme.typography.labelSmall)
                }
            }
            val selCount = editor.selection.size
            Text(
                if (selCount == 0) toolLabel(editor.tool) else "選択: $selCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selCount > 0) {
                val sb = editor.trace.boundsOf(editor.selection)
                if (sb != null) {
                    Text(
                        "X${dev.svgpro.core.util.Numbers.format(sb.x, 1)} Y${dev.svgpro.core.util.Numbers.format(sb.y, 1)} " +
                            "W${dev.svgpro.core.util.Numbers.format(sb.width, 1)} H${dev.svgpro.core.util.Numbers.format(sb.height, 1)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // 幅を制限しないとズームクラスタが画面外へ押し出されるため、
                        // 残り幅に収めて省略する（weightは非fill: 必要幅まで）
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(3f, fill = false),
                    )
                }
                val md = editor.lastMoveDelta
                if (md != Vec.ZERO) {
                    Text(
                        "Δ ${dev.svgpro.core.util.Numbers.format(kotlin.math.hypot(md.x, md.y), 1)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))

            // 表示トグル + ズームクラスター（Illustratorのドキュメントバー風）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MiniZoomButton(
                    AppIcons.Ruler, "ルーラー",
                    active = editor.showRulers,
                ) {
                    editor.showRulers = !editor.showRulers
                    editor.onChanged()
                }
                MiniZoomButton(AppIcons.ZoomOut, "ズームアウト") {
                    editor.zoomBy(1.0 / 1.25, Vec(editor.viewportW / 2.0, editor.viewportH / 2.0))
                }
                Text(
                    "${(editor.zoom * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .widthIn(min = 40.dp)
                        .clip(RoundedCornerShape(UiMetrics.CornerSmall))
                        .clickable { editor.zoomTo100() }
                        .padding(vertical = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                MiniZoomButton(AppIcons.ZoomIn, "ズームイン") {
                    editor.zoomBy(1.25, Vec(editor.viewportW / 2.0, editor.viewportH / 2.0))
                }
                MiniZoomButton(AppIcons.Fit, "全体表示") { editor.fitToPage() }
                MiniZoomButton(AppIcons.RotateCW, "キャンバス回転元に戻す") {
                    editor.setCanvasRotationDeg(0.0)
                }
                MiniZoomButton(AppIcons.RotateCW, "キャンバス回転 -90°") {
                    editor.rotateCanvas(-90.0)
                }
                MiniZoomButton(AppIcons.RotateCW, "キャンバス回転 +90°") {
                    editor.rotateCanvas(90.0)
                }
                Text(
                    if (editor.canvasRotation != 0.0) "回転 ${(editor.canvasRotation).toInt()}°" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
    }
}

@Composable
private fun MiniZoomButton(
    icon: ImageVector,
    desc: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(UiMetrics.MiniButton)
            .clip(RoundedCornerShape(UiMetrics.CornerSmall))
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = desc,
            modifier = Modifier.size(UiMetrics.IconSmall),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ―――― ダイアログ ――――

@Composable
private fun NewDocumentDialog(onDismiss: () -> Unit, onCreate: (Double, Double) -> Unit) {
    var w by remember { mutableStateOf("512") }
    var h by remember { mutableStateOf("512") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新規ドキュメント") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = w, onValueChange = { w = it }, label = { Text("幅") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = h, onValueChange = { h = it }, label = { Text("高さ") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    AssistChip(onClick = { w = "512"; h = "512" }, label = { Text("512") })
                    AssistChip(onClick = { w = "1024"; h = "1024" }, label = { Text("1024") })
                    AssistChip(onClick = { w = "1920"; h = "1080" }, label = { Text("1920×1080") })
                    AssistChip(onClick = { w = "1080"; h = "1920" }, label = { Text("1080×1920") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val wd = dev.svgpro.core.util.Numbers.parse(w) ?: 512.0
                val ht = dev.svgpro.core.util.Numbers.parse(h) ?: 512.0
                onCreate(wd.coerceIn(4.0, 100000.0), ht.coerceIn(4.0, 100000.0))
            }) { Text("作成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

// ファイルI/Oヘルパ

private fun tokenSourceOf(xml: String): TokenSource {
    val queue = dev.svgpro.AndroidTokenSource.tokenQueue(xml)
    return object : TokenSource {
        override fun next(): dev.svgpro.core.svg.Token =
            queue.removeFirstOrNull() ?: dev.svgpro.core.svg.Token.EndDocument
    }
}

fun openDocument(vm: EditorViewModel, uri: android.net.Uri) {
    try {
        val resolver = dev.svgpro.AppContextHolder.get()?.contentResolver ?: return
        val text = resolver.openInputStream(uri)?.use { stream ->
            val input = if (uri.lastPathSegment?.endsWith(".svgz", ignoreCase = true) == true) {
                java.util.zip.GZIPInputStream(stream)
            } else stream
            BufferedReader(java.io.InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        } ?: return
        val doc = dev.svgpro.core.svg.SvgLoader.load(::tokenSourceOf, text)
        if (doc != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "untitled.svg"
            vm.editor.loadDocument(doc, name)
            vm.currentUri = uri
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun saveToUri(vm: EditorViewModel, uri: android.net.Uri, editor: Editor) {
    try {
        val resolver = dev.svgpro.AppContextHolder.get()?.contentResolver ?: return
        resolver.openOutputStream(uri, "wt")?.use { out ->
            val bytes = editor.doc.serialize().toByteArray(Charsets.UTF_8)
            if (uri.lastPathSegment?.endsWith(".svgz", ignoreCase = true) == true) {
                java.util.zip.GZIPOutputStream(out).use { gzos -> gzos.write(bytes) }
            } else {
                out.write(bytes)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/** 画像ファイルを data URI（PNG Base64）として埋め込み image 要素を追加する */
fun importImage(vm: EditorViewModel, uri: android.net.Uri) {
    try {
        val resolver = dev.svgpro.AppContextHolder.get()?.contentResolver ?: return
        resolver.openInputStream(uri)?.use { input ->
            val bmp = android.graphics.BitmapFactory.decodeStream(input) ?: return
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            val el = dev.svgpro.core.svg.Element("image")
            el.setAttr("href", "data:image/png;base64,$base64")
            el.setAttr("xlink:href", "data:image/png;base64,$base64")
            el.setAttr("x", "0")
            el.setAttr("y", "0")
            el.setAttr("width", bmp.width.toString())
            el.setAttr("height", bmp.height.toString())
            vm.editor.addElement(el, "import image")
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/** 検索可能なコマンドパレット（カテゴリ18）。 */
@Composable
private fun CommandPalette(
    editor: Editor,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onTransform: () -> Unit,
    onReplaceColor: () -> Unit,
    onSvgSource: () -> Unit,
    onSvgOptimize: () -> Unit,
    onImageImport: () -> Unit,
    onExportPng: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val all = commandList(
        editor,
        onSave = onSave,
        onSaveAs = onSaveAs,
        onTransform = onTransform,
        onReplaceColor = onReplaceColor,
        onSvgSource = onSvgSource,
        onSvgOptimize = onSvgOptimize,
        onImageImport = onImageImport,
        onExportPng = onExportPng,
    )
    val filtered = if (query.isBlank()) all else all.filter { it.first.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("コマンドパレット") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(min = 340.dp).heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("コマンドを検索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (filtered.isEmpty()) {
                    Text("一致するコマンドはありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        filtered.forEach { (label, shortcut, action) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .clickable { action(); onDismiss() },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    if (shortcut.isNotEmpty()) {
                                        Text(shortcut, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Icon(AppIcons.More, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss() }) { Text("閉じる") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** コマンドパレット用のコマンド一覧（ラベル, ショートカット, アクション）。 */
private fun commandList(
    editor: Editor,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onTransform: () -> Unit,
    onReplaceColor: () -> Unit,
    onSvgSource: () -> Unit,
    onSvgOptimize: () -> Unit,
    onImageImport: () -> Unit,
    onExportPng: () -> Unit,
): List<Triple<String, String, () -> Unit>> {
    return listOf(
        Triple("元に戻す", "Ctrl+Z") { if (editor.undoStack.canUndo) editor.undo() },
        Triple("やり直す", "Ctrl+Shift+Z / Ctrl+Y") { if (editor.undoStack.canRedo) editor.redo() },
        Triple("保存", "Ctrl+S") { onSave() },
        Triple("別名で保存…", "") { onSaveAs() },
        Triple("コピー", "Ctrl+C") { editor.copySelection() },
        Triple("カット", "Ctrl+X") { editor.cutSelection() },
        Triple("ペースト", "Ctrl+V") { editor.pasteFromClipboard(inPlace = false) },
        Triple("削除", "Delete") { editor.deleteSelection() },
        Triple("複製", "Ctrl+D") { editor.duplicateSelection() },
        Triple("全選択", "Ctrl+A") {
            editor.selectAllInBox(
                Bounds(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
                additive = false,
            )
        },
        Triple("選択反転", "") { editor.invertSelection() },
        Triple("グループ化", "Ctrl+G") { editor.groupSelection() },
        Triple("グループ解除", "Ctrl+Shift+G") { editor.ungroupSelection() },
        Triple("数値変形…", "") { onTransform() },
        Triple("色の一括置換…", "") { onReplaceColor() },
        Triple("ルーラー表示", "Ctrl+R") { editor.showRulers = !editor.showRulers; editor.onChanged() },
        Triple("グリッド表示", "") { editor.showGrid = !editor.showGrid; editor.onChanged() },
        Triple("グリッドスナップ", "") { editor.snapToGrid = !editor.snapToGrid; editor.onChanged() },
        Triple("SVGソースコード編集…", "") { onSvgSource() },
        Triple("SVG最適化・検証…", "") { onSvgOptimize() },
        Triple("画像をインポート…", "") { onImageImport() },
        Triple("PNG書き出し…", "") { onExportPng() },
    )
}

/** カラーパレット（スウォッチ）を JSON ファイルに保存する。 */
fun saveSwatches(editor: Editor, uri: android.net.Uri) {
    try {
        val resolver = dev.svgpro.AppContextHolder.get()?.contentResolver ?: return
        val json = "[" + editor.colorSwatches.joinToString(",") { v: Int -> v.toString() } + "]"
        resolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/** カラーパレット（スウォッチ）を JSON ファイルから読み込む。 */
fun loadSwatches(editor: Editor, uri: android.net.Uri) {
    try {
        val resolver = dev.svgpro.AppContextHolder.get()?.contentResolver ?: return
        val text = resolver.openInputStream(uri)?.use { stream ->
            BufferedReader(java.io.InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } ?: return
        val trimmed = text.trim()
        val body = if (trimmed.startsWith("[") && trimmed.endsWith("]")) trimmed.substring(1, trimmed.length - 1) else trimmed
        val values = body.split(",").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        editor.colorSwatches.clear()
        editor.colorSwatches.addAll(values)
        editor.notifyChanged()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
