package dev.svgpro.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * ツールボタン用の長押しヘルプ。
 *
 * - 通常タップ: 即座にアクションを実行（従来どおりの操作性）
 * - 長押し: ボタン名と説明をポップアップ表示（外側タップで閉じる）
 *
 * 以前は「タップでヒント表示」と「タップで実行」が同じクリックに割り当てられ、
 * ヒントが一度も表示されない状態だった。長押しに分離することで、
 * 操作を遅らせずにヘルプを読めるようにしている。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LongPressTooltip(
    label: String,
    description: String? = null,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    enabled: Boolean = true,
) {
    var showHint by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier.combinedClickable(
                enabled = enabled,
                onClick = {
                    if (showHint) showHint = false else onClick()
                },
                onLongClick = {
                    if (enabled && label.isNotEmpty()) showHint = true
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }

        DropdownMenu(
            expanded = showHint,
            onDismissRequest = { showHint = false },
            modifier = Modifier.widthIn(min = 180.dp, max = 280.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                description?.let { desc ->
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}
