package com.workbuddy.proxy.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 登录会话看护。
 *
 * 为什么放在服务端而不是页面里：OAuth 授权页会把整个 WebView 导航走，页面里的
 * 轮询随之消失——用户登录成功后回到控制台，令牌就再也没人去领了。这里由服务端
 * 独立线程池按 state 轮询，页面只查状态，跳走也不影响入池。
 */
object LoginWatcher {

    class Sync(val state: String, val realm: String) {
        @Volatile var status: String = "pending"
        @Volatile var message: String = "等待浏览器登录"
        @Volatile var uid: String = ""
        @Volatile var nickname: String = ""
        @Volatile var updatedAt: Long = System.currentTimeMillis()
    }

    private const val TTL_MS = 10 * 60 * 1000L

    private val sessions = ConcurrentHashMap<String, Sync>()
    private val futures = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val pool = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "wb-login").apply { isDaemon = true }
    }

    @Synchronized
    fun start(realm: String, state: String): Sync {
        sessions[state]?.let { return it }
        val sync = Sync(state, Realms.normalize(realm))
        sessions[state] = sync
        val future = pool.scheduleWithFixedDelay({ tick(sync) }, 1, 2, TimeUnit.SECONDS)
        futures[state] = future
        Logs.info("开始轮询登录 state=${state.take(8)}… (${Realms.label(realm)})")
        return sync
    }

    fun status(state: String): Sync? = sessions[state]

    fun cancel(state: String) = finish(state)

    private fun finish(state: String) {
        sessions.remove(state)
        futures.remove(state)?.cancel(false)
    }

    private fun tick(sync: Sync) {
        if (System.currentTimeMillis() - sync.updatedAt > TTL_MS) {
            sync.status = "expired"
            sync.message = "登录窗口已过期，请重新发起"
            finish(sync.state)
            return
        }
        if (sync.status == "ok" || sync.status == "error" || sync.status == "expired") {
            finish(sync.state)
            return
        }
        val r = runCatching { Upstream.pollLogin(sync.realm, sync.state) }
            .getOrElse { Upstream.PollResult("pending", null, "poll error: ${it.message}") }
        sync.updatedAt = System.currentTimeMillis()
        when (r.status) {
            "ok" -> {
                val acc = r.account
                if (acc != null) {
                    val saved = AccountStore.add(acc)
                    sync.uid = saved.uid
                    sync.nickname = saved.label()
                    sync.status = "ok"
                    sync.message = "登录成功：${saved.label()}"
                    Logs.info("账号入池 ${saved.label()} (${Realms.label(saved.realm)})")
                    // 状态留 5 分钟再清：用户可能跳去系统浏览器登录，切回来时
                    // 已经过了好几分钟，过早清掉会让页面读到 unknown。
                    pool.schedule({ finish(sync.state) }, 300, TimeUnit.SECONDS)
                } else {
                    sync.status = "pending"
                    sync.message = "等待令牌下发"
                }
            }
            "pending" -> {
                sync.status = "pending"
                sync.message = r.message.ifBlank { "等待浏览器登录" }
            }
            "expired" -> {
                sync.status = "expired"
                sync.message = r.message
                pool.schedule({ finish(sync.state) }, 10, TimeUnit.SECONDS)
            }
            else -> {
                sync.status = "error"
                sync.message = r.message
                pool.schedule({ finish(sync.state) }, 10, TimeUnit.SECONDS)
            }
        }
    }
}
