package com.workbuddy.proxy.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 少量运行时覆盖项（不常改，但改了对排障很关键）。
 * 单独放一个对象，避免把 Prefs 变成一个什么都往里塞的口袋。
 */
object Tuning {

    private const val FILE = "wb_tuning"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    private fun ready(): Boolean = ::sp.isInitialized

    /** 国内版对话基址：copilot.tencent.com 或 www.workbuddy.cn（海外出口用后者）。 */
    var cnBase: String
        get() = if (ready())
            Realms.chatBaseRaw(sp.getString("cn_base", Realms.CN_BASE_PRIMARY))
        else Realms.CN_BASE_PRIMARY
        set(v) { if (ready()) sp.edit().putString("cn_base", Realms.chatBaseRaw(v)).apply() }

    /**
     * 国际版对话基址。默认官方入口；留出覆盖是为了接自建中转 / 企业反代，
     * 联调时也能把它指向本地 mock。
     */
    var intlBase: String
        get() = if (ready())
            Realms.httpBaseOr(sp.getString("intl_base", Realms.INTL_BASE), Realms.INTL_BASE)
        else Realms.INTL_BASE
        set(v) { if (ready()) sp.edit().putString("intl_base", Realms.httpBaseOr(v, Realms.INTL_BASE)).apply() }

    /** 请求体上限，超了直接 413，避免手机内存被单条请求打爆。 */
    var maxBodyBytes: Int
        get() = if (ready()) sp.getInt("max_body", 32 * 1024 * 1024) else 32 * 1024 * 1024
        set(v) { if (ready()) sp.edit().putInt("max_body", v).apply() }

    /** 上游读超时（秒）。思考类模型首包可能很慢。 */
    var readTimeoutSec: Int
        get() = if (ready()) sp.getInt("read_timeout", 300) else 300
        set(v) { if (ready()) sp.edit().putInt("read_timeout", v).apply() }

    /** 单请求最多换几个账号重试。 */
    var maxRetries: Int
        get() = if (ready()) sp.getInt("max_retries", 2) else 2
        set(v) { if (ready()) sp.edit().putInt("max_retries", v).apply() }

    /** 是否对上游响应做空噪声清洗（去掉空 delta 字段）。 */
    var cleanChunks: Boolean
        get() = if (ready()) sp.getBoolean("clean_chunks", true) else true
        set(v) { if (ready()) sp.edit().putBoolean("clean_chunks", v).apply() }

    /** 是否记录最近请求日志（内存环形缓冲，面板可见）。 */
    var keepLogs: Boolean
        get() = if (ready()) sp.getBoolean("keep_logs", true) else true
        set(v) { if (ready()) sp.edit().putBoolean("keep_logs", v).apply() }
}
