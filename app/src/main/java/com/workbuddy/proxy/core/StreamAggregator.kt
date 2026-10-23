package com.workbuddy.proxy.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 SSE 流折成一个非流式的 chat.completion。
 *
 * 上游只接受 `stream: true`（非流式请求会回 400 Non-stream chat request is
 * currently not supported），所以「客户端要 JSON」这件事只能由网关自己完成：
 * 内部照样开流，读完整条流再拼出 message。
 *
 * 合并规则与参考实现一致：content / reasoning_content 顺序拼接；tool_calls 按
 * index 增量合并，首片带 id 与函数名、后续片只有 arguments 片段；空函数名的
 * 占位调用丢弃（上游会在流尾补 `{"name":"","arguments":""}`）。
 */
class StreamAggregator(private val fallbackModel: String) {

    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val toolCalls = LinkedHashMap<Int, JSONObject>()
    private var finish: String? = null
    private var usage: JSONObject? = null
    private var id: String = ""
    private var model: String = fallbackModel

    fun feed(chunk: JSONObject) {
        val newId = chunk.optString("id", "")
        if (newId.isNotBlank()) id = newId
        val newModel = chunk.optString("model", "")
        if (newModel.isNotBlank()) model = newModel

        chunk.optJSONObject("usage")?.let { u ->
            val prev = usage
            if (prev == null || u.optInt("total_tokens", 0) >= prev.optInt("total_tokens", 0)) {
                usage = u
            }
        }

        val choices = chunk.optJSONArray("choices") ?: return
        for (i in 0 until choices.length()) {
            val choice = choices.optJSONObject(i) ?: continue
            choice.optJSONObject("delta")?.let { delta ->
                val piece = delta.optString("content", "")
                if (piece.isNotEmpty()) content.append(piece)

                val think = delta.optString("reasoning_content", "")
                if (think.isNotEmpty()) reasoning.append(think)

                delta.optJSONArray("tool_calls")?.let { calls ->
                    for (j in 0 until calls.length()) {
                        mergeToolCall(calls.optJSONObject(j) ?: continue)
                    }
                }
            }
            val fr = choice.optString("finish_reason", "")
            if (fr.isNotBlank()) finish = fr
        }
    }

    private fun mergeToolCall(tc: JSONObject) {
        val index = if (tc.has("index")) tc.optInt("index") else toolCalls.size
        val fn = tc.optJSONObject("function")
        val callId = tc.optString("id", "")
        val name = fn?.optString("name", "") ?: ""
        val args = fn?.optString("arguments", "") ?: ""

        val entry = toolCalls.getOrPut(index) {
            JSONObject()
                .put("id", callId.ifBlank { "call_" + Identity.hex32().take(24) })
                .put("type", "function")
                .put("function", JSONObject().put("name", "").put("arguments", ""))
        }
        if (callId.isNotBlank()) entry.put("id", callId)
        val f = entry.getJSONObject("function")
        if (name.isNotBlank()) f.put("name", f.optString("name", "") + name)
        if (args.isNotEmpty()) f.put("arguments", f.optString("arguments", "") + args)
    }

    fun build(): JSONObject {
        val message = JSONObject()
        message.put("role", "assistant")
        message.put("content", content.toString())
        if (reasoning.isNotEmpty()) {
            message.put("reasoning_content", reasoning.toString())
        }

        val valid = toolCalls.entries
            .sortedBy { it.key }
            .map { it.value }
            .filter { it.optJSONObject("function")?.optString("name", "")?.isNotBlank() == true }

        var finishReason = finish ?: "stop"
        if (valid.isNotEmpty()) {
            val arr = JSONArray()
            for (v in valid) arr.put(v)
            message.put("tool_calls", arr)
            if (finishReason == "stop") finishReason = "tool_calls"
        }

        val choice = JSONObject()
            .put("index", 0)
            .put("message", message)
            .put("finish_reason", finishReason)

        val choices = JSONArray()
        choices.put(choice)

        val out = JSONObject()
            .put("id", id.ifBlank { "chatcmpl-" + Identity.hex32() })
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", choices)
        usage?.let { out.put("usage", it) }
        return out
    }
}
