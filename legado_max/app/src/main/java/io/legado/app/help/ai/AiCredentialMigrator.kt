package io.legado.app.help.ai

import io.legado.app.constant.PreferKey
import io.legado.app.data.ai.AiImageProviderConfig
import io.legado.app.data.ai.AiMcpServerConfig
import io.legado.app.data.ai.AiProviderConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把旧明文 SharedPreferences 里残留的 AI 凭据一次性迁到
 * [AiCredentialStore] 加密保险箱。幂等，多次调用安全。
 *
 * 触发时机：app 启动后第一次读 AI 配置前（[BaseApplication.onCreate]）。
 * 不阻塞主线程 —— 实际读取是 IO，可在子线程跑。
 */
object AiCredentialMigrator {

    private val done = AtomicBoolean(false)

    suspend fun runIfNeeded() {
        if (!done.compareAndSet(false, true)) return
        // 顺序：tavily 独立凭据 -> 三个 list
        migrateTavily()
        migrateProviderList()
        migrateImageProviderList()
        migrateMcpServerList()
    }

    private suspend fun migrateTavily() {
        val key = AiCredentialKeys.tavilyApiKey()
        if (AiCredentialStore.peekCached(key) != null) return
        val legacy = appCtx.getPrefString(PreferKey.aiTavilyApiKey).orEmpty()
        if (legacy.isBlank()) return
        AiCredentialStore.put(key, legacy)
        // 清掉明文
        appCtx.removePref(PreferKey.aiTavilyApiKey)
    }

    private suspend fun migrateProviderList() =
        migrateList<AiProviderConfig>(
            prefKey = PreferKey.aiProviderList,
            takeApiKey = { it.apiKey },
            clearApiKey = { cfg -> cfg.copy(apiKey = "") },
            credentialKeyFor = { id -> AiCredentialKeys.providerApiKey(id) },
        )

    private suspend fun migrateImageProviderList() =
        migrateList<AiImageProviderConfig>(
            prefKey = PreferKey.aiImageProviderList,
            takeApiKey = { it.apiKey },
            clearApiKey = { cfg -> cfg.copy(apiKey = "") },
            credentialKeyFor = { id -> AiCredentialKeys.imageApiKey(id) },
        )

    private suspend fun migrateMcpServerList() =
        migrateList<AiMcpServerConfig>(
            prefKey = PreferKey.aiMcpServerList,
            takeApiKey = { it.apiKey },
            clearApiKey = { cfg -> cfg.copy(apiKey = "") },
            credentialKeyFor = { id -> AiCredentialKeys.mcpApiKey(id) },
        )

    private suspend inline fun <reified T> migrateList(
        prefKey: String,
        takeApiKey: (T) -> String,
        clearApiKey: (T) -> T,
        credentialKeyFor: (String) -> String,
    ) {
        val raw = appCtx.getPrefString(prefKey)
        if (raw.isNullOrBlank()) return
        val list: List<T> = GSON.fromJsonArray<T>(raw).getOrDefault(emptyList())
        if (list.isEmpty()) return
        var mutated = false
        val rewritten = list.map { cfg ->
            val id = extractId(cfg as Any) ?: return@map cfg
            val embedded = takeApiKey(cfg)
            val storeKey = credentialKeyFor(id)
            val cached = AiCredentialStore.peekCached(storeKey)
            if (embedded.isNotBlank() && cached == null) {
                AiCredentialStore.put(storeKey, embedded)
                mutated = true
                clearApiKey(cfg)
            } else {
                cfg
            }
        }
        if (mutated) {
            val json = GSON.toJson(rewritten)
            appCtx.putPrefString(prefKey, json)
        }
    }

    /**
     * 通过反射读 id 字段（三个 data class 都有 `val id: String`）。
     */
    private fun extractId(cfg: Any): String? = try {
        cfg.javaClass.getDeclaredField("id").apply { isAccessible = true }
            .get(cfg) as? String
    } catch (t: Throwable) {
        null
    }
}
