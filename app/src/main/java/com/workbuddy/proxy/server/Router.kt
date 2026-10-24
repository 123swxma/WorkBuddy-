package com.workbuddy.proxy.server

import com.workbuddy.proxy.App
import com.workbuddy.proxy.core.Account
import com.workbuddy.proxy.core.AccountDetail
import com.workbuddy.proxy.core.ApiKey
import com.workbuddy.proxy.core.KeyStore
import com.workbuddy.proxy.core.AccountStore
import com.workbuddy.proxy.core.AnthropicRpc
import com.workbuddy.proxy.core.Identity
import com.workbuddy.proxy.core.Logs
import com.workbuddy.proxy.core.LoginWatcher
import com.workbuddy.proxy.core.Prefs
import com.workbuddy.proxy.core.ProxyEngine
import com.workbuddy.proxy.core.Realms
import com.workbuddy.proxy.core.Tuning
import com.workbuddy.proxy.core.Upstream
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 路由表。
 *
 * 两类入口刻意分开：
 *  - /v1/…   给 OpenAI / Anthropic 客户端，走 Bearer 或 x-api-key 鉴权
 *  - /api/…  给内置控制台，默认只允许 127.0.0.1（手机本地 WebView），
 *            局域网访问需要带 ?key=
 * 这样把面板暴露到局域网不会顺带把管理口暴露出去。
 */
object Router {

    private const val VERSION = "1.0.0"

    @Volatile private var consoleCache: String? = null
    @Volatile var serviceRunning: Boolean = false
    @Volatile var activePort: Int = 0

    fun handle(req: HttpReq) {
        if (req.method == "OPTIONS") {
            req.sendEmpty(204)
            return
        }
        try {
            when {
                req.path == "/" || req.path == "/index.html" -> serveConsole(req)
                req.path == "/health" -> req.sendJson(200, """{"ok":true,"version":"$VERSION"}""")
                req.path.startsWith("/v1/") -> handleV1(req)
                req.path.startsWith("/api/") -> handleApi(req)
                else -> req.sendError(404, "not found: ${req.path}")
            }
        } catch (e: Exception) {
            Logs.error("router ${req.path}: ${e.message}")
            runCatching { req.sendError(500, e.message ?: "internal error") }
        }
    }

    // ------------------------------------------------------------ /v1

    private fun handleV1(req: HttpReq) {
        val key = identifyKey(req)
        if (key == null) {
            req.sendJson(
                401,
                """{"error":{"message":"invalid api key","type":"invalid_request_error","code":"invalid_api_key"}}"""
            )
            return
        }
        val realm = key.realm
        when (req.path) {
            "/v1/chat/completions" -> if (requirePost(req)) ProxyEngine.handleChat(req, realm, key.label)
            "/v1/models" -> ProxyEngine.handleModels(req, realm)
            "/v1/messages" -> if (requirePost(req)) AnthropicRpc.handleMessages(req, realm, key.label)
            "/v1/messages/count_tokens" -> if (requirePost(req)) AnthropicRpc.handleCountTokens(req)
            "/v1/completions" -> req.sendError(
                400,
                "本网关只实现了 chat/completions；请改用 /v1/chat/completions"
            )
            else -> req.sendError(404, "unknown endpoint: ${req.path}")
        }
    }

    private fun requirePost(req: HttpReq): Boolean {
        if (req.method != "POST") {
            req.sendError(405, "method ${req.method} not allowed")
            return false
        }
        return true
    }

    /**
     * 认 Bearer，也认 Anthropic 客户端的 x-api-key。
     * 返回命中的 Key（带区域约束），没命中回 null。
     */
    private fun identifyKey(req: HttpReq): ApiKey? {
        val bearer = req.bearerToken()
        if (bearer.isNotEmpty()) {
            KeyStore.find(bearer)?.let { return it }
        }
        val xKey = req.header("x-api-key") ?: ""
        if (xKey.isNotEmpty()) {
            KeyStore.find(xKey)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------ /api

    private fun handleApi(req: HttpReq) {
        if (!req.isLocalClient()) {
            val supplied = req.query["key"] ?: ""
            if (KeyStore.find(supplied) == null) {
                req.sendError(403, "面板管理接口只允许本机访问；局域网请带 ?key=<API Key>")
                return
            }
        }
        when {
            req.path == "/api/status" -> req.sendJson(200, statusJson().toString())

            req.path == "/api/accounts" && req.method == "GET" -> {
                val arr = JSONArray()
                for (a in AccountStore.all()) arr.put(a.publicJson())
                req.sendJson(200, JSONObject().put("accounts", arr).toString())
            }

            req.path == "/api/accounts/manual" && req.method == "POST" -> addManual(req)

            // 账号详情（积分 / 加油站签到）必须先于通用 accountAction 匹配
            req.path.endsWith("/detail") && req.path.startsWith("/api/accounts/") -> accountDetail(req)

            req.path.endsWith("/checkin") && req.path.startsWith("/api/accounts/") &&
                req.method == "POST" -> accountCheckin(req)

            req.path.startsWith("/api/accounts/") -> accountAction(req)

            req.path == "/api/login/start" -> startLogin(req)
            req.path == "/api/login/poll" -> pollLogin(req)

            req.path == "/api/logs" -> {
                val arr = JSONArray()
                for (e in Logs.recent(req.query["n"]?.toIntOrNull() ?: 120)) {
                    arr.put(JSONObject().put("time", e.timeText()).put("level", e.level).put("message", e.message))
                }
                req.sendJson(200, JSONObject().put("logs", arr).toString())
            }

            req.path == "/api/keys" && req.method == "GET" -> {
                val arr = JSONArray()
                for (k in KeyStore.all()) arr.put(k.publicJson())
                req.sendJson(200, JSONObject().put("keys", arr).toString())
            }

            req.path == "/api/keys" && req.method == "POST" -> {
                val body = runCatching { JSONObject(req.bodyText) }.getOrNull() ?: JSONObject()
                val created = KeyStore.create(
                    body.optString("label"),
                    body.optString("realm", Realms.ANY)
                )
                Logs.info("新建 API Key「${created.label}」(${Realms.keyRealmLabel(created.realm)})")
                req.sendJson(200, JSONObject().put("key", created.publicJson()).toString())
            }

            req.path.startsWith("/api/keys/") -> keyAction(req)

            req.path == "/api/config" -> handleConfig(req)

            req.path == "/api/test" -> handleTest(req)

            else -> req.sendError(404, "unknown api: ${req.path}")
        }
    }

    private fun statusJson(): JSONObject {
        val lan = lanIp()
        val port = if (activePort > 0) activePort else Prefs.port
        return JSONObject()
            .put("version", VERSION)
            .put("running", serviceRunning)
            .put("port", port)
            .put("baseUrl", "http://127.0.0.1:$port/v1")
            .put("lanUrl", if (lan.isBlank()) "" else "http://$lan:$port/v1")
            .put("apiKey", KeyStore.all().firstOrNull { it.enabled }?.key ?: "")
            .put("keyCount", KeyStore.size())
            .put("allowLan", Prefs.allowLan)
            .put("promptMode", Prefs.promptMode)
            .put("cnBase", Tuning.cnBase)
            .put("intlBase", Tuning.intlBase)
            .put("readTimeoutSec", Tuning.readTimeoutSec)
            .put("maxRetries", Tuning.maxRetries)
            .put("cleanChunks", Tuning.cleanChunks)
            .put("accountCount", AccountStore.size())
            .put("accounts", AccountStore.all().count { it.enabled })
            .put("models", JSONArray(ProxyEngine.cachedModelIds()))
            .put("realm", Realms.label("intl"))
    }

    private fun addManual(req: HttpReq) {
        val obj = runCatching { JSONObject(req.bodyText) }.getOrNull() ?: run {
            req.sendError(400, "invalid JSON")
            return
        }
        val token = obj.optString("token").trim()
        if (token.isBlank()) {
            req.sendError(400, "token 不能为空")
            return
        }
        val realm = Realms.normalize(obj.optString("realm", Realms.INTL))
        val uid = obj.optString("uid").ifBlank { Identity.uidFromJwt(token) }
            .ifBlank { "u_" + Identity.hex32().take(10) }
        val acc = Account(
            uid = uid,
            nickname = obj.optString("nickname").ifBlank { uid.take(8) },
            accessToken = token,
            refreshToken = obj.optString("refreshToken"),
            expiresAt = Identity.expFromJwt(token),
            realm = realm,
            domain = Realms.domain(realm),
            enabled = true,
            addedAt = System.currentTimeMillis()
        )
        AccountStore.add(acc)
        Logs.info("手动添加账号 ${acc.label()} (${Realms.label(realm)})")
        req.sendJson(200, JSONObject().put("account", acc.publicJson()).toString())
    }

    /**
     * 账号详情：积分余额、到期最近的一包、Buddy 加油站签到状态。
     *
     * 上游这条路要打两三个接口，卡片反复点开时不必每次都问一遍，
     * 结果缓存 60 秒；控制台传 `?refresh=1` 可强制刷新。
     */
    private fun accountDetail(req: HttpReq) {
        val rest = req.path.removePrefix("/api/accounts/")
        val uid = rest.substringBefore("/detail").trim()
        if (uid.isBlank()) {
            req.sendError(400, "uid required")
            return
        }
        val account = AccountStore.find(uid)
        if (account == null) {
            req.sendError(404, "账号不存在")
            return
        }
        val force = req.query["refresh"] == "1" || req.query["refresh"] == "true"
        val cached = detailCache[uid]
        if (!force && cached != null && System.currentTimeMillis() - cached.at < 60_000) {
            req.sendJson(200, cached.detail.toJson().toString())
            return
        }
        val detail = Upstream.fetchAccountDetail(account)
        if (detail.error.isBlank()) {
            detailCache[uid] = CachedDetail(detail, System.currentTimeMillis())
        }
        req.sendJson(200, detail.toJson().toString())
    }

    /** 删除 / 启停 / 改名：/api/keys/{id}[/toggle|rename] */
    private fun keyAction(req: HttpReq) {
        val rest = req.path.removePrefix("/api/keys/")
        val id = rest.substringBefore("/").trim()
        if (id.isBlank()) {
            req.sendError(400, "id required")
            return
        }
        val action = rest.substringAfter("/", "")
        when {
            req.method == "DELETE" && action.isBlank() -> {
                if (!KeyStore.hasEnabled() || KeyStore.all().count { it.enabled } <= 1) {
                    // 允许删到只剩一把，但不能一把不剩
                    val target = KeyStore.findById(id)
                    if (target != null && target.enabled && KeyStore.all().count { it.enabled } <= 1) {
                        req.sendError(400, "至少要保留一把启用的 Key")
                        return
                    }
                }
                val ok = KeyStore.remove(id)
                req.sendJson(200, JSONObject().put("removed", ok).toString())
            }
            action == "toggle" && req.method == "POST" -> {
                val body = runCatching { JSONObject(req.bodyText) }.getOrNull() ?: JSONObject()
                val enabled = body.optBoolean("enabled", true)
                if (!enabled && KeyStore.all().count { it.enabled } <= 1) {
                    req.sendError(400, "至少要保留一把启用的 Key")
                    return
                }
                KeyStore.setEnabled(id, enabled)
                req.sendJson(200, JSONObject().put("enabled", enabled).toString())
            }
            action == "update" && req.method == "POST" -> {
                val body = runCatching { JSONObject(req.bodyText) }.getOrNull() ?: JSONObject()
                KeyStore.update(id, body.optString("label"), body.optString("realm"))
                req.sendJson(200, JSONObject().put("ok", true).toString())
            }
            action == "rename" && req.method == "POST" -> {
                val body = runCatching { JSONObject(req.bodyText) }.getOrNull() ?: JSONObject()
                KeyStore.rename(id, body.optString("label"))
                req.sendJson(200, JSONObject().put("ok", true).toString())
            }
            else -> req.sendError(404, "unknown key action")
        }
    }

    private class CachedDetail(val detail: AccountDetail, val at: Long)

    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, CachedDetail>()

    /**
     * 触发一次 Buddy 加油站签到。
     *
     * 成功后把详情缓存清掉，这样面板再展开时看到的是签到后的积分与状态，
     * 而不是 60 秒窗口里的旧快照。
     */
    private fun accountCheckin(req: HttpReq) {
        val rest = req.path.removePrefix("/api/accounts/")
        val uid = rest.substringBefore("/checkin").trim()
        if (uid.isBlank()) {
            req.sendError(400, "uid required")
            return
        }
        val account = AccountStore.find(uid)
        if (account == null) {
            req.sendError(404, "账号不存在")
            return
        }
        val result = Upstream.checkin(account)
        if (result.ok) {
            detailCache.remove(uid)
            Logs.info("签到 ${account.label()}: ${if (result.alreadyChecked) "今日已签" else "成功"}")
        } else {
            Logs.warn("签到失败 ${account.label()}: ${result.message}")
        }
        req.sendJson(
            200,
            JSONObject()
                .put("ok", result.ok)
                .put("alreadyChecked", result.alreadyChecked)
                .put("code", result.code)
                .put("message", result.message)
                .toString()
        )
    }

    private fun accountAction(req: HttpReq) {
        val rest = req.path.removePrefix("/api/accounts/")
        val parts = rest.split("/")
        val uid = parts.getOrNull(0) ?: run {
            req.sendError(400, "uid required")
            return
        }
        val action = parts.getOrNull(1) ?: ""
        when {
            req.method == "DELETE" && action.isBlank() -> {
                val ok = AccountStore.remove(uid)
                req.sendJson(200, JSONObject().put("removed", ok).toString())
            }
            action == "toggle" && req.method == "POST" -> {
                val obj = runCatching { JSONObject(req.bodyText) }.getOrNull() ?: JSONObject()
                val enabled = obj.optBoolean("enabled", true)
                AccountStore.setEnabled(uid, enabled)
                req.sendJson(200, JSONObject().put("enabled", enabled).toString())
            }
            else -> req.sendError(404, "unknown account action")
        }
    }

    private fun startLogin(req: HttpReq) {
        val obj = runCatching { JSONObject(req.bodyText) }.getOrNull() ?: JSONObject()
        val realm = Realms.normalize(obj.optString("realm", Realms.INTL))
        try {
            val start = Upstream.startLogin(realm)
            LoginWatcher.start(realm, start.state)
            req.sendJson(
                200,
                JSONObject()
                    .put("state", start.state)
                    .put("authUrl", start.authUrl)
                    .put("realm", realm)
                    .toString()
            )
        } catch (e: Exception) {
            Logs.error("login/start: ${e.message}")
            req.sendError(502, "申请授权失败: ${e.message}")
        }
    }

    private fun pollLogin(req: HttpReq) {
        val state = req.query["state"] ?: ""
        if (state.isBlank()) {
            req.sendError(400, "state required")
            return
        }
        val sync = LoginWatcher.status(state)
        if (sync == null) {
            req.sendJson(200, JSONObject().put("status", "unknown").put("message", "state 未被识别").toString())
            return
        }
        req.sendJson(
            200,
            JSONObject()
                .put("status", sync.status)
                .put("message", sync.message)
                .put("uid", sync.uid)
                .put("nickname", sync.nickname)
                .toString()
        )
    }

    private fun handleConfig(req: HttpReq) {
        if (req.method == "GET") {
            req.sendJson(200, statusJson().toString())
            return
        }
        val obj = runCatching { JSONObject(req.bodyText) }.getOrNull() ?: run {
            req.sendError(400, "invalid JSON")
            return
        }
        if (obj.has("allowLan")) Prefs.allowLan = obj.optBoolean("allowLan")
        if (obj.has("promptMode")) Prefs.promptMode = obj.optString("promptMode")
        if (obj.has("cnBase")) Tuning.cnBase = obj.optString("cnBase")
        if (obj.has("intlBase")) Tuning.intlBase = obj.optString("intlBase")
        if (obj.has("readTimeoutSec")) Tuning.readTimeoutSec = obj.optInt("readTimeoutSec", 300)
        if (obj.has("maxRetries")) Tuning.maxRetries = obj.optInt("maxRetries", 2)
        if (obj.has("cleanChunks")) Tuning.cleanChunks = obj.optBoolean("cleanChunks")
        if (obj.has("port")) Prefs.port = obj.optInt("port", 8788)
        req.sendJson(200, statusJson().put("needRestart", true).toString())
    }

    /** 用当前账号池发一条最小请求，验证整条链路。 */
    private fun handleTest(req: HttpReq) {
        val obj = runCatching { JSONObject(req.bodyText) }.getOrNull() ?: JSONObject()
        val model = obj.optString("model").ifBlank { ProxyEngine.cachedModelIds().firstOrNull() ?: "gpt-5.5" }
        val payload = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("max_tokens", 64)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
        val bytes = ProxyEngine.buildUpstreamBody(payload).toString().toByteArray(Charsets.UTF_8)
        // 上游只吃流式，这里由 complete() 内部开流再聚合
        val result = ProxyEngine.complete(bytes, model, null, "链路测试")
        val body = result.body
        if (body == null) {
            req.sendJson(
                200,
                JSONObject()
                    .put("ok", false)
                    .put("status", result.status)
                    .put("message", result.message)
                    .toString()
            )
            return
        }
        val reply = body.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        req.sendJson(
            200, JSONObject()
                .put("ok", true)
                .put("model", model)
                .put("account", result.account)
                .put("reply", reply.take(300))
                .toString()
        )
    }

    // ------------------------------------------------------------ 控制台

    private fun serveConsole(req: HttpReq) {
        val html = consoleCache ?: synchronized(this) {
            consoleCache ?: App.instance.assets.open("console.html")
                .use { it.readBytes().toString(Charsets.UTF_8) }
                .also { consoleCache = it }
        }
        req.sendBytes(200, html.toByteArray(Charsets.UTF_8), "text/html; charset=utf-8")
    }

    fun lanIp(): String {
        return runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return@runCatching addr.hostAddress ?: ""
                    }
                }
            }
            ""
        }.getOrDefault("")
    }
}
