package dev.svgpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.svgpro.editor.Editor
import dev.svgpro.editor.selectSameFill
import dev.svgpro.editor.selectSameKind
import dev.svgpro.editor.selectSameStroke
import dev.svgpro.editor.swapFillStrokeSelection

/**
 * デスクトップ／タブレット向けPCライクメニューバー。
 * 横並びのテキストメニューで、各項目をクリックするとドロップダウンメニューが開く。
 * スマホ（compact）では表示せず、TopBarのハンバーガーメニューを使う。
 *
 * すべてのアクションは外部からコールバックで注入されるため、
 * EditorScreen 側の既存ロジクス（openLauncher, doSave 等）を流用できる。
 */
@Composable
fun MenuBar(
    editor: Editor,
    refresh: Int,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onExportPng: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onGroup: () -> Unit,
    onUngroup: () -> Unit,
    onGrid: () -> Unit,
    onSnap: () -> Unit,
    onRulers: () -> Unit,
    onOutline: () -> Unit,
    onText: () -> Unit,
    onShortcuts: () -> Unit,
    onFontToPath: () -> Unit,
    onPanel: (String) -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().height(UiMetrics.BarHeight),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MenuItem("ファイル") {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = {},
                    modifier = Modifier.width(200.dp),
                ) {
                    MenuDropdownItem("新規ドキュメント", { onNew() })
                    MenuDropdownItem("SVGを開く…", { onOpen() })
                    HorizontalDivider()
                    MenuDropdownItem("上書き保存", { onSave() }, enabled = editor.dirty)
                    MenuDropdownItem("別名で保存…", { onSaveAs() })
                    HorizontalDivider()
                    MenuDropdownItem("PNG書き出し…", { onExportPng() })
                }
            }
            MenuItem("編集") {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = {},
                    modifier = Modifier.width(220.dp),
                ) {
                    MenuDropdownItem("元に戻す", { onUndo() }, enabled = editor.undoStack.canUndo)
                    MenuDropdownItem("やり直す", { onRedo() }, enabled = editor.undoStack.canRedo)
                    HorizontalDivider()
                    MenuDropdownItem("コピー", { onCopy() }, enabled = editor.selection.isNotEmpty())
                    MenuDropdownItem("カット", { onCut() }, enabled = editor.selection.isNotEmpty())
                    MenuDropdownItem("ペースト", { onPaste() }, enabled = editor.clipboard.isNotEmpty())
                    MenuDropdownItem("削除", { onDelete() }, enabled = editor.selection.isNotEmpty())
                    MenuDropdownItem("複製", { onDuplicate() }, enabled = editor.selection.isNotEmpty())
                    HorizontalDivider()
                    MenuDropdownItem("全選択", { onSelectAll() })
                    MenuDropdownItem("選択反転", { onInvertSelection() })
                    MenuDropdownItem("同じ塗りを選択", { editor.selectSameFill() }, enabled = editor.selection.isNotEmpty())
                    MenuDropdownItem("同じ線を選択", { editor.selectSameStroke() }, enabled = editor.selection.isNotEmpty())
                    MenuDropdownItem("同じ種類を選択", { editor.selectSameKind() }, enabled = editor.selection.isNotEmpty())
                    HorizontalDivider()
                    MenuDropdownItem("塗りと線を入れ替え", { editor.swapFillStrokeSelection() }, enabled = editor.selection.isNotEmpty())
                }
            }
            MenuItem("表示") {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = {},
                    modifier = Modifier.width(180.dp),
                ) {
                    MenuDropdownItem("グリッド表示", { onGrid() }, checked = editor.showGrid)
                    MenuDropdownItem("グリッドスナップ", { onSnap() }, checked = editor.snapToGrid)
                    MenuDropdownItem("ルーラー表示", { onRulers() }, checked = editor.showRulers)
                    MenuDropdownItem("アウトライン表示", { onOutline() }, checked = editor.showOutlineMode)
                    HorizontalDivider()
                    MenuDropdownItem("レイヤー / オブジェクト", { onPanel("layers") })
                    MenuDropdownItem("プロパティ", { onPanel("props") })
                    MenuDropdownItem("操作履歴", { onPanel("history") })
                    MenuDropdownItem("ナビゲーター", { onPanel("navigator") })
                }
            }
            MenuItem("オブジェクト") {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = {},
                    modifier = Modifier.width(180.dp),
                ) {
                    MenuDropdownItem("グループ化", { onGroup() }, enabled = editor.selection.size >= 2)
                    MenuDropdownItem("グループ解除", { onUngroup() }, enabled = editor.selection.any { it.tag == "g" })
                    HorizontalDivider()
                    MenuDropdownItem("選択テキストをパス化", { onFontToPath() },
                        enabled = editor.selection.any { it.tag == "text" })
                }
            }
            MenuItem("テキスト") {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = {},
                    modifier = Modifier.width(160.dp),
                ) {
                    MenuDropdownItem("テキストを追加", { onText() })
                }
            }
            MenuItem("ヘルプ") {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = {},
                    modifier = Modifier.width(180.dp),
                ) {
                    MenuDropdownItem("ショートカット一覧", { onShortcuts() })
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        content()
    }
    // DropdownMenu は expanded フラグで開閉を制御。クリックでトグル。
    Box(
        Modifier
            .padding(vertical = 4.dp)
            .clickable { expanded = !expanded },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (expanded) androidx.compose.ui.text.font.FontWeight.Bold
            else androidx.compose.ui.text.font.FontWeight.Normal,
        )
    }
}

@Composable
private fun MenuDropdownItem(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    checked: Boolean = false,
) {
    DropdownMenuItem(
        text = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                if (checked) {
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        onClick = { if (enabled) onClick() },
        enabled = enabled,
    )
}
