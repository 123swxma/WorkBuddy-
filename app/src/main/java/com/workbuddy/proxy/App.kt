package com.workbuddy.proxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.workbuddy.proxy.core.AccountStore
import com.workbuddy.proxy.core.KeyStore
import com.workbuddy.proxy.core.UsageStore
import com.workbuddy.proxy.core.Prefs
import com.workbuddy.proxy.core.Tuning
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Tuning.init(this)
        AccountStore.init(this)
        KeyStore.init(this)
        UsageStore.init(this)
        createChannel()
        installCrashHandler()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(ProxyService.CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    ProxyService.CHANNEL_ID,
                    getString(R.string.svc_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.setShowBadge(false)
                ch.description = "反代服务常驻通知"
                nm.createNotificationChannel(ch)
            }
        }
    }

    /**
     * 崩溃堆栈落到 filesDir/last_crash.txt。
     *
     * 手机端出问题用户只能描述「闪退」，没有堆栈就无从下手；
     * 这里保留最近一次崩溃，装好后能在 App 里直接看到。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = Log.getStackTraceString(error)
                File(filesDir, CRASH_FILE).writeText(
                    "${System.currentTimeMillis()}\n$text"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"

        lateinit var instance: App
            private set
    }
}
