package dev.svgpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.svgpro.core.svg.Element
import dev.svgpro.editor.Editor
import dev.svgpro.editor.Trace

/**
 * レイヤー/オブジェクトパネル。
 */
@Composable
fun LayersPanel(editor: Editor, refresh: Int) {
    var renameTarget by remember { mutableStateOf<Element?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("レイヤー / オブジェクト", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val n = editor.doc.root.childElements().count { it.tag == "g" } + 1
                editor.createLayer("Layer $n")
            }) {
                Icon(AppIcons.Plus, contentDescription = "レイヤー追加")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        val items = collectTree(editor)
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.el.hashCode() }) { row ->
                ObjectRow(
                    editor = editor,
                    row = row,
                    onRename = { renameTarget = row.el },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PanelIconButton(AppIcons.Up, "前面へ") { editor.reorderSelection(front = true, absolute = false) }
            PanelIconButton(AppIcons.Down, "背面へ") { editor.reorderSelection(front = false, absolute = false) }
            PanelIconButton(AppIcons.Front, "最前面へ") { editor.reorderSelection(front = true, absolute = true) }
            PanelIconButton(AppIcons.Back, "最背面へ") { editor.reorderSelection(front = false, absolute = true) }
            PanelIconButton(AppIcons.Group, "グループ化") { editor.groupSelection() }
            PanelIconButton(AppIcons.Ungroup, "グループ解除") { editor.ungroupSelection() }
            PanelIconButton(AppIcons.Delete, "削除") { editor.deleteSelection() }
        }
    }

    renameTarget?.let { el ->
        TextInputDialog(
            title = "名前変更",
            initial = el.attr("id") ?: el.tag,
            onDismiss = { renameTarget = null },
        ) { name ->
            if (name.isNotBlank()) editor.renameElement(el, name)
            renameTarget = null
        }
    }
}

@Composable
fun PanelIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(20.dp))
    }
}

data class TreeRow(val el: Element, val depth: Int)

fun collectTree(editor: Editor): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    fun walk(el: Element, depth: Int) {
        if (el.tag == "defs") return
        out.add(TreeRow(el, depth))
        if (el.tag == "g" || el.tag == "a") {
            for (c in el.childElements()) walk(c, depth + 1)
        }
    }
    for (c in editor.doc.root.childElements()) walk(c, 0)
    return out
}

@Composable
fun ObjectRow(editor: Editor, row: TreeRow, onRename: () -> Unit) {
    val el = row.el
    val selected = editor.isSelected(el) || editor.selection.any { editor.trace.itemOf(it)?.layer === el }
    val locked = el.attr(dev.svgpro.editor.Trace.LOCK_ATTR) == "1"
    val hidden = dev.svgpro.core.model.StyleUtil.getProperty(el, "display") == "none"
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth().clickable {
            if (editor.doc.isSelectableShape(el) || el.tag == "g") editor.select(el)
        },
    ) {
        Row(
            Modifier.padding(start = (8 + row.depth * 14).dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = labelFor(el),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { onRename() },
            )
            Icon(
                if (hidden) AppIcons.EyeClosed else AppIcons.EyeOpen,
                contentDescription = if (hidden) "表示" else "非表示",
                modifier = Modifier
                    .size(34.dp)
                    .clickable { editor.setHidden(el, !hidden) }
                    .padding(7.dp),
                tint = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                if (locked) AppIcons.LockIcon else AppIcons.UnlockIcon,
                contentDescription = if (locked) "ロック解除" else "ロック",
                modifier = Modifier
                    .size(34.dp)
                    .clickable { editor.setLocked(el, !locked) }
                    .padding(7.dp),
                tint = if (locked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun labelFor(el: Element): String {
    val id = el.attr("id")
    if (!id.isNullOrBlank()) return id
    return when (el.tag) {
        "g" -> "グループ"
        "text" -> "テキスト \"${el.textContent().take(12)}\""
        "image" -> "画像"
        "use" -> "参照"
        else -> el.tag
    }
}
