package com.workbuddy.proxy.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 账号池。线程安全的轮询调度 + 失败冷却。
 *
 * 设计取舍：不做会话亲和（affinity）也不做权重打分——手机端通常只有一两个号，
 * 越简单越可预测。某个号失败只影响它自己，下一个请求自动落到别的号上。
 */
object AccountStore {

    private const val FILE = "wb_accounts"
    private const val K_LIST = "accounts"

    private val lock = Any()
    private val accounts = ArrayList<Account>()
    private var cursor = 0
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
                        accounts.add(Account.fromJson(arr.getJSONObject(i)))
                    }
                }
            }
            loaded = true
        }
    }

    fun all(): List<Account> = synchronized(lock) { ArrayList(accounts) }

    fun size(): Int = synchronized(lock) { accounts.size }

    fun find(uid: String): Account? = synchronized(lock) { accounts.firstOrNull { it.uid == uid } }

    fun add(a: Account): Account = synchronized(lock) {
        val idx = accounts.indexOfFirst { it.uid == a.uid }
        if (idx >= 0) {
            val old = accounts[idx]
            // 重新登录：令牌与状态全部刷新，保留开关偏好
            a.enabled = old.enabled
            accounts[idx] = a
        } else {
            accounts.add(a)
        }
        saveLocked()
        a
    }

    fun remove(uid: String): Boolean = synchronized(lock) {
        val removed = accounts.removeAll { it.uid == uid }
        if (removed) saveLocked()
        removed
    }

    fun setEnabled(uid: String, enabled: Boolean) = synchronized(lock) {
        accounts.firstOrNull { it.uid == uid }?.let {
            it.enabled = enabled
            saveLocked()
        }
    }

    fun replace(uid: String, mutate: (Account) -> Unit) = synchronized(lock) {
        accounts.firstOrNull { it.uid == uid }?.let {
            mutate(it)
            saveLocked()
        }
    }

    /** 轮询取一个可用账号；realm 为 null 表示不限区域。 */
    fun pick(realm: String?, exclude: Set<String> = emptySet()): Account? = synchronized(lock) {
        if (accounts.isEmpty()) return null
        val now = System.currentTimeMillis()
        val total = accounts.size
        for (off in 0 until total) {
            val i = (cursor + off) % total
            val a = accounts[i]
            if (!a.enabled) continue
            if (a.accessToken.isBlank()) continue
            if (realm != null && a.realm != realm) continue
            if (a.uid in exclude) continue
            if (a.cooldownUntil > now) continue
            cursor = (i + 1) % total
            return a
        }
        return null
    }

    fun markSuccess(a: Account) {
        a.failStreak = 0
        a.cooldownUntil = 0
        a.lastError = ""
        a.tokenExpired = false
    }

    /**
     * 记一笔失败并按连败次数退避：30s → 1m → 2m → 5m → 10m（上限）。
     * 上游明确限流（429 / 额度）由调用方给更长的冷却。
     */
    fun markFailure(a: Account, message: String, cooldownMs: Long = 0) {
        a.failStreak += 1
        a.lastError = message.take(300)
        val backoff = when (a.failStreak) {
            1 -> 30_000L
            2 -> 60_000L
            3 -> 120_000L
            4 -> 300_000L
            else -> 600_000L
        }
        val wait = if (cooldownMs > 0) maxOf(cooldownMs, backoff) else backoff
        a.cooldownUntil = System.currentTimeMillis() + wait
    }

    private fun saveLocked() {
        val arr = JSONArray()
        for (a in accounts) arr.put(a.toJson())
        sp.edit().putString(K_LIST, arr.toString()).apply()
    }

    fun save() = synchronized(lock) { if (loaded) saveLocked() }
}
