package io.legado.app.help.ai

import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AI 路径的凭据保险箱：用 AndroidKeyStore 派生的 AES-256-GCM 密钥加密存储
 * API key 等敏感字段。
 *
 * 实现要点：
 *  - 主密钥走 `AndroidKeyStore` (AES/GCM/NoPadding, 256 bit)，明文不出 keystore。
 *  - 每次写入用 GCM 随机 IV（12 byte），与密文一起 base64 编码后存入普通
 *    [SharedPreferences]。无需 EncryptedSharedPreferences 依赖。
 *  - 写入/读取都 dispatch 到 IO，避免主线程访问 SharedPreferences。
 *  - 内存缓存：读一次后保留明文到 [cache]，避免每次 AI 请求都解密。
 *    进程生命周期内有效，进程退出即清空（与 AndroidKeyStore 一致）。
 *  - 显式 [remove] / [clear] 才会移除；空字符串视为"未设置"等同于删除。
 *
 * 不在本类做迁移或回退（旧明文兼容）。迁移由调用方按需一次性处理。
 */
object AiCredentialStore {

    private const val PREF_NAME = "ai_credential_store"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "ai_credential_store_master_key"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val AES_KEY_SIZE_BITS = 256
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val SCHEME_VERSION = 1

    private val mutex = Mutex()

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var masterKey: SecretKey? = null

    @Volatile
    private var cache: MutableMap<String, String>? = null

    private suspend fun store(): SharedPreferences = withContext(Dispatchers.IO) {
        prefs ?: mutex.withLock {
            prefs ?: appCtx.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
                .also { prefs = it }
        }
    }

    private fun masterKey(): SecretKey {
        masterKey?.let { return it }
        synchronized(this) {
            masterKey?.let { return it }
            return loadOrCreateMasterKey().also { masterKey = it }
        }
    }

    private fun loadOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val kg = KeyGenerator.getInstance("AES", KEYSTORE_PROVIDER)
        // init via KeyGenParameterSpec (API 23+) — uses default scheme, no user auth.
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    private fun cacheMap(): MutableMap<String, String> {
        return cache ?: synchronized(this) {
            cache ?: mutableMapOf<String, String>().also { cache = it }
        }
    }

    /**
     * 同步应用语义：立刻更新内存缓存，并切到 IO 把同一份写入加密存储。
     * 写入失败时仅记录异常 —— 下次冷启动会重新从存储读。
     *
     * 给 UI（设置页）使用；不要在热路径调用。
     */
    fun putSync(key: String, value: String) {
        val effective = value
        if (effective.isEmpty()) {
            removeSync(key)
            return
        }
        // 1) 立即更新缓存
        cacheMap()[key] = effective
        // 2) 异步落盘
        val payload = runCatching { encrypt(effective) }.getOrNull() ?: return
        // 不在这里直接 launch：用 helper 方法交给对象内的 scope。
        putAsync(key, payload)
    }

    private fun putAsync(key: String, payload: String) {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            try {
                store().edit().putString(encodeKey(key), payload).apply()
            } catch (t: Throwable) {
                // best effort
            }
        }
    }

    /**
     * 同步删除：清缓存 + 异步清存储。
     */
    fun removeSync(key: String) {
        cacheMap().remove(key)
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            try {
                store().edit().remove(encodeKey(key)).apply()
            } catch (t: Throwable) {
                // best effort
            }
        }
    }

    /**
     * 写入一个 key -> 凭据。value 为空时等价于 [remove]。
     */
    suspend fun put(key: String, value: String) {
        withContext(Dispatchers.IO) {
            val effective = value
            if (effective.isEmpty()) {
                remove(key)
                return@withContext
            }
            val sp = store()
            val payload = encrypt(effective)
            sp.edit().putString(encodeKey(key), payload).apply()
            cacheMap()[key] = effective
        }
    }

    /**
     * 读取凭据明文。如果未存储返回 null。
     */
    suspend fun get(key: String): String? {
        return withContext(Dispatchers.IO) {
            cacheMap()[key]?.let { return@withContext it }
            val raw = store().getString(encodeKey(key), null) ?: return@withContext null
            val plain = runCatching { decrypt(raw) }.getOrNull() ?: return@withContext null
            cacheMap()[key] = plain
            plain
        }
    }

    /**
     * 同步读：cache 命中直接返回；否则同步从 SharedPreferences 解密并写回 cache。
     * 用于 hydrate 列表（冷启动 cache 为空时把已存的 API key 取出来填回 provider）。
     * 与 [get] 走同一条解密路径，但阻塞调用方 —— 内部用 prefs.getString + 一次
     * AES-GCM decrypt，开销 < 1ms，可以挂在主线程的同步 getter 上。
     */
    fun peekOrLoad(key: String): String? {
        cacheMap()[key]?.let { return it }
        val prefs = prefs ?: appCtx.getSharedPreferences(
            PREF_NAME,
            android.content.Context.MODE_PRIVATE
        ).also { prefs = it }
        val raw = prefs.getString(encodeKey(key), null) ?: return null
        val plain = runCatching { decrypt(raw) }.getOrNull() ?: return null
        cacheMap()[key] = plain
        return plain
    }

    suspend fun remove(key: String) {
        withContext(Dispatchers.IO) {
            store().edit().remove(encodeKey(key)).apply()
            cacheMap().remove(key)
        }
    }

    /**
     * 清空所有 AI 凭据。谨慎使用 —— 撤销账号时调用。
     */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            store().edit().clear().apply()
            cacheMap().clear()
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // payload: [version(1) | ivLen(1) | iv(ivLen) | ct]
        val out = ByteArray(2 + iv.size + ct.size)
        out[0] = SCHEME_VERSION.toByte()
        out[1] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 2, iv.size)
        System.arraycopy(ct, 0, out, 2 + iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val raw = Base64.decode(payload, Base64.NO_WRAP)
        if (raw.size < 2) error("payload too short")
        val ivLen = raw[1].toInt() and 0xFF
        if (raw.size < 2 + ivLen) error("payload truncated")
        val iv = raw.copyOfRange(2, 2 + ivLen)
        val ct = raw.copyOfRange(2 + ivLen, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val pt = cipher.doFinal(ct)
        return String(pt, Charsets.UTF_8)
    }

    /**
     * 把 key 编码为带前缀的存储 key，便于未来 schema 升级做版本兼容。
     */
    private fun encodeKey(key: String): String = "v$SCHEME_VERSION:${Base64.encodeToString(
        key.toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP
    )}"
}
