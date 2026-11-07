package dev.svgpro.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
import dev.svgpro.core.svg.Element
import dev.svgpro.core.svg.TokenSource
import dev.svgpro.editor.Editor
import dev.svgpro.editor.buildDocumentFromSelection
import dev.svgpro.editor.invertSelection
import dev.svgpro.editor.swapFillStrokeSelection
import dev.svgpro.editor.renderToBitmap
import dev.svgpro.editor.exportPdfToUri
import dev.svgpro.editor.selectSameFill
import dev.svgpro.editor.selectSameFont
import dev.svgpro.editor.selectSameKind
import dev.svgpro.editor.selectSameStroke
import dev.svgpro.editor.selectionGaps
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
    val focusManager = LocalFocusManager.current

    var showMenu by remember { mutableStateOf(false) }
    var showNewDoc by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    /** ドック式パネルのレイアウト状態（タブ化・表示切替・リサイズ） */
    val dock = remember { DockState() }
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
    var showTextOnCircle by remember { mutableStateOf(false) }
    var showObjectSearch by remember { mutableStateOf(false) }
    var animationMode by remember { mutableStateOf(false) }
    var selectedFontFamily by remember { mutableStateOf<String?>(null) }
    var exportPngUri by remember { mutableStateOf<android.net.Uri?>(null) }

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showDocSettings by remember { mutableStateOf(false) }
    var showGuideCreate by remember { mutableStateOf(false) }
    var showSvgAttributeEdit by remember { mutableStateOf(false) }

    /** キャンバスビューの画面内位置（インラインテキスト入力の配置に使用） */
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }

    /** 未保存のドキュメントタブを閉じる際の確認対象index */
    var closeTabPending by remember { mutableStateOf<Int?>(null) }

    /** テキストインラインエディタのモード（null=非表示） */
    val textEditState = remember { mutableStateOf<TextEditMode?>(null) }
    var textEditMode by textEditState

    // 自動バックアップ（クラッシュ復元用）+ 最近使ったファイル復元
    LaunchedEffect(Unit) {
        vm.startAutosave(context)
        vm.loadRecentFiles(context)
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
            textEditMode = TextEditMode.Create(pos)
        }
        editor.requestTextEdit = { el ->
            textEditMode = TextEditMode.Edit(el, el.textContent())
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

    // アニメーション再生中は keyframeTimeMs が毎フレーム変化するため、
    // CanvasView を invalidate して onDraw 内の applyAnimationAt を走らせる。
    // （以前は再生中のみ無効化しており、停止中のスクラブがキャンバスに反映されなかった）
    LaunchedEffect(editor.keyframeTimeMs) {
        canvasView.invalidate()
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
        if (uri != null && saveToUri(vm, uri, editor)) {
            vm.currentUri = uri
            editor.documentName = uri.lastPathSegment?.substringAfterLast('/') ?: "untitled.svg"
            editor.markSaved()
            vm.clearBackup(context)
        }
    }

    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            exportPngUri = uri
            showExportPng = true
        }
    }

    val exportSvgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        if (uri != null) {
            exportSelectionToUri(vm, uri, editor)
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            val vb = editor.doc.viewBox
            editor.exportPdfToUri(uri, vb.width.toInt().coerceAtLeast(100), vb.height.toInt().coerceAtLeast(100))
        }
    }

    val imageImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importImage(vm, uri)
    }

    val fontImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 選択されたフォントをキャッシュへコピーして登録してからマネージャーを開く
            //（以前はURIを無視してマネージャーを開くだけの無反応ボタンだった）
            try {
                val resolver = dev.svgpro.AppContextHolder.get()?.contentResolver
                if (resolver != null) {
                    resolver.openInputStream(uri)?.use { input ->
                        val dir = java.io.File(context.cacheDir, "fonts")
                        dir.mkdirs()
                        val base = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                            ?: "font-${System.currentTimeMillis()}"
                        val outFile = java.io.File(dir, base)
                        java.io.FileOutputStream(outFile).use { input.copyTo(it) }
                        vm.loadFontFromFile(outFile.absolutePath)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
        // アニメーション定義をドキュメントに書き戻す（保存前）
        editor.saveAnimationsToDocument()
        val uri = vm.currentUri
        if (uri != null) {
            if (saveToUri(vm, uri, editor)) {
                editor.markSaved()
                vm.clearBackup(context)
            }
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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // フォーカス中の入力欄がある間はグローバルショートカットを抑止する。
            .onFocusChanged { MainActivity.textInputFocused = it.hasFocus },
    ) {
        val isExpanded = maxWidth >= 840.dp
        val isMedium = !isExpanded && maxWidth >= 600.dp
        // 下部ドックの高さ上限（Column内から参照するため先に固定する）
        val bottomDockMaxH = maxHeight * 0.72f

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
                compact = !isExpanded && !isMedium,
                expanded = isExpanded,
                animationMode = animationMode,
                onAnimationModeToggle = { editor.setAnimationMode(!animationMode) },
                showMenu = showMenu,
                onMenuDismiss = { showMenu = false },
                onMenuOpen = { showMenu = true },
                onNew = { showNewDoc = true },
                onOpen = { openLauncher.launch(arrayOf("image/svg+xml", "text/plain", "text/xml", "*/*")) },
                onSave = { doSave() },
                onSaveAs = { createLauncher.launch(editor.documentName) },
                onPanel = { name -> dock.toggle(name) },
                isPanelOpen = { id -> dock.isOpen(id) },
                onFontManager = { showFontManager = true },
                onTextCreate = { textEditMode = TextEditMode.Create(Vec(editor.viewportW / 2, editor.viewportH / 2)) },
                onImageImport = { imageImportLauncher.launch(arrayOf("image/*")) },
                onFontImport = { fontImportLauncher.launch(arrayOf("application/x-font-ttf", "application/x-font-otf", "font/ttf", "font/otf", "*/*")) },
                onSettings = { showDocSettings = true },
                onSvgSource = { showSvgSource = true },
                onSvgOptimize = { showSvgOptimize = true },
                onCommandPalette = { showCommandPalette = true },
                onGuideCreate = { showGuideCreate = true },
                onParametricShape = { showParametricShape = true },
                onChart = { showChart = true },
                onMacroRecorder = { showMacroRecorder = true },
                onComments = { showComments = true },
                onStylePreset = { showStylePreset = true },
                onBrushPreset = { showBrushPreset = true },
                onShortcutCustomize = { showShortcutCustomize = true },
                onObjectSearch = { showObjectSearch = true },
                onSvgAttributeEdit = { showSvgAttributeEdit = true },
                onExportPdf = { pdfLauncher.launch("document.pdf") },
                onTextOnCircle = { showTextOnCircle = true },
                recentFiles = vm.recentFiles,
                onOpenRecent = { uriStr -> openDocument(vm, android.net.Uri.parse(uriStr)) },
                onExportSelection = { exportSvgLauncher.launch("selection.svg") },
                onDuplicateDoc = { vm.duplicateDocumentInTab() },
            )

        // ドキュメントタブ（複数ドキュメント管理。2枚以上で表示）
        if (vm.docTabs.size > 1) {
            DocumentTabBar(
                vm = vm,
                editor = editor,
                refresh = refresh,
                onSelect = { vm.switchDocTab(it) },
                onCloseRequest = { i ->
                    val dirty = if (i == vm.activeDocTab) editor.dirty else (vm.docTabs.getOrNull(i)?.snapshot?.dirty == true)
                    if (dirty) closeTabPending = i else vm.closeDocTab(i)
                },
                onNew = { showNewDoc = true },
            )
        }

            // タブレット・デスクトップ向けPCライクメニューバー（拡張サイズ時のみ表示）
            if (isExpanded) {
                MenuBar(
                    editor = editor,
                    refresh = refresh,
                    onNew = { showNewDoc = true },
                    onOpen = { openLauncher.launch(arrayOf("image/svg+xml", "text/plain", "text/xml", "*/*")) },
                    onSave = { doSave() },
                    onSaveAs = { createLauncher.launch(editor.documentName) },
                    onExportPng = { pngLauncher.launch("export.png") },
                    onUndo = { editor.undo() },
                    onRedo = { editor.redo() },
                    onCopy = { editor.copySelection() },
                    onCut = { editor.cutSelection() },
                    onPaste = { editor.pasteFromClipboard(inPlace = false) },
                    onDelete = { editor.deleteSelection() },
                    onDuplicate = { editor.duplicateSelection() },
                    onSelectAll = {
                        editor.selectAllInBox(
                            Bounds(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
                            additive = false,
                        )
                    },
                    onInvertSelection = { editor.invertSelection() },
                    onGroup = { editor.groupSelection() },
                    onUngroup = { editor.ungroupSelection() },
                    onGrid = { editor.showGrid = !editor.showGrid; editor.onChanged() },
                    onSnap = { editor.snapToGrid = !editor.snapToGrid; editor.onChanged() },
                    onRulers = { editor.showRulers = !editor.showRulers; editor.onChanged() },
                    onOutline = { editor.showOutlineMode = !editor.showOutlineMode; editor.onChanged() },
                    onText = {
                        textEditMode = TextEditMode.Create(Vec(editor.viewportW / 2, editor.viewportH / 2))
                    },
                    onShortcuts = { showShortcuts = true },
                    onFontToPath = { showFontToPath = true },
                    onPanel = { name -> dock.toggle(name) },
                    isPanelOpen = { id -> dock.isOpen(id) },
                    onExportSelection = { exportSvgLauncher.launch("selection.svg") },
                    onParametricShape = { showParametricShape = true },
                    onChart = { showChart = true },
                    onMacroRecorder = { showMacroRecorder = true },
                    onComments = { showComments = true },
                    onStylePreset = { showStylePreset = true },
                    onBrushPreset = { showBrushPreset = true },
                    onShortcutCustomize = { showShortcutCustomize = true },
                )            }

            // ペン作成中は確定バーを出す（Columnスコープで評価し、Rowラムダへは値を渡す。
            // こうするとpenDraftの変化がstrong skippingを介しても確実に反映される）
            val penDraftActive = editor.penDraft != null

            Row(Modifier.weight(1f)) {
                if (isExpanded || isMedium) {
                    ToolRail(editor, refresh, vertical = true)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // キャンバス操作を始めたら入力欄のフォーカスを外し、
                        // 1キーのツール切替などのショートカットを再有効化する。
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                    focusManager.clearFocus()
                                }
                            }
                        },
                ) {
                    AndroidView(
                        factory = { canvasView },
                        update = { view -> view.invalidate() },
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { canvasOrigin = it.positionInRoot() },
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
                if (isExpanded && (animationMode || dock.groups.isNotEmpty())) {
                    DockHost(
                        editor = editor,
                        refresh = refresh,
                        dock = dock,
                        animationMode = animationMode,
                        onCloseAnimation = { editor.setAnimationMode(false) },
                        onSaveSwatches = onSaveSwatches,
                        onLoadSwatches = onLoadSwatches,
                        customFontFamilies = vm.loadedFontFamilies,
                        onShowFontManager = { showFontManager = true },
                        onReplaceColor = { showReplaceColor = true },
                    )
                }
            }

            // compact: 下部ツールバー
            if (!isExpanded && !isMedium) {
                ToolRail(editor, refresh, vertical = false)
            }

            // compact / medium: 下部ドック（タブ化・高さリサイズ可）
            if (!isExpanded && (animationMode || dock.openPanels().isNotEmpty())) {
                BottomDock(
                    editor = editor,
                    refresh = refresh,
                    dock = dock,
                    animationMode = animationMode,
                    onCloseAnimation = { editor.setAnimationMode(false) },
                    maxDp = bottomDockMaxH,
                    onSaveSwatches = onSaveSwatches,
                    onLoadSwatches = onLoadSwatches,
                    customFontFamilies = vm.loadedFontFamilies,
                    onShowFontManager = { showFontManager = true },
                    onReplaceColor = { showReplaceColor = true },
                )
            }

            StatusBar(editor, refresh, compact = !isExpanded && !isMedium)
        }

        if (showDocSettings) {
            DocumentSettingsDialog(editor = editor, refresh = refresh, onDismiss = { showDocSettings = false })
        }

        if (showTextOnCircle) {
            TextOnCircleDialog(editor = editor, onDismiss = { showTextOnCircle = false })
        }

        if (showGuideCreate) {
            GuideCreateDialog(editor = editor, onDismiss = { showGuideCreate = false })
        }

        if (showObjectSearch) {
            ObjectSearchDialog(editor = editor, onDismiss = { showObjectSearch = false })
        }

        if (showSvgAttributeEdit) {
            SvgAttributeDialog(editor = editor, onDismiss = { showSvgAttributeEdit = false })
        }

        if (showNewDoc) {
            NewDocumentDialog(
                onDismiss = { showNewDoc = false },
                onCreate = { w, h ->
                    vm.newDocument(w, h)
                    vm.currentUri = null
                    editor.documentName = "untitled.svg"
                    showNewDoc = false
                },
            )
        }

        val currentEditMode: TextEditMode? = textEditMode
        if (currentEditMode != null) {
            TextInputOverlay(
                editor = editor,
                mode = currentEditMode,
                canvasOrigin = canvasOrigin,
                onDismiss = { textEditMode = null },
                onConfirm = { value ->
                    when (currentEditMode) {
                        is TextEditMode.Create -> {
                            if (value.isNotBlank()) {
                                val pos = currentEditMode.pos
                                val el = TextAttr.newElement(value, pos, editor.curTextSize, editor.curFontFamily)
                                StyleUtil.setProperty(el, "fill", Color.css((editor.curFill as? Paint.Solid)?.argb ?: 0xFF16202A.toInt()))
                                StyleUtil.setProperty(el, "font-weight", editor.curFontWeight)
                                StyleUtil.setProperty(el, "font-style", editor.curFontStyle)
                                editor.addElement(el, "text")
                            }
                        }
                        is TextEditMode.Edit -> {
                            if (currentEditMode.el.tag == "text") {
                                editor.commit("text edit") { batch ->
                                    batch.touch(currentEditMode.el)
                                    currentEditMode.el.setTextContent(value)
                                }
                            }
                        }
                    }
                    textEditMode = null
                },
            )
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
                origin = canvasOrigin,
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
                onExportSelection = { exportSvgLauncher.launch("selection.svg") },
                onGuideCreate = { showGuideCreate = true },
                onParametricShape = { showParametricShape = true },
                onChart = { showChart = true },
                onMacroRecorder = { showMacroRecorder = true },
                onComments = { showComments = true },
                onStylePreset = { showStylePreset = true },
                onBrushPreset = { showBrushPreset = true },
                onShortcutCustomize = { showShortcutCustomize = true },
                onObjectSearch = { showObjectSearch = true },
                onSvgAttributeEdit = { showSvgAttributeEdit = true },
                onPanel = { name -> dock.toggle(name) },
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

        if (closeTabPending != null) {
            AlertDialog(
                onDismissRequest = { closeTabPending = null },
                title = { Text("未保存のドキュメント") },
                text = { Text("このタブには未保存の変更があります。閉じてもよろしいですか？") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.closeDocTab(closeTabPending ?: 0)
                        closeTabPending = null
                    }) { Text("破棄して閉じる") }
                },
                dismissButton = {
                    TextButton(onClick = { closeTabPending = null }) { Text("キャンセル") }
                },
            )
        }
    }
}

/** キーボードショートカット */
private fun handleKey(editor: Editor, keyCode: Int, event: android.view.KeyEvent, save: () -> Unit): Boolean {
    val meta = event.metaState
    val ctrl = (meta and android.view.KeyEvent.META_CTRL_ON) != 0
    val shift = (meta and android.view.KeyEvent.META_SHIFT_ON) != 0
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
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_Z && !shift -> { editor.undo(); true }
        (ctrl && keyCode == android.view.KeyEvent.KEYCODE_Z && shift) || (ctrl && keyCode == android.view.KeyEvent.KEYCODE_Y) -> { editor.redo(); true }
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_S -> { save(); true }
        keyCode == android.view.KeyEvent.KEYCODE_DEL || keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> { editor.deleteSelection(); true }
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_C -> { editor.copySelection(); true }
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_X -> { editor.cutSelection(); true }
        // ペースト（インプレース）: 同じ位置へ貼り付け（Ctrl+Shift+V は通常ペーストより先に判定）
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_V && shift -> { editor.pasteFromClipboard(inPlace = true); true }
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_V -> { editor.pasteFromClipboard(inPlace = false); true }
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_D -> { editor.duplicateSelection(); true }
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_G && !shift -> { editor.groupSelection(); true }
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_G && shift -> { editor.ungroupSelection(); true }
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_A -> {
            editor.selectAllInBox(
                Bounds(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
                additive = false,
            )
            true
        }
        keyCode == android.view.KeyEvent.KEYCODE_J && ctrl -> { editor.transformAgain(); true }
        // Escape: ペン下書きの取消、または選択解除
        keyCode == android.view.KeyEvent.KEYCODE_ESCAPE -> {
            if (editor.penDraft != null) editor.cancelPenDraft() else editor.clearSelection()
            true
        }
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_R -> { editor.showRulers = !editor.showRulers; editor.onChanged(); true }
        // ビュー: Ctrl+0 全体表示 / Ctrl+1 100%
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_0 -> { editor.fitToPage(); true }
        ctrl && keyCode == android.view.KeyEvent.KEYCODE_1 -> { editor.zoomTo100(); true }
        keyCode == android.view.KeyEvent.KEYCODE_V && !ctrl -> { editor.tool = ToolType.SELECT; true }
        keyCode == android.view.KeyEvent.KEYCODE_A && !ctrl -> { editor.tool = ToolType.NODE; true }
        keyCode == android.view.KeyEvent.KEYCODE_P && !ctrl -> { editor.tool = ToolType.PEN; true }
        keyCode == android.view.KeyEvent.KEYCODE_N && !ctrl -> { editor.tool = ToolType.FREEHAND; true }
        keyCode == android.view.KeyEvent.KEYCODE_R && !ctrl -> { editor.tool = ToolType.RECT; true }
        keyCode == android.view.KeyEvent.KEYCODE_O && !ctrl -> { editor.tool = ToolType.ELLIPSE; true }
        keyCode == android.view.KeyEvent.KEYCODE_L && !ctrl -> { editor.tool = ToolType.LINE; true }
        keyCode == android.view.KeyEvent.KEYCODE_T && !ctrl -> { editor.tool = ToolType.TEXT; true }
        keyCode == android.view.KeyEvent.KEYCODE_I && !ctrl -> { editor.tool = ToolType.EYEDROPPER; true }
        keyCode == android.view.KeyEvent.KEYCODE_H && !ctrl -> { editor.tool = ToolType.HAND; true }
        keyCode == android.view.KeyEvent.KEYCODE_M && !ctrl -> { editor.tool = ToolType.MEASURE; true }
        keyCode == android.view.KeyEvent.KEYCODE_G && !ctrl -> { editor.tool = ToolType.GRADIENT; true }
        keyCode == android.view.KeyEvent.KEYCODE_X && !ctrl -> { editor.swapFillStrokeSelection(); true }
        keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && editor.selection.isNotEmpty() -> { nudge(editor, if (shift) -10.0 else -1.0, 0.0); true }
        keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && editor.selection.isNotEmpty() -> { nudge(editor, if (shift) 10.0 else 1.0, 0.0); true }
        keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && editor.selection.isNotEmpty() -> { nudge(editor, 0.0, if (shift) -10.0 else -1.0); true }
        keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && editor.selection.isNotEmpty() -> { nudge(editor, 0.0, if (shift) 10.0 else 1.0); true }
        else -> false
    }
}

private fun nudge(editor: Editor, dx: Double, dy: Double) {
    val session = editor.beginDrag("nudge", editor.selection)
    editor.moveSelection(Vec(dx, dy))
    editor.endDrag(session)
}

// ―――― 上部バー ――――

/** ドキュメントタブバー（複数ドキュメント管理） */
@Composable
private fun DocumentTabBar(
    vm: EditorViewModel,
    editor: Editor,
    refresh: Int,
    onSelect: (Int) -> Unit,
    onCloseRequest: (Int) -> Unit,
    onNew: () -> Unit,
) {
    // refreshによりdirty等の変化を反映
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            vm.docTabs.forEachIndexed { i, tab ->
                val active = i == vm.activeDocTab
                val dirty = if (active) editor.dirty else (tab.snapshot?.dirty == true)
                Row(
                    Modifier
                        .padding(horizontal = 2.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.surfaceContainerHighest
                            else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .clickable { onSelect(i) }
                        .padding(start = 10.dp, end = 2.dp)
                        .height(26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dirty) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        tab.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                    IconButton(onClick = { onCloseRequest(i) }, modifier = Modifier.size(20.dp)) {
                        Icon(
                            AppIcons.Close,
                            contentDescription = "${tab.name} を閉じる",
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(onClick = onNew, modifier = Modifier.size(24.dp)) {
                Icon(AppIcons.Plus, contentDescription = "新しいドキュメントタブ", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TopBar(
    editor: Editor,
    refresh: Int,
    compact: Boolean,
    expanded: Boolean,
    animationMode: Boolean,
    onAnimationModeToggle: () -> Unit,
    showMenu: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onPanel: (String) -> Unit,
    isPanelOpen: (String) -> Boolean,
    onFontManager: () -> Unit,
    onTextCreate: () -> Unit,
    onImageImport: () -> Unit,
    onFontImport: () -> Unit,
    onSettings: () -> Unit,
    onSvgSource: () -> Unit,
    onSvgOptimize: () -> Unit,
    onCommandPalette: () -> Unit,
    recentFiles: List<Pair<String, String>> = emptyList(),
    onOpenRecent: (String) -> Unit = {},
    onExportSelection: () -> Unit = {},
    onDuplicateDoc: () -> Unit = {},
    onGuideCreate: () -> Unit = {},
    onParametricShape: () -> Unit = {},
    onChart: () -> Unit = {},
    onMacroRecorder: () -> Unit = {},
    onComments: () -> Unit = {},
    onStylePreset: () -> Unit = {},
    onBrushPreset: () -> Unit = {},
    onShortcutCustomize: () -> Unit = {},
    onObjectSearch: () -> Unit = {},
    onSvgAttributeEdit: () -> Unit = {},
    onExportPdf: () -> Unit = {},
    onTextOnCircle: () -> Unit = {},
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
                    modifier = Modifier.width(260.dp),
                ) {
                    // ファイル操作（上位の直接アクセスはトップバー右側アイコンへ）
                    SectionTitle("ファイル")
                    DropdownMenuItem(
                        text = { Text("新規ドキュメント") },
                        leadingIcon = { Icon(AppIcons.Plus, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onNew() },
                    )
                    DropdownMenuItem(text = { Text("SVGを開く…") }, onClick = { onMenuDismiss(); onOpen() })
                    if (recentFiles.isNotEmpty()) {
                        HorizontalDivider()
                        SectionTitle("最近使ったファイル")
                        recentFiles.forEach { (name, uriStr) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        name,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = { Icon(AppIcons.Folder, null, modifier = Modifier.size(18.dp)) },
                                onClick = { onMenuDismiss(); onOpenRecent(uriStr) },
                            )
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("上書き保存") },
                        leadingIcon = { Icon(AppIcons.Save, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onSave() },
                    )
                    DropdownMenuItem(text = { Text("別名で保存…") }, onClick = { onMenuDismiss(); onSaveAs() })
                    HorizontalDivider()
                    // 編集
                    SectionTitle("編集")
                    DropdownMenuItem(
                        text = { Text("元に戻す (Ctrl+Z)") },
                        leadingIcon = { Icon(AppIcons.Undo, null, modifier = Modifier.size(18.dp)) },
                        enabled = editor.undoStack.canUndo,
                        onClick = { onMenuDismiss(); editor.undo() },
                    )
                    DropdownMenuItem(
                        text = { Text("やり直す (Ctrl+Shift+Z / Ctrl+Y)") },
                        leadingIcon = { Icon(AppIcons.Redo, null, modifier = Modifier.size(18.dp)) },
                        enabled = editor.undoStack.canRedo,
                        onClick = { onMenuDismiss(); editor.redo() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("コピー (Ctrl+C)") },
                        leadingIcon = { Icon(AppIcons.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                        enabled = editor.selection.isNotEmpty(),
                        onClick = { onMenuDismiss(); editor.copySelection() },
                    )
                    DropdownMenuItem(
                        text = { Text("カット (Ctrl+X)") },
                        leadingIcon = { Icon(AppIcons.ContentCut, null, modifier = Modifier.size(18.dp)) },
                        enabled = editor.selection.isNotEmpty(),
                        onClick = { onMenuDismiss(); editor.cutSelection() },
                    )
                    DropdownMenuItem(
                        text = { Text("ペースト (Ctrl+V)") },
                        leadingIcon = { Icon(AppIcons.ContentPaste, null, modifier = Modifier.size(18.dp)) },
                        enabled = editor.clipboard.isNotEmpty(),
                        onClick = { onMenuDismiss(); editor.pasteFromClipboard(inPlace = false) },
                    )
                    DropdownMenuItem(
                        text = { Text("削除 (Delete)") },
                        leadingIcon = { Icon(AppIcons.Delete, null, modifier = Modifier.size(18.dp)) },
                        enabled = editor.selection.isNotEmpty(),
                        onClick = { onMenuDismiss(); editor.deleteSelection() },
                    )
                    DropdownMenuItem(
                        text = { Text("複製 (Ctrl+D)") },
                        leadingIcon = { Icon(AppIcons.Group, null, modifier = Modifier.size(18.dp)) },
                        enabled = editor.selection.isNotEmpty(),
                        onClick = { onMenuDismiss(); editor.duplicateSelection() },
                    )
                    HorizontalDivider()
                    // 表示
                    SectionTitle("表示")
                    DropdownMenuItem(
                        text = { Text("ルーラー表示 (Ctrl+R)") },
                        leadingIcon = { Icon(AppIcons.Ruler, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); editor.showRulers = !editor.showRulers; editor.onChanged() },
                    )
                    DropdownMenuItem(
                        text = { Text("グリッド表示") },
                        leadingIcon = { Icon(AppIcons.Grid, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); editor.showGrid = !editor.showGrid; editor.onChanged() },
                    )
                    DropdownMenuItem(
                        text = { Text("アウトライン表示") },
                        leadingIcon = { Icon(AppIcons.Outline, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); editor.showOutlineMode = !editor.showOutlineMode; editor.onChanged() },
                    )
                    DropdownMenuItem(
                        text = { Text("アートボード中心にガイド") },
                        leadingIcon = { Icon(AppIcons.Grid, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); editor.addCenterGuides() },
                    )
                    DropdownMenuItem(
                        text = { Text("ガイドを作成…") },
                        leadingIcon = { Icon(AppIcons.Ruler, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onGuideCreate() },
                    )
                    HorizontalDivider()
                    SectionTitle("パネル")
                    DropdownMenuItem(
                        text = { Text("レイヤー / オブジェクト") },
                        leadingIcon = { Icon(AppIcons.Layers, null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (isPanelOpen("layers")) Icon(AppIcons.Check, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = { onMenuDismiss(); onPanel("layers") },
                    )
                    DropdownMenuItem(
                        text = { Text("プロパティ") },
                        leadingIcon = { Icon(AppIcons.Palette, null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (isPanelOpen("props")) Icon(AppIcons.Check, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = { onMenuDismiss(); onPanel("props") },
                    )
                    DropdownMenuItem(
                        text = { Text("操作履歴") },
                        leadingIcon = { Icon(AppIcons.Edit, null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (isPanelOpen("history")) Icon(AppIcons.Check, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = { onMenuDismiss(); onPanel("history") },
                    )
                    DropdownMenuItem(
                        text = { Text("ナビゲーター") },
                        leadingIcon = { Icon(AppIcons.Navigator, null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (isPanelOpen("navigator")) Icon(AppIcons.Check, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = { onMenuDismiss(); onPanel("navigator") },
                    )
                    HorizontalDivider()
                    // ツール・その他
                    SectionTitle("ツール・その他")
                    DropdownMenuItem(
                        text = { Text("テキストを追加 (T)") },
                        leadingIcon = { Icon(AppIcons.TextTool, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onTextCreate() },
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
                        text = { Text("フォントマネージャー…") },
                        leadingIcon = { Icon(AppIcons.Font, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onFontManager() },
                    )
                    HorizontalDivider()
                    // ドキュメント
                    SectionTitle("ドキュメント")
                    DropdownMenuItem(text = { Text("ドキュメント設定…") }, onClick = { onMenuDismiss(); onSettings() })
                    DropdownMenuItem(
                        text = { Text("ドキュメント複製") },
                        onClick = { onMenuDismiss(); onDuplicateDoc() },
                    )
                    HorizontalDivider()
                    // 上級
                    SectionTitle("上級")
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
                        text = { Text("選択をSVGで書き出し…") },
                        leadingIcon = { Icon(AppIcons.Save, null, modifier = Modifier.size(18.dp)) },
                        enabled = editor.selection.isNotEmpty(),
                        onClick = { onMenuDismiss(); onExportSelection() },
                    )
                    DropdownMenuItem(
                        text = { Text("PDF書き出し…") },
                        leadingIcon = { Icon(AppIcons.Save, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onExportPdf() },
                    )
                    DropdownMenuItem(
                        text = { Text("コマンドパレット…") },
                        leadingIcon = { Icon(AppIcons.Search, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onCommandPalette() },
                    )
                    DropdownMenuItem(
                        text = { Text("パラメトリック図形生成…") },
                        onClick = { onMenuDismiss(); onParametricShape() },
                    )
                    DropdownMenuItem(
                        text = { Text("チャート/グラフ生成…") },
                        onClick = { onMenuDismiss(); onChart() },
                    )
                    DropdownMenuItem(
                        text = { Text("マクロ記録・再生…") },
                        onClick = { onMenuDismiss(); onMacroRecorder() },
                    )
                    DropdownMenuItem(
                        text = { Text("コメント/注釈…") },
                        onClick = { onMenuDismiss(); onComments() },
                    )
                    DropdownMenuItem(
                        text = { Text("スタイルプリセット…") },
                        onClick = { onMenuDismiss(); onStylePreset() },
                    )
                    DropdownMenuItem(
                        text = { Text("ブラシプリセット…") },
                        onClick = { onMenuDismiss(); onBrushPreset() },
                    )
                    DropdownMenuItem(
                        text = { Text("ショートカットのカスタマイズ…") },
                        onClick = { onMenuDismiss(); onShortcutCustomize() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { if (editor.isEditable()) "閲覧専用モードにする" else "編集モードにする" },
                        leadingIcon = { Icon(AppIcons.LockIcon, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); editor.setViewOnlyMode(!editor.isEditable()) },
                    )
                    DropdownMenuItem(
                        text = { if (animationMode) "通常モードに戻る" else "アニメーションモードにする" },
                        leadingIcon = { Icon(AppIcons.StopIcon, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onMenuDismiss(); onAnimationModeToggle() },
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

            // ―――― よく使う操作を直接アイコンでアクセス ――――
            // 画面幅が狭いときはメニューに任せ、主要操作だけを残して
            // ボタンが画面外へはみ出して押せなくなるのを防ぐ。
            if (!compact) {
                BarIconButton(AppIcons.Plus, "新規ドキュメント", description = "新しいSVGドキュメントを作成します", onClick = onNew)
                BarIconButton(AppIcons.Folder, "開く", description = "SVGファイルを開きます", onClick = onOpen)
                BarDivider()
            }
            BarIconButton(AppIcons.Undo, "元に戻す", description = "直前の操作を元に戻します（Ctrl+Z）", enabled = editor.undoStack.canUndo, onClick = { editor.undo() })
            BarIconButton(AppIcons.Redo, "やり直す", description = "元に戻した操作をやり直します（Ctrl+Shift+Z）", enabled = editor.undoStack.canRedo, onClick = { editor.redo() })
            BarDivider()
            if (expanded) {
                BarIconButton(AppIcons.ContentCopy, "コピー", description = "選択をコピーします（Ctrl+C）", enabled = editor.selection.isNotEmpty(), onClick = { editor.copySelection() })
                BarIconButton(AppIcons.ContentCut, "カット", description = "選択をカットします（Ctrl+X）", enabled = editor.selection.isNotEmpty(), onClick = { editor.cutSelection() })
                BarIconButton(AppIcons.ContentPaste, "ペースト", description = "クリップボードをペーストします（Ctrl+V）", enabled = editor.clipboard.isNotEmpty(), onClick = { editor.pasteFromClipboard(inPlace = false) })
                BarDivider()
                BarIconButton(AppIcons.Delete, "削除", description = "選択を削除します（Delete）", enabled = editor.selection.isNotEmpty(), onClick = { editor.deleteSelection() })
                BarIconButton(AppIcons.Group, "複製", description = "選択を複製します（Ctrl+D）", enabled = editor.selection.isNotEmpty(), onClick = { editor.duplicateSelection() })
                BarDivider()
            }
            BarIconButton(AppIcons.Save, "保存", description = "現在のドキュメントを上書き保存します（Ctrl+S）", onClick = onSave)
            BarDivider()
            BarIconButton(
                AppIcons.Layers, "レイヤー",
                description = "レイヤー / オブジェクトパネルの表示を切り替え",
                active = isPanelOpen("layers"), onClick = { onPanel("layers") },
            )
            BarIconButton(
                AppIcons.Palette, "プロパティ",
                description = "選択オブジェクトの塗り・線・テキスト等を編集",
                active = isPanelOpen("props"), onClick = { onPanel("props") },
            )
            if (!compact) {
                BarIconButton(
                    AppIcons.Edit, "履歴",
                    description = "操作履歴パネルの表示を切り替え",
                    active = isPanelOpen("history"), onClick = { onPanel("history") },
                )
                BarIconButton(
                    AppIcons.Navigator, "ナビゲーター",
                    description = "ドキュメント全体と表示範囲を確認・移動",
                    active = isPanelOpen("navigator"), onClick = { onPanel("navigator") },
                )
            }
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
    description: String? = null,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        else androidx.compose.ui.graphics.Color.Transparent,
        label = "barBg",
    )
    LongPressTooltip(
        label = desc,
        description = description,
        content = {
            Box(
                Modifier
                    .size(UiMetrics.BarButton)
                    .clip(RoundedCornerShape(UiMetrics.Corner))
                    .background(bg),
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
        },
        onClick = onClick,
        enabled = enabled,
    )
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
        ToolType.TRIANGLE to AppIcons.Triangle,
    ),
    listOf(ToolType.TEXT to AppIcons.TextTool),
    listOf(
        ToolType.GRADIENT to AppIcons.GradientTool,
        ToolType.EYEDROPPER to AppIcons.Eyedropper,
        ToolType.MEASURE to AppIcons.Measure,
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
    ToolType.TRIANGLE -> "三角形"
    ToolType.TEXT -> "テキスト"
    ToolType.GRADIENT -> "グラデーション"
    ToolType.EYEDROPPER -> "スポイト"
    ToolType.MEASURE -> "計測"
    ToolType.HAND -> "手のひら"
}

private fun toolDescription(t: ToolType): String = when (t) {
    ToolType.SELECT -> "オブジェクトを選択・移動・変形します"
    ToolType.NODE -> "パスのアンカーノードを直接編集します"
    ToolType.PEN -> "ベジェ曲線でパスを描画します"
    ToolType.FREEHAND -> "マウス・指でフリーハンド描画します"
    ToolType.RECT -> "長方形を作成します"
    ToolType.RRECT -> "角丸長方形を作成します"
    ToolType.ELLIPSE -> "楕円・円を作成します"
    ToolType.LINE -> "直線を描画します"
    ToolType.POLYLINE -> "ポリラインを描画します"
    ToolType.POLYGON -> "多角形を作成します"
    ToolType.STAR -> "星形を作成します"
    ToolType.ARC -> "円弧を描画します"
    ToolType.SECTOR -> "扇形を作成します"
    ToolType.SPIRAL -> "螺旋を描画します"
    ToolType.TEXT -> "テキストオブジェクトを作成します"
    ToolType.GRADIENT -> "グラデーションツールを選択します"
    ToolType.EYEDROPPER -> "キャンバス上の色をサンプリングします"
    ToolType.MEASURE -> "2点間の距離と角度を計測します（Shiftで角度スナップ・計測後にガイド化できます）"
    ToolType.TRIANGLE -> "三角形を作成します"
    ToolType.HAND -> "キャンバスをパン（移動）します"
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
                RailToggle(AppIcons.Grid, "グリッド", description = "グリッド線の表示・非表示を切り替え", editor.showGrid, refresh) {
                    editor.showGrid = !editor.showGrid; editor.onChanged()
                }
                RailToggle(AppIcons.Magnet, "グリッドスナップ", description = "オブジェクトをグリッドにスナップ", editor.snapToGrid, refresh) {
                    editor.snapToGrid = !editor.snapToGrid; editor.onChanged()
                }
                RailToggle(AppIcons.PenOnly, "ペン優先（指を無視）", description = "指入力を無視し、ペン（スタイラス）のみ反応", editor.penOnly, refresh) {
                    editor.penOnly = !editor.penOnly; editor.onChanged()
                }
                RailToggle(AppIcons.Magnet, "距離スナップ", description = "オブジェクト同士の距離にスナップ", editor.snapToDistance, refresh) {
                    editor.snapToDistance = !editor.snapToDistance; editor.onChanged()
                }
                RailToggle(AppIcons.Grid, "ピクセルグリッド", description = "ピクセルグリッドの表示・非表示を切り替え", editor.showPixelGrid, refresh) {
                    editor.showPixelGrid = !editor.showPixelGrid; editor.onChanged()
                }
                RailToggle(AppIcons.Outline, "アウトライン表示", description = "オブジェクトをアウトライン（輪郭のみ）表示", editor.showOutlineMode, refresh) {
                    editor.showOutlineMode = !editor.showOutlineMode; editor.onChanged()
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
                RailToggle(AppIcons.Grid, "グリッド", description = "グリッド線の表示・非表示を切り替え", editor.showGrid, refresh) {
                    editor.showGrid = !editor.showGrid; editor.onChanged()
                }
                RailToggle(AppIcons.Magnet, "グリッドスナップ", description = "オブジェクトをグリッドにスナップ", editor.snapToGrid, refresh) {
                    editor.snapToGrid = !editor.snapToGrid; editor.onChanged()
                }
                RailToggle(AppIcons.PenOnly, "ペン優先（指を無視）", description = "指入力を無視し、ペン（スタイラス）のみ反応", editor.penOnly, refresh) {
                    editor.penOnly = !editor.penOnly; editor.onChanged()
                }
                RailToggle(AppIcons.Magnet, "距離スナップ", description = "オブジェクト同士の距離にスナップ", editor.snapToDistance, refresh) {
                    editor.snapToDistance = !editor.snapToDistance; editor.onChanged()
                }
                RailToggle(AppIcons.Grid, "ピクセルグリッド", description = "ピクセルグリッドの表示・非表示を切り替え", editor.showPixelGrid, refresh) {
                    editor.showPixelGrid = !editor.showPixelGrid; editor.onChanged()
                }
                RailToggle(AppIcons.Outline, "アウトライン表示", description = "オブジェクトをアウトライン（輪郭のみ）表示", editor.showOutlineMode, refresh) {
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
    val label = toolLabel(tool)
    LongPressTooltip(
        label = label,
        description = toolDescription(tool),
        content = {
            Box(
                Modifier
                    .size(UiMetrics.ToolButton)
                    .clip(RoundedCornerShape(UiMetrics.Corner))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = label,
                    modifier = Modifier.size(UiMetrics.Icon),
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun RailToggle(icon: ImageVector, desc: String, description: String? = null, active: Boolean, refresh: Int, onToggle: () -> Unit) {
    // refreshによりstrong skippingを打ち消し、activeの変化（editor側フラグ）を反映する
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        else androidx.compose.ui.graphics.Color.Transparent,
        label = "toggleBg",
    )
    LongPressTooltip(
        label = desc,
        description = description,
        content = {
            Box(
                Modifier
                    .size(UiMetrics.ToolButton)
                    .clip(RoundedCornerShape(UiMetrics.Corner))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = desc,
                    modifier = Modifier.size(17.dp),
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onToggle,
    )
}

// ―――― パネル ――――

/** 操作履歴パネル */
@Composable
internal fun HistoryPanel(editor: Editor, refresh: Int) {
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
                            // redoHistory[0] が最新（次のredoで適用される操作）。
                            // 一覧の上から i 番目まで進むには redo を (i+1) 回適用する。
                            stack.redoTimes(i + 1)
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
internal fun NavigatorPanel(editor: Editor, refresh: Int) {
    val vb = editor.doc.viewBox
    val panelW = 220.dp
    val panelH = 150.dp
    val density = LocalDensity.current
    val pW = with(density) { panelW.toPx() }
    val pH = with(density) { panelH.toPx() }

    // ドキュメント全体をパネルに収める（余白 6px）
    val scale = minOf(
        (pW - 12f) / vb.width.coerceAtLeast(1.0).toFloat(),
        (pH - 12f) / vb.height.coerceAtLeast(1.0).toFloat(),
    )
    val offX = (pW - vb.width.toFloat() * scale) / 2f - vb.minX.toFloat() * scale
    val offY = (pH - vb.height.toFloat() * scale) / 2f - vb.minY.toFloat() * scale

    // 現在表示しているuser領域（回転も考慮）
    val v0 = editor.screenToUser(Vec(0.0, 0.0))
    val v1 = editor.screenToUser(Vec(editor.viewportW, editor.viewportH))
    val vx0 = (minOf(v0.x, v1.x) * scale + offX).toFloat()
    val vy0 = (minOf(v0.y, v1.y) * scale + offY).toFloat()
    val vw = (kotlin.math.abs(v1.x - v0.x) * scale).toFloat()
    val vh = (kotlin.math.abs(v1.y - v0.y) * scale).toFloat()

    val docRectColor = MaterialTheme.colorScheme.onSurfaceVariant
    val viewColor = MaterialTheme.colorScheme.primary

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("ナビゲーター")
        Text(
            "${dev.svgpro.core.util.Numbers.format(editor.zoom * 100.0)}%  クリックでその位置へ移動",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier
                .size(panelW, panelH)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .pointerInput(vb.minX, vb.minY, vb.width, vb.height, scale, offX, offY) {
                    detectTapGestures { p ->
                        val ux = ((p.x - offX) / scale).toDouble() + vb.minX
                        val uy = ((p.y - offY) / scale).toDouble() + vb.minY
                        editor.centerOnUser(Vec(ux, uy))
                    }
                },
        ) {
            // ページ範囲
            drawRect(
                color = docRectColor,
                topLeft = Offset(vb.minX.toFloat() * scale + offX, vb.minY.toFloat() * scale + offY),
                size = androidx.compose.ui.geometry.Size(vb.width.toFloat() * scale, vb.height.toFloat() * scale),
                style = Stroke(width = 1.2f),
            )
            // アートボード
            editor.doc.artboards.forEach { a ->
                drawRect(
                    color = docRectColor.copy(alpha = 0.45f),
                    topLeft = Offset(a.x.toFloat() * scale + offX, a.y.toFloat() * scale + offY),
                    size = androidx.compose.ui.geometry.Size(a.w.toFloat() * scale, a.h.toFloat() * scale),
                    style = Stroke(width = 0.8f),
                )
            }
            // 現在のビューポート
            drawRect(
                color = viewColor.copy(alpha = 0.18f),
                topLeft = Offset(vx0, vy0),
                size = androidx.compose.ui.geometry.Size(vw.coerceAtLeast(4f), vh.coerceAtLeast(4f)),
            )
            drawRect(
                color = viewColor,
                topLeft = Offset(vx0, vy0),
                size = androidx.compose.ui.geometry.Size(vw.coerceAtLeast(4f), vh.coerceAtLeast(4f)),
                style = Stroke(width = 1.5f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactButton("全体表示") { editor.fitToPage() }
            CompactButton("選択にフィット") { editor.fitToSelection() }
        }
        if (editor.doc.artboards.size > 1) {
            Text("アートボード", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                editor.doc.artboards.forEachIndexed { i, a ->
                    val active = i == editor.currentArtboardIndex
                    Text(
                        a.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editor.setCurrentArtboard(i) }
                            .background(
                                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

// ―――― ステータスバー（ズーム操作つき） ――――

@Composable
private fun StatusBar(editor: Editor, refresh: Int, compact: Boolean) {
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
                // 2選択間の隙間（ロゴの字間・余白チェック用）
                if (selCount == 2) {
                    val gaps = editor.selectionGaps()
                    if (gaps != null) {
                        val unit = editor.rulerUnitLabel()
                        val hGap = editor.rulerLength(gaps.first)
                        val vGap = editor.rulerLength(gaps.second)
                        Text(
                            "隙間 H${dev.svgpro.core.util.Numbers.format(hGap, 1)}$unit / V${dev.svgpro.core.util.Numbers.format(vGap, 1)}$unit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 直近の計測（メジャーツール）: 長さ+角度+ガイド化
                val measure = editor.lastMeasure
                if (measure != null) {
                    val len = editor.rulerLength(editor.measureLength() ?: 0.0)
                    val ang = editor.measureAngleDeg() ?: 0.0
                    val unit = editor.rulerUnitLabel()
                    Text(
                        "計測 ${dev.svgpro.core.util.Numbers.format(len, 2)}$unit ∠${dev.svgpro.core.util.Numbers.format(ang, 1)}°",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(
                        onClick = { editor.createMeasureGuide() },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("ガイド化", style = MaterialTheme.typography.labelSmall) }
                    TextButton(
                        onClick = { editor.clearMeasure() },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("消去", style = MaterialTheme.typography.labelSmall) }
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
                if (!compact) {
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
            // 新しいタブとして開く（既存セッションは退避される）
            vm.openDocumentInTab(doc, name, uri)
            // 後日の「最近使ったファイル」から再オープンできるよう権限を保持
            try {
                resolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) {
                // 永続権限がないプロバイダは無視
            }
            dev.svgpro.AppContextHolder.get()?.let { ctx ->
                vm.recordRecentFile(ctx, name, uri)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun saveToUri(vm: EditorViewModel, uri: android.net.Uri, editor: Editor): Boolean {
    return try {
        // アニメーショントラックをドキュメント属性に保存してからシリアライズ
        editor.saveKeyframesToDocument()
        val resolver = dev.svgpro.AppContextHolder.get()?.contentResolver ?: return false
        resolver.openOutputStream(uri, "wt")?.use { out ->
            val bytes = editor.doc.serialize().toByteArray(Charsets.UTF_8)
            if (uri.lastPathSegment?.endsWith(".svgz", ignoreCase = true) == true) {
                java.util.zip.GZIPOutputStream(out).use { gzos -> gzos.write(bytes) }
            } else {
                out.write(bytes)
            }
        } ?: return false
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

/** 選択要素だけを新しいSVGドキュメントとして書き出す */
fun exportSelectionToUri(vm: EditorViewModel, uri: android.net.Uri, editor: Editor): Boolean {
    return try {
        val doc = editor.buildDocumentFromSelection() ?: return false
        val resolver = dev.svgpro.AppContextHolder.get()?.contentResolver ?: return false
        resolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(doc.serialize().toByteArray(Charsets.UTF_8))
        } ?: return false
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

/** 画像ファイルを data URI（PNG Base64）として埋め込み image 要素を追加する */
fun importImage(vm: EditorViewModel, uri: android.net.Uri) {
    try {
        val resolver = dev.svgpro.AppContextHolder.get()?.contentResolver ?: return
        resolver.openInputStream(uri)?.use { input ->
            val bmp = android.graphics.BitmapFactory.decodeStream(input) ?: return
            val width = bmp.width
            val height = bmp.height
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            // recycle前に寸法を取得しておく（recycle後のwidth/height参照は端末により不安定）
            bmp.recycle()
            val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            val el = dev.svgpro.core.svg.Element("image")
            el.setAttr("href", "data:image/png;base64,$base64")
            el.setAttr("xlink:href", "data:image/png;base64,$base64")
            el.setAttr("x", "0")
            el.setAttr("y", "0")
            el.setAttr("width", width.toString())
            el.setAttr("height", height.toString())
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
    onExportSelection: () -> Unit = {},
    onExportPdf: () -> Unit = {},
    onGuideCreate: () -> Unit = {},
    onParametricShape: () -> Unit = {},
    onChart: () -> Unit = {},
    onMacroRecorder: () -> Unit = {},
    onComments: () -> Unit = {},
    onStylePreset: () -> Unit = {},
    onBrushPreset: () -> Unit = {},
    onShortcutCustomize: () -> Unit = {},
    onObjectSearch: () -> Unit = {},
    onSvgAttributeEdit: () -> Unit = {},
    onTextOnCircle: () -> Unit = {},
    onPanel: (String) -> Unit,
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
        onExportSelection = onExportSelection,
        onGuideCreate = onGuideCreate,
        onParametricShape = onParametricShape,
        onChart = onChart,
        onMacroRecorder = onMacroRecorder,
        onComments = onComments,
        onStylePreset = onStylePreset,
        onBrushPreset = onBrushPreset,
        onShortcutCustomize = onShortcutCustomize,
        onObjectSearch = onObjectSearch,
        onSvgAttributeEdit = onSvgAttributeEdit,
        onTextOnCircle = onTextOnCircle,
        onExportPdf = onExportPdf,
        onPanel = onPanel,
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
    onExportSelection: () -> Unit = {},
    onExportPdf: () -> Unit = {},
    onGuideCreate: () -> Unit = {},
    onParametricShape: () -> Unit = {},
    onChart: () -> Unit = {},
    onMacroRecorder: () -> Unit = {},
    onComments: () -> Unit = {},
    onStylePreset: () -> Unit = {},
    onBrushPreset: () -> Unit = {},
    onShortcutCustomize: () -> Unit = {},
    onObjectSearch: () -> Unit = {},
    onSvgAttributeEdit: () -> Unit = {},
    onTextOnCircle: () -> Unit = {},
    onPanel: (String) -> Unit,
): List<Triple<String, String, () -> Unit>> {
    return listOf(
        Triple("元に戻す", "Ctrl+Z") { if (editor.undoStack.canUndo) editor.undo() },
        Triple("やり直す", "Ctrl+Shift+Z / Ctrl+Y") { if (editor.undoStack.canRedo) editor.redo() },
        Triple("保存", "Ctrl+S") { onSave() },
        Triple("別名で保存…", "") { onSaveAs() },
        Triple("コピー", "Ctrl+C") { editor.copySelection() },
        Triple("カット", "Ctrl+X") { editor.cutSelection() },
        Triple("ペースト", "Ctrl+V") { editor.pasteFromClipboard(inPlace = false) },
        Triple("前面にペースト", "") { editor.pasteFromClipboard(inPlace = true, toFront = true) },
        Triple("背面にペースト", "") { editor.pasteFromClipboard(inPlace = true, toFront = false) },
        Triple("削除", "Delete") { editor.deleteSelection() },
        Triple("複製", "Ctrl+D") { editor.duplicateSelection() },
        Triple("全選択", "Ctrl+A") {
            editor.selectAllInBox(
                Bounds(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
                additive = false,
            )
        },
        Triple("選択反転", "") { editor.invertSelection() },
        Triple("同じ塗りを選択", "") { editor.selectSameFill() },
        Triple("同じ線を選択", "") { editor.selectSameStroke() },
        Triple("同じ種類を選択", "") { editor.selectSameKind() },
        Triple("同じフォントを選択", "") { editor.selectSameFont() },
        Triple("塗りと線を入れ替え", "X") { editor.swapFillStrokeSelection() },
        Triple("グループ化", "Ctrl+G") { editor.groupSelection() },
        Triple("グループ解除", "Ctrl+Shift+G") { editor.ungroupSelection() },
        Triple("数値変形…", "") { onTransform() },
        Triple("色の一括置換…", "") { onReplaceColor() },
        Triple("ルーラー表示", "Ctrl+R") { editor.showRulers = !editor.showRulers; editor.onChanged() },
        Triple("グリッド表示", "") { editor.showGrid = !editor.showGrid; editor.onChanged() },
        Triple("グリッドスナップ", "") { editor.snapToGrid = !editor.snapToGrid; editor.onChanged() },
        Triple("アウトライン表示", "") { editor.showOutlineMode = !editor.showOutlineMode; editor.onChanged() },
        Triple("選択にフィット", "") { editor.fitToSelection() },
        Triple("全体表示", "") { editor.fitToPage() },
        Triple("計測ツール", "M") { editor.tool = ToolType.MEASURE },
        Triple("アートボード中心にガイド", "") { editor.addCenterGuides() },
        Triple("ガイドを作成…", "") { onGuideCreate() },
        Triple("パラメトリック図形生成…", "") { onParametricShape() },
        Triple("チャート/グラフ生成…", "") { onChart() },
        Triple("マクロ記録・再生…", "") { onMacroRecorder() },
        Triple("コメント/注釈…", "") { onComments() },
        Triple("スタイルプリセット…", "") { onStylePreset() },
        Triple("ブラシプリセット…", "") { onBrushPreset() },
        Triple("ショートカットのカスタマイズ…", "") { onShortcutCustomize() },
        Triple("オブジェクト検索…", "") { onObjectSearch() },
        Triple("ピクセルプレビュー", "") { editor.showPixelGrid = !editor.showPixelGrid; editor.onChanged() },
        Triple("パネル: レイヤー / オブジェクト", "") { onPanel("layers") },
        Triple("パネル: プロパティ", "") { onPanel("props") },
        Triple("パネル: 操作履歴", "") { onPanel("history") },
        Triple("パネル: ナビゲーター", "") { onPanel("navigator") },
        Triple("SVGソースコード編集…", "") { onSvgSource() },
        Triple("SVG最適化・検証…", "") { onSvgOptimize() },
        Triple("画像をインポート…", "") { onImageImport() },
        Triple("PNG書き出し…", "") { onExportPng() },
        Triple("選択をSVGで書き出し…", "") { onExportSelection() },
        Triple("PDF書き出し…", "") { onExportPdf() },
        Triple("SVG属性直接編集…", "") { onSvgAttributeEdit() },
        Triple("テキストを円周に沿わせる…", "") { onTextOnCircle() },
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

/** テキストインラインエディタのモード。 */
sealed interface TextEditMode {
    /** 新規作成：クリック位置にテキストを作成 */
    data class Create(val pos: Vec) : TextEditMode
    /** 既存テキスト編集：選択中の text 要素を編集 */
    data class Edit(val el: Element, val initialText: String) : TextEditMode
}

/**
 * キャンバス上に重ねるインラインテキスト入力フィールド。
 * ポップアップダイアログを出さず、キャンバス上のクリック位置（または既存テキスト位置）
 * に直接表示して Enter で確定・Escape でキャンセルできる。
 */
@Composable
private fun TextInputOverlay(
    editor: Editor,
    mode: TextEditMode,
    canvasOrigin: Offset,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 表示位置を画面座標に変換（キャンバス内のピクセル座標 → ルート座標 → dp）
    val screenPos = remember(mode, canvasOrigin) {
        when (mode) {
            is TextEditMode.Create -> editor.userToScreen(mode.pos)
            is TextEditMode.Edit -> {
                val x = dev.svgpro.core.util.Numbers.parse(mode.el.attr("x")) ?: 0.0
                val y = dev.svgpro.core.util.Numbers.parse(mode.el.attr("y")) ?: 0.0
                editor.userToScreen(Vec(x, y))
            }
        }
    }
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val posX = canvasOrigin.x + screenPos.x.toFloat()
    val posY = canvasOrigin.y + screenPos.y.toFloat()
    val offsetX = with(density) { posX.toDp() }
    val offsetY = with(density) { posY.toDp() }

    val initialText = when (mode) {
        is TextEditMode.Edit -> mode.initialText
        else -> ""
    }
    var text by remember(initialText) { mutableStateOf(initialText) }

    // モード変更時にテキストをリセットし、入力欄へフォーカスを移す
    LaunchedEffect(mode) {
        text = initialText
        // レイアウト確定後にフォーカス要求（失敗しても致命的ではない）
        runCatching { focusRequester.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val safeX = offsetX.coerceIn(0.dp, (maxWidth - 288.dp).coerceAtLeast(0.dp))
        val safeY = offsetY.coerceIn(0.dp, (maxHeight - 150.dp).coerceAtLeast(0.dp))
        Column(
            Modifier
                .offset(x = safeX, y = safeY)
                .width(280.dp)
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = false,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onDismiss() }) { Text("キャンセル") }
                Button(onClick = { onConfirm(text) }) { Text("OK") }
            }
        }
    }
}
