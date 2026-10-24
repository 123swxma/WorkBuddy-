package com.workbuddy.proxy.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 一个 API Key。`realm` 决定这把 Key 只能走哪个区域的账号：
 *  - `intl` 只派国际版账号
 *  - `cn`   只派国内版账号
 *  - `any`  不限制，按模型名推断
 *
 * 这样不同客户端可以各用各的 Key，国内外流量互不干扰——同一个模型名在
 * 两个区域都存在时，靠 Key 决定走哪边，而不是靠猜。
 */
class ApiKey(
    val id: String,
    val key: String,
    var label: String,
    var realm: String,
    var enabled: Boolean,
    val createdAt: Long,
    var lastUsedAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("key", key)
        .put("label", label)
        .put("realm", realm)
        .put("enabled", enabled)
        .put("createdAt", createdAt)
        .put("lastUsedAt", lastUsedAt)

    /** 面板展示用：完整 Key 必须可见，否则用户没法复制。 */
    fun publicJson(): JSONObject = toJson()

    companion object {
        fun fromJson(o: JSONObject): ApiKey = ApiKey(
            id = o.optString("id"),
            key = o.optString("key"),
            label = o.optString("label", "未命名"),
            realm = Realms.normalizeKeyRealm(o.optString("realm", "any")),
            enabled = o.optBoolean("enabled", true),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            lastUsedAt = o.optLong("lastUsedAt", 0L)
        )
    }
}

object KeyStore {

    private const val FILE = "wb_keys"
    private const val K_LIST = "keys"
    private const val LEGACY_FILE = "wb_proxy"
    private const val LEGACY_KEY = "api_key"

    private val lock = Any()
    private val keys = ArrayList<ApiKey>()
    private lateinit var sp: SharedPreferences
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val raw = sp.getString(K_LIST, "") ?: ""
            if (raw.isNotBlank()) {
                runCatching {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        keys.add(ApiKey.fromJson(arr.getJSONObject(i)))
                    }
                }
            }
            if (keys.isEmpty()) {
                // 迁移：老版本只有一把自动生成的 Key，把它变成第一条
                val legacy = runCatching {
                    ctx.applicationContext.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
                        .getString(LEGACY_KEY, "") ?: ""
                }.getOrDefault("")
                val first = if (legacy.isNotBlank()) {
                    ApiKey(
                        id = shortId(),
                        key = legacy,
                        label = "默认 Key",
                        realm = "any",
                        enabled = true,
                        createdAt = System.currentTimeMillis(),
                        lastUsedAt = 0L
                    )
                } else {
                    build("默认 Key", "any")
                }
                keys.add(first)
                saveLocked()
            }
            loaded = true
        }
    }

    fun all(): List<ApiKey> = synchronized(lock) { ArrayList(keys) }

    fun size(): Int = synchronized(lock) { keys.size }

    /** 按 Key 值查（只认启用的）。鉴权走这里。 */
    fun find(token: String): ApiKey? = synchronized(lock) {
        if (token.isBlank()) return null
        val hit = keys.firstOrNull { it.enabled && it.key == token } ?: return null
        hit.lastUsedAt = System.currentTimeMillis()
        hit
    }

    fun findById(id: String): ApiKey? = synchronized(lock) { keys.firstOrNull { it.id == id } }

    fun create(label: String, realm: String): ApiKey = synchronized(lock) {
        val created = build(uniqueLabel(label.ifBlank { "新 Key" }, null), realm)
        keys.add(created)
        saveLocked()
        created
    }

    /**
     * 名称去重，跟 Windows 新建文件夹一个规则：
     * 「电脑端」→「电脑端 (2)」→「电脑端 (3)」…
     *
     * @param excludeId 改自己名字时把自己排除掉，否则会变成「x (2)」
     */
    private fun uniqueLabel(base: String, excludeId: String?): String {
        val taken = keys.filter { it.id != excludeId }.map { it.label }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val removed = keys.removeAll { it.id == id }
        if (removed) saveLocked()
        removed
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        keys.firstOrNull { it.id == id }?.let {
            it.enabled = enabled
            saveLocked()
        }
    }

    fun rename(id: String, label: String) = synchronized(lock) {
        keys.firstOrNull { it.id == id }?.let {
            it.label = uniqueLabel(label.ifBlank { it.label }, id)
            saveLocked()
        }
    }

    /** 同时改名称与区域（区域改了立即影响后续派号）。 */
    fun update(id: String, label: String, realm: String) = synchronized(lock) {
        keys.firstOrNull { it.id == id }?.let {
            it.label = uniqueLabel(label.ifBlank { it.label }, id)
            it.realm = Realms.normalizeKeyRealm(realm)
            saveLocked()
        }
    }

    /** 至少留一把启用的 Key，避免用户把自己锁在门外。 */
    fun hasEnabled(): Boolean = synchronized(lock) { keys.any { it.enabled } }

    private fun build(label: String, realm: String): ApiKey = ApiKey(
        id = shortId(),
        key = newSecret(),
        label = label,
        realm = Realms.normalizeKeyRealm(realm),
        enabled = true,
        createdAt = System.currentTimeMillis(),
        lastUsedAt = 0L
    )

    private fun saveLocked() {
        val arr = JSONArray()
        for (k in keys) arr.put(k.toJson())
        sp.edit().putString(K_LIST, arr.toString()).apply()
    }

    private fun shortId(): String {
        val b = ByteArray(4)
        SecureRandom().nextBytes(b)
        val sb = StringBuilder()
        for (x in b) sb.append("%02x".format(x.toInt() and 0xFF))
        return sb.toString()
    }

    private fun newSecret(): String {
        val raw = ByteArray(24)
        SecureRandom().nextBytes(raw)
        val sb = StringBuilder("sk-wb-")
        for (b in raw) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}
