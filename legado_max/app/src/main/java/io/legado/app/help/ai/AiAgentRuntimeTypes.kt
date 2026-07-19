package io.legado.app.help.ai

import org.json.JSONObject

internal data class AiAgentToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

internal data class AiAgentAssistantTurn(
    val content: String,
    val toolCalls: List<AiAgentToolCall>,
    val rawMessage: JSONObject,
    val reasoningContent: String = ""
)

internal data class AiToolExecutionOptions(
    val useAllTools: Boolean,
    val extraToolNames: Set<String> = emptySet(),
    /**
     * 高危工具执行前的用户确认回调。
     *
     * 返回 `true` 表示放行，`false` 表示拒绝。拒绝时 [AiAgentRuntime] 会直接
     * 返回 [AiToolRegistry.userDeclinedResultJson] 给模型，不再调用工具。
     *
     * 默认实现恒返回 `true`，保持现有非交互上下文（如非前台 batch）的行为不变。
     * 接入 UI 的调用方（[io.legado.app.help.ai.AiChatService]）应传入实际弹窗
     * 实现的 suspend lambda。
     */
    val riskConfirmation: suspend (toolName: String, args: String, risk: AiToolRisk) -> Boolean =
        { _, _, _ -> true }
)
