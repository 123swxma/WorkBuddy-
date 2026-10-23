package com.workbuddy.proxy

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.workbuddy.proxy.core.Logs
import com.workbuddy.proxy.core.Prefs
import com.workbuddy.proxy.server.HttpServer
import com.workbuddy.proxy.server.Router

/**
 * 前台服务：持有 HTTP 监听。
 *
 * 手机厂商的省电策略会杀后台进程，所以这里必须是前台服务 + 常驻通知，
 * 而不是普通 Service。startForeground 在 onCreate 后立刻调用，满足 Android
 * 8+ 的 5 秒窗口要求。
 */
class ProxyService : Service() {

    companion object {
        const val CHANNEL_ID = "wb_proxy_svc"
        const val ACTION_START = "com.workbuddy.proxy.START"
        const val ACTION_STOP = "com.workbuddy.proxy.STOP"
        private const val NOTIF_ID = 0x5701

        @Volatile var running: Boolean = false
            private set

        @Volatile var activePort: Int = 0
            private set

        @Volatile var lastError: String = ""
            private set

        fun start(ctx: Context) {
            val intent = Intent(ctx, ProxyService::class.java).setAction(ACTION_START)
            // Android 12+ 在后台启动前台服务会被系统拒绝（开机广播就是这种场景），
            // 这里吞掉异常：失败时用户手动点一次即可，不该把 App 打崩。
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }.onFailure { Logs.warn("启动服务被拒绝: ${it.message}") }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, ProxyService::class.java).setAction(ACTION_STOP)
            runCatching { ctx.startService(intent) }
            // startService 在部分后台场景会被系统拒绝；stopService 走 onDestroy，
            // 同样会执行 shutdown()，两条路任一成功都能把监听关掉。
            runCatching { ctx.stopService(Intent(ctx, ProxyService::class.java)) }
        }
    }

    private var server: HttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 不依赖 Application 是否已建好渠道：服务自己兜一层。
        // 渠道缺失时 startForeground 会抛 «invalid channel»，五秒后系统直接杀进程——
        // 表现就是「点开就闪退」。
        ensureChannel()
        val foregroundOk = runCatching {
            startForegroundCompat(buildNotification("正在启动…"))
        }.onFailure {
            lastError = "前台通知失败：${it.message}"
            Logs.error(lastError)
        }.isSuccess
        if (!foregroundOk) {
            // 进不了前台就不要假装在跑，否则系统五秒后仍会杀掉进程
            runCatching { stopSelf() }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = android.app.NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.svc_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopForegroundSafe()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ensureStarted()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun ensureStarted() {
        if (server?.isRunning == true) {
            publishNotification()
            return
        }
        val requested = Prefs.port
        var ok = false
        var usedPort = requested
        // 端口被占（常见于上一个实例没退干净）时顺延几个，避免整个服务起不来
        for (candidate in requested until requested + 10) {
            val s = HttpServer(candidate, Prefs.allowLan) { req -> Router.handle(req) }
            if (s.start()) {
                server = s
                usedPort = candidate
                ok = true
                break
            }
            lastError = s.lastError
        }
        if (!ok) {
            running = false
            activePort = 0
            Router.serviceRunning = false
            Router.activePort = 0
            Logs.error("监听失败: $lastError")
            publishNotification("启动失败：$lastError")
            return
        }
        if (usedPort != requested) Prefs.port = usedPort
        running = true
        activePort = usedPort
        Router.serviceRunning = true
        Router.activePort = usedPort
        Logs.info("服务已启动，端口 $usedPort（${if (Prefs.allowLan) "局域网可访问" else "仅本机"}）")
        publishNotification()
    }

    private fun shutdown() {
        runCatching { server?.stop() }
        server = null
        running = false
        activePort = 0
        Router.serviceRunning = false
        Router.activePort = 0
        Logs.info("服务已停止")
    }

    private fun publishNotification(error: String? = null) {
        val text = when {
            error != null -> error
            running -> {
                val lan = Router.lanIp()
                if (lan.isNotBlank() && Prefs.allowLan) "http://$lan:$activePort/v1" else "http://127.0.0.1:$activePort/v1"
            }
            else -> "未运行"
        }
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags()
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.svc_running))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
