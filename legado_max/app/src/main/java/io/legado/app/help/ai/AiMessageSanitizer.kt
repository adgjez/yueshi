package io.legado.app.help.ai

/**
 * 用户消息清洗：把进入 LLM 上下文的内容规整为安全、最小形式。
 *
 * 主要场景：
 * - 粘贴文本含零宽字符（\u200B-\u200D, \u2060, \uFEFF），LLM 看到后容易出现偏移
 * - 复制粘贴过程中混入控制字符（除 \n \t \r 外）
 * - retry 路径回放旧消息时，可能携带历史遗留的异常字符
 *
 * 不做的事（避免影响阅读软件核心用例）：
 * - 不限制长度：用户经常粘贴整章/整段内容
 * - 不合并连续空行：诗歌、分段、列表依赖空行
 * - 不 trim 整体：保留 trailing/leading 换行给用户表达多段落
 */
internal fun normalizeMessageContent(content: String): String {
    if (content.isEmpty()) return content
    val sb = StringBuilder(content.length)
    for (index in content.indices) {
        val ch = content[index]
        val keep = when {
            ch == '\n' || ch == '\t' || ch == '\r' -> true
            ch.isISOControl() -> false
            ch == '\u200B' || ch == '\u200C' || ch == '\u200D' -> false
            ch == '\u2060' || ch == '\uFEFF' -> false
            else -> true
        }
        if (keep) sb.append(ch)
    }
    return sb.toString()
}
