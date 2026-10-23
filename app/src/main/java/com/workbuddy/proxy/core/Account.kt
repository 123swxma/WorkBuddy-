package com.workbuddy.proxy.core

import org.json.JSONObject

/**
 * 一个 WorkBuddy 账号：uid + 访问令牌 + 区域（国际版 / 国内版）。
 *
 * 上游只认 Bearer accessToken，uid 用来拼 X-User-Id 并参与会话 id 派生；
 * refreshToken 用于 401 时静默续期，续不上就把账号标成 expired 让用户重登。
 */
class Account(
    val uid: String,
    var nickname: String,
    var accessToken: String,
    var refreshToken: String,
    var expiresAt: Long,
    var realm: String,
    var domain: String,
    var enabled: Boolean,
    val addedAt: Long
) {
    // ---- 运行时状态（不落盘）----
    @Volatile var cooldownUntil: Long = 0
    @Volatile var lastError: String = ""
    @Volatile var failStreak: Int = 0
    @Volatile var tokenExpired: Boolean = false

    fun label(): String = nickname.ifBlank { uid.take(8) }

    /** 令牌是否可用（未过期或未知过期时间）。 */
    fun tokenUsable(now: Long = System.currentTimeMillis()): Boolean {
        if (tokenExpired) return false
        if (accessToken.isBlank()) return false
        return expiresAt <= 0 || expiresAt - 60_000 > now
    }

    fun ready(now: Long = System.currentTimeMillis()): Boolean =
        enabled && cooldownUntil <= now && accessToken.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("nickname", nickname)
        put("accessToken", accessToken)
        put("refreshToken", refreshToken)
        put("expiresAt", expiresAt)
        put("realm", realm)
        put("domain", domain)
        put("enabled", enabled)
        put("addedAt", addedAt)
    }

    /** 给面板看的版本：不带令牌明文，只给掩码。 */
    fun publicJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("nickname", nickname)
        put("realm", realm)
        put("domain", domain)
        put("enabled", enabled)
        put("addedAt", addedAt)
        put("expiresAt", expiresAt)
        put("cooldownUntil", cooldownUntil)
        put("failStreak", failStreak)
        put("lastError", lastError)
        put("tokenExpired", tokenExpired)
        put("tokenTail", if (accessToken.length > 8) accessToken.takeLast(6) else "")
    }

    companion object {
        fun fromJson(o: JSONObject): Account = Account(
            uid = o.optString("uid"),
            nickname = o.optString("nickname"),
            accessToken = o.optString("accessToken"),
            refreshToken = o.optString("refreshToken"),
            expiresAt = o.optLong("expiresAt", 0L),
            realm = o.optString("realm", "intl").ifBlank { "intl" },
            domain = o.optString("domain"),
            enabled = o.optBoolean("enabled", true),
            addedAt = o.optLong("addedAt", System.currentTimeMillis())
        )
    }
}
