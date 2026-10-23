package com.workbuddy.proxy.core

import com.workbuddy.proxy.server.HttpReq
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * 转发引擎：把 OpenAI 兼容请求按官方桌面端身份送到 WorkBuddy，并把上游字节流接回来。
 *
 * 上游返回的就是 OpenAI Chat Completions 帧，所以这里不做结构翻译——只做三件必需的事：
 *  1) 补齐上游强校验的 system 首条消息；
 *  2) 按账号轮询 + 失败换号（401 先续期同号重试，4xx 直接回客户端）；
 *  3) 流式时按行透传并清掉上游填充的空噪声字段。
 */
object ProxyEngine {

    private const val DEFAULT_SYSTEM = "You are a helpful assistant."

    /** 无账号时的兜底模型目录（官方桌面端常见模型）。 */
    private val FALLBACK_MODELS = listOf(
        "gpt-5.5", "gpt-5.6-sol", "gpt-5.4",
        "glm-5.3", "glm-5.1",
        "deepseek-v4.1-flash", "deepseek-v4-pro",
        "kimi-k2.6", "kimi-k2-thinking",
        "hunyuan-chat", "gemini-3.5-flash"
    )

    private val modelLock = Any()
    private var cachedModels: List<String> = emptyList()
    private var cachedAt = 0L

    /** 打开上游的结果：要么拿到连接，要么是给客户端的错误。 */
    sealed class OpenResult {
        class Ok(val conn: HttpURLConnection, val account: Account) : OpenResult()
        class Err(val status: Int, val message: String) : OpenResult()
    }

    // ------------------------------------------------------------ chat

    fun handleChat(req: HttpReq, keyRealm: String? = null) {
        val client = try {
            JSONObject(req.bodyText)
        } catch (e: Exception) {
            req.sendError(400, "invalid JSON body: ${e.message}")
            return
        }
        val model = client.optString("model")
        if (model.isBlank()) {
            req.sendError(400, "model is required")
            return
        }
        val stream = client.optBoolean("stream", false)
        val bytes = buildUpstreamBody(client).toString().toByteArray(Charsets.UTF_8)

        when (val res = openWithRetry(bytes, model, true, keyRealm)) {
            is OpenResult.Err -> req.sendError(res.status, res.message)
            is OpenResult.Ok -> {
                if (stream) {
                    Logs.info("chat $model via ${res.account.label()} (stream)")
                    pipeSse(req, res.conn, model, res.account)
                } else {
                    pipeAggregated(req, res.conn, model, res.account)
                }
            }
        }
    }

    /**
     * 选号 + 打开上游。401 先续期再用同一个号打一次；429/5xx 换号；其余 4xx 原样返回。
     */
    fun openWithRetry(
        bytes: ByteArray,
        model: String,
        stream: Boolean,
        keyRealm: String? = null
    ): OpenResult {
        val tried = HashSet<String>()
        var lastStatus = 503
        var lastMessage = "no usable account"
        val maxAttempts = maxOf(1, Tuning.maxRetries + 1)

        for (i in 0 until maxAttempts) {
            val acc = AccountStore.pick(preferredRealm(model, keyRealm), tried) ?: break
            tried.add(acc.uid)

            if (!acc.tokenUsable()) {
                if (!Upstream.refresh(acc)) {
                    AccountStore.markFailure(acc, "令牌过期，需重新登录")
                    lastStatus = 401
                    lastMessage = "账号 ${acc.label()} 登录已失效，请重新添加"
                    continue
                }
                AccountStore.save()
            }

            val conn = try {
                Upstream.openChat(acc, bytes, model, stream)
            } catch (e: UpstreamError) {
                if (e.isAuthError) {
                    if (Upstream.refresh(acc)) {
                        AccountStore.save()
                        val retry = runCatching { Upstream.openChat(acc, bytes, model, stream) }
                        if (retry.isSuccess) {
                            AccountStore.markSuccess(acc)
                            return OpenResult.Ok(retry.getOrThrow(), acc)
                        }
                    }
                    acc.tokenExpired = true
                    AccountStore.markFailure(acc, "HTTP ${e.status}：登录失效")
                    lastStatus = 401
                    lastMessage = "账号 ${acc.label()} 凭据被上游拒绝"
                    continue
                }
                if (e.isRetryable) {
                    val cool = if (e.isRateLimited) parseRetryAfter(e.retryAfter) else 0L
                    AccountStore.markFailure(acc, "HTTP ${e.status}", cool)
                    lastStatus = 502
                    lastMessage = "上游 HTTP ${e.status}"
                    continue
                }
                AccountStore.markSuccess(acc)
                Logs.warn("chat $model -> ${e.status} ${e.detail.take(120)}")
                return OpenResult.Err(e.status.coerceIn(400, 499), friendlyUpstreamError(e.detail))
            } catch (e: Exception) {
                AccountStore.markFailure(acc, "连接失败: ${e.message}")
                lastStatus = 502
                lastMessage = "连接上游失败: ${e.message}"
                continue
            }

            AccountStore.markSuccess(acc)
            return OpenResult.Ok(conn, acc)
        }

        if (AccountStore.size() == 0) {
            return OpenResult.Err(503, "账号池为空：请先在 App 里添加 WorkBuddy 账号")
        }
        return OpenResult.Err(lastStatus, lastMessage)
    }

    // ------------------------------------------------------------ 请求体整形

    fun buildUpstreamBody(client: JSONObject): JSONObject {
        val body = JSONObject(client.toString())
        // Responses API / 客户端本地字段，上游 chat 端点不认识
        body.remove("stream_options")
        body.remove("metadata")
        body.remove("conversation_id")
        body.remove("previous_response_id")

        val messages = body.optJSONArray("messages")
        val out = JSONArray()
        var hasSystem = false
        if (messages != null) {
            for (i in 0 until messages.length()) {
                val m = messages.optJSONObject(i) ?: continue
                var role = m.optString("role")
                if (role == "developer") {
                    role = "system"
                    m.put("role", "system")
                }
                if (role == "system") hasSystem = true
                out.put(m)
            }
        }

        when (Prefs.promptMode) {
            "keep" -> body.put("messages", out)
            "replace" -> {
                val rebuilt = JSONArray()
                rebuilt.put(JSONObject().put("role", "system").put("content", DEFAULT_SYSTEM))
                for (i in 0 until out.length()) {
                    val m = out.optJSONObject(i) ?: continue
                    if (m.optString("role") != "system") rebuilt.put(m)
                }
                body.put("messages", rebuilt)
            }
            else -> {
                if (hasSystem) {
                    body.put("messages", out)
                } else {
                    val rebuilt = JSONArray()
                    rebuilt.put(JSONObject().put("role", "system").put("content", DEFAULT_SYSTEM))
                    for (i in 0 until out.length()) rebuilt.put(out.get(i))
                    body.put("messages", rebuilt)
                }
            }
        }

        if (!body.has("max_tokens") && !body.has("max_completion_tokens")) {
            body.put("max_tokens", 8192)
        }
        // 上游只接受流式（非流式会回 400 "Non-stream chat request is currently
        // not supported"）。客户端要 JSON 时由本网关聚合，见 StreamAggregator。
        body.put("stream", true)
        if (!body.has("stream_options")) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }
        return body
    }

    // ------------------------------------------------------------ 响应处理

    fun pipeSse(req: HttpReq, conn: HttpURLConnection, model: String, acc: Account) {
        // 开流可能就失败（客户端已断开）：先护住上游连接，别让它泄漏
        val writer = try {
            req.startStream("text/event-stream; charset=utf-8")
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            return
        }
        var sawDone = false
        var textChars = 0
        try {
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("data:")) {
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") {
                        sawDone = true
                        writer.write("data: [DONE]\n\n")
                        continue
                    }
                    val cleaned = if (Tuning.cleanChunks) cleanChunk(payload) else payload
                    textChars += extractTextLength(cleaned)
                    writer.write("data: $cleaned\n\n")
                } else if (line.startsWith(":")) {
                    writer.write("$line\n\n")
                }
                // 其余（空行 / 非 SSE 行）丢弃：data 段已自带分帧
            }
            if (!sawDone) writer.write("data: [DONE]\n\n")
        } catch (e: Exception) {
            Logs.warn("stream $model interrupted: ${e.message}")
        } finally {
            runCatching { conn.disconnect() }
            runCatching { writer.end() }
        }
        Logs.info("stream $model done, $textChars chars via ${acc.label()}")
    }

    /**
     * 客户端要非流式：上游照样开流，读完再聚合。
     * 上游对 `stream: false` 直接 400，这不是可协商的选项。
     */
    private fun pipeAggregated(req: HttpReq, conn: HttpURLConnection, model: String, acc: Account) {
        try {
            val out = readAggregated(conn, model)
            if (out == null) {
                req.sendError(502, "上游返回了空流，请稍后重试")
                return
            }
            req.sendJson(200, out.toString())
            Logs.info("chat $model (json) via ${acc.label()}")
        } catch (e: Exception) {
            req.sendError(502, "读取上游响应失败: ${e.message}")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 同步取一次完整回复（内部走流式再聚合），供链路测试与 Anthropic 非流式使用。 */
    data class CompleteResult(
        val body: JSONObject?,
        val status: Int,
        val message: String,
        val account: String
    )

    fun complete(bytes: ByteArray, model: String, keyRealm: String? = null): CompleteResult {
        return when (val r = openWithRetry(bytes, model, true, keyRealm)) {
            is OpenResult.Err -> CompleteResult(null, r.status, r.message, "")
            is OpenResult.Ok -> {
                val body = try {
                    readAggregated(r.conn, model)
                } catch (e: Exception) {
                    Logs.warn("aggregate failed: ${e.message}")
                    null
                } finally {
                    runCatching { r.conn.disconnect() }
                }
                if (body == null) {
                    CompleteResult(null, 502, "上游返回了空流", r.account.label())
                } else {
                    CompleteResult(body, 200, "", r.account.label())
                }
            }
        }
    }

    private fun readAggregated(conn: HttpURLConnection, model: String): JSONObject? {
        val aggregator = StreamAggregator(model)
        var sawChunk = false
        val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload == "[DONE]") break
            val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            aggregator.feed(chunk)
            sawChunk = true
        }
        return if (sawChunk) aggregator.build() else null
    }

    /** 去掉上游为占位而填充的空 delta 字段——客户端会把它们当成真实事件。 */
    fun cleanChunk(raw: String): String {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        var changed = false
        val choices = obj.optJSONArray("choices")
        if (choices != null) {
            for (i in 0 until choices.length()) {
                val choice = choices.optJSONObject(i) ?: continue
                if (choice.has("finish_reason") && choice.optString("finish_reason").isEmpty()) {
                    choice.put("finish_reason", JSONObject.NULL)
                    changed = true
                }
                val delta = choice.optJSONObject("delta") ?: continue
                for (k in listOf("content", "reasoning_content", "role", "refusal")) {
                    if (delta.has(k) && delta.optString(k).isEmpty()) {
                        delta.remove(k)
                        changed = true
                    }
                }
                val tc = delta.optJSONArray("tool_calls")
                if (tc != null && tc.length() == 0) {
                    delta.remove("tool_calls")
                    changed = true
                }
            }
        }
        return if (changed) obj.toString() else raw
    }

    private fun extractTextLength(json: String): Int {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return 0
        val choices = obj.optJSONArray("choices") ?: return 0
        var n = 0
        for (i in 0 until choices.length()) {
            val delta = choices.optJSONObject(i)?.optJSONObject("delta") ?: continue
            n += delta.optString("content").length
            n += delta.optString("reasoning_content").length
        }
        return n
    }

    // ------------------------------------------------------------ models

    fun handleModels(req: HttpReq, keyRealm: String? = null) {
        val now = System.currentTimeMillis()
        synchronized(modelLock) {
            if (cachedModels.isNotEmpty() && now - cachedAt < 300_000) {
                req.sendJson(200, modelsJson(cachedModels))
                return
            }
        }
        val pinned = if (keyRealm == null || keyRealm == Realms.ANY) null else keyRealm
        val acc = AccountStore.pick(pinned)
        if (acc == null) {
            req.sendJson(200, modelsJson(FALLBACK_MODELS))
            return
        }
        val list = runCatching {
            if (!acc.tokenUsable() && !Upstream.refresh(acc)) emptyList() else Upstream.fetchModels(acc)
        }.getOrElse {
            Logs.warn("models fetch failed: ${it.message}")
            emptyList()
        }
        if (list.isEmpty()) {
            req.sendJson(200, modelsJson(FALLBACK_MODELS))
            return
        }
        synchronized(modelLock) {
            cachedModels = list
            cachedAt = now
        }
        req.sendJson(200, modelsJson(list))
    }

    fun cachedModelIds(): List<String> = synchronized(modelLock) {
        if (cachedModels.isEmpty()) FALLBACK_MODELS else cachedModels
    }

    private fun modelsJson(ids: List<String>): String {
        val arr = JSONArray()
        for (id in ids) {
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("object", "model")
                    .put("created", 0)
                    .put("owned_by", "workbuddy")
            )
        }
        return JSONObject().put("object", "list").put("data", arr).toString()
    }

    // ------------------------------------------------------------ 工具

    /**
     * 决定这次请求派哪个区域的账号。
     *
     * API Key 上绑了区域就以它为准（用户建 Key 时选的，优先级最高）；
     * 没绑（any）才按模型名推断，最后才看账号池里只剩哪个区域。
     */
    fun preferredRealm(model: String, keyRealm: String? = null): String? {
        val pinned = Realms.normalizeKeyRealm(keyRealm)
        if (pinned != Realms.ANY) return pinned
        val m = model.lowercase()
        if (m.startsWith("minimax-") || m.startsWith("deepseek-v4-pro")) return Realms.CN
        val realms = AccountStore.all().filter { it.enabled }.map { it.realm }.toSet()
        return if (realms.size == 1) realms.first() else null
    }

    private fun parseRetryAfter(v: String): Long {
        val t = v.trim().toLongOrNull() ?: return 0
        return t * 1000
    }

    /** 上游错误体通常是 JSON，抽出 msg/message 给客户端一个能读的原因。 */
    fun friendlyUpstreamError(detail: String): String {
        val obj = runCatching { JSONObject(detail) }.getOrNull() ?: return detail.take(300)
        val msg = obj.optString("msg").ifBlank {
            obj.optJSONObject("error")?.optString("message").orEmpty()
        }.ifBlank { obj.optString("message") }
        return if (msg.isBlank()) detail.take(300) else msg
    }
}
