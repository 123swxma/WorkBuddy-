package com.workbuddy.proxy.core

import com.workbuddy.proxy.server.ChunkedWriter
import com.workbuddy.proxy.server.HttpReq
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Messages ⇄ OpenAI Chat Completions 适配。
 *
 * Claude Code / Anthropic SDK 说的是 Messages 协议（content 是块数组、事件是
 * message_start / content_block_delta / …），而上游说的是 Chat Completions。
 * 这里做双向翻译：请求先把块数组摊平成 messages + tools，响应再按 Anthropic
 * 的事件序列重放——包括 tool_use 的 input_json_delta 增量。
 */
object AnthropicRpc {

    // ------------------------------------------------------------- 入口

    fun handleMessages(req: HttpReq, keyRealm: String? = null, keyLabel: String = "") {
        val anthropic = try {
            JSONObject(req.bodyText)
        } catch (e: Exception) {
            req.sendError(400, "invalid JSON body: ${e.message}")
            return
        }
        val requestedModel = anthropic.optString("model")
        val model = resolveModel(requestedModel)
        val stream = anthropic.optBoolean("stream", false)
        val openai = toOpenAi(anthropic, model)
        val body = ProxyEngine.buildUpstreamBody(openai)
        // 不再覆盖 stream：上游只接受流式，强制逻辑统一在 buildUpstreamBody
        val bytes = body.toString().toByteArray(Charsets.UTF_8)

        when (val res = ProxyEngine.openWithRetry(bytes, model, true, keyRealm, keyLabel)) {
            is ProxyEngine.OpenResult.Err -> {
                // Anthropic 的错误形状与 OpenAI 不同，客户端按 error.type 分支
                req.sendJson(
                    res.status,
                    JSONObject().put(
                        "type", "error"
                    ).put(
                        "error", JSONObject()
                            .put("type", anthropicErrorType(res.status))
                            .put("message", res.message)
                    ).toString()
                )
            }

            is ProxyEngine.OpenResult.Ok -> {
                // 上游只支持流式；非流式请求在这里开流、聚合、再翻译成 Messages 形状
                if (!stream) {
                    runCatching { res.conn.disconnect() }
                    val probe = ProxyEngine.complete(bytes, model, keyRealm, keyLabel)
                    val obj = probe.body
                    val shown = requestedModel.ifBlank { model }
                    if (obj == null) {
                        req.sendJson(
                            if (probe.status in 400..499) probe.status else 502,
                            JSONObject().put("type", "error").put(
                                "error", JSONObject()
                                    .put("type", anthropicErrorType(probe.status))
                                    .put("message", probe.message)
                            ).toString()
                        )
                    } else {
                        req.sendJson(200, toAnthropic(obj, shown).toString())
                    }
                } else {
                    streamTranslate(req, res.conn, requestedModel.ifBlank { model })
                }
            }
        }
    }

    /** /v1/messages/count_tokens：按字符粗估，够 Claude Code 做上下文裁剪。 */
    fun handleCountTokens(req: HttpReq) {
        val obj = runCatching { JSONObject(req.bodyText) }.getOrNull()
        var chars = 0
        if (obj != null) {
            chars += obj.optString("system").length
            obj.optJSONArray("messages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    chars += JSONObject(m.toString()).toString().length
                }
            }
        }
        val tokens = if (chars == 0) 1 else (chars / 3.2).toInt() + 1
        req.sendJson(200, JSONObject().put("input_tokens", tokens).toString())
    }

    private fun anthropicErrorType(status: Int): String = when (status) {
        400 -> "invalid_request_error"
        401 -> "authentication_error"
        403 -> "permission_error"
        404 -> "not_found_error"
        413 -> "request_too_large"
        429 -> "rate_limit_error"
        else -> "api_error"
    }

    /**
     * 模型名映射。
     *
     * Claude Code 默认发 claude-sonnet-4-5 这类名字，上游没有这些模型；
     * 直接把 claude* 折到上游目录里的 GPT 系模型，其余名字原样放行
     * （用户显式填的上游模型名不应被改写）。
     */
    fun resolveModel(requested: String): String {
        val r = requested.trim()
        val known = ProxyEngine.cachedModelIds()
        if (r.isBlank()) return known.firstOrNull() ?: "gpt-5.5"
        if (known.any { it.equals(r, ignoreCase = true) }) return r
        val low = r.lowercase()
        if (low.startsWith("claude") || low.startsWith("anthropic")) {
            return known.firstOrNull { it.startsWith("gpt-") }
                ?: known.firstOrNull()
                ?: "gpt-5.5"
        }
        return r
    }

    // ------------------------------------------------------------- 请求翻译

    fun toOpenAi(a: JSONObject, modelOverride: String = ""): JSONObject {
        val out = JSONObject()
        val model = modelOverride.ifBlank { a.optString("model") }
        out.put("model", model)
        if (a.has("max_tokens")) out.put("max_tokens", a.optInt("max_tokens"))
        if (a.has("temperature")) out.put("temperature", a.optDouble("temperature", 1.0))
        if (a.has("top_p")) out.put("top_p", a.optDouble("top_p", 1.0))
        a.optJSONArray("stop_sequences")?.let {
            if (it.length() > 0) out.put("stop", it)
        }

        val messages = JSONArray()
        val systemText = when (val sys = a.opt("system")) {
            is String -> sys
            is JSONArray -> joinTextBlocks(sys)
            else -> ""
        }
        if (systemText.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemText))
        }

        val src = a.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until src.length()) {
            val m = src.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            when (val content = m.opt("content")) {
                is String -> messages.put(JSONObject().put("role", role).put("content", content))

                is JSONArray -> {
                    val text = StringBuilder()
                    val toolCalls = JSONArray()
                    val toolResults = JSONArray()
                    for (j in 0 until content.length()) {
                        val block = content.optJSONObject(j) ?: continue
                        when (block.optString("type")) {
                            "text" -> text.append(block.optString("text"))
                            "tool_use" -> {
                                val input = block.opt("input") ?: JSONObject()
                                toolCalls.put(
                                    JSONObject()
                                        .put("id", block.optString("id"))
                                        .put("type", "function")
                                        .put("function", JSONObject()
                                            .put("name", block.optString("name"))
                                            .put("arguments", input.toString()))
                                )
                            }
                            "tool_result" -> {
                                val body = when (val c = block.opt("content")) {
                                    is String -> c
                                    is JSONArray -> joinTextBlocks(c)
                                    else -> ""
                                }
                                toolResults.put(
                                    JSONObject()
                                        .put("role", "tool")
                                        .put("tool_call_id", block.optString("tool_use_id"))
                                        .put("content", body)
                                )
                            }
                            // image / document 块暂不透传：上游 chat 端点对图片走
                            // 另一套 vision 参数，贸然翻译会直接 400
                            else -> Unit
                        }
                    }

                    if (role == "assistant" && toolCalls.length() > 0) {
                        val am = JSONObject().put("role", "assistant")
                        am.put("content", if (text.isEmpty()) "" else text.toString())
                        am.put("tool_calls", toolCalls)
                        messages.put(am)
                    } else if (toolResults.length() > 0) {
                        for (k in 0 until toolResults.length()) messages.put(toolResults.get(k))
                        if (text.isNotEmpty()) {
                            messages.put(JSONObject().put("role", "user").put("content", text.toString()))
                        }
                    } else {
                        messages.put(JSONObject().put("role", role).put("content", text.toString()))
                    }
                }

                else -> messages.put(JSONObject().put("role", role).put("content", ""))
            }
        }
        out.put("messages", messages)

        val tools = a.optJSONArray("tools")
        if (tools != null && tools.length() > 0) {
            val conv = JSONArray()
            for (i in 0 until tools.length()) {
                val t = tools.optJSONObject(i) ?: continue
                conv.put(
                    JSONObject().put("type", "function").put(
                        "function", JSONObject()
                            .put("name", t.optString("name"))
                            .put("description", t.optString("description"))
                            .put("parameters", t.opt("input_schema") ?: JSONObject().put("type", "object"))
                    )
                )
            }
            out.put("tools", conv)
            when (val tc = a.opt("tool_choice")) {
                is JSONObject -> when (tc.optString("type")) {
                    "any" -> out.put("tool_choice", "required")
                    "none" -> out.put("tool_choice", "none")
                    "tool" -> out.put(
                        "tool_choice",
                        JSONObject().put("type", "function")
                            .put("function", JSONObject().put("name", tc.optString("name")))
                    )
                    else -> out.put("tool_choice", "auto")
                }
                is String -> out.put("tool_choice", tc)
            }
        }
        return out
    }

    private fun joinTextBlocks(arr: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> sb.append(b.optString("text"))
                "tool_result" -> sb.append(joinTextBlocks(b.optJSONArray("content") ?: JSONArray()))
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------- 响应翻译（非流式）

    fun toAnthropic(openAi: JSONObject, model: String): JSONObject {
        val out = JSONObject()
        out.put("id", "msg_" + Identity.hex32().take(24))
        out.put("type", "message")
        out.put("role", "assistant")
        out.put("model", model)

        val content = JSONArray()
        val choice = openAi.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val text = message?.optString("content").orEmpty()
        if (text.isNotEmpty()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }
        val toolCalls = message?.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val input = runCatching { JSONObject(fn.optString("arguments", "{}")) }
                    .getOrDefault(JSONObject())
                content.put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", tc.optString("id").ifBlank { "toolu_" + Identity.hex32().take(20) })
                        .put("name", fn.optString("name"))
                        .put("input", input)
                )
            }
        }
        if (content.length() == 0) {
            content.put(JSONObject().put("type", "text").put("text", ""))
        }
        out.put("content", content)
        out.put("stop_reason", mapStopReason(choice?.optString("finish_reason")))
        out.put("stop_sequence", JSONObject.NULL)

        val usage = openAi.optJSONObject("usage")
        out.put(
            "usage", JSONObject()
                .put("input_tokens", usage?.optInt("prompt_tokens", 0) ?: 0)
                .put("output_tokens", usage?.optInt("completion_tokens", 0) ?: 0)
        )
        return out
    }

    private fun mapStopReason(raw: String?): String = when (raw) {
        "length" -> "max_tokens"
        "tool_calls", "function_call" -> "tool_use"
        "content_filter" -> "end_turn"
        else -> "end_turn"
    }

    // ------------------------------------------------------------- 流式翻译

    private fun streamTranslate(req: HttpReq, conn: java.net.HttpURLConnection, model: String) {
        val writer = try {
            req.startStream("text/event-stream; charset=utf-8")
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            return
        }
        val translator = StreamTranslator(writer, model)
        try {
            translator.start()
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload == "[DONE]") break
                val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                translator.feed(chunk)
            }
            translator.finish()
        } catch (e: Exception) {
            Logs.warn("anthropic stream interrupted: ${e.message}")
            runCatching { translator.finish() }
        } finally {
            runCatching { conn.disconnect() }
            runCatching { writer.end() }
        }
    }

    /**
     * OpenAI 流 → Anthropic 事件流。
     *
     * 需要注意的状态机：OpenAI 把 tool_calls 按 index 分片下发（首片带 id/name，
     * 后续只有 arguments 片段），而 Anthropic 要求一个块先 start 再收若干
     * input_json_delta 再 stop。所以这里维护 index → 块号的映射，并且文本块与
     * 工具块不能同时打开。
     */
    class StreamTranslator(private val writer: ChunkedWriter, private val model: String) {

        private var started = false
        private var blockIndex = -1
        private var currentType: String? = null
        private val toolBlocks = HashMap<Int, Int>()
        private var stopReason: String? = null
        private var outputTokens = 0
        private var inputTokens = 0

        fun start() {
            if (started) return
            started = true
            val message = JSONObject()
                .put("id", "msg_" + Identity.hex32().take(24))
                .put("type", "message")
                .put("role", "assistant")
                .put("model", model)
                .put("content", JSONArray())
                .put("stop_reason", JSONObject.NULL)
                .put("stop_sequence", JSONObject.NULL)
                .put("usage", JSONObject().put("input_tokens", 0).put("output_tokens", 0))
            event("message_start", JSONObject().put("type", "message_start").put("message", message))
        }

        fun feed(chunk: JSONObject) {
            start()
            chunk.optJSONObject("usage")?.let { u ->
                inputTokens = u.optInt("prompt_tokens", inputTokens)
                outputTokens = u.optInt("completion_tokens", outputTokens)
            }
            val choices = chunk.optJSONArray("choices") ?: return
            val choice = choices.optJSONObject(0) ?: return
            val finish = choice.optString("finish_reason", "").ifBlank { null }
            val delta = choice.optJSONObject("delta")
            if (delta != null) {
                handleDelta(delta)
            }
            if (finish != null) stopReason = finish
        }

        private fun handleDelta(delta: JSONObject) {
            val text = delta.optString("content", "")
            if (text.isNotEmpty()) {
                ensureTextBlock()
                event(
                    "content_block_delta", JSONObject()
                        .put("type", "content_block_delta")
                        .put("index", blockIndex)
                        .put("delta", JSONObject().put("type", "text_delta").put("text", text))
                )
            }
            val calls = delta.optJSONArray("tool_calls") ?: return
            for (i in 0 until calls.length()) {
                val tc = calls.optJSONObject(i) ?: continue
                val openaiIndex = tc.optInt("index", i)
                val fn = tc.optJSONObject("function") ?: continue
                var block = toolBlocks[openaiIndex]
                if (block == null) {
                    closeBlock()
                    blockIndex += 1
                    block = blockIndex
                    toolBlocks[openaiIndex] = block
                    currentType = "tool_use"
                    event(
                        "content_block_start", JSONObject()
                            .put("type", "content_block_start")
                            .put("index", block)
                            .put("content_block", JSONObject()
                                .put("type", "tool_use")
                                .put(
                                    "id",
                                    tc.optString("id").ifBlank { "toolu_" + Identity.hex32().take(20) }
                                )
                                .put("name", fn.optString("name"))
                                .put("input", JSONObject()))
                    )
                }
                val args = fn.optString("arguments", "")
                if (args.isNotEmpty()) {
                    event(
                        "content_block_delta", JSONObject()
                            .put("type", "content_block_delta")
                            .put("index", block)
                            .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", args))
                    )
                }
            }
        }

        private fun ensureTextBlock() {
            if (currentType == "text") return
            closeBlock()
            blockIndex += 1
            currentType = "text"
            event(
                "content_block_start", JSONObject()
                    .put("type", "content_block_start")
                    .put("index", blockIndex)
                    .put("content_block", JSONObject().put("type", "text").put("text", ""))
            )
        }

        private fun closeBlock() {
            if (currentType == null) return
            event(
                "content_block_stop", JSONObject()
                    .put("type", "content_block_stop")
                    .put("index", blockIndex)
            )
            currentType = null
        }

        fun finish() {
            start()
            closeBlock()
            val reason = when (stopReason) {
                "length" -> "max_tokens"
                "tool_calls", "function_call" -> "tool_use"
                else -> "end_turn"
            }
            event(
                "message_delta", JSONObject()
                    .put("type", "message_delta")
                    .put("delta", JSONObject().put("stop_reason", reason).put("stop_sequence", JSONObject.NULL))
                    .put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", outputTokens))
            )
            event("message_stop", JSONObject().put("type", "message_stop"))
        }

        private fun event(name: String, data: JSONObject) {
            writer.write("event: $name\ndata: $data\n\n")
        }
    }
}
