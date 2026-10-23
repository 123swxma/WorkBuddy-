package com.workbuddy.proxy

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.workbuddy.proxy.core.Account
import com.workbuddy.proxy.core.AccountStore
import com.workbuddy.proxy.core.Identity
import com.workbuddy.proxy.core.LoginWatcher
import com.workbuddy.proxy.core.Logs
import com.workbuddy.proxy.core.Realms
import com.workbuddy.proxy.core.Upstream

/**
 * OAuth 登录。
 *
 * 两条打开方式，因为 Google 的政策把它们分开了：
 *  - **系统浏览器**（默认给国际版）：Google 从 2021 起拒绝在嵌入式 WebView 里
 *    完成 OAuth，会直接甩「此浏览器或应用可能不安全」。真实浏览器才认。
 *  - **应用内 WebView**（国内版够用）：腾讯的登录页不挑容器，省去切应用。
 *
 * 两条路都不拦截回调 URL：上游是「服务端按 state 认领浏览器会话」，只要浏览器
 * 那边登录成功，轮询 /v2/plugin/auth/token 就能拿到令牌。真正的轮询在
 * [LoginWatcher] 里跑（后台线程池），所以跳去浏览器、甚至 Activity 被回收，
 * 只要进程还在，登录结果都不会丢。
 */
class LoginActivity : WbActivity() {

    private lateinit var web: WebView
    private lateinit var hint: TextView
    private lateinit var progress: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private var realm: String = Realms.INTL
    private var state: String? = null
    private var authUrl: String? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        realm = intent.getStringExtra(EXTRA_REALM) ?: Realms.INTL
        hint = findViewById(R.id.loginHint)
        progress = findViewById(R.id.loginProgress)
        web = findViewById(R.id.loginWeb)

        title = "登录 ${Realms.label(realm)}"

        // 不覆盖 UA：伪装成桌面 Chrome 反而让 Google 更容易判定「非受信浏览器」，
        // 系统默认 UA 在真实浏览器里才有意义。
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
            }
        }

        findViewById<Button>(R.id.btnBrowser).setOnClickListener { openInBrowser() }
        findViewById<Button>(R.id.btnInApp).setOnClickListener { openInWebView() }

        TouchFx.applyTo(findViewById(android.R.id.content))
        begin()
    }

    private fun begin() {
        hint.text = "正在向 ${Realms.label(realm)} 申请授权…"
        Thread {
            try {
                val start = Upstream.startLogin(realm)
                LoginWatcher.start(realm, start.state)
                runOnUiThread {
                    state = start.state
                    authUrl = start.authUrl
                    if (realm == Realms.INTL) {
                        // 国际版默认走系统浏览器：Google 账号在 WebView 里必被拒
                        hint.text = "已用系统浏览器打开登录页。完成登录后回到本页，会自动检测并入库。"
                        openInBrowser()
                    } else {
                        hint.text = "请完成登录，成功后会自动入池"
                        progress.visibility = View.VISIBLE
                        openInWebView()
                    }
                    handler.postDelayed(pollRunnable, 1500)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hint.text = "申请授权失败：${e.message}"
                    progress.visibility = View.GONE
                    offerManual()
                }
            }
        }.start()
    }

    private fun openInBrowser() {
        val url = authUrl ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { startActivity(intent) }.isSuccess
        if (!ok) {
            hint.text = "没有可用的浏览器，已改为应用内打开"
            openInWebView()
        }
    }

    private fun openInWebView() {
        val url = authUrl ?: return
        progress.visibility = View.VISIBLE
        web.loadUrl(url)
    }

    /** 只查 [LoginWatcher] 的状态，真正的轮询在后台线程池里。 */
    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (finished) return
            val st = state ?: return
            val sync = LoginWatcher.status(st)
            if (sync == null) {
                handler.postDelayed(this, 3000)
                return
            }
            when (sync.status) {
                "ok" -> {
                    finished = true
                    progress.visibility = View.GONE
                    toast(sync.message.ifBlank { "登录成功" })
                    setResult(RESULT_OK, Intent().putExtra("uid", sync.uid))
                    finish()
                }
                "error", "expired" -> {
                    hint.text = sync.message
                    handler.postDelayed(this, 4000)
                }
                else -> {
                    hint.text = sync.message.ifBlank { "等待浏览器登录…" }
                    handler.postDelayed(this, 2000)
                }
            }
        }
    }

    private fun offerManual() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 10, 40, 0)
        }
        val input = EditText(this).apply { hint = "粘贴 accessToken" }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("改为粘贴 Token")
            .setView(box)
            .setNegativeButton("关闭") { _, _ -> finish() }
            .setPositiveButton("保存") { _, _ ->
                val token = input.text.toString().trim()
                if (token.length < 20) {
                    toast("Token 不完整")
                    return@setPositiveButton
                }
                val uid = Identity.uidFromJwt(token).ifBlank { "u_" + Identity.hex32().take(10) }
                AccountStore.add(
                    Account(
                        uid = uid,
                        nickname = uid.take(8),
                        accessToken = token,
                        refreshToken = "",
                        expiresAt = Identity.expFromJwt(token),
                        realm = realm,
                        domain = Realms.domain(realm),
                        enabled = true,
                        addedAt = System.currentTimeMillis()
                    )
                )
                setResult(RESULT_OK)
                finish()
            }
            .show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            web.loadUrl("about:blank")
            web.destroy()
        }
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_REALM = "realm"
    }
}
