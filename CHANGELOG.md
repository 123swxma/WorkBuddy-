# 更新日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [1.9.1] - 2026-10-23

### Changed

- Key 卡片的「＋ 添加」按钮去掉加号，文字居中，左右留 18dp 内边距。

## [1.9.0] - 2026-10-23

### Changed

- Key 列表改成卡片式，对齐网页版：
  - 标题栏显示「API KEY（N 个生效）」+ 右上「＋ 添加」。
  - 每把 Key 一张卡：名称、【已启用】与【固定国际版/国内版】徽章、掩码 `Key: sk-wb-1fa5******b94e`、创建时间、复制/编辑/禁用/删除。
  - 取消下拉展开交互，进页即全量显示。

## [1.8.0] - 2026-10-22

### Changed

- 设置入口下移：「我的」页的设置卡挪到最底部，去掉「打开」按钮，整张卡可点（右侧 `›` 提示）。
- tab 图标 `☺` 换成 `◍`。
- 全局交互动画：所有按钮与卡片加按下缩放（96% / 90ms），列表项淡入上移，页面切换淡入滑动。

## [1.7.0] - 2026-10-22

### Changed

- 控制台改为总览页：控制台 tab 现在是「服务状态 + 接入信息 + 终端样式运行日志」。
- 日志带 INFO/WARN/ERROR 级别筛选与关键词过滤，黑底等宽、级别染色，每 2 秒刷新。
- 网页版完整面板仍留在页底入口。运行日志已从代理页移走。

## [1.6.0] - 2026-10-22

### Added

- 内嵌控制台：控制台 tab 直接内嵌完整 Web 面板（不再跳新页面），服务没启动时显示提示。
- 实时日志：代理页新增「运行日志」卡，每 2 秒刷新最近 12 条。
- 浅色外观：设置里新增**浅色 / 深色**，App 界面与 Web 面板一起切换。

## [1.5.0] - 2026-10-22

### Added

- 底部导航：界面拆成四个 tab——**代理**（启停与状态）、**API**（Base URL、Key 管理、监听端口）、**控制台**（打开 Web 面板）、**我的**（账号池、设置入口）。
- 设置页可调字号（小/标准/大/特大）与卡片紧凑度，改完立即生效。

## [1.4.1] - 2026-10-22

### Changed

- 新建 Key 界面重做：区域选择从三条长单选换成三张可点选的卡片（选中亮青色描边），下方实时显示「选了它会发生什么」。
- 危险操作二次确认：删除与停用都要在弹窗里再确认一次，确认键标红；只剩一把启用 Key 时直接拦下。

## [1.4.0] - 2026-10-22

### Changed

- Key 改成下拉列表：首页接入信息里的 Key 变成可展开的下拉菜单，每把 Key 一行，行内直接有 复制 / 编辑 / 停用 / 删除。
- 主按钮换成「＋ 新建 API Key」。
- 编辑可以随时改名称与区域，改完立刻影响派号。控制台同步加了「编辑」。

## [1.3.0] - 2026-10-22

### Added

- 多 Key + 区域绑定：Key 不再自动生成一把，改为用户自己创建，创建时可选「仅国际版 / 仅国内版 / 不限制」。
- 绑了区域的 Key 只会派该区域的账号——同一个模型名两边都有时，靠 Key 决定走哪边。
- App 首页「管理 Key」和控制台「设置 → API Key」都能建。

### Changed

- 旧版本的那把 Key 会自动迁移成「默认 Key（不限制）」，老客户端不用改。

## [1.2.1] - 2026-10-21

### Fixed

- 修国际版 Google 登录：Google 从 2021 起拒绝在嵌入式 WebView 里完成 OAuth（提示「此浏览器或应用可能不安全」）。国际版登录页现在默认用**系统浏览器**打开授权页，完成后再切回 App 自动入池；国内版仍可在应用内登录。
- 去掉把 UA 伪装成桌面 Chrome 的做法——那反而更像伪造。

## [1.2.0] - 2026-10-21

### Added

- Buddy 加油站签到：国内版账号展开后，若今日未领会多一个「签到领积分」按钮，点一下直接打上游 `daily-checkin`，成功后详情与积分自动刷新。App 首页与控制台都有。

## [1.1.0] - 2026-10-21

### Added

- 账号详情：点账号卡片（App 首页或控制台）向下展开，显示档位、积分余量（带占比条）、Buddy 加油站今日领取状态与连续天数、最近到期的一包、积分包数量。
- 数据实时取上游计费接口，结果缓存 60 秒，收起再点开会刷新。

## [1.0.2] - 2026-10-21

### Changed

- 上游只接受流式请求（非流式会回 `400 Non-stream chat request is currently not supported`），现在网关内部一律开流、非流式客户端由网关聚合 SSE 后返回 JSON。
- **改为手动启动**：App 打开不再自动占用端口，也没有开机自启，只有点「启动服务」才监听。可直接覆盖安装（同签名），无需卸载。

### Fixed

- 上游强制流式：参考实现的 `build_upstream_body` 里 `body["stream"] = True` 是写死的，此前把客户端的 `stream` 原样传了上去。现在 `buildUpstreamBody` 统一覆写为 `stream: true` 并补 `stream_options.include_usage`，非流式请求由 `StreamAggregator` 读完整条 SSE 再拼成 `chat.completion`（含 tool_calls 按 index 合并、丢弃空函数名占位）。`/api/test` 与 Anthropic 非流式都改走这条路。
- 手动启动：删掉 `Prefs.autoStart` / `bootStart`、`BootReceiver` 与 `RECEIVE_BOOT_COMPLETED` 权限，`MainActivity` 不再在 `onCreate`/`onResume` 拉起服务。

## [1.0.1] - 2026-10-20

### Fixed

- 修「点开就闪退」。根因是 `AndroidManifest.xml` 的 `<application>` 漏了 `android:name=".App"`：
  1. Application 不实例化 → `App.onCreate` 里的通知渠道从未创建；
  2. `ProxyService.onCreate` 用该渠道构造通知，`startForeground` 抛 `invalid channel`；
  3. 五秒内没进入前台，系统按「前台服务未就绪」直接杀掉进程——表现就是闪退。
- 修复三处：补上 Application 注册；服务自己在 `onCreate` 建渠道，不再依赖 Application；`startForeground` 失败时主动 `stopSelf`，不把进程交给系统杀。
- 增加崩溃堆栈落盘（`last_crash.txt`），下次再出问题能直接看到栈。

## [1.0.0] - 2026-10-20

### Added

- 首个版本：原生 Kotlin 实现的 WorkBuddy 反代，单 APK 跑在手机上，对外提供 OpenAI Chat Completions 与 Anthropic Messages 两种协议。

[Unreleased]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.9.1...HEAD
[1.9.1]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.9.0...v1.9.1
[1.9.0]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.8.0...v1.9.0
[1.8.0]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.7.0...v1.8.0
[1.7.0]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.5.0...v1.6.0
[1.5.0]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.4.1...v1.5.0
[1.4.1]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.4.0...v1.4.1
[1.4.0]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.2.1...v1.3.0
[1.2.1]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/USERNAME/WorkBuddyProxy/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/USERNAME/WorkBuddyProxy/releases/tag/v1.0.0
