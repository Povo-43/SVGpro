package dev.svgpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * ヒントポップアップを表示するラッパーコンポーネント。
 *
 * ボタンやアイコンをラップし、タップでヒントポップアップを
 * 表示/非表示に切り替える。タブレット・スマホ両方で動作する。
 *
 * - タップでヒント表示・非表示をトグル
 * - ヒントはコンポーネントの下方中央にポップアップ表示
 * - enabled=false の時はヒントも表示しない
 */
@Composable
fun LongPressTooltip(
    hint: String,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    enabled: Boolean = true,
) {
    var showHint by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .clickable(
                enabled = enabled,
                onClick = {
                    showHint = !showHint
                    if (!showHint) onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()

        if (showHint && hint.isNotEmpty() && enabled) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    tonalElevation = 8.dp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = hint.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
