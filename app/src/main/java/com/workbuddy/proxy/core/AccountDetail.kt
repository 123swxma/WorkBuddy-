package com.workbuddy.proxy.core

import org.json.JSONArray
import org.json.JSONObject

/** 一个积分包（免费包 / 付费包 / 活动包）。 */
data class CreditPackage(
    val name: String,
    val code: String,
    val remain: Double,
    val size: Double,
    val used: Double,
    val expireAt: String,
    val daysLeft: Double?,
    val reason: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("code", code)
        .put("remain", remain)
        .put("size", size)
        .put("used", used)
        .put("expireAt", expireAt)
        .put("daysLeft", daysLeft ?: JSONObject.NULL)
        .put("reason", reason)
}

/** Buddy 加油站（每日签到）状态。国际版没有这个活动。 */
data class CheckinState(
    val supported: Boolean,
    val todayCheckedIn: Boolean,
    val streakDays: Int,
    val dailyCredit: Double,
    val todayCredit: Double,
    val active: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("supported", supported)
        .put("todayCheckedIn", todayCheckedIn)
        .put("streakDays", streakDays)
        .put("dailyCredit", dailyCredit)
        .put("todayCredit", todayCredit)
        .put("active", active)
}

/** 签到结果。alreadyChecked 表示今日本来就已领，不算失败。 */
data class CheckinResult(
    val ok: Boolean,
    val code: Int,
    val message: String,
    val alreadyChecked: Boolean
)

/**
 * 账号详情：积分余量、最近到期、签到状态、档位。
 *
 * 两个区域的取数路径不同，这里归一成一份结构：
 *  - 国际版：POST /v2/billing/meter/get-user-resource → data.Response.Data.Accounts[]
 *  - 国内版：资源摘要 + 免费包 + 付费包，签到另打 copilot.tencent.com 的
 *            /v2/billing/meter/checkin-activity-status
 */
data class AccountDetail(
    val uid: String,
    val nickname: String,
    val realm: String,
    val paid: Boolean?,
    val remain: Double,
    val used: Double,
    val size: Double,
    val packages: List<CreditPackage>,
    val checkin: CheckinState,
    val updatedAt: Long,
    val error: String = ""
) {
    val remainPercent: Double
        get() = if (size > 0) (remain / size * 100.0) else 0.0

    fun toJson(): JSONObject {
        val pkgArr = JSONArray()
        for (p in packages) pkgArr.put(p.toJson())
        return JSONObject()
            .put("uid", uid)
            .put("nickname", nickname)
            .put("realm", realm)
            .put("paid", paid ?: JSONObject.NULL)
            .put("remain", remain)
            .put("used", used)
            .put("size", size)
            .put("remainPercent", String.format(java.util.Locale.US, "%.1f", remainPercent))
            .put("packages", pkgArr)
            .put("checkin", checkin.toJson())
            .put("updatedAt", updatedAt)
            .put("error", error)
    }

    companion object {
        fun error(a: Account, message: String): AccountDetail = AccountDetail(
            uid = a.uid,
            nickname = a.label(),
            realm = a.realm,
            paid = null,
            remain = 0.0,
            used = 0.0,
            size = 0.0,
            packages = emptyList(),
            checkin = CheckinState(false, false, 0, 0.0, 0.0, false),
            updatedAt = System.currentTimeMillis(),
            error = message
        )
    }
}
