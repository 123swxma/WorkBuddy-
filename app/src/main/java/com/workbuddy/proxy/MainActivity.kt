package com.workbuddy.proxy

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.workbuddy.proxy.core.AccountStore
import com.workbuddy.proxy.core.Prefs
import com.workbuddy.proxy.core.Realms
import com.workbuddy.proxy.core.Tuning
import com.workbuddy.proxy.server.Router

class MainActivity : WbActivity() {

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var addrText: TextView
    private lateinit var baseUrlText: TextView
    private lateinit var accountsBox: LinearLayout
    private lateinit var accountsTitle: TextView
    private lateinit var btnToggle: Button

    private val handler = Handler(Looper.getMainLooper())
    private var lastAccountsSignature = ""
    private var currentTab = 0

    private var appliedFontScale = 1.0f
    private var appliedCompact = false
    private var appliedDark = true
    private var logLevel = "all"
    private var logQuery = ""
    private var previousTab = -1

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Prefs.init(this)
        Tuning.init(this)
        AccountStore.init(this)
        appliedFontScale = Prefs.fontScale
        appliedCompact = Prefs.compact
        appliedDark = Prefs.darkTheme

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        addrText = findViewById(R.id.addrText)
        baseUrlText = findViewById(R.id.baseUrlText)
        accountsBox = findViewById(R.id.accountsBox)
        accountsTitle = findViewById(R.id.accountsTitle)
        btnToggle = findViewById(R.id.btnToggle)

        findViewById<TextView>(R.id.versionText).text =
            "v1.0.0 · Android ${Build.VERSION.RELEASE} · 账号池 ${AccountStore.size()}"



        btnToggle.setOnClickListener { toggleService() }

        findViewById<TextView>(R.id.tabProxy).setOnClickListener { switchTab(TAB_PROXY) }
        findViewById<TextView>(R.id.tabApi).setOnClickListener { switchTab(TAB_API) }
        findViewById<TextView>(R.id.tabConsole).setOnClickListener { switchTab(TAB_CONSOLE) }
        findViewById<TextView>(R.id.tabData).setOnClickListener { switchTab(TAB_DATA) }
        findViewById<TextView>(R.id.keyDropdown).setOnClickListener { showKeyPicker() }
        findViewById<TextView>(R.id.timeDropdown).setOnClickListener { showTimePicker() }
        findViewById<TextView>(R.id.tabMine).setOnClickListener { switchTab(TAB_MINE) }
        switchTab(TAB_PROXY)

        // 设置入口是整张卡片，点哪儿都能进
        findViewById<View>(R.id.settingsCard).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        bindLogFilter(R.id.logAll, "all")
        bindLogFilter(R.id.logInfo, "info")
        bindLogFilter(R.id.logWarn, "warn")
        bindLogFilter(R.id.logError, "error")
        updateLogFilterButtons()

        findViewById<EditText>(R.id.logSearch).addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    logQuery = s?.toString().orEmpty()
                    renderLogs()
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            }
        )

        findViewById<Button>(R.id.btnCopyBase).setOnClickListener {
            copyText("http://127.0.0.1:${portNow()}/v1", "Base URL")
        }
        findViewById<Button>(R.id.btnCopyBaseUrl).setOnClickListener {
            copyText("http://127.0.0.1:${portNow()}/v1", "Base URL")
        }
        findViewById<Button>(R.id.btnCopyApiKey).setOnClickListener {
            val k = com.workbuddy.proxy.core.KeyStore.all().firstOrNull { it.enabled }
            if (k == null) toast("没有启用的 Key") else copyText(k.key, "API Key")
        }

        val portInput = findViewById<EditText>(R.id.portInput)
        portInput.setText(Prefs.port.toString())
        findViewById<Button>(R.id.btnSavePort).setOnClickListener {
            val raw = portInput.text.toString().trim().toIntOrNull()
            if (raw == null || raw !in 1024..65535) {
                toast("端口要在 1024–65535 之间")
                return@setOnClickListener
            }
            Prefs.port = raw
            toast("已保存，重启服务后生效")
            render()
        }

        findViewById<Button>(R.id.btnAddAccount).setOnClickListener { showRealmPicker() }

        findViewById<Button>(R.id.btnNewKey).setOnClickListener { showKeyForm(null) }

        requestNotificationPermission()
        showLastCrashIfAny()

        TouchFx.applyTo(findViewById(android.R.id.content))

        // 刻意不自动开服：端口由用户自己点「启动服务」才占用。
        // 想常驻就在点开之后锁一下后台，别让用户在不知情时被监听。
    }

    /** 上次崩溃的堆栈只提示一次，看完就删——留着它反而会让用户每次开 App 都被打扰。 */
    private fun showLastCrashIfAny() {
        val file = java.io.File(filesDir, App.CRASH_FILE)
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        runCatching { file.delete() }
        val body = android.widget.TextView(this).apply {
            this.text = text.take(6000)
            textSize = 9.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val wrap = android.widget.ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle("上次崩溃记录")
            .setView(wrap)
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页回来时，字号或密度可能变了——重建一次才吃得到新值
        if (kotlin.math.abs(Prefs.fontScale - appliedFontScale) > 0.001f) {
            appliedFontScale = Prefs.fontScale
            recreate()
            return
        }
        if (Prefs.compact != appliedCompact) {
            appliedCompact = Prefs.compact
            recreate()
            return
        }
        if (Prefs.darkTheme != appliedDark) {
            appliedDark = Prefs.darkTheme
            recreate()
            return
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * 启停服务：按钮进入「启动中 / 停止中」的过渡态，状态圆点做脉冲，
     * 等 running 真正翻转后再淡入新状态——避免点一下没反应、过一秒突然跳变。
     */
    private fun toggleService() {
        val starting = !ProxyService.running
        btnToggle.isEnabled = false
        btnToggle.text = if (starting) "启动中…" else "停止中…"
        btnToggle.alpha = 0.7f
        pulseDot()

        if (starting) ProxyService.start(this) else ProxyService.stop(this)

        val deadline = System.currentTimeMillis() + 2500
        val poll = object : Runnable {
            override fun run() {
                val reached = ProxyService.running == starting
                if (reached || System.currentTimeMillis() > deadline) {
                    btnToggle.isEnabled = true
                    btnToggle.alpha = 1f
                    render()
                    fadeIn(findViewById(R.id.statusText))
                    val dot = findViewById<View>(R.id.statusDot)
                    dot.animate().cancel()
                    dot.alpha = 1f
                } else {
                    handler.postDelayed(this, 120)
                }
            }
        }
        handler.postDelayed(poll, 150)
    }

    /** 圆点呼吸：等待期间给个「还在处理」的视觉提示。 */
    private fun pulseDot() {
        val dot = findViewById<View>(R.id.statusDot)
        dot.animate().cancel()
        val anim = android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.22f)
        anim.duration = 300
        anim.repeatMode = android.animation.ValueAnimator.REVERSE
        anim.repeatCount = 7
        anim.start()
    }

    private fun fadeIn(view: View) {
        view.animate().cancel()
        view.alpha = 0.2f
        view.animate().alpha(1f).setDuration(280).start()
    }

    // ------------------------------------------------------------ 底部导航

    private fun switchTab(tab: Int) {
        val pages = mapOf(
            TAB_PROXY to R.id.pageProxy,
            TAB_API to R.id.pageApi,
            TAB_CONSOLE to R.id.pageConsole,
            TAB_DATA to R.id.pageData,
            TAB_MINE to R.id.pageMine
        )
        val tabs = mapOf(
            TAB_PROXY to R.id.tabProxy,
            TAB_API to R.id.tabApi,
            TAB_CONSOLE to R.id.tabConsole,
            TAB_DATA to R.id.tabData,
            TAB_MINE to R.id.tabMine
        )
        for ((id, pageId) in pages) {
            val page = findViewById<View>(pageId)
            if (id == tab) {
                if (page.visibility != View.VISIBLE) {
                    page.visibility = View.VISIBLE
                    if (previousTab != -1 && previousTab != tab) TouchFx.pageIn(page)
                }
            } else {
                page.visibility = View.GONE
            }
        }
        previousTab = tab
        for ((id, tabId) in tabs) {
            val tv = findViewById<TextView>(tabId)
            tv.setTextColor(getColor(if (id == tab) R.color.brand else R.color.dim))
        }
        currentTab = tab
        if (tab == TAB_API) renderKeys()
        if (tab == TAB_MINE) renderAccounts()
        if (tab == TAB_CONSOLE) renderConsole()
        if (tab == TAB_DATA) renderStats()
    }

    /** 控制台页：状态、接入信息、日志一屏看完。 */
    private fun renderConsole() {
        val running = ProxyService.running
        val port = portNow()
        val lan = Router.lanIp()

        val status = findViewById<TextView>(R.id.conStatusText)
        status.text = if (running) "运行中" else "未启动"
        status.setTextColor(getColor(if (running) R.color.brand else R.color.dim))

        findViewById<TextView>(R.id.conAddrText).text = if (running) {
            if (Prefs.allowLan && lan.isNotBlank()) {
                "本机 127.0.0.1:$port · 局域网 $lan:$port"
            } else {
                "仅本机 127.0.0.1:$port"
            }
        } else {
            "—"
        }

        findViewById<TextView>(R.id.conBaseUrl).text = "http://127.0.0.1:$port/v1"

        val keys = com.workbuddy.proxy.core.KeyStore.all()
        val primary = keys.firstOrNull { it.enabled }
        findViewById<TextView>(R.id.conKey).text = when {
            primary == null -> "（没有启用的 Key）"
            keys.size == 1 -> primary.key
            else -> "${primary.key.take(24)}…  ·  共 ${keys.size} 个"
        }
    }

    private fun portNow(): Int =
        if (ProxyService.activePort > 0) ProxyService.activePort else Prefs.port

    private fun bindLogFilter(id: Int, level: String) {
        findViewById<Button>(id).setOnClickListener {
            logLevel = level
            updateLogFilterButtons()
            renderLogs()
        }
    }

    private fun updateLogFilterButtons() {
        val map = linkedMapOf(
            "all" to R.id.logAll,
            "info" to R.id.logInfo,
            "warn" to R.id.logWarn,
            "error" to R.id.logError
        )
        for ((level, id) in map) {
            val on = level == logLevel
            val btn = findViewById<Button>(id)
            btn.setBackgroundResource(if (on) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost)
            btn.setTextColor(getColor(if (on) R.color.on_brand else R.color.text))
        }
    }

    private fun logLevelColor(level: String): Int = when (level) {
        "error" -> android.graphics.Color.parseColor("#EF5350")
        "warn" -> android.graphics.Color.parseColor("#FFB74D")
        else -> android.graphics.Color.parseColor("#4FC3F7")
    }

    /** 终端样式日志：时间暗、级别染色、正文浅灰，与 cmd 面板同一观感。 */
    private fun renderLogs() {
        val box = findViewById<TextView>(R.id.logBox) ?: return
        var list = com.workbuddy.proxy.core.Logs.recent(400)
        if (logLevel != "all") list = list.filter { it.level == logLevel }
        val query = logQuery.trim()
        if (query.isNotEmpty()) {
            list = list.filter {
                it.message.contains(query, ignoreCase = true) ||
                    it.level.contains(query, ignoreCase = true)
            }
        }
        if (list.size > 200) list = list.subList(list.size - 200, list.size)
        if (list.isEmpty()) {
            box.text = "（无匹配日志）"
            return
        }

        val timeColor = android.graphics.Color.parseColor("#7A8794")
        val msgColor = android.graphics.Color.parseColor("#D5DCE2")
        val sb = android.text.SpannableStringBuilder()
        for (e in list) {
            val t0 = sb.length
            sb.append(e.timeText()).append("  ")
            sb.setSpan(
                android.text.style.ForegroundColorSpan(timeColor),
                t0, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            val tag = e.level.uppercase(java.util.Locale.US).padEnd(5)
            val l0 = sb.length
            sb.append(tag).append(" ")
            sb.setSpan(
                android.text.style.ForegroundColorSpan(logLevelColor(e.level)),
                l0, l0 + tag.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            val m0 = sb.length
            sb.append(e.message).append("\n")
            sb.setSpan(
                android.text.style.ForegroundColorSpan(msgColor),
                m0, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        box.text = sb
    }

    // ------------------------------------------------------------ 数据页

    private val CARD_H = 138
    private var selectedKeyLabel: String? = null
    private var selectedRange = 0
    private var lastFilterSig = ""

    private fun fmtCompact(v: Long): String = when {
        v >= 1_000_000 -> String.format(java.util.Locale.US, "%.2fM", v / 1_000_000.0)
        v >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", v / 1_000.0)
        else -> v.toString()
    }

    private fun deltaText(cur: Double, prev: Double): String {
        if (prev <= 0.0 && cur <= 0.0) return "—"
        if (prev <= 0.0) return "▲ 新增"
        val p = (cur - prev) / prev * 100.0
        val arrow = if (p >= 0) "▲" else "▼"
        return "$arrow ${String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(p))}%"
    }

    private fun renderStats() {
        val grid = findViewById<LinearLayout>(R.id.statsGrid) ?: return
        renderFilters()
        val (since, prevSince) = rangeWindow(selectedRange)
        val summary = com.workbuddy.proxy.core.UsageStore.summary(
            selectedKeyLabel, since, prevSince
        )
        grid.removeAllViews()

        val tokenColor = android.graphics.Color.parseColor("#F5A623")
        val reqColor = android.graphics.Color.parseColor("#7E57C2")
        val speedColor = android.graphics.Color.parseColor("#4FC3F7")
        val cacheColor = android.graphics.Color.parseColor("#9575CD")
        val costColor = android.graphics.Color.parseColor("#EF5350")
        val okColor = android.graphics.Color.parseColor("#2E7D32")

        // 第一行：TOKEN 消耗 / 请求数
        grid.addView(
            statRow(
                buildStatCard(
                    "◆", tokenColor, "TOKEN 消耗",
                    fmtCompact(summary.totalTokens),
                    deltaText(summary.totalTokens.toDouble(), summary.prevTokens.toDouble()),
                    listOf(
                        "输入 ${fmtCompact(summary.promptTokens)} · 输出 ${fmtCompact(summary.completionTokens)}",
                        "思考 ${fmtCompact(summary.reasoningTokens)} · 缓存 ${fmtCompact(summary.cachedTokens)}"
                    ),
                    summary.tokenSeries, tokenColor
                ),
                buildStatCard(
                    "⇅", reqColor, "请求数",
                    "${summary.requests} 次",
                    deltaText(summary.requests.toDouble(), summary.prevRequests.toDouble()),
                    listOf(
                        "失败 ${summary.failed} · 成功率 ${String.format(java.util.Locale.US, "%.1f", summary.successRate)}%",
                        "平均耗时 ${summary.avgLatencyMs / 1000.0}s"
                    ),
                    summary.requestSeries, reqColor
                )
            )
        )

        // 第二行：生成速度 / 缓存命中率
        grid.addView(
            statRow(
                buildStatCard(
                    "⚡", speedColor, "生成速度",
                    "${String.format(java.util.Locale.US, "%.1f", summary.avgTokPerSec)} tok/s",
                    deltaText(summary.avgTokPerSec, summary.prevSpeed),
                    listOf(
                        "平均耗时 ${summary.avgLatencyMs / 1000.0} s · 首字 ${summary.avgTtftMs / 1000.0} s",
                        "峰值 ${String.format(java.util.Locale.US, "%.0f", summary.peakTokPerSec)} tok/s · p90 首字 ${summary.p90TtftMs / 1000.0} s"
                    ),
                    summary.speedSeries, speedColor
                ),
                buildStatCard(
                    "⟳", cacheColor, "缓存命中率",
                    "${String.format(java.util.Locale.US, "%.1f", summary.cacheHitRate)}%",
                    deltaText(summary.cacheHitRate, summary.prevCacheRate),
                    listOf("缓存复用 ${fmtCompact(summary.cachedTokens)} tokens"),
                    summary.cacheSeries, cacheColor
                )
            )
        )

        // 第三行：成功率整行拉长
        val okCard = buildRingCardWide(
            summary.successRate.toFloat(), okColor,
            summary.requests, summary.failed,
            deltaText(summary.successRate, 100.0)
        )
        val okLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_H)
        )
        okLp.bottomMargin = dp(8)
        okCard.layoutParams = okLp
        grid.addView(okCard)

    }

    private val RANGE_NAMES = listOf("今天", "近三天", "本周", "本月", "近30天", "全部")

    private fun renderFilters() {
        val keyDd = findViewById<TextView>(R.id.keyDropdown) ?: return
        val timeDd = findViewById<TextView>(R.id.timeDropdown) ?: return
        val keys = com.workbuddy.proxy.core.UsageStore.knownKeys()
        if (selectedKeyLabel != null && keys.none { it == selectedKeyLabel }) {
            selectedKeyLabel = null
        }
        keyDd.text = selectedKeyLabel ?: "全部 Key"
        timeDd.text = RANGE_NAMES[selectedRange]
    }

    private fun showKeyPicker() {
        val keys = com.workbuddy.proxy.core.UsageStore.knownKeys()
        val items = arrayOf("全部 Key") + keys.toTypedArray()
        val checked = if (selectedKeyLabel == null) 0 else keys.indexOf(selectedKeyLabel) + 1
        AlertDialog.Builder(this)
            .setTitle("按 API Key 筛选")
            .setSingleChoiceItems(items, checked.coerceAtLeast(0)) { dialog, which ->
                selectedKeyLabel = if (which == 0) null else keys[which - 1]
                dialog.dismiss()
                renderStats()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTimePicker() {
        AlertDialog.Builder(this)
            .setTitle("时间范围")
            .setSingleChoiceItems(RANGE_NAMES.toTypedArray(), selectedRange) { dialog, which ->
                selectedRange = which
                dialog.dismiss()
                renderStats()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 返回 (窗口起点, 上一个等长窗口起点)。 */
    private fun rangeWindow(range: Int): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val cal0 = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val todayStart = cal0.timeInMillis

        val since = when (range) {
            0 -> todayStart
            1 -> todayStart - 2 * day
            2 -> {
                val c = java.util.Calendar.getInstance().apply { timeInMillis = todayStart }
                val dow = c.get(java.util.Calendar.DAY_OF_WEEK) // 周日=1 … 周六=7
                val offset = (dow + 5) % 7                       // 周一=0
                todayStart - offset * day
            }
            3 -> {
                val c = java.util.Calendar.getInstance().apply { timeInMillis = todayStart }
                c.set(java.util.Calendar.DAY_OF_MONTH, 1)
                c.timeInMillis
            }
            4 -> todayStart - 29 * day
            else -> 0L
        }
        val span = (now - since).coerceAtLeast(day)
        return since to (since - span).coerceAtLeast(0L)
    }

    private fun statRow(left: View, right: View): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        val l = LinearLayout.LayoutParams(0, dp(CARD_H), 1f)
        val r = LinearLayout.LayoutParams(0, dp(CARD_H), 1f)
        r.marginStart = dp(8)
        row.addView(left, l)
        row.addView(right, r)
        return row
    }

    private fun buildStatCard(
        icon: String,
        iconColor: Int,
        title: String,
        value: String,
        delta: String,
        lines: List<String>,
        series: List<Float>,
        seriesColor: Int
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(11), dp(10), dp(11), dp(8))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = icon
            setTextColor(iconColor)
            textSize = 11f
            setPadding(0, 0, dp(4), 0)
        })
        head.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.dim))
            textSize = 10.5f
        })
        card.addView(head)

        card.addView(TextView(this).apply {
            text = value
            setTextColor(getColor(R.color.text))
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(3), 0, 0)
        })

        card.addView(TextView(this).apply {
            text = delta
            setTextColor(getColor(R.color.dim))
            textSize = 10f
            setPadding(0, dp(1), 0, 0)
        })

        for (line in lines) {
            card.addView(TextView(this).apply {
                text = line
                setTextColor(getColor(R.color.dim))
                textSize = 9.5f
                setPadding(0, dp(1), 0, 0)
            })
        }

        // 占位撑开，让折线始终贴底，五张卡高度一致时底部对齐
        card.addView(View(this), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        card.addView(com.workbuddy.proxy.ui.SparklineView(this).apply {
            setData(series, seriesColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)
            ).apply { topMargin = dp(4) }
        })

        return card
    }

    /** 整行宽的成功率卡：左环形、右文字，横向铺满。 */
    private fun buildRingCardWide(
        percent: Float,
        color: Int,
        requests: Int,
        failed: Int,
        delta: String
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = Gravity.CENTER_VERTICAL
        }

        card.addView(com.workbuddy.proxy.ui.RingProgressView(this).apply {
            setPercent(percent, color)
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
        })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            text = "✓  请求成功率"
            setTextColor(getColor(R.color.dim))
            textSize = 10.5f
        })
        info.addView(TextView(this).apply {
            text = "${String.format(java.util.Locale.US, "%.1f", percent)}%"
            setTextColor(getColor(R.color.text))
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        })
        info.addView(TextView(this).apply {
            text = "$delta  较昨日"
            setTextColor(getColor(R.color.dim))
            textSize = 10f
            setPadding(0, dp(1), 0, 0)
        })
        info.addView(TextView(this).apply {
            text = "共 $requests 次 · 失败 $failed 次"
            setTextColor(getColor(R.color.dim))
            textSize = 10f
            setPadding(0, dp(1), 0, 0)
        })
        card.addView(info)

        return card
    }

    // ------------------------------------------------------------ API Key

    private fun copyText(text: String, label: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("api-key", text))
        toast("$label 已复制")
    }

    /** Key 列表：与网页版一致——每把一张卡，名字 + 状态/区域徽章 + 掩码 + 创建时间 + 四个操作。 */
    private fun renderKeys() {
        val keys = com.workbuddy.proxy.core.KeyStore.all()
        val panel = findViewById<LinearLayout>(R.id.keysPanel)
        val enabled = keys.count { it.enabled }
        findViewById<TextView>(R.id.keysTitle).text = "API KEY（$enabled 个生效）"

        panel.removeAllViews()
        if (keys.isEmpty()) {
            panel.addView(TextView(this).apply {
                text = "还没有 Key，点右上角「＋ 添加」"
                setTextColor(getColor(R.color.dim))
                textSize = 12f
                setPadding(0, dp(6), 0, dp(6))
            })
            return
        }
        for (k in keys) panel.addView(buildKeyCard(k))
    }

    private fun buildKeyCard(k: com.workbuddy.proxy.core.ApiKey): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = k.label
            setTextColor(getColor(R.color.text))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(badge(if (k.enabled) "已启用" else "已停用", k.enabled))
        head.addView(badge(realmBadge(k.realm), null))
        card.addView(head)

        card.addView(TextView(this).apply {
            text = "Key: " + maskKey(k.key)
            setTextColor(getColor(R.color.dim))
            textSize = 11.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(5), 0, 0)
        })

        card.addView(TextView(this).apply {
            text = "创建时间 " + fmtTime(k.createdAt)
            setTextColor(getColor(R.color.dim))
            textSize = 10.5f
            setPadding(0, dp(2), 0, dp(2))
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        actions.addView(keyActionButton("复制") { copyText(k.key, "API Key") })
        actions.addView(keyActionButton("编辑") { showKeyForm(k) })
        actions.addView(
            keyActionButton(if (k.enabled) "禁用" else "启用") { confirmToggle(k) }
        )
        actions.addView(keyActionButton("删除", danger = true) { confirmDelete(k) })
        card.addView(actions)

        TouchFx.attach(card)
        return card
    }

    private fun badge(text: String, on: Boolean?): View {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 10.5f
            setPadding(dp(8), dp(2), dp(8), dp(3))
            setBackgroundResource(
                when (on) {
                    true -> R.drawable.bg_badge_ok
                    false -> R.drawable.bg_badge_off
                    null -> R.drawable.bg_badge_realm
                }
            )
            setTextColor(
                getColor(
                    when (on) {
                        false -> R.color.dim
                        else -> R.color.brand
                    }
                )
            )
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = dp(6)
        tv.layoutParams = lp
        return tv
    }

    private fun realmBadge(realm: String): String =
        when (com.workbuddy.proxy.core.Realms.normalizeKeyRealm(realm)) {
            com.workbuddy.proxy.core.Realms.INTL -> "固定国际版"
            com.workbuddy.proxy.core.Realms.CN -> "固定国内版"
            else -> "不限制"
        }

    private fun maskKey(key: String): String =
        if (key.length <= 14) key else key.take(10) + "******" + key.takeLast(4)

    private fun fmtTime(ts: Long): String =
        java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ts))

    private fun keyActionButton(text: String, danger: Boolean = false, onClick: () -> Unit): View {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginEnd = dp(6)
        return Button(this).apply {
            this.text = text
            textSize = 11.5f
            isAllCaps = false
            setBackgroundResource(R.drawable.bg_btn_ghost)
            setTextColor(getColor(if (danger) R.color.danger else R.color.text))
            setPadding(dp(4), dp(5), dp(4), dp(5))
            minWidth = 0
            minimumWidth = 0
            layoutParams = lp
            setOnClickListener { onClick() }
            TouchFx.attachOne(this)
        }
    }

    /** 停用 / 启用都要过一道确认：客户端会立刻 401，不该误触。 */
    private fun confirmToggle(k: com.workbuddy.proxy.core.ApiKey) {
        val enabledCount = com.workbuddy.proxy.core.KeyStore.all().count { it.enabled }
        if (k.enabled && enabledCount <= 1) {
            toast("至少要保留一把启用的 Key")
            return
        }
        val action = if (k.enabled) "停用" else "启用"
        val detail = if (k.enabled) {
            "${k.label} · ${Realms.keyRealmLabel(k.realm)}\n\n" +
                "停用后使用这把 Key 的客户端会立刻收到 401；Key 本身保留，随时可以再启用。"
        } else {
            "${k.label} · ${Realms.keyRealmLabel(k.realm)}\n\n启用后使用这把 Key 的客户端可以正常调用。"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${action}这把 Key？")
            .setMessage(detail)
            .setNegativeButton("取消", null)
            .setPositiveButton(action) { _, _ ->
                com.workbuddy.proxy.core.KeyStore.setEnabled(k.id, !k.enabled)
                render()
                toast("已$action")
            }
            .create()
        dialog.setOnShowListener {
            if (k.enabled) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.danger))
            }
        }
        dialog.show()
    }

    /** 删除是破坏性的：必须看到名字再点一次。 */
    private fun confirmDelete(k: com.workbuddy.proxy.core.ApiKey) {
        val enabledCount = com.workbuddy.proxy.core.KeyStore.all().count { it.enabled }
        if (k.enabled && enabledCount <= 1) {
            toast("至少要保留一把启用的 Key")
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除这把 Key？")
            .setMessage(
                "${k.label} · ${Realms.keyRealmLabel(k.realm)}\n\n" +
                    "删除后不可恢复，使用它的客户端会立刻收到 401，必须换一把 Key 才能继续用。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                com.workbuddy.proxy.core.KeyStore.remove(k.id)
                render()
                toast("已删除 ${k.label}")
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.danger))
        }
        dialog.show()
    }

    private fun formLabel(text: String, topMargin: Int = 0): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.dim))
            textSize = 11.5f
            setPadding(0, dp(topMargin), 0, dp(4))
        }

    /** 一张可点选的区域卡：标题 + 一句说明。 */
    private fun buildRealmCard(
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(7)
            layoutParams = lp
            setOnClickListener { onClick() }
            TouchFx.attachOne(this)
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text))
            textSize = 13.5f
        })
        card.addView(TextView(this).apply {
            text = subtitle
            setTextColor(getColor(R.color.dim))
            textSize = 11f
            setPadding(0, dp(3), 0, 0)
        })
        return card
    }

    /** 新建与编辑共用一张表单；existing 为 null 就是新建。 */
    private fun showKeyForm(existing: com.workbuddy.proxy.core.ApiKey?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }

        box.addView(formLabel("名称"))
        val input = EditText(this).apply {
            hint = "例如「电脑端」「手机 Claude Code」"
            setSingleLine(true)
            setText(existing?.label ?: "")
        }
        box.addView(input)

        box.addView(formLabel("使用哪个区域的账号", 14))

        var chosen = Realms.normalizeKeyRealm(existing?.realm ?: Realms.INTL)
        val cards = ArrayList<Pair<String, LinearLayout>>()
        val effect = TextView(this).apply {
            setTextColor(getColor(R.color.dim))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        }

        fun refresh() {
            for ((realm, view) in cards) {
                view.setBackgroundResource(
                    if (realm == chosen) R.drawable.bg_option_selected else R.drawable.bg_card
                )
            }
            effect.text = when (chosen) {
                Realms.INTL -> "只派国际版账号；池里没有可用国际号时请求直接失败，不会偷偷换到国内号。"
                Realms.CN -> "只派国内版账号；国内入口不通时（例如海外出口连 copilot）请求会失败。"
                else -> "国际/国内账号都能用，按模型名与池里可用账号自动选。"
            }
        }

        val options = listOf(
            Triple(Realms.INTL, "仅国际版", "workbuddy.ai 的账号"),
            Triple(Realms.CN, "仅国内版", "codebuddy.cn 的账号"),
            Triple(Realms.ANY, "不限制", "自动选择区域")
        )
        for ((realm, title, subtitle) in options) {
            val card = buildRealmCard(title, subtitle) {
                chosen = realm
                refresh()
            }
            cards.add(realm to card)
            box.addView(card)
        }
        box.addView(effect)
        refresh()

        val scroller = android.widget.ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建 API Key" else "编辑 API Key")
            .setView(scroller)
            .setNegativeButton("取消", null)
            .setPositiveButton(if (existing == null) "创建" else "保存") { _, _ ->
                val realm = chosen
                val label = input.text.toString().trim()
                if (existing == null) {
                    val created = com.workbuddy.proxy.core.KeyStore.create(label, realm)
                    render()
                    showKeyCreated(created)
                } else {
                    com.workbuddy.proxy.core.KeyStore.update(existing.id, label, realm)
                    render()
                    toast("已保存")
                }
            }
            .show()
    }

    private fun showKeyCreated(k: com.workbuddy.proxy.core.ApiKey) {
        AlertDialog.Builder(this)
            .setTitle("已创建")
            .setMessage("${k.label} · ${Realms.keyRealmLabel(k.realm)}\n\n${k.key}")
            .setPositiveButton("复制") { _, _ -> copyText(k.key, "API Key") }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ------------------------------------------------------------ 账号

    private fun showRealmPicker() {
        val options = arrayOf("国际版 (www.workbuddy.ai)", "国内版 (copilot.tencent.com)")
        AlertDialog.Builder(this)
            .setTitle("选择账号区域")
            .setItems(options) { _, which ->
                val realm = if (which == 1) Realms.CN else Realms.INTL
                startActivityForResult(
                    Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_REALM, realm),
                    REQ_LOGIN
                )
            }
            .setNeutralButton("手动粘贴 Token") { _, _ -> showManualToken() }
            .show()
    }

    private fun showManualToken() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
        }
        val input = EditText(this).apply {
            hint = "粘贴 accessToken（JWT 形态）"
            minLines = 3
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("手动添加账号")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val token = input.text.toString().trim()
                if (token.length < 20) {
                    toast("Token 看起来不完整")
                    return@setPositiveButton
                }
                addTokenDirect(token)
            }
            .show()
    }

    private fun addTokenDirect(token: String) {
        val uid = com.workbuddy.proxy.core.Identity.uidFromJwt(token)
            .ifBlank { "u_" + com.workbuddy.proxy.core.Identity.hex32().take(10) }
        val acc = com.workbuddy.proxy.core.Account(
            uid = uid,
            nickname = uid.take(8),
            accessToken = token,
            refreshToken = "",
            expiresAt = com.workbuddy.proxy.core.Identity.expFromJwt(token),
            realm = Realms.INTL,
            domain = Realms.domain(Realms.INTL),
            enabled = true,
            addedAt = System.currentTimeMillis()
        )
        AccountStore.add(acc)
        toast("已加入账号池")
        render()
    }

    private fun renderAccounts() {
        val accounts = AccountStore.all()
        accountsTitle.text = "账号池 · ${accounts.size}"
        val signature = accounts.joinToString("|") {
            "${it.uid}:${it.enabled}:${it.cooldownUntil > System.currentTimeMillis()}:${it.nickname}"
        }
        if (signature == lastAccountsSignature) return
        lastAccountsSignature = signature

        accountsBox.removeAllViews()
        if (accounts.isEmpty()) {
            val tip = TextView(this).apply {
                text = "还没有账号。点下方按钮添加。"
                setTextColor(getColor(R.color.dim))
                textSize = 13f
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            accountsBox.addView(tip)
            return
        }
        for (a in accounts) {
            accountsBox.addView(buildAccountRow(a))
        }
    }

    private fun buildAccountRow(a: com.workbuddy.proxy.core.Account): View {
        // 外层垂直卡片：头部一行 + 点击后展开的详情区
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            isClickable = true
            isFocusable = true
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cooling = a.cooldownUntil > System.currentTimeMillis()
        val state = when {
            !a.enabled -> "已禁用"
            a.tokenExpired -> "登录失效，需重登"
            cooling -> "冷却中 ${(a.cooldownUntil - System.currentTimeMillis()) / 1000}s"
            a.lastError.isNotBlank() -> a.lastError
            else -> "可用"
        }
        val color = when {
            !a.enabled || a.tokenExpired -> R.color.danger
            cooling -> R.color.warn
            else -> R.color.brand
        }
        info.addView(TextView(this).apply {
            text = "${a.label()}  ·  ${Realms.label(a.realm)}"
            setTextColor(getColor(R.color.text))
            textSize = 15f
        })
        info.addView(TextView(this).apply {
            text = "$state  ·  点开看积分"
            setTextColor(getColor(color))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(info)

        val toggle = Button(this).apply {
            text = if (a.enabled) "禁用" else "启用"
            textSize = 12f
            isAllCaps = false
            setBackgroundResource(R.drawable.bg_btn_ghost)
            setTextColor(getColor(R.color.text))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            minWidth = 0
            minimumWidth = 0
            setOnClickListener {
                AccountStore.setEnabled(a.uid, !a.enabled)
                lastAccountsSignature = ""
                render()
            }
        }
        val del = Button(this).apply {
            text = "删除"
            textSize = 12f
            isAllCaps = false
            setBackgroundResource(R.drawable.bg_btn_ghost)
            setTextColor(getColor(R.color.danger))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            minWidth = 0
            minimumWidth = 0
            setOnClickListener {
                AccountStore.remove(a.uid)
                lastAccountsSignature = ""
                render()
                toast("已删除 ${a.label()}")
            }
        }
        val lpBtn = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) }
        header.addView(toggle, lpBtn)
        header.addView(del, LinearLayout.LayoutParams(lpBtn).apply { marginStart = dp(6) })

        val detailBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(12))
            visibility = View.GONE
        }
        card.addView(header)
        card.addView(detailBox)
        TouchFx.enter(card, (accountsBox.childCount * 30).toLong())
        header.setOnClickListener {
            if (detailBox.visibility == View.VISIBLE) {
                collapseBox(detailBox)
            } else {
                openDetail(detailBox, a)
            }
        }
        TouchFx.attach(header, toggle, del)
        return card
    }

    // ------------------------------------------------------------ 账号详情

    private fun openDetail(box: LinearLayout, account: com.workbuddy.proxy.core.Account) {
        box.removeAllViews()
        box.addView(detailRow("积分", "正在读取…", R.color.dim))
        box.visibility = View.VISIBLE
        expandBox(box)

        Thread {
            val detail = com.workbuddy.proxy.core.Upstream.fetchAccountDetail(account)
            runOnUiThread {
                if (box.visibility != View.VISIBLE) return@runOnUiThread
                renderDetail(box, detail, account)
                box.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                box.requestLayout()
            }
        }.start()
    }

    private fun renderDetail(
        box: LinearLayout,
        d: com.workbuddy.proxy.core.AccountDetail,
        account: com.workbuddy.proxy.core.Account
    ) {
        box.removeAllViews()
        if (d.error.isNotBlank()) {
            box.addView(detailRow("获取失败", d.error, R.color.danger))
            return
        }
        val tier = when (d.paid) {
            true -> "付费用户"
            false -> "免费用户"
            null -> "未知"
        }
        box.addView(detailRow("档位", tier, R.color.text))
        box.addView(detailRow("剩余积分", "${fmtNum(d.remain)} / ${fmtNum(d.size)}", R.color.brand))
        box.addView(buildProgress(d.remainPercent))

        if (d.checkin.supported) {
            val extra = StringBuilder()
            if (d.checkin.streakDays > 0) extra.append(" · 连续 ").append(d.checkin.streakDays).append(" 天")
            if (d.checkin.todayCredit > 0) {
                extra.append(" · 今日 +").append(fmtNum(d.checkin.todayCredit))
            }
            val label = if (d.checkin.todayCheckedIn) "今日已领" else "今日未领"
            box.addView(
                detailRow(
                    "Buddy 加油站", label + extra.toString(),
                    if (d.checkin.todayCheckedIn) R.color.brand else R.color.warn
                )
            )
            if (!d.checkin.todayCheckedIn && d.checkin.active) {
                box.addView(buildCheckinButton(account, box))
            }
        } else {
            box.addView(detailRow("Buddy 加油站", "国际版无此活动", R.color.dim))
        }

        val soon = d.packages.firstOrNull { (it.daysLeft ?: -1.0) >= 0 && it.remain > 0 }
        if (soon != null) {
            val days = (soon.daysLeft ?: 0.0).toInt()
            box.addView(
                detailRow("最近到期", "${soon.name} 剩 ${fmtNum(soon.remain)}（${days} 天）", R.color.text)
            )
        }
        if (d.packages.isNotEmpty()) {
            box.addView(detailRow("积分包", "${d.packages.size} 个", R.color.text))
        }
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(d.updatedAt))
        box.addView(detailRow("更新于", time, R.color.dim))
    }

    private fun buildCheckinButton(
        account: com.workbuddy.proxy.core.Account,
        box: LinearLayout
    ): View {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(4)
        lp.bottomMargin = dp(4)
        return Button(this).apply {
            text = "签到领积分"
            textSize = 13f
            isAllCaps = false
            setBackgroundResource(R.drawable.bg_btn_primary)
            setTextColor(getColor(R.color.on_brand))
            setPadding(dp(14), dp(6), dp(14), dp(6))
            minWidth = 0
            minimumWidth = 0
            layoutParams = lp
            TouchFx.attachOne(this)
            setOnClickListener {
                isEnabled = false
                text = "签到中…"
                Thread {
                    val r = com.workbuddy.proxy.core.Upstream.checkin(account)
                    runOnUiThread {
                        toast(r.message.ifBlank { if (r.ok) "签到成功" else "签到失败" })
                        refreshDetail(box, account)
                    }
                }.start()
            }
        }
    }

    private fun refreshDetail(box: LinearLayout, account: com.workbuddy.proxy.core.Account) {
        Thread {
            val d = com.workbuddy.proxy.core.Upstream.fetchAccountDetail(account)
            runOnUiThread {
                if (box.visibility != View.VISIBLE) return@runOnUiThread
                renderDetail(box, d, account)
                box.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                box.requestLayout()
            }
        }.start()
    }

    private fun detailRow(label: String, value: String, colorRes: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.dim))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        row.addView(TextView(this).apply {
            text = value
            setTextColor(getColor(colorRes))
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun buildProgress(percent: Double): View {
        val safe = percent.coerceIn(0.0, 100.0).toFloat()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(8))
        }
        bar.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.brand))
            layoutParams = LinearLayout.LayoutParams(0, dp(5), maxOf(safe, 0.4f))
        })
        bar.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.card2))
            layoutParams = LinearLayout.LayoutParams(0, dp(5), maxOf(100f - safe, 0.4f))
        })
        return bar
    }

    private fun expandBox(box: View) {
        val width = (box.parent as? View)?.width ?: resources.displayMetrics.widthPixels
        box.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val target = box.measuredHeight
        if (target <= 0) return
        box.layoutParams.height = 1
        box.requestLayout()
        val anim = android.animation.ValueAnimator.ofInt(1, target)
        anim.duration = 200
        anim.addUpdateListener {
            box.layoutParams.height = it.animatedValue as Int
            box.requestLayout()
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                box.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                box.requestLayout()
            }
        })
        anim.start()
    }

    private fun collapseBox(box: View) {
        val anim = android.animation.ValueAnimator.ofInt(box.height, 1)
        anim.duration = 180
        anim.addUpdateListener {
            box.layoutParams.height = it.animatedValue as Int
            box.requestLayout()
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                box.visibility = View.GONE
                box.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                box.requestLayout()
            }
        })
        anim.start()
    }

    private fun fmtNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", v)

    // ------------------------------------------------------------ 渲染

    private fun render() {
        val running = ProxyService.running
        statusText.text = if (running) "服务运行中" else "服务已停止"
        statusDot.setBackgroundResource(if (running) R.drawable.dot_on else R.drawable.dot_idle)
        if (btnToggle.isEnabled) {
            btnToggle.text = if (running) "停止服务" else "启动服务"
        }

        val port = if (ProxyService.activePort > 0) ProxyService.activePort else Prefs.port
        val lan = Router.lanIp()
        addrText.visibility = if (running) View.VISIBLE else View.GONE
        addrText.text = if (running) {
            if (Prefs.allowLan && lan.isNotBlank()) "本机 127.0.0.1:$port · 局域网 $lan:$port"
            else "仅本机 127.0.0.1:$port"
        } else ""

        baseUrlText.text = "http://127.0.0.1:$port/v1"

        renderKeys()

        val vn = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        findViewById<TextView>(R.id.versionText).text = "v$vn"

        renderAccounts()
        renderLogs()
        renderConsole()
        if (currentTab == TAB_DATA) renderStats()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) {
            lastAccountsSignature = ""
            render()
            toast("账号已加入")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11) }
            }
        }
    }

    /** 紧凑模式下整体收 18%，代码里创建的卡片与间距都会跟着变。 */
    private fun dp(v: Int): Int {
        val scale = if (Prefs.compact) 0.82f else 1.0f
        return (v * scale * resources.displayMetrics.density).toInt()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_LOGIN = 1001
        private const val TAB_PROXY = 0
        private const val TAB_API = 1
        private const val TAB_CONSOLE = 2
        private const val TAB_DATA = 3
        private const val TAB_MINE = 4
    }
}
