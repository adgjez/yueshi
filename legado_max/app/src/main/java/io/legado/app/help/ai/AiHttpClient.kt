package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * AI 路径专用的 OkHttpClient。
 *
 * 为什么不复用 [io.legado.app.help.http.okHttpClient]：
 *  - 全局 client 会带上 [io.legado.app.help.config.AppConfig.unsafeSsl]，
 *    关闭证书校验对 AI 路径毫无意义反而增加风险；
 *  - 全局 client 的 cookie jar / address cache / cronet / DecompressInterceptor
 *    都是为"用户配置书源"服务，对 AI 是无意义的副作用；
 *  - 全局 client 的 [io.legado.app.help.http.OkHttpExceptionInterceptor] 会
 *    把错误包装成书源语义，对 AI 流式解析不利。
 *
 * AI 路径只要：
 *  - 强制 TLS（不允许 CLEARTEXT 降级）
 *  - 强制不带 unsafeSsl
 *  - 默认长 read timeout（流式响应可长达 5min）
 *  - 不带 cookie / dns cache
 */
object AiHttpClient {

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 120L
    private const val WRITE_TIMEOUT_SECONDS = 60L
    private const val CALL_TIMEOUT_SECONDS = 300L

    @Volatile
    private var cachedClient: OkHttpClient? = null

    /**
     * 取得（或惰性构造）AI 路径默认 OkHttpClient。
     * 进程级单例。配置变更不需要重建（AI 路径配置基本不变）。
     */
    fun client(): OkHttpClient {
        return cachedClient ?: synchronized(this) {
            cachedClient ?: builder().build().also { cachedClient = it }
        }
    }

    /**
     * 返回一个新的 builder，便于调用方在默认配置上做局部调整（如测试时换 UA）。
     * 不会污染 [client] 缓存。
     */
    fun builder(): OkHttpClient.Builder {
        val specs = listOf(
            ConnectionSpec.MODERN_TLS,
            ConnectionSpec.COMPATIBLE_TLS
        )
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionSpecs(specs)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(UserAgentInterceptor)
    }

    /**
     * 显式让 AI 路径的请求带上 [io.legado.app.help.config.AppConfig.userAgent]，
     * 同时**不**继承全局 OkHttpClient 的 keep-alive / cache-control 行为
     * （全局默认对所有请求都加，对 AI 流式响应反而可能造成旧缓冲）。
     */
    private object UserAgentInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.header(AppConst.UA_NAME) != null) {
                return chain.proceed(request)
            }
            val builder = request.newBuilder()
                .addHeader(AppConst.UA_NAME, AppConfig.userAgent)
            return chain.proceed(builder.build())
        }
    }
}

private typealias AppConst = io.legado.app.constant.AppConst
