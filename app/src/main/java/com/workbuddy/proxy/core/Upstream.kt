package com.workbuddy.proxy.core

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WorkBuddy 上游的最小客户端。
 *
 * 上游 /v2/chat/completions 本身就是 OpenAI Chat Completions 形态（choices[].delta、
 * reasoning_content、tool_calls 都在），所以这里不做协议翻译——只负责按官方桌面端的
 * 身份头把请求送出去，并把字节流原样接回来。协议翻译只发生在 Anthropic 入口。
 */
object Upstream {

    data class HttpResult(val code: Int, val body: String)

    data class LoginStart(val state: String, val authUrl: String)

    data class PollResult(val status: String, val account: Account?, val message: String)

    // ---------------------------------------------------------------- HTTP

    fun http(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 60_000
    ): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.useCaches = false
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { readAll(it) } ?: ""
            return HttpResult(code, text)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    fun readAll(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toString("UTF-8")
    }

    /**
     * 建立 chat 长连接。返回已拿到响应头的连接，调用方自己读 body。
     * 只有状态码 2xx 才返回；其余情况直接抛 [UpstreamError] 带上状态码与响应体片段，
     * 让上层决定是换号重试还是把错误回给客户端。
     */
    fun openChat(account: Account, body: ByteArray, model: String, stream: Boolean): HttpURLConnection {
        val url = Realms.chatBase(account.realm) + Realms.PATH_CHAT
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 20_000
        conn.readTimeout = Tuning.readTimeoutSec * 1000
        conn.useCaches = false
        conn.doOutput = true
        val headers = chatHeaders(account, model, null, stream)
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val detail = runCatching { conn.errorStream?.use { readAll(it) } ?: "" }.getOrDefault("")
            val retryAfter = runCatching { conn.getHeaderField("Retry-After") }.getOrNull() ?: ""
            runCatching { conn.disconnect() }
            throw UpstreamError(code, detail, retryAfter)
        }
        return conn
    }

    // ------------------------------------------------------------- 身份头

    fun chatHeaders(a: Account, model: String, convId: String?, stream: Boolean): Map<String, String> {
        val realm = a.realm
        val msgId = Identity.hex32()
        val conv = convId ?: Identity.hex32()
        val h = linkedMapOf(
            "X-Agent-Intent" to "craft",
            "X-Agent-Purpose" to "conversation",
            "X-Agent-Type" to "main",
            "X-IDE-Name" to "WorkBuddy",
            "X-IDE-Type" to "WorkBuddy",
            "X-IDE-Version" to Realms.desktopVersion(realm),
            "X-Private-Data" to "true",
            "X-Product" to "SaaS",
            "X-Domain" to a.domain.ifBlank { Realms.domain(realm) },
            "User-Agent" to Realms.chatUa(realm),
            "X-User-Id" to a.uid,
            "Authorization" to "Bearer ${a.accessToken}",
            "X-Conversation-ID" to conv,
            "X-Conversation-Request-ID" to msgId,
            "X-Root-Request-ID" to msgId,
            "X-Conversation-Message-ID" to msgId,
            "X-Request-ID" to msgId,
            "Content-Type" to "application/json",
            "Accept" to "text/event-stream",
            "Accept-Language" to Realms.acceptLanguage(realm)
        )
        h.putAll(Identity.traceHeaders())
        return h
    }

    private fun loginHeaders(realm: String): Map<String, String> = linkedMapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json, text/plain, */*",
        "X-Requested-With" to "XMLHttpRequest",
        "User-Agent" to Realms.billingUa(realm),
        "Origin" to Realms.origin(realm),
        "Referer" to Realms.origin(realm) + "/",
        "Accept-Language" to Realms.acceptLanguage(realm)
    )

    // ------------------------------------------------------------- 登录

    fun startLogin(realm: String): LoginStart {
        val url = Realms.chatBase(realm) + Realms.PATH_AUTH_STATE + "?platform=CLI"
        val res = http("POST", url, loginHeaders(realm), "{}".toByteArray(), readTimeoutMs = 30_000)
        if (res.code !in 200..299) {
            throw IOException("auth/state HTTP ${res.code}: ${res.body.take(200)}")
        }
        val data = runCatching { JSONObject(res.body).optJSONObject("data") }.getOrNull() ?: JSONObject()
        val state = data.optString("state")
        val authUrl = data.optString("authUrl")
        if (state.isBlank() || authUrl.isBlank()) {
            throw IOException("auth/state 未返回 state/authUrl: ${res.body.take(200)}")
        }
        return LoginStart(state, authUrl)
    }

    fun pollLogin(realm: String, state: String): PollResult {
        val url = Realms.chatBase(realm) + Realms.PATH_AUTH_TOKEN +
            "?state=" + URLEncoder.encode(state, "UTF-8")
        val res = runCatching {
            http("GET", url, loginHeaders(realm), null, readTimeoutMs = 30_000)
        }.getOrElse { return PollResult("pending", null, "poll error: ${it.message}") }

        val obj = runCatching { JSONObject(res.body) }.getOrElse {
            return PollResult("pending", null, "waiting (HTTP ${res.code})")
        }
        val code = obj.optInt("code", -1)
        if (code == Realms.LOGIN_PENDING_CODE) {
            return PollResult("pending", null, obj.optString("msg").ifBlank { "等待浏览器登录" })
        }
        if (code != 0) {
            return PollResult("error", null, "code=$code msg=${obj.optString("msg")}")
        }
        val data = obj.optJSONObject("data") ?: JSONObject()
        val token = data.optString("accessToken")
        if (token.isBlank()) return PollResult("pending", null, "等待令牌下发")

        val uid = Identity.uidFromJwt(token).ifBlank { "u_" + Identity.hex32().take(10) }
        val nickname = fetchNickname(realm, token).ifBlank { uid.take(8) }
        val account = Account(
            uid = uid,
            nickname = nickname,
            accessToken = token,
            refreshToken = data.optString("refreshToken"),
            expiresAt = Identity.normalizeEpoch(safeLong(data, "expiresAt"))
                .let { if (it > 0) it else Identity.expFromJwt(token) },
            realm = Realms.normalize(realm),
            domain = data.optString("domain").ifBlank { Realms.domain(realm) },
            enabled = true,
            addedAt = System.currentTimeMillis()
        )
        return PollResult("ok", account, "登录成功")
    }

    private fun fetchNickname(realm: String, token: String): String {
        return runCatching {
            val url = Realms.chatBase(realm) + Realms.PATH_LOGIN_ACCOUNT
            val headers = loginHeaders(realm).toMutableMap()
            headers["Authorization"] = "Bearer $token"
            val res = http("GET", url, headers, null, readTimeoutMs = 15_000)
            if (res.code !in 200..299) return ""
            val data = JSONObject(res.body).optJSONObject("data") ?: return ""
            data.optString("nickname").ifBlank { data.optString("username") }
        }.getOrDefault("")
    }

    // ------------------------------------------------------------- 刷新

    /** 用 refreshToken 静默续期。成功时直接改传入账号的字段并返回 true。 */
    fun refresh(a: Account): Boolean {
        if (a.refreshToken.isBlank()) {
            a.tokenExpired = true
            return false
        }
        val realm = a.realm
        val any = (realm == Realms.CN)
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to Realms.billingUa(realm),
            "Origin" to Realms.origin(realm),
            "Referer" to Realms.origin(realm) + "/",
            "X-Refresh-Token" to a.refreshToken,
            "X-Auth-Refresh-Source" to if (any) "workbuddy" else "plugin",
            "X-User-Id" to a.uid,
            "X-Domain" to a.domain.ifBlank { Realms.domain(realm) },
            "X-CodeBuddy-Request" to "1",
            "Accept-Language" to Realms.acceptLanguage(realm)
        )
        val url = Realms.chatBase(realm) + Realms.PATH_AUTH_REFRESH
        val res = runCatching {
            http("POST", url, headers, "{}".toByteArray(), readTimeoutMs = 30_000)
        }.getOrElse {
            a.lastError = "refresh failed: ${it.message}"
            return false
        }
        if (res.code !in 200..299) {
            if (res.code == 401) a.tokenExpired = true
            a.lastError = "refresh HTTP ${res.code}"
            return false
        }
        val obj = runCatching { JSONObject(res.body) }.getOrNull() ?: return false
        var data = obj.optJSONObject("data") ?: JSONObject()
        data.optJSONObject("data")?.let { data = it }
        val token = data.optString("accessToken")
        if (token.isBlank()) {
            a.tokenExpired = true
            a.lastError = "refresh 未返回令牌"
            return false
        }
        a.accessToken = token
        if (data.optString("refreshToken").isNotBlank()) a.refreshToken = data.optString("refreshToken")
        a.expiresAt = Identity.normalizeEpoch(safeLong(data, "expiresAt"))
            .let { if (it > 0) it else Identity.expFromJwt(token) }
        a.tokenExpired = false
        a.lastError = ""
        return true
    }

    // ------------------------------------------------------------- 模型

    /** 上游模型目录：data.agents[].models[]，去重保序。 */
    fun fetchModels(account: Account): List<String> {
        val url = Realms.chatBase(account.realm) + Realms.PATH_MODELS
        val headers = chatHeaders(account, "", null, false).toMutableMap()
        headers["Accept"] = "application/json"
        val res = http("GET", url, headers, null, readTimeoutMs = 30_000)
        if (res.code !in 200..299) throw UpstreamError(res.code, res.body, "")
        val root = JSONObject(res.body)
        val agents = root.optJSONObject("data")?.optJSONArray("agents") ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until agents.length()) {
            val models = agents.optJSONObject(i)?.optJSONArray("models") ?: continue
            for (j in 0 until models.length()) {
                val id = models.optString(j)
                if (id.isNotBlank()) out.add(id)
            }
        }
        return out.toList()
    }

    // ------------------------------------------------------------- 账号详情

    /** 计费/积分接口的标头：比 chat 轻量，不吃 X-Agent-* 那套身分。 */
    fun billingHeaders(a: Account): Map<String, String> {
        val realm = a.realm
        val h = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to Realms.billingUa(realm),
            "Origin" to Realms.origin(realm),
            "Referer" to Realms.origin(realm) + "/",
            "Authorization" to "Bearer ${a.accessToken}",
            "X-User-Id" to a.uid,
            "X-Domain" to a.domain.ifBlank { Realms.domain(realm) },
            "X-CodeBuddy-Request" to "1",
            "Accept-Language" to Realms.acceptLanguage(realm),
            "X-Request-ID" to Identity.hex32(),
            "X-Machine-ID" to Identity.machineId(a.uid),
            "X-Session-ID" to Identity.sessionId(a.uid),
            "X-No-Enterprise-Id" to "1"
        )
        if (realm == Realms.CN) h["X-Product"] = "SaaS"
        return h
    }

    /** 拉一个账号的积分与签到状态。401 先续期再试一次。 */
    fun fetchAccountDetail(a: Account): AccountDetail {
        var refreshed = false
        while (true) {
            try {
                return if (a.realm == Realms.CN) fetchDetailCn(a) else fetchDetailIntl(a)
            } catch (e: UpstreamError) {
                if (e.isAuthError && !refreshed && refresh(a)) {
                    refreshed = true
                    AccountStore.save()
                    continue
                }
                return AccountDetail.error(a, "上游 HTTP ${e.status}")
            } catch (e: Exception) {
                return AccountDetail.error(a, e.message ?: "获取积分失败")
            }
        }
    }

    private fun postJson(url: String, headers: Map<String, String>, body: String): JSONObject {
        val res = http("POST", url, headers, body.toByteArray(Charsets.UTF_8), readTimeoutMs = 20_000)
        if (res.code !in 200..299) throw UpstreamError(res.code, res.body, "")
        return JSONObject(res.body)
    }

    /** 国际版：一次 get-user-resource 拿全部积分包；没有加油站活动。 */
    private fun fetchDetailIntl(a: Account): AccountDetail {
        val base = Realms.billingBase(a.realm)
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val body = JSONObject()
            .put("PageNumber", 1)
            .put("PageSize", 100)
            .put("ProductCode", "p_tcaca")
            .put("Status", org.json.JSONArray().put(0).put(3))
            .put("PackageEndTimeRangeBegin", now)
            .put("PackageEndTimeRangeEnd", "2036-01-01 00:00:00")
        val root = postJson(base + Realms.PATH_GET_RESOURCE, billingHeaders(a), body.toString())

        val data = root.optJSONObject("data")
        val accounts = data?.optJSONObject("Response")?.optJSONObject("Data")?.optJSONArray("Accounts")
            ?: data?.optJSONArray("Accounts")
            ?: org.json.JSONArray()

        val packages = ArrayList<CreditPackage>()
        for (i in 0 until accounts.length()) {
            accounts.optJSONObject(i)?.let { packages.add(parsePackage(it)) }
        }
        return build(a, null, packages, CheckinState(false, false, 0, 0.0, 0.0, false))
    }

    /** 国内版：资源摘要 → 免费包 / 付费包 → 加油站签到状态。 */
    private fun fetchDetailCn(a: Account): AccountDetail {
        val base = Realms.billingBase(a.realm)
        val headers = billingHeaders(a)
        var paid: Boolean? = null
        val packages = ArrayList<CreditPackage>()

        val summary = runCatching {
            postJson(base + Realms.PATH_RESOURCE_SUMMARY, headers, "{}")
        }.getOrNull()
        val sData = summary?.optJSONObject("data")
        if (sData != null && sData.has("IsPaidUser")) {
            paid = sData.optBoolean("IsPaidUser")
        }

        val codes = ArrayList<String>()
        sData?.optJSONArray("Packages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val code = arr.optJSONObject(i)?.optString("PackageCode").orEmpty()
                if (code.isNotBlank()) codes.add(code)
            }
        }

        if (codes.isNotEmpty()) {
            val codeArr = org.json.JSONArray()
            for (c in codes) codeArr.put(c)

            val freeBody = JSONObject()
                .put("PackageCodes", codeArr)
                .put("PageNumber", 1)
                .put("PageSize", 200)
                .put("Status", org.json.JSONArray().put(0))
            runCatching {
                postJson(base + Realms.PATH_FREE_PACKAGES, headers, freeBody.toString())
            }.getOrNull()?.optJSONObject("data")?.optJSONArray("Accounts")
                ?.let { collectPackages(it, packages) }

            val paidBody = JSONObject()
                .put("PackageCodes", codeArr)
                .put("PageNumber", 1)
                .put("PageSize", 200)
                .put("Status", org.json.JSONArray().put(0).put(3))
                .put("NeedRenewInfo", true)
            runCatching {
                postJson(base + Realms.PATH_PAID_PACKAGES, headers, paidBody.toString())
            }.getOrNull()?.optJSONObject("data")?.optJSONArray("Accounts")
                ?.let { collectPackages(it, packages) }
        }

        // 加油站签到：国内版走 copilot.tencent.com
        val checkin = runCatching {
            val res = postJson(Realms.chatBase(a.realm) + Realms.PATH_CHECKIN_STATUS, headers, "{}")
            val d = res.optJSONObject("data") ?: JSONObject()
            CheckinState(
                supported = true,
                todayCheckedIn = d.optBoolean("today_checked_in", false),
                streakDays = d.optInt("streak_days", 0),
                dailyCredit = d.optDouble("daily_credit", 0.0),
                todayCredit = d.optDouble("today_credit", 0.0),
                active = d.optBoolean("active", true)
            )
        }.getOrElse {
            CheckinState(true, false, 0, 0.0, 0.0, true)
        }

        return build(a, paid, packages, checkin)
    }

    private fun collectPackages(arr: org.json.JSONArray, out: MutableList<CreditPackage>) {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(parsePackage(obj))
        }
    }

    private fun parsePackage(o: JSONObject): CreditPackage {
        fun num(vararg keys: String): Double {
            for (k in keys) {
                val v = o.opt(k) ?: continue
                if (v == JSONObject.NULL) continue
                val text = v.toString()
                if (text.isBlank()) continue
                text.toDoubleOrNull()?.let { return it }
            }
            return 0.0
        }

        val size = num("CycleCapacitySizePrecise", "CycleCapacitySize", "CapacitySizePrecise", "CapacitySize")
        var remain = num("CycleCapacityRemainPrecise", "CycleCapacityRemain", "CapacityRemainPrecise", "CapacityRemain")
        var used = num("CycleCapacityUsedPrecise", "CycleCapacityUsed", "CapacityUsedPrecise", "CapacityUsed")
        if (size > 0 && used <= 0 && remain <= size) {
            used = maxOf(0.0, size - remain)
        } else if (size > 0 && remain <= 0 && used < size) {
            remain = maxOf(0.0, size - used)
        }

        val reason = runCatching {
            val attrs = o.optJSONArray("AccountAttributes") ?: return@runCatching ""
            for (i in 0 until attrs.length()) {
                val attr = attrs.optJSONObject(i) ?: continue
                if (attr.optString("Key") == "grantReason") {
                    return@runCatching attr.optString("Value")
                }
            }
            ""
        }.getOrDefault("")

        val expireAt = o.optString("CycleEndTime").ifBlank { o.optString("ExpiredTime") }
        return CreditPackage(
            name = o.optString("PackageName").ifBlank { "积分包" },
            code = o.optString("PackageCode"),
            remain = remain,
            size = size,
            used = used,
            expireAt = expireAt,
            daysLeft = daysUntil(expireAt),
            reason = reason
        )
    }

    private fun daysUntil(text: String): Double? {
        if (text.isBlank()) return null
        return runCatching {
            val clean = text.replace("T", " ").substringBefore(".").take(19)
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val date = fmt.parse(clean) ?: return null
            (date.time - System.currentTimeMillis()) / 86_400_000.0
        }.getOrNull()
    }

    private fun build(
        a: Account,
        paid: Boolean?,
        packages: List<CreditPackage>,
        checkin: CheckinState
    ): AccountDetail {
        val sorted = packages.sortedWith(
            compareBy<CreditPackage> { if (it.daysLeft != null && it.daysLeft!! >= 0) 0 else 1 }
                .thenBy { it.daysLeft ?: 99_999.0 }
                .thenByDescending { it.remain }
        )
        return AccountDetail(
            uid = a.uid,
            nickname = a.label(),
            realm = a.realm,
            paid = paid,
            remain = sorted.sumOf { it.remain },
            used = sorted.sumOf { it.used },
            size = sorted.sumOf { it.size },
            packages = sorted,
            checkin = checkin,
            updatedAt = System.currentTimeMillis()
        )
    }

    // ------------------------------------------------------------- 签到

    /**
     * Buddy 加油站签到。仅国内版有，企业号不支持。
     *
     * 上游的「今日已签到」有两种表达：正常 200 + code=10001，或 HTTP 400 但
     * 响应体里 code=10001 / msg 含「已签到」。两种都说明今日已处于完成态，
     * 按成功返回，不当作失败。
     */
    fun checkin(a: Account): CheckinResult {
        if (a.realm != Realms.CN) {
            return CheckinResult(false, -1, "只有国内版账号有 Buddy 加油站", false)
        }
        val url = Realms.billingBase(a.realm) + Realms.PATH_DAILY_CHECKIN
        val headers = billingHeaders(a)
        val res = try {
            http("POST", url, headers, "{}".toByteArray(Charsets.UTF_8), readTimeoutMs = 20_000)
        } catch (e: Exception) {
            return CheckinResult(false, -1, "签到请求失败: ${e.message}", false)
        }

        val obj = runCatching { JSONObject(res.body) }.getOrNull()
        val code = obj?.optInt("code", -1) ?: -1
        val msg = obj?.optString("msg").orEmpty().ifBlank { "HTTP ${res.code}" }
        val already = code == 10001 || msg.contains("已签到")

        return when {
            res.code in 200..299 && (code == 0 || code == 10001) ->
                CheckinResult(true, code, msg, already)
            res.code !in 200..299 && already ->
                CheckinResult(true, code, msg, true)
            res.code == 401 || res.code == 403 ->
                CheckinResult(false, res.code, "登录已失效，请重新添加账号", false)
            else ->
                CheckinResult(false, code, msg, false)
        }
    }

    // ------------------------------------------------------------- 工具

    private fun safeLong(o: JSONObject, key: String): Long {
        val v = o.opt(key) ?: return 0
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0
            else -> 0
        }
    }
}

/** 上游返回非 2xx 时抛出，带上状态码与响应片段供上层判定。 */
class UpstreamError(val status: Int, val detail: String, val retryAfter: String) :
    IOException("upstream HTTP $status: ${detail.take(200)}") {

    val isRateLimited: Boolean get() = status == 429
    val isAuthError: Boolean get() = status == 401 || status == 403
    val isRetryable: Boolean get() = status == 429 || status == 502 || status == 503 || status == 504
}
