package io.legado.app.help.ai

import io.legado.app.data.ai.AiImageProviderConfig
import io.legado.app.data.ai.AiMcpServerConfig
import io.legado.app.data.ai.AiProviderConfig

/**
 * 把列表中各项的 `apiKey` 从 [AiCredentialStore] 取出来填回去。
 *
 *  - 优先用 [AiCredentialStore.peekCached]（即加密存储）值。
 *  - 若 store 缺值而 entry 自带明文（老数据兼容），调用 [AiCredentialStore.putSync]
 *    把它挪到 store（同时清掉 entry 的明文）。
 *
 * 读到的列表对外保持"完整"形态，调用方无需关心 apiKey 是从哪来的。
 */
internal fun List<AiProviderConfig>.hydrateProviderApiKeys(
    keyOf: (String) -> String
): List<AiProviderConfig> = map { provider ->
    val storeKey = keyOf(provider.id)
    val cached = AiCredentialStore.peekCached(storeKey)
    when {
        cached != null -> provider.copy(apiKey = cached)
        provider.apiKey.isNotBlank() -> {
            // 旧数据：明文嵌在 list 里，迁到 store
            AiCredentialStore.putSync(storeKey, provider.apiKey)
            provider
        }
        else -> provider
    }
}

internal fun List<AiMcpServerConfig>.hydrateMcpApiKeys(
    keyOf: (String) -> String
): List<AiMcpServerConfig> = map { server ->
    val storeKey = keyOf(server.id)
    val cached = AiCredentialStore.peekCached(storeKey)
    when {
        cached != null -> server.copy(apiKey = cached)
        server.apiKey.isNotBlank() -> {
            AiCredentialStore.putSync(storeKey, server.apiKey)
            server
        }
        else -> server
    }
}

internal fun List<AiImageProviderConfig>.hydrateImageApiKeys(
    keyOf: (String) -> String
): List<AiImageProviderConfig> = map { provider ->
    val storeKey = keyOf(provider.id)
    val cached = AiCredentialStore.peekCached(storeKey)
    when {
        cached != null -> provider.copy(apiKey = cached)
        provider.apiKey.isNotBlank() -> {
            AiCredentialStore.putSync(storeKey, provider.apiKey)
            provider
        }
        else -> provider
    }
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
