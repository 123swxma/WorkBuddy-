package com.workbuddy.proxy

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import com.workbuddy.proxy.core.Prefs
import com.workbuddy.proxy.core.Realms
import com.workbuddy.proxy.core.Tuning

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
        bindAdvanced()
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

    /** 网络与上游：以前藏在网页面板的设置页里，现在搬到 App 内。 */
    private fun bindAdvanced() {
        val port = findViewById<EditText>(R.id.setPort).apply { setText(Prefs.port.toString()) }
        val lan = findViewById<Switch>(R.id.setLan).apply { isChecked = Prefs.allowLan }
        val intl = findViewById<EditText>(R.id.setIntlBase).apply { setText(Tuning.intlBase) }
        val timeout = findViewById<EditText>(R.id.setTimeout)
            .apply { setText(Tuning.readTimeoutSec.toString()) }
        val retries = findViewById<EditText>(R.id.setRetries)
            .apply { setText(Tuning.maxRetries.toString()) }
        val clean = findViewById<Switch>(R.id.setClean).apply { isChecked = Tuning.cleanChunks }

        when (Prefs.promptMode) {
            "keep" -> findViewById<RadioButton>(R.id.setPromptKeep).isChecked = true
            "replace" -> findViewById<RadioButton>(R.id.setPromptReplace).isChecked = true
            else -> findViewById<RadioButton>(R.id.setPromptAuto).isChecked = true
        }
        if (Tuning.cnBase.contains("workbuddy.cn")) {
            findViewById<RadioButton>(R.id.setCnAlt).isChecked = true
        } else {
            findViewById<RadioButton>(R.id.setCnPrimary).isChecked = true
        }

        findViewById<Button>(R.id.btnSaveAdvanced).setOnClickListener {
            val p = port.text.toString().trim().toIntOrNull()
            if (p == null || p !in 1024..65535) {
                toast("端口要在 1024–65535 之间")
                return@setOnClickListener
            }
            Prefs.port = p
            Prefs.allowLan = lan.isChecked
            Prefs.promptMode = when (
                findViewById<RadioGroup>(R.id.setPromptGroup).checkedRadioButtonId
            ) {
                R.id.setPromptKeep -> "keep"
                R.id.setPromptReplace -> "replace"
                else -> "auto"
            }
            Tuning.cnBase =
                if (findViewById<RadioGroup>(R.id.setCnGroup).checkedRadioButtonId == R.id.setCnAlt) {
                    Realms.CN_BASE_ALT
                } else {
                    Realms.CN_BASE_PRIMARY
                }
            Tuning.intlBase = intl.text.toString().trim()
            Tuning.readTimeoutSec = (timeout.text.toString().trim().toIntOrNull() ?: 300)
                .coerceIn(30, 1800)
            Tuning.maxRetries = (retries.text.toString().trim().toIntOrNull() ?: 2)
                .coerceIn(0, 5)
            Tuning.cleanChunks = clean.isChecked

            toast(
                if (ProxyService.running) "已保存，端口改动需重启服务"
                else "已保存"
            )
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

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
