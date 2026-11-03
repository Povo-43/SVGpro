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
 * ボタン横に機能説明ポップアップを表示するラッパーコンポーネント。
 *
 * タップでヒントの表示／非表示をトグルする。
 * ヒントはボタンの「右側」に固定表示されるため、指で隠れて見えにくい
 * という問題を回避する。
 *
 * - ヒント表示中は元の onClick は発火しない（誤操作防止）
 * - ヒント非表示時にタップすると onClick を実行
 * - description が設定されている場合は、ラベルに加えて詳しい説明文も表示
 * - enabled=false の時はヒントもクリックも無効化
 */
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

    Box(
        modifier = modifier
            .clickable(
                enabled = enabled,
                onClick = {
                    if (showHint) {
                        // ヒント表示中は閉じるだけ（アクションは実行しない）
                        showHint = false
                    } else {
                        onClick()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()

        if (showHint && label.isNotEmpty() && enabled) {
            Box(Modifier.align(Alignment.CenterEnd).padding(start = 8.dp)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    tonalElevation = 8.dp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.widthIn(max = 220.dp)) {
                        Text(
                            text = label.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        )
                        description?.let { desc ->
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                            )
                        }
                    }
                }
            }
        }
    }
}
