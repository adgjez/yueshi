package io.legado.app.help.ai

/**
 * AI 路径凭据在 [AiCredentialStore] 里的命名空间 / 编码。
 *
 *  - `tavily/apiKey`              —— Tavily 搜索 API key（独立凭据）
 *  - `provider/{id}/apiKey`       —— AI 文本模型 provider
 *  - `image/{id}/apiKey`          —— AI 图像 provider
 *  - `mcp/{id}/apiKey`            —— MCP server bearer
 *
 * 写入时请用 [encode] + [decode]（已 base64），避免把原始 id（含
 * 用户自定义名称）原样落到 SharedPreferences key 里。
 */
object AiCredentialKeys {

    const val NS_TAVILY = "tavily"
    const val NS_PROVIDER = "provider"
    const val NS_IMAGE = "image"
    const val NS_MCP = "mcp"
    const val SUFFIX_API_KEY = "apiKey"

    fun providerApiKey(providerId: String): String =
        "$NS_PROVIDER/${encode(providerId)}/$SUFFIX_API_KEY"

    fun imageApiKey(providerId: String): String =
        "$NS_IMAGE/${encode(providerId)}/$SUFFIX_API_KEY"

    fun mcpApiKey(serverId: String): String =
        "$NS_MCP/${encode(serverId)}/$SUFFIX_API_KEY"

    fun tavilyApiKey(): String = "$NS_TAVILY/$SUFFIX_API_KEY"

    private fun encode(segment: String): String =
        android.util.Base64.encodeToString(
            segment.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
}
