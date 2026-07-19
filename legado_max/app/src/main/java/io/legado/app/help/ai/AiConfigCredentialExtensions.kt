package io.legado.app.help.ai

import io.legado.app.data.ai.AiImageProviderConfig
import io.legado.app.data.ai.AiMcpServerConfig
import io.legado.app.data.ai.AiProviderConfig

/**
 * 把列表中各项的 `apiKey` 从 [AiCredentialStore] 取出来填回去。
 *
 * setter 走 [persistProviderApiKeys] 后 list JSON 里的 `apiKey` 字段永远是
 * 空字符串（单一事实源在 store），hydrate 只负责从 store 读回来。
 */
internal fun List<AiProviderConfig>.hydrateProviderApiKeys(
    keyOf: (String) -> String
): List<AiProviderConfig> = map { provider ->
    val storeKey = keyOf(provider.id)
    val cached = AiCredentialStore.peekOrLoad(storeKey)
    if (cached != null) provider.copy(apiKey = cached) else provider
}

internal fun List<AiMcpServerConfig>.hydrateMcpApiKeys(
    keyOf: (String) -> String
): List<AiMcpServerConfig> = map { server ->
    val storeKey = keyOf(server.id)
    val cached = AiCredentialStore.peekOrLoad(storeKey)
    if (cached != null) server.copy(apiKey = cached) else server
}

internal fun List<AiImageProviderConfig>.hydrateImageApiKeys(
    keyOf: (String) -> String
): List<AiImageProviderConfig> = map { provider ->
    val storeKey = keyOf(provider.id)
    val cached = AiCredentialStore.peekOrLoad(storeKey)
    if (cached != null) provider.copy(apiKey = cached) else provider
}

/**
 * 把列表中各项的 `apiKey` 持久化到 [AiCredentialStore]，并返回 apiKey 字段
 * 全部清空的"可序列化"列表（用于回写到 SharedPreferences 的 GSON blob）。
 *
 *  - 若 apiKey 为空，等价于从 store 删除该 key（保持单一事实源）。
 */
internal fun List<AiProviderConfig>.persistProviderApiKeys(
    keyOf: (String) -> String
): List<AiProviderConfig> = map { provider ->
    val storeKey = keyOf(provider.id)
    if (provider.apiKey.isNotBlank()) {
        AiCredentialStore.putSync(storeKey, provider.apiKey)
    } else {
        AiCredentialStore.removeSync(storeKey)
    }
    provider.copy(apiKey = "")
}

internal fun List<AiMcpServerConfig>.persistMcpApiKeys(
    keyOf: (String) -> String
): List<AiMcpServerConfig> = map { server ->
    val storeKey = keyOf(server.id)
    if (server.apiKey.isNotBlank()) {
        AiCredentialStore.putSync(storeKey, server.apiKey)
    } else {
        AiCredentialStore.removeSync(storeKey)
    }
    server.copy(apiKey = "")
}

internal fun List<AiImageProviderConfig>.persistImageApiKeys(
    keyOf: (String) -> String
): List<AiImageProviderConfig> = map { provider ->
    val storeKey = keyOf(provider.id)
    if (provider.apiKey.isNotBlank()) {
        AiCredentialStore.putSync(storeKey, provider.apiKey)
    } else {
        AiCredentialStore.removeSync(storeKey)
    }
    provider.copy(apiKey = "")
}
