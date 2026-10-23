package com.workbuddy.proxy

import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.workbuddy.proxy.core.Prefs

/**
 * 外观设置：字号与卡片密度。
 *
 * 字号走 [attachBaseContext] 覆盖 configuration.fontScale——这是唯一能让
 * XML 里写死的 sp 值整体放大的办法，不用把每个 textSize 都搬进代码。
 * 改动后调用 recreate()，本页与主界面都按新字号重排。
 */
class SettingsActivity : WbActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Prefs.init(this)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.themeLight).setOnClickListener {
            Prefs.darkTheme = false
            renderTheme()
            recreate()
        }
        findViewById<Button>(R.id.themeDark).setOnClickListener {
            Prefs.darkTheme = true
            renderTheme()
            recreate()
        }
        renderTheme()

        val buttons = mapOf(
            0.85f to findViewById<Button>(R.id.fontSmall),
            1.0f to findViewById<Button>(R.id.fontNormal),
            1.15f to findViewById<Button>(R.id.fontLarge),
            1.3f to findViewById<Button>(R.id.fontXLarge)
        )
        for ((scale, btn) in buttons) {
            btn.setOnClickListener {
                Prefs.fontScale = scale
                renderScale()
        TouchFx.applyTo(findViewById(android.R.id.content))
                recreate()
            }
        }

        val sw = findViewById<Switch>(R.id.swCompact)
        sw.isChecked = Prefs.compact
        sw.setOnCheckedChangeListener { _, checked ->
            Prefs.compact = checked
        }

        findViewById<Button>(R.id.btnResetAppearance).setOnClickListener {
            Prefs.fontScale = 1.0f
            Prefs.compact = false
            Prefs.darkTheme = true
            sw.isChecked = false
            renderScale()
            renderTheme()
            recreate()
        }

        val vn = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        renderScale()

        findViewById<TextView>(R.id.aboutText).text =
            "WorkBuddy 反代 v$vn\n" +
                "本机账号池 → 标准 OpenAI / Anthropic 接口\n" +
                "所有凭据只存在本机 App 私有目录，不上传任何服务器。"
    }

    private fun renderTheme() {
        val dark = Prefs.darkTheme
        val light = findViewById<Button>(R.id.themeLight)
        val night = findViewById<Button>(R.id.themeDark)
        light.setBackgroundResource(if (!dark) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost)
        night.setBackgroundResource(if (dark) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost)
        light.setTextColor(getColor(if (!dark) R.color.on_brand else R.color.text))
        night.setTextColor(getColor(if (dark) R.color.on_brand else R.color.text))
    }

    private fun renderScale() {
        val current = Prefs.fontScale
        val map = mapOf(
            0.85f to R.id.fontSmall,
            1.0f to R.id.fontNormal,
            1.15f to R.id.fontLarge,
            1.3f to R.id.fontXLarge
        )
        for ((scale, id) in map) {
            val btn = findViewById<Button>(id)
            val on = kotlin.math.abs(current - scale) < 0.01f
            btn.setBackgroundResource(
                if (on) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost
            )
            btn.setTextColor(
                getColor(if (on) R.color.on_brand else R.color.text)
            )
        }
    }
}
