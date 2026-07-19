package io.legado.app.ui.main.ai.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.help.ai.AiToolRegistry
import io.legado.app.help.ai.AiToolRisk

/**
 * 高危工具执行前的用户确认弹窗。
 *
 * 由 [io.legado.app.ui.main.ai.AiChatViewModel.pendingRiskConfirm] 驱动：
 * StateFlow 为 null 时不渲染；非 null 时显示工具名、风险等级标签和参数摘要，
 * 用户点击"允许"或"拒绝"后调用 [onResolve] 恢复被挂起的工具执行。
 *
 * 弹窗不可通过"返回键 / 点击外部"关闭 —— 风险操作必须显式选择。
 */
@Composable
fun AiToolRiskConfirmDialog(
    toolName: String,
    args: String,
    risk: AiToolRisk,
    style: AiComposeStyle,
    onResolve: (allowed: Boolean) -> Unit
) {
    Dialog(
        onDismissRequest = { /* 必须显式选择，不允许外部 dismiss */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(style.metrics.cardRadius),
            color = style.colors.cardSurface,
            border = BorderStroke(style.metrics.strokeWidth, style.colors.danger),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text(
                    text = "高危工具需要确认",
                    color = style.colors.danger,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "工具：${AiToolRegistry.displayNameOfTool(toolName)}",
                    color = style.colors.primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "标识：$toolName",
                    color = style.colors.secondaryText,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = style.colors.danger.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(style.metrics.chipRadius),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "此操作可能不可逆或消耗付费/算力资源，请确认是否放行。",
                        color = style.colors.danger,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
                if (args.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "参数",
                        color = style.colors.secondaryText,
                        fontSize = 11.5.sp
                    )
                    Surface(
                        color = style.colors.background.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(style.metrics.chipRadius),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Text(
                            text = args.take(2_000),
                            color = style.colors.primaryText,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(10.dp)
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionChip(
                        label = "拒绝",
                        color = style.colors.secondaryText,
                        modifier = Modifier.weight(1f),
                        onClick = { onResolve(false) }
                    )
                    ActionChip(
                        label = "允许执行",
                        color = style.colors.danger,
                        modifier = Modifier.weight(1f),
                        onClick = { onResolve(true) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp)
        )
    }
}
