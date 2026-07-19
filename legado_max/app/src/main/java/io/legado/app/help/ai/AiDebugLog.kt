package io.legado.app.help.ai

private const val MAX_DEBUG_LOG_CHARS = 16_000
private const val MAX_DEBUG_PAYLOAD_CHARS = 8_000

/**
 * 脱敏后的调试日志。redact Bearer / apiKey / authorization / token / secret /
 * data: image 等敏感字段，避免 trace 里泄露。
 *
 * 顶层 internal 工具：[io.legado.app.help.ai.AiChatService] 和
 * [io.legado.app.help.ai.AiAgentRuntime] 都依赖，避免互相 import 形成循环依赖。
 */
internal fun safeDebugLog(text: String): String {
    return safeDebugPayload(text, MAX_DEBUG_LOG_CHARS)
}

internal fun StringBuilder.toSafeDebugLog(): String = safeDebugLog(toString())

internal fun safeDebugPayload(text: String, maxChars: Int = MAX_DEBUG_PAYLOAD_CHARS): String {
    val sanitized = text
        .replace(Regex("Bearer\\s+[^\\s\"']+", RegexOption.IGNORE_CASE), "Bearer <redacted>")
        .replace(Regex("(\"(?:api[_-]?key|authorization|token|secret)\"\\s*:\\s*\")([^\"]+)(\")", RegexOption.IGNORE_CASE), "$1<redacted>$3")
        .replace(Regex("data:image/[^\\s\"')]+"), "data:image/<redacted>")
    return if (sanitized.length <= maxChars) {
        sanitized
    } else {
        sanitized.take(maxChars) + "\n...<truncated ${sanitized.length - maxChars} chars>"
    }
}
