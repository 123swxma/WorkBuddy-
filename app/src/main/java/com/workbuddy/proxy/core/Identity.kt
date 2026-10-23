package com.workbuddy.proxy.core

import java.security.MessageDigest
import java.util.UUID

/**
 * 设备/会话标识派生，对齐官方桌面客户端 5.8.4 的抓包形态：
 *  - conversation / message id：32 位无连字符 hex
 *  - machineId / sessionId：带连字符的 UUIDv4 形状，按账号稳定派生
 *  - trace：X-Trace-ID / traceparent / b3 / X-B3-*
 *
 * 这些值全部随请求现生成或按账号派生：同一账号的机器码恒定，不同账号互不关联，
 * 这正是上游风控检查的形态。
 */
object Identity {

    fun uuid(): String = UUID.randomUUID().toString()

    fun hex32(): String = UUID.randomUUID().toString().replace("-", "")

    /** 由 uid + salt 稳定派生 UUIDv4 形状的伪设备标识。 */
    fun deriveId(uid: String, salt: String): String {
        val seed = "$salt:${uid.ifBlank { "anonymous" }}"
        val md = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
        val h = StringBuilder()
        for (b in md) h.append("%02x".format(b.toInt() and 0xFF))
        var s = h.toString()
        s = s.substring(0, 12) + "4" + s.substring(13, 16) + "a" + s.substring(17)
        return "${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20, 32)}"
    }

    fun machineId(uid: String): String = deriveId(uid, "machine")

    fun sessionId(uid: String): String = deriveId(uid, "session")

    /** 链路追踪头族（桌面端主请求形态，withParent=false 时与抓包一致）。 */
    fun traceHeaders(): Map<String, String> {
        val trace = hex32()
        val span = hex16()
        return linkedMapOf(
            "X-Trace-ID" to trace,
            "traceparent" to "00-$trace-$span-01",
            "b3" to "$trace-$span-1",
            "X-B3-TraceId" to trace,
            "X-B3-SpanId" to span,
            "X-B3-Sampled" to "1"
        )
    }

    private fun hex16(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)

    /** 从 JWT 里取 uid（sub / userId / uid），取不到就回空串。 */
    fun uidFromJwt(token: String): String {
        return jwtPayload(token)?.let { obj ->
            for (k in listOf("sub", "userId", "uid", "user_id", "email")) {
                val v = obj.optString(k, "")
                if (v.isNotBlank()) return v
            }
            ""
        } ?: ""
    }

    /** 从 JWT 里取过期时间（毫秒），取不到回 0。 */
    fun expFromJwt(token: String): Long {
        val exp = jwtPayload(token)?.optLong("exp", 0) ?: return 0
        return if (exp > 0) normalizeEpoch(exp) else 0
    }

    /**
     * 解析 JWT payload。上游可能给出无 padding 的 base64url，
     * Android 的 URL_SAFE 解码器对缺 padding 的输入会抛异常，这里补齐。
     */
    private fun jwtPayload(token: String): org.json.JSONObject? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val raw = parts[1].replace('-', '+').replace('_', '/')
            val pad = (4 - raw.length % 4) % 4
            val bytes = android.util.Base64.decode(raw + "=".repeat(pad), android.util.Base64.DEFAULT)
            org.json.JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    /** 上游的时间戳可能是秒也可能是毫秒，统一成毫秒。 */
    fun normalizeEpoch(value: Long): Long = when {
        value <= 0 -> 0
        value < 1_000_000_000_000L -> value * 1000
        else -> value
    }
}
