package com.workbuddy.proxy.core

/**
 * 两个区域的上游参数。全部取自 v1.7.29 抓包核对值：
 *  - 国际版桌面端 5.8.4，UA 三段式 workbuddy-ai
 *  - 国内版桌面端 5.8.3，UA 三段式 WorkBuddy
 *
 * 国内版对话 base 默认 copilot.tencent.com；从海外出口连它会 TLS 复位，
 * 所以留了 www.workbuddy.cn 作为可切换的等价入口。
 */
object Realms {

    const val INTL = "intl"
    const val CN = "cn"

    const val INTL_BASE = "https://www.workbuddy.ai"
    const val INTL_DOMAIN = "www.workbuddy.ai"
    const val CN_BASE_PRIMARY = "https://copilot.tencent.com"
    const val CN_BASE_ALT = "https://www.workbuddy.cn"
    const val CN_DOMAIN = "copilot.tencent.com"
    const val CN_ORIGIN = "https://www.codebuddy.cn"

    fun normalize(realm: String?): String = if (realm == CN) CN else INTL

    fun label(realm: String?): String = if (normalize(realm) == CN) "国内版" else "国际版"

    /** 把任意输入收敛成合法的 http(s) 基址，否则回落。 */
    fun httpBaseOr(value: String?, fallback: String): String {
        val t = (value ?: "").trim().trimEnd('/')
        return if (t.startsWith("http://") || t.startsWith("https://")) t else fallback
    }

    /** 国内版基址的合法化（默认主入口）。 */
    fun chatBaseRaw(value: String?): String = httpBaseOr(value, CN_BASE_PRIMARY)

    /** 对话/登录/模型共用的上游基址。 */
    fun chatBase(realm: String?): String =
        if (normalize(realm) == CN) Tuning.cnBase else Tuning.intlBase

    fun origin(realm: String?): String =
        if (normalize(realm) == CN) CN_ORIGIN else INTL_BASE

    fun domain(realm: String?): String =
        if (normalize(realm) == CN) CN_DOMAIN else INTL_DOMAIN

    fun desktopVersion(realm: String?): String =
        if (normalize(realm) == CN) "5.8.3" else "5.8.4"

    /** 桌面端聊天 UA（三段式：WorkBuddy / 产品 / 内置 CLI）。 */
    fun chatUa(realm: String?): String =
        if (normalize(realm) == CN) "WorkBuddy/5.8.3 WorkBuddy/5.8.3 CLI/2.161.0"
        else "WorkBuddy/5.8.4 workbuddy-ai/5.8.4 CLI/2.161.0"

    /** 登录/刷新/计费类接口的 UA（两段式）。 */
    fun billingUa(realm: String?): String =
        if (normalize(realm) == CN) "WorkBuddy/5.8.3" else "WorkBuddy/5.8.4"

    fun acceptLanguage(realm: String?): String =
        if (normalize(realm) == CN) "zh-CN" else "en-US"

    // ---- 端点 ----
    const val PATH_CHAT = "/v2/chat/completions"
    const val PATH_MODELS = "/v2/enterprises/personal/models"
    const val PATH_AUTH_STATE = "/v2/plugin/auth/state"
    const val PATH_AUTH_TOKEN = "/v2/plugin/auth/token"
    const val PATH_AUTH_REFRESH = "/v2/plugin/auth/token/refresh"
    const val PATH_LOGIN_ACCOUNT = "/v2/plugin/login/account"

    // ---- 计费 / 积分 / 签到 ----
    const val PATH_GET_RESOURCE = "/v2/billing/meter/get-user-resource"
    const val PATH_RESOURCE_SUMMARY = "/billing/meter/get-user-resource-summary"
    const val PATH_FREE_PACKAGES = "/billing/meter/get-user-resource-free-packages"
    const val PATH_PAID_PACKAGES = "/billing/meter/get-user-resource-paid-packages"
    const val PATH_CHECKIN_STATUS = "/v2/billing/meter/checkin-activity-status"
    const val PATH_DAILY_CHECKIN = "/v2/billing/meter/daily-checkin"

    /** 国内版计费域：积分接口在 codebuddy.cn 上，不在 copilot.tencent.com。 */
    const val CN_BILLING = "https://www.codebuddy.cn"

    /** 计费/积分请求该打的基址（与 chat 基址不一定同域）。 */
    fun billingBase(realm: String?): String =
        if (normalize(realm) == CN) CN_BILLING else Tuning.intlBase

    const val ANY = "any"

    /**
     * API Key 的区域约束：intl / cn / any。
     * 与 [normalize] 不同——这里 "any" 是合法值，表示不限定区域。
     */
    fun normalizeKeyRealm(value: String?): String = when ((value ?: "").trim().lowercase()) {
        "intl", "international", "global", "国际", "国际版" -> INTL
        "cn", "china", "国内", "国内版" -> CN
        else -> ANY
    }

    fun keyRealmLabel(realm: String?): String = when (normalizeKeyRealm(realm)) {
        INTL -> "仅国际版"
        CN -> "仅国内版"
        else -> "不限制"
    }

    const val LOGIN_PENDING_CODE = 11217
}
