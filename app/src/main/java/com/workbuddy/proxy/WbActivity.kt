package com.workbuddy.proxy

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import com.workbuddy.proxy.core.Prefs

/**
 * 所有界面的基类：统一施加「字号」与「浅色/深色」两项外观偏好。
 *
 * 采用覆盖 configuration 而不是换主题的做法——字号靠 fontScale，深色靠
 * uiMode=night 触发 values-night，这样子面布局里写死的 @color / sp
 * 全部自动跟随，不用把每个控件都搬进代码改一遍。
 */
abstract class WbActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        Prefs.init(newBase)
        val cfg = Configuration(newBase.resources.configuration)
        var changed = false

        val scale = runCatching { Prefs.fontScale }.getOrDefault(1.0f)
        if (scale != 1.0f) {
            cfg.fontScale = scale
            changed = true
        }

        val dark = runCatching { Prefs.darkTheme }.getOrDefault(true)
        val isNight = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (isNight != dark) {
            val mask = Configuration.UI_MODE_NIGHT_MASK
            cfg.uiMode = (cfg.uiMode and mask.inv()) or
                (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            changed = true
        }

        super.attachBaseContext(if (changed) newBase.createConfigurationContext(cfg) else newBase)
    }
}
