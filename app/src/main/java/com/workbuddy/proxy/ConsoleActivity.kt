package com.workbuddy.proxy

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import com.workbuddy.proxy.core.Prefs

/** 内置控制台：直接加载本机服务提供的 Web 面板，省掉一个原生设置页。 */
class ConsoleActivity : WbActivity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_console)
        web = findViewById(R.id.consoleWeb)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = false
        web.settings.setSupportZoom(false)
        web.settings.loadWithOverviewMode = true
        web.settings.builtInZoomControls = false
        web.webViewClient = WebViewClient()
        val port = if (ProxyService.activePort > 0) ProxyService.activePort else Prefs.port
        val theme = if (Prefs.darkTheme) "dark" else "light"
        web.loadUrl("http://127.0.0.1:$port/?theme=$theme")
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) {
            web.goBack()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        runCatching {
            web.loadUrl("about:blank")
            web.destroy()
        }
        super.onDestroy()
    }
}
