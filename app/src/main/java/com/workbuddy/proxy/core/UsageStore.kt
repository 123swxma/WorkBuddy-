package com.workbuddy.proxy.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/** 一次转发请求的用量快照。 */
data class UsageRecord(
    val at: Long,
    val model: String,
    val account: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int,
    val reasoningTokens: Int,
    val ttftMs: Long,
    val totalMs: Long,
    val ok: Boolean,
    val keyLabel: String = ""
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    /** 出字速度：只算首字之后到结束这段。 */
    val tokPerSec: Double
        get() {
            val genMs = totalMs - ttftMs
            if (genMs <= 0 || completionTokens <= 0) return 0.0
            return completionTokens * 1000.0 / genMs
        }

    fun toJson(): JSONObject = JSONObject()
        .put("at", at).put("m", model).put("a", account).put("k", keyLabel)
        .put("p", promptTokens).put("c", completionTokens)
        .put("h", cachedTokens).put("r", reasoningTokens)
        .put("t", ttftMs).put("d", totalMs).put("ok", ok)

    companion object {
        fun fromJson(o: JSONObject): UsageRecord = UsageRecord(
            at = o.optLong("at"),
            model = o.optString("m"),
            account = o.optString("a"),
            promptTokens = o.optInt("p"),
            completionTokens = o.optInt("c"),
            cachedTokens = o.optInt("h"),
            reasoningTokens = o.optInt("r"),
            ttftMs = o.optLong("t"),
            totalMs = o.optLong("d"),
            ok = o.optBoolean("ok", true),
            keyLabel = o.optString("k")
        )
    }
}

/** 一次请求的计量器：从 SSE 帧里抠 usage，顺便记首字时间。 */
class UsageTracker(
    private val model: String,
    private val account: String,
    private val keyLabel: String = ""
) {

    private val startAt = System.currentTimeMillis()
    private var firstAt = 0L
    private var usage: JSONObject? = null

    fun onChunk(chunk: JSONObject) {
        if (firstAt == 0L && hasVisibleText(chunk)) {
            firstAt = System.currentTimeMillis()
        }
        chunk.optJSONObject("usage")?.let { usage = it }
    }

    fun finish(ok: Boolean = true) {
        val end = System.currentTimeMillis()
        val u = usage
        UsageStore.record(
            UsageRecord(
                at = startAt,
                model = model,
                account = account,
                promptTokens = intOf(u, "prompt_tokens", "input_tokens"),
                completionTokens = intOf(u, "completion_tokens", "output_tokens"),
                cachedTokens = nestedInt(u, "prompt_tokens_details", "cached_tokens")
                    .takeIf { it > 0 }
                    ?: intOf(u, "prompt_cache_hit_tokens", "cached_tokens"),
                reasoningTokens = nestedInt(u, "completion_tokens_details", "reasoning_tokens")
                    .takeIf { it > 0 }
                    ?: intOf(u, "reasoning_tokens"),
                ttftMs = if (firstAt > 0) firstAt - startAt else 0L,
                totalMs = end - startAt,
                ok = ok,
                keyLabel = keyLabel
            )
        )
    }

    private fun hasVisibleText(chunk: JSONObject): Boolean {
        val choices = chunk.optJSONArray("choices") ?: return false
        for (i in 0 until choices.length()) {
            val delta = choices.optJSONObject(i)?.optJSONObject("delta") ?: continue
            if (delta.optString("content").isNotEmpty()) return true
            if (delta.optString("reasoning_content").isNotEmpty()) return true
        }
        return false
    }

    private fun intOf(o: JSONObject?, vararg keys: String): Int {
        if (o == null) return 0
        for (k in keys) {
            val v = o.opt(k) ?: continue
            val n = when (v) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: continue
                else -> continue
            }
            if (n > 0) return n
        }
        return 0
    }

    private fun nestedInt(o: JSONObject?, parent: String, child: String): Int {
        if (o == null) return 0
        val p = o.optJSONObject(parent) ?: return 0
        return p.optInt(child, 0)
    }
}

/** 概览数据：五张卡片要的数字和折线都从这里出。 */
data class UsageSummary(
    val requests: Int,
    val failed: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val reasoningTokens: Long,
    val avgLatencyMs: Long,
    val avgTtftMs: Long,
    val peakTokPerSec: Double,
    val avgTokPerSec: Double,
    val p90TtftMs: Long,
    val costYuan: Double,
    val cacheHitRate: Double,
    val successRate: Double,
    val tokenSeries: List<Float>,
    val requestSeries: List<Float>,
    val speedSeries: List<Float>,
    val cacheSeries: List<Float>,
    val prevRequests: Int,
    val prevTokens: Long,
    val prevSpeed: Double,
    val prevCacheRate: Double,
    val prevCost: Double
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

object UsageStore {

    private const val FILE_NAME = "usage.jsonl"
    private const val MAX = 5000
    private const val BUCKETS = 24

    private val list = Collections.synchronizedList(ArrayList<UsageRecord>())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wb-usage").apply { isDaemon = true }
    }
    private var file: File? = null

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, FILE_NAME)
        file = f
        if (f.exists()) {
            runCatching {
                val lines = f.readLines()
                val keep = if (lines.size > MAX * 2) lines.takeLast(MAX) else lines
                for (line in keep) {
                    if (line.isBlank()) continue
                    runCatching { UsageRecord.fromJson(JSONObject(line)) }.getOrNull()?.let { list.add(it) }
                }
                while (list.size > MAX) list.removeAt(0)
                if (lines.size > MAX * 2) rewrite(f, keep)
            }
        }
    }

    fun record(r: UsageRecord) {
        synchronized(list) {
            list.add(r)
            while (list.size > MAX) list.removeAt(0)
        }
        val f = file ?: return
        io.execute {
            runCatching { f.appendText(r.toJson().toString() + "\n") }
        }
    }

    fun all(): List<UsageRecord> = synchronized(list) { ArrayList(list) }

    /**
     * 筛选可选的 Key 名称：当前配置的 + 历史记录里出现过的。
     *
     * 只读历史的话，刚建好还没发过请求的 Key 不会出现在下拉里；
     * 只读配置的话，删掉的 Key 就看不了它的历史数据。两边并集最实用。
     */
    fun knownKeys(): List<String> {
        val set = LinkedHashSet<String>()
        runCatching {
            for (k in KeyStore.all()) {
                if (k.label.isNotBlank()) set.add(k.label)
            }
        }
        for (r in all()) {
            if (r.keyLabel.isNotBlank()) set.add(r.keyLabel)
        }
        return set.toList()
    }

    private fun rewrite(f: File, lines: List<String>) {
        runCatching { f.writeText(lines.joinToString("\n", postfix = "\n")) }
    }

    // ---------------------------------------------------------------- 聚合

    /**
     * @param keyLabel 只统计这个 Key；null 表示全部
     * @param since    统计窗口起点（毫秒）
     * @param prevSince 上一个等长窗口的起点，用来算环比
     */
    fun summary(keyLabel: String?, since: Long, prevSince: Long): UsageSummary {
        val now = System.currentTimeMillis()
        val snapshot = all()

        val inWindow = snapshot.filter {
            it.at >= since && (keyLabel == null || it.keyLabel == keyLabel)
        }
        val prevWindow = snapshot.filter {
            it.at >= prevSince && it.at < since && (keyLabel == null || it.keyLabel == keyLabel)
        }

        val okList = inWindow.filter { it.ok }
        val failed = inWindow.size - okList.size

        val prompt = okList.sumOf { it.promptTokens.toLong() }
        val completion = okList.sumOf { it.completionTokens.toLong() }
        val cached = okList.sumOf { it.cachedTokens.toLong() }
        val reasoning = okList.sumOf { it.reasoningTokens.toLong() }

        val speeds = okList.map { it.tokPerSec }.filter { it > 0 }
        val ttfts = okList.map { it.ttftMs }.filter { it > 0 }.sorted()

        // 折线：把窗口等分成 24 段，任何时间范围都画得出来
        val span = (now - since).coerceAtLeast(1L)
        val bucketMs = (span / BUCKETS).coerceAtLeast(1L)
        val tokenSeries = FloatArray(BUCKETS)
        val reqSeries = FloatArray(BUCKETS)
        val speedSeries = FloatArray(BUCKETS)
        val cacheSeries = FloatArray(BUCKETS)
        val speedCount = IntArray(BUCKETS)
        val promptByBucket = LongArray(BUCKETS)
        val cachedByBucket = LongArray(BUCKETS)

        for (r in okList) {
            val idx = ((r.at - since) / bucketMs).toInt().coerceIn(0, BUCKETS - 1)
            tokenSeries[idx] += r.totalTokens.toFloat()
            reqSeries[idx] += 1f
            if (r.tokPerSec > 0) {
                speedSeries[idx] += r.tokPerSec.toFloat()
                speedCount[idx] += 1
            }
            promptByBucket[idx] += r.promptTokens
            cachedByBucket[idx] += r.cachedTokens
        }
        for (i in 0 until BUCKETS) {
            if (speedCount[i] > 0) speedSeries[i] /= speedCount[i]
            cacheSeries[i] = if (promptByBucket[i] > 0) {
                cachedByBucket[i].toFloat() / promptByBucket[i] * 100f
            } else 0f
        }

        val prevOk = prevWindow.filter { it.ok }
        val prevPrompt = prevOk.sumOf { it.promptTokens.toLong() }
        val prevCompletion = prevOk.sumOf { it.completionTokens.toLong() }
        val prevCached = prevOk.sumOf { it.cachedTokens.toLong() }
        val prevSpeeds = prevOk.map { it.tokPerSec }.filter { it > 0 }

        return UsageSummary(
            requests = inWindow.size,
            failed = failed,
            promptTokens = prompt,
            completionTokens = completion,
            cachedTokens = cached,
            reasoningTokens = reasoning,
            avgLatencyMs = if (okList.isEmpty()) 0 else okList.sumOf { it.totalMs } / okList.size,
            avgTtftMs = if (ttfts.isEmpty()) 0 else ttfts.average().toLong(),
            peakTokPerSec = speeds.maxOrNull() ?: 0.0,
            avgTokPerSec = if (speeds.isEmpty()) 0.0 else speeds.average(),
            p90TtftMs = if (ttfts.isEmpty()) 0 else ttfts[(ttfts.size * 0.9).toInt().coerceAtMost(ttfts.size - 1)],
            costYuan = estimateCost(prompt, completion),
            cacheHitRate = if (prompt > 0) cached.toDouble() / prompt * 100.0 else 0.0,
            successRate = if (inWindow.isEmpty()) 0.0 else okList.size.toDouble() / inWindow.size * 100.0,
            tokenSeries = tokenSeries.toList(),
            requestSeries = reqSeries.toList(),
            speedSeries = speedSeries.toList(),
            cacheSeries = cacheSeries.toList(),
            prevRequests = prevWindow.size,
            prevTokens = prevPrompt + prevCompletion,
            prevSpeed = if (prevSpeeds.isEmpty()) 0.0 else prevSpeeds.average(),
            prevCacheRate = if (prevPrompt > 0) prevCached.toDouble() / prevPrompt * 100.0 else 0.0,
            prevCost = estimateCost(prevPrompt, prevCompletion)
        )
    }

    /** 粗略折算：输入 ¥2/M、输出 ¥8/M。只用来给个量级，不是账单。 */
    fun estimateCost(prompt: Long, completion: Long): Double =
        prompt / 1_000_000.0 * 2.0 + completion / 1_000_000.0 * 8.0
}
