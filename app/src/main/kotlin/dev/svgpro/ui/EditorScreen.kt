package dev.svgpro.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
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
import dev.svgpro.editor.ToolType
import java.io.BufferedReader

/**
 * メインエディタ画面。
 * レイアウト: 上部バー / [左ツールレール | キャンバス | 右パネル(wide時)] / 下部ツールバー(小画面)
 */
@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    val editor = vm.editor
    var refresh by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    var pendingTextPos by remember { mutableStateOf<Vec?>(null) }
    var showTextCreate by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDocSettings by remember { mutableStateOf(false) }
    var showNewDoc by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showPanel by remember { mutableStateOf<String?>(null) }

    // エディタ変更通知 → 再描画
    LaunchedEffect(Unit) {
        editor.onChanged = { refresh++ }
        editor.onSelectionChanged = { refresh++ }
        editor.onZoomChanged = { refresh++ }
        editor.requestTextInput = { pos ->
            pendingTextPos = pos
            showTextCreate = true
        }
    }

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

    fun doSave() {
        val uri = vm.currentUri
        if (uri != null) {
            saveToUri(vm, uri, editor)
            editor.markSaved()
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

    val canvasView = remember { CanvasView(context) }
    LaunchedEffect(Unit) { canvasView.setup(editor) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isWide = maxWidth > 700.dp
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TopBar(
                editor = editor,
                onMenu = { showMenu = true },
                onUndo = { editor.undo() },
                onRedo = { editor.redo() },
                onSave = { doSave() },
                onFit = { editor.fitToPage() },
                onZoom100 = { editor.zoomTo100() },
                onPanel = { name -> showPanel = if (showPanel == name) null else name },
                activePanel = showPanel,
            )

            Row(Modifier.weight(1f)) {
                if (isWide) {
                    ToolRail(editor, vertical = true)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AndroidView(
                        factory = { canvasView },
                        update = { view -> view.invalidate() },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // ペン作成中の確定/取消
                    if (editor.penDraft != null) {
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(onClick = { editor.commitPenDraft(true) }) {
                                Icon(AppIcons.Check, null); Spacer(Modifier.width(4.dp)); Text("閉じて確定")
                            }
                            FilledTonalButton(onClick = { editor.commitPenDraft(false) }) {
                                Icon(AppIcons.Check, null); Spacer(Modifier.width(4.dp)); Text("確定")
                            }
                            OutlinedButton(onClick = { editor.cancelPenDraft() }) {
                                Icon(AppIcons.Close, null); Spacer(Modifier.width(4.dp)); Text("取消")
                            }
                        }
                    }
                }
                if (isWide) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier.width(300.dp).fillMaxHeight(),
                    ) {
                        when (showPanel) {
                            "layers" -> LayersPanel(editor, refresh)
                            else -> PropertiesPanel(editor, refresh)
                        }
                    }
                }
            }

            if (!isWide) {
                ToolRail(editor, vertical = false)
            }

            if (!isWide && showPanel != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                ) {
                    when (showPanel) {
                        "layers" -> LayersPanel(editor, refresh)
                        else -> PropertiesPanel(editor, refresh)
                    }
                }
            }

            StatusBar(editor)
        }

        if (showMenu) {
            MainMenu(
                onDismiss = { showMenu = false },
                onNew = { showMenu = false; showNewDoc = true },
                onOpen = {
                    showMenu = false
                    openLauncher.launch(arrayOf("image/svg+xml", "text/plain", "text/xml", "*/*"))
                },
                onSave = { showMenu = false; doSave() },
                onSaveAs = { showMenu = false; createLauncher.launch(editor.documentName) },
                onSettings = { showMenu = false; showDocSettings = true },
            )
        }

        if (showDocSettings) {
            DocumentSettingsDialog(editor = editor, onDismiss = { showDocSettings = false })
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
    return when {
        ctrl && keyCode == KeyEvent.KEYCODE_Z && !shift -> { editor.undo(); true }
        (ctrl && keyCode == KeyEvent.KEYCODE_Z && shift) || (ctrl && keyCode == KeyEvent.KEYCODE_Y) -> { editor.redo(); true }
        ctrl && keyCode == KeyEvent.KEYCODE_S -> { save(); true }
        keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> { editor.deleteSelection(); true }
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

@Composable
private fun TopBar(
    editor: Editor,
    onMenu: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onFit: () -> Unit,
    onZoom100: () -> Unit,
    onPanel: (String) -> Unit,
    activePanel: String?,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenu) { Icon(AppIcons.MenuIcon, "メニュー") }
            Column(Modifier.weight(1f)) {
                Text(
                    editor.documentName + if (editor.dirty) " ●" else "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    "${(editor.zoom * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUndo, enabled = editor.undoStack.canUndo) { Icon(AppIcons.Undo, "元に戻す") }
            IconButton(onClick = onRedo, enabled = editor.undoStack.canRedo) { Icon(AppIcons.Redo, "やり直す") }
            IconButton(onClick = onFit) { Icon(AppIcons.Fit, "全体表示") }
            IconButton(onClick = onZoom100) { Text("1:1", style = MaterialTheme.typography.labelMedium) }
            IconButton(onClick = { onPanel("layers") }) {
                Icon(AppIcons.Layers, "レイヤー", tint = if (activePanel == "layers") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { onPanel("props") }) {
                Icon(AppIcons.Palette, "プロパティ", tint = if (activePanel == "props") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onSave) { Icon(AppIcons.Save, "保存") }
        }
    }
}

@Composable
private fun StatusBar(editor: Editor) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val vb = editor.doc.viewBox
            Text(
                "${dev.svgpro.core.util.Numbers.format(vb.width)} × ${dev.svgpro.core.util.Numbers.format(vb.height)}",
                style = MaterialTheme.typography.labelSmall,
            )
            val selCount = editor.selection.size
            Text(
                if (selCount == 0) "オブジェクトなし" else "選択: $selCount",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** ツールレール（縦/横） */
@Composable
fun ToolRail(editor: Editor, vertical: Boolean) {
    val tools = listOf(
        ToolType.SELECT to AppIcons.Select,
        ToolType.NODE to AppIcons.Node,
        ToolType.PEN to AppIcons.Pen,
        ToolType.FREEHAND to AppIcons.Pencil,
        ToolType.RECT to AppIcons.Rect,
        ToolType.RRECT to AppIcons.RRect,
        ToolType.ELLIPSE to AppIcons.Circle,
        ToolType.LINE to AppIcons.Line,
        ToolType.POLYLINE to AppIcons.Polyline,
        ToolType.POLYGON to AppIcons.Polygon,
        ToolType.STAR to AppIcons.Star,
        ToolType.TEXT to AppIcons.TextTool,
        ToolType.EYEDROPPER to AppIcons.Eyedropper,
        ToolType.HAND to AppIcons.Hand,
    )
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        if (vertical) {
            Column(
                Modifier.width(52.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                tools.forEach { (tool, icon) -> ToolButton(editor, tool, icon) }
                Spacer(Modifier.height(6.dp))
                ToggleButtonMini("グリッド", editor.showGrid) { editor.showGrid = !editor.showGrid; editor.onChanged() }
                ToggleButtonMini("スナップ", editor.snapToGrid) { editor.snapToGrid = !editor.snapToGrid }
                ToggleButtonMini("ペンのみ", editor.penOnly) { editor.penOnly = !editor.penOnly }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                tools.forEach { (tool, icon) -> ToolButton(editor, tool, icon) }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ToggleButtonMini("グリッド", editor.showGrid) { editor.showGrid = !editor.showGrid; editor.onChanged() }
                    ToggleButtonMini("ペンのみ", editor.penOnly) { editor.penOnly = !editor.penOnly }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(editor: Editor, tool: ToolType, icon: ImageVector) {
    val selected = editor.tool == tool
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else androidx.compose.ui.graphics.Color.Transparent
    Box(
        Modifier
            .size(46.dp)
            .background(bg, MaterialTheme.shapes.small)
            .clickable { editor.tool = tool },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = tool.name, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ToggleButtonMini(label: String, checked: Boolean, onToggle: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun MainMenu(
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SVGpro") },
        text = {
            Column {
                MenuRow("新規ドキュメント", onNew)
                MenuRow("SVGを開く…", onOpen)
                MenuRow("上書き保存", onSave)
                MenuRow("別名で保存…", onSaveAs)
                MenuRow("ドキュメント設定…", onSettings)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
    )
}

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
            BufferedReader(java.io.InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
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
            out.write(editor.doc.serialize().toByteArray(Charsets.UTF_8))
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
