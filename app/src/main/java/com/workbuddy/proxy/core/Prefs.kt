package com.workbuddy.proxy.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用级配置。刻意保持扁平：端口、Key、局域网开关、开机自启。
 *
 * Key 首次读取时自动生成并落盘，之后稳定复用——客户端一旦配好就不必再改，
 * 除非用户主动在面板里点「重置 Key」。
 */
object Prefs {

    private const val FILE = "wb_proxy"
    private const val K_PORT = "port"
    private const val K_API_KEY = "api_key"
    private const val K_LAN = "allow_lan"
    private const val K_PROMPT_MODE = "prompt_mode"
    private const val K_FONT_SCALE = "font_scale"
    private const val K_COMPACT = "compact"
    private const val K_DARK = "dark_theme"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    var port: Int
        get() = sp.getInt(K_PORT, 8788)
        set(v) { sp.edit().putInt(K_PORT, v.coerceIn(1024, 65535)).apply() }

    /** 局域网可访问（0.0.0.0）还是仅本机（127.0.0.1）。 */
    var allowLan: Boolean
        get() = sp.getBoolean(K_LAN, true)
        set(v) { sp.edit().putBoolean(K_LAN, v).apply() }

    /** 界面字号倍率：0.85 / 1.0 / 1.15 / 1.3。 */
    var fontScale: Float
        get() = sp.getFloat(K_FONT_SCALE, 1.0f)
        set(v) { sp.edit().putFloat(K_FONT_SCALE, v.coerceIn(0.8f, 1.5f)).apply() }

    /** 深色外观。true = values-night 生效。 */
    var darkTheme: Boolean
        get() = sp.getBoolean(K_DARK, true)
        set(v) { sp.edit().putBoolean(K_DARK, v).apply() }

    /** 紧凑模式：缩小卡片内边距。 */
    var compact: Boolean
        get() = sp.getBoolean(K_COMPACT, false)
        set(v) { sp.edit().putBoolean(K_COMPACT, v).apply() }

    /**
     * 上游 system prompt 注入策略：
     *  - "keep"   保留客户端自带的 system（原样透传）
     *  - "replace"用统一 prompt 覆盖
     *  - "auto"   没有 system 时补一句默认值（默认）
     */
    var promptMode: String
        get() = sp.getString(K_PROMPT_MODE, "auto") ?: "auto"
        set(v) { sp.edit().putString(K_PROMPT_MODE, v).apply() }

}
