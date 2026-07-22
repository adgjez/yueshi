package io.legado.app.help.ai

import io.legado.app.BuildConfig
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.postJson
import io.legado.app.data.ai.AiMcpServerConfig
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AiMcpClient {

    private const val PROTOCOL_VERSION = "2025-06-18"
    private const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
    private const val HEADER_SESSION_ID = "Mcp-Session-Id"
    private const val TOOL_CACHE_TTL_MS = 60_000L
    private const val MAX_MCP_RESPONSE_BYTES = 1_048_576L

    private data class SessionState(
        val sessionId: String?,
        val protocolVersion: String,
        val configFingerprint: String
    )

    private data class CachedTools(
        val fingerprint: String,
        val createdAt: Long,
        val tools: List<AiResolvedTool>
    )

    private data class McpToolDescriptor(
        val name: String,
        val title: String,
        val description: String,
        val inputSchema: JSONObject
    )

    // 并发 MCP 工具调用可能同时读写这两个 Map，用 ConcurrentHashMap 避免 race。
    private val sessionMap = ConcurrentHashMap<String, SessionState>()
    private val toolCache = ConcurrentHashMap<String, CachedTools>()

    // per-server Mutex：避免一个 server 的慢 HTTP 阻塞其他 server。
    // 同一 server 的 ensureSession / resolveTools 仍串行，保证 check-then-act 原子性。
    private val serverLocks = ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(serverId: String): Mutex =
        serverLocks.computeIfAbsent(serverId) { Mutex() }

    /**
     * 删除 server 配置时调用，清理对应的 session/tool 缓存，避免内存残留。
     * 不删除 serverLocks：若并发 callTool 正持有该 Mutex，删除后 mutexFor 会创建新锁，
     * 破坏互斥性。serverId 是 UUID 不会复用，残留的空 Mutex 体积可忽略。
     */
    fun invalidate(serverId: String) {
        sessionMap.remove(serverId)
        toolCache.remove(serverId)
    }

    suspend fun resolveTools(servers: List<AiMcpServerConfig>): List<AiResolvedTool> {
        val result = mutableListOf<AiResolvedTool>()
        val usedNames = mutableSetOf<String>()
        servers.filter { it.enabled }.forEach { server ->
            val fingerprint = server.fingerprint()
            val cached = toolCache[server.id]
            if (cached != null
                && cached.fingerprint == fingerprint
                && System.currentTimeMillis() - cached.createdAt < TOOL_CACHE_TTL_MS
            ) {
                cached.tools.forEach {
                    if (usedNames.add(it.name)) result += it
                }
                return@forEach
            }
            // check-fetch-write 不原子：两个协程都通过缓存检查后会各自远端拉取并写入。
            // 用 per-server Mutex 串行化整段，second waiter 进来后 double-check 缓存可直接复用。
            // 持锁期间调用 listToolsLocked（内部用 ensureSessionLocked，不再重复加锁）。
            val tools = mutexFor(server.id).withLock {
                val doubleChecked = toolCache[server.id]
                if (doubleChecked != null
                    && doubleChecked.fingerprint == fingerprint
                    && System.currentTimeMillis() - doubleChecked.createdAt < TOOL_CACHE_TTL_MS
                ) {
                    return@withLock doubleChecked.tools
                }
                val fetched = runCatching {
                    val localNames = usedNames.toMutableSet()
                    listToolsLocked(server, fingerprint).mapIndexed { index, descriptor ->
                        val alias = buildToolAlias(server, descriptor.name, index, localNames)
                        localNames += alias
                        buildResolvedTool(server, alias, descriptor)
                    }
                }.getOrElse {
                    if (it is CancellationException) throw it
                    sessionMap.remove(server.id)
                    emptyList()
                }
                toolCache[server.id] = CachedTools(
                    fingerprint = fingerprint,
                    createdAt = System.currentTimeMillis(),
                    tools = fetched
                )
                fetched
            }
            tools.forEach {
                if (usedNames.add(it.name)) result += it
            }
        }
        return result
    }

    /**
     * 调用方必须已持有 [mutexFor] 对应的 per-server Mutex，session 在内部通过
     * [ensureSessionLocked] 获得（不加锁）。
     */
    private suspend fun listToolsLocked(
        server: AiMcpServerConfig,
        fingerprint: String
    ): List<McpToolDescriptor> {
        val session = ensureSessionLocked(server, fingerprint)
        return listToolsWithSession(server, session)
    }

    private suspend fun listToolsWithSession(
        server: AiMcpServerConfig,
        session: SessionState
    ): List<McpToolDescriptor> {
        val tools = mutableListOf<McpToolDescriptor>()
        var cursor: String? = null
        do {
            val requestId = nextRequestId()
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val response = postJsonRpc(
                server = server,
                session = session,
                body = jsonRpcRequest("tools/list", params, requestId),
                requestId = requestId
            )
            val result = response.optJSONObject("result") ?: JSONObject()
            val toolArray = result.optJSONArray("tools") ?: JSONArray()
            for (index in 0 until toolArray.length()) {
                val tool = toolArray.optJSONObject(index) ?: continue
                tools += McpToolDescriptor(
                    name = tool.optString("name"),
                    title = tool.optString("title"),
                    description = tool.optString("description"),
                    inputSchema = tool.optJSONObject("inputSchema") ?: emptyObjectSchema()
                )
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
        } while (cursor != null)
        return tools.filter { it.name.isNotBlank() }
    }

    private fun buildResolvedTool(
        server: AiMcpServerConfig,
        alias: String,
        descriptor: McpToolDescriptor
    ): AiResolvedTool {
        val description = buildString {
            append("MCP ")
            append(server.name)
            if (descriptor.title.isNotBlank() && descriptor.title != descriptor.name) {
                append(" - ")
                append(descriptor.title)
            }
            if (descriptor.description.isNotBlank()) {
                append(": ")
                append(descriptor.description)
            }
        }
        return AiResolvedTool(
            name = alias,
            definition = JSONObject().apply {
                put("type", "function")
                put(
                    "function",
                    JSONObject().apply {
                        put("name", alias)
                        put("description", description)
                        put("parameters", sanitizeSchema(descriptor.inputSchema))
                    }
                )
            },
            execute = { args ->
                callTool(server, descriptor.name, args)
            }
        )
    }

    suspend fun callTool(
        server: AiMcpServerConfig,
        toolName: String,
        arguments: JSONObject?
    ): String {
        val session = ensureSession(server)
        val requestId = nextRequestId()
        val response = postJsonRpc(
            server = server,
            session = session,
            body = jsonRpcRequest(
                method = "tools/call",
                params = JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments ?: JSONObject())
                },
                id = requestId
            ),
            requestId = requestId
        )
        return (response.optJSONObject("result") ?: JSONObject()).toString()
    }

    private suspend fun ensureSession(server: AiMcpServerConfig): SessionState {
        val fingerprint = server.fingerprint()
        sessionMap[server.id]?.takeIf { it.configFingerprint == fingerprint }?.let { return it }
        // check-then-initialize 不原子：两个协程都通过 check 后会各自发 initialize，
        // 一个 session 被另一个覆盖，被覆盖的 SSE 连接泄漏。用 per-server Mutex 串行化，
        // second waiter 拿锁后 double-check 直接复用 first 的结果。
        val session = mutexFor(server.id).withLock {
            ensureSessionLocked(server, fingerprint)
        }
        // notifications/initialized 是 fire-and-forget，不必阻塞其他协程拿锁，放在锁外。
        sendInitializedNotification(server, session)
        return session
    }

    /**
     * 调用方必须已持有 [mutexFor] 对应的 per-server Mutex。
     */
    private suspend fun ensureSessionLocked(
        server: AiMcpServerConfig,
        fingerprint: String
    ): SessionState {
        sessionMap[server.id]?.takeIf { it.configFingerprint == fingerprint }?.let { return it }
        val requestId = nextRequestId()
        val initializeBody = jsonRpcRequest(
            method = "initialize",
            params = JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put(
                    "clientInfo",
                    JSONObject().apply {
                        put("name", "Legado")
                        put("version", BuildConfig.VERSION_NAME)
                    }
                )
                put("capabilities", JSONObject())
            },
            id = requestId
        )
        val response = AiHttpClient.client().newCallResponse {
            url(server.endpoint)
            addHeader("Accept", "application/json, text-event-stream")
            addHeader("Content-Type", "application/json")
            server.apiKey.trim().takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            postJson(initializeBody.toString())
        }
        return response.use { rawResponse ->
            val rpcResponse = readJsonRpcResponse(rawResponse, requestId)
            val result = rpcResponse.optJSONObject("result") ?: JSONObject()
            val initialized = SessionState(
                sessionId = rawResponse.header(HEADER_SESSION_ID),
                protocolVersion = result.optString("protocolVersion").ifBlank { PROTOCOL_VERSION },
                configFingerprint = fingerprint
            )
            sessionMap[server.id] = initialized
            toolCache.remove(server.id)
            initialized
        }
    }

    private suspend fun sendInitializedNotification(
        server: AiMcpServerConfig,
        session: SessionState
    ) {
        runCatching {
            AiHttpClient.client().newCallResponse {
                url(server.endpoint)
                addHeader("Accept", "application/json, text/event-stream")
                addHeader("Content-Type", "application/json")
                addHeader(HEADER_PROTOCOL_VERSION, session.protocolVersion)
                session.sessionId?.let { addHeader(HEADER_SESSION_ID, it) }
                server.apiKey.trim().takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
                postJson(
                    JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("method", "notifications/initialized")
                    }.toString()
                )
            }.close()
        }
    }

    private suspend fun postJsonRpc(
        server: AiMcpServerConfig,
        session: SessionState,
        body: JSONObject,
        requestId: String
    ): JSONObject {
        return runCatching {
            AiHttpClient.client().newCallResponse {
                url(server.endpoint)
                addHeader("Accept", "application/json, text/event-stream")
                addHeader("Content-Type", "application/json")
                addHeader(HEADER_PROTOCOL_VERSION, session.protocolVersion)
                session.sessionId?.let { addHeader(HEADER_SESSION_ID, it) }
                server.apiKey.trim().takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
                postJson(body.toString())
            }.use { rawResponse ->
                readJsonRpcResponse(rawResponse, requestId)
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            // session 可能已失效，清理缓存让下次 ensureSession 重新初始化。
            // 不在此处重试，避免持锁调用方嵌套加锁自死锁。
            sessionMap.remove(server.id)
            toolCache.remove(server.id)
            throw it
        }
    }

    private fun readJsonRpcResponse(response: Response, requestId: String): JSONObject {
        val body = response.body ?: throw IllegalStateException("MCP empty response body")
        if (!response.isSuccessful) {
            val payload = response.peekBody(16_384).string()
            throw IllegalStateException(
                "MCP ${response.code} ${response.message}: ${extractJsonRpcError(payload)}"
            )
        }
        val contentLength = body.contentLength()
        if (contentLength > MAX_MCP_RESPONSE_BYTES) {
            throw IllegalStateException("MCP response is too large")
        }
        val payload = body.stringLimited(MAX_MCP_RESPONSE_BYTES, "MCP response is too large")
        if (payload.isBlank()) {
            return JSONObject()
        }
        val isSse = response.header("Content-Type").orEmpty().contains("text/event-stream")
        val rpcResponse = if (isSse) {
            parseSsePayload(payload, requestId)
        } else {
            JSONObject(payload)
        }
        rpcResponse.optJSONObject("error")?.let { error ->
            throw IllegalStateException(
                error.optString("message").ifBlank { error.toString() }
            )
        }
        return rpcResponse
    }

    private fun parseSsePayload(payload: String, requestId: String): JSONObject {
        val eventData = StringBuilder()
        payload.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.startsWith("data:") -> {
                    eventData.append(line.removePrefix("data:").trim()).append('\n')
                }

                line.isBlank() -> {
                    if (eventData.isNotEmpty()) {
                        val json = JSONObject(eventData.toString().trim())
                        if (json.opt("id")?.toString() == requestId) {
                            return json
                        }
                        eventData.clear()
                    }
                }
            }
        }
        if (eventData.isNotEmpty()) {
            val json = JSONObject(eventData.toString().trim())
            if (json.opt("id")?.toString() == requestId) {
                return json
            }
        }
        throw IllegalStateException("MCP SSE response missing matching id")
    }

    private fun ResponseBody.stringLimited(maxBytes: Long, tooLargeMessage: String): String {
        val contentLength = contentLength()
        if (contentLength > maxBytes) {
            throw IllegalStateException(tooLargeMessage)
        }
        val source = source()
        source.request(maxBytes + 1L)
        if (source.buffer.size > maxBytes) {
            throw IllegalStateException(tooLargeMessage)
        }
        val charset = contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return source.buffer.readString(charset)
    }

    private fun sanitizeSchema(schema: JSONObject?): JSONObject {
        val result = schema?.let { JSONObject(it.toString()) } ?: JSONObject()
        if (result.optString("type").isBlank()) {
            result.put("type", "object")
        }
        if (!result.has("properties")) {
            result.put("properties", JSONObject())
        }
        return result
    }

    private fun emptyObjectSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
            put("additionalProperties", true)
        }
    }

    private fun buildToolAlias(
        server: AiMcpServerConfig,
        toolName: String,
        index: Int,
        usedNames: Collection<String>
    ): String {
        val serverSlug = slug(server.id).take(16).ifBlank { "server" }
        val toolSlug = slug(toolName).take(32).ifBlank { "tool" }
        val base = "mcp_${serverSlug}_${toolSlug}"
        var candidate = base.take(64)
        if (candidate !in usedNames) return candidate
        val suffix = "_${server.id.filter { it.isLetterOrDigit() }.takeLast(6)}_${index + 1}"
        candidate = (base.take(64 - suffix.length) + suffix).take(64)
        return candidate
    }

    private fun slug(value: String): String {
        return value.lowercase(Locale.getDefault())
            .map { ch ->
                when {
                    ch.isLetterOrDigit() -> ch
                    else -> '_'
                }
            }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
    }

    private fun AiMcpServerConfig.fingerprint(): String {
        // apiKey 用 SHA-256 hash 参与 fingerprint，避免明文长期驻留 sessionMap/toolCache。
        val apiKeyHash = sha256Hex(apiKey.trim())
        return listOf(id, name.trim(), endpoint.trim(), apiKeyHash, enabled).joinToString("|")
    }

    private fun sha256Hex(input: String): String {
        if (input.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun jsonRpcRequest(method: String, params: JSONObject, id: String): JSONObject {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
    }

    private fun nextRequestId(): String = UUID.randomUUID().toString()

    private fun extractJsonRpcError(payload: String): String {
        return runCatching {
            JSONObject(payload).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty().ifBlank { payload }
    }
}
