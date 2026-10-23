# WorkBuddy 反代（Android 版）

把 WorkBuddy 账号池封装成本机 HTTP 接口，跑在手机上，对外提供 **OpenAI Chat Completions** 与 **Anthropic Messages** 两种协议。原生 Kotlin 实现，零第三方依赖，APK 约 830 KB。手机自己就能当网关：同一 Wi-Fi 下的电脑、平板、其他设备直接连手机，不用再开一台常驻电脑。

参考实现：[ardeyouxipianyi/workbuddy2api-hub](https://github.com/ardeyouxipianyi/workbuddy2api-hub)（Python 桌面网关）。本项目按它的抓包结论重写了移动端内核，协议参数与身份头逐项对齐，但**没有沿用 Python 运行时**——原生 Kotlin，零第三方依赖，APK 约 780 KB。

---

## 一、安装

安装包：[WorkBuddyProxy-1.9.1.apk](dist/WorkBuddyProxy-1.9.1.apk)

> **1.9.1**：Key 卡片的「＋ 添加」按钮去掉加号，文字居中，左右留 18dp 内边距。
>
> **1.9.0 Key 列表改成卡片式**：对齐网页版——标题栏显示「API KEY（N 个生效）」+ 右上「＋ 添加」，每把 Key 一张卡（名称、【已启用】与【固定国际版/国内版】徽章、掩码 `Key: sk-wb-1fa5******b94e`、创建时间、复制/编辑/禁用/删除）。下拉展开的交互取消，进页即全量显示。
>
> **1.8.0 设置入口下移 + 全局交互动画**：「我的」页的设置卡挪到最底部，去掉「打开」按钮，整张卡可点（右侧 `›` 提示）；tab 图标 `☺` 换成 `◍`。所有按钮与卡片加了按下缩放（96% / 90ms）、列表项淡入上移、页面切换淡入滑动。
>
> **1.7.0 控制台改为总览页**：控制台 tab 现在是「服务状态 + 接入信息 + 终端样式运行日志」，日志带 INFO/WARN/ERROR 级别筛选与关键词过滤，黑底等宽、级别染色，每 2 秒刷新；网页版完整面板仍留在页底入口。运行日志已从代理页移走。
>
> **1.6.0 内嵌控制台 + 实时日志 + 浅色外观**：控制台 tab 直接内嵌完整 Web 面板（不再跳新页面），服务没启动时显示提示；代理页新增「运行日志」卡，每 2 秒刷新最近 12 条。设置里新增外观选项：**浅色 / 深色**，App 界面与 Web 面板一起切换。
>
> **1.5.0 底部导航 + 设置页**：界面拆成四个 tab——**代理**（启停与状态）、**API**（Base URL、Key 管理、监听端口）、**控制台**（打开 Web 面板）、**我的**（账号池、设置入口）。新增设置页可调字号（小/标准/大/特大）与卡片紧凑度，改完立即生效。
>
> **1.4.1 新建 Key 界面重做 + 危险操作二次确认**：区域选择从三条长单选换成三张可点选的卡片（选中亮青色描边），下方实时显示「选了它会发生什么」。删除与停用都要在弹窗里再确认一次，确认键标红；只剩一把启用 Key 时直接拦下。
>
> **1.4.0 Key 改成下拉列表**：首页接入信息里的 Key 变成可展开的下拉菜单，每把 Key 一行，行内直接有 复制 / 编辑 / 停用 / 删除；主按钮换成「＋ 新建 API Key」。编辑可以随时改名称与区域，改完立刻影响派号。控制台同步加了「编辑」。
>
> **1.3.0 多 Key + 区域绑定**：Key 不再自动生成一把，改为用户自己创建，创建时可选「仅国际版 / 仅国内版 / 不限制」。绑了区域的 Key 只会派该区域的账号——同一个模型名两边都有时，靠 Key 决定走哪边。App 首页「管理 Key」和控制台「设置 → API Key」都能建。旧版本的那把 Key 会自动迁移成「默认 Key（不限制）」，老客户端不用改。
>
> **1.2.1 修国际版 Google 登录**：Google 从 2021 起拒绝在嵌入式 WebView 里完成 OAuth（提示「此浏览器或应用可能不安全」）。国际版登录页现在默认用**系统浏览器**打开授权页，完成后再切回 App 自动入池；国内版仍可在应用内登录。之前把 UA 伪装成桌面 Chrome 的做法也一并去掉了——那反而更像伪造。
>
> **1.2.0 新增 Buddy 加油站签到**：国内版账号展开后，若今日未领会多一个「签到领积分」按钮，点一下直接打上游 `daily-checkin`，成功后详情与积分自动刷新。App 首页与控制台都有。
>
> **1.1.0 新增账号详情**：点账号卡片（App 首页或控制台）向下展开，显示档位、积分余量（带占比条）、Buddy 加油站今日领取状态与连续天数、最近到期的一包、积分包数量。数据实时取上游计费接口，结果缓存 60 秒，收起再点开会刷新。
>
> **1.0.2 两处改动**：① 上游只接受流式请求（非流式会回 `400 Non-stream chat request is currently not supported`），现在网关内部一律开流、非流式客户端由网关聚合 SSE 后返回 JSON；② **改为手动启动**——App 打开不再自动占用端口，也没有开机自启，只有点「启动服务」才监听。可直接覆盖安装（同签名），无需卸载。

- 最低 Android 7.0（API 24），目标 API 34
- 使用 debug 密钥签名，安装时需允许「未知来源」
- 不需要 root，不需要装 Termux / Python

## 二、首次使用

1. 打开 App，在 **代理** 页点 **启动服务**（默认不自动启动，也不会开机自启），服务监听 `127.0.0.1:8788`。局域网开关在控制台的设置里。
2. 切到 **我的** 页，点 **＋ 添加 WorkBuddy 账号** → 选区域：
   - **国际版**：默认用系统浏览器打开授权页（Google 账号必须走这条，WebView 会被 Google 判定为不安全浏览器）。登录完切回 App，会自动检测并入库。
   - **国内版**：可在应用内 WebView 完成，也可点「用浏览器打开」。
   两种方式都不需要复制粘贴任何东西；轮询在服务端跑，跳走也不会丢结果。
3. 切到 **API** 页，复制 **API Base URL** 与 **API Key**（Key 可以自建多把、各绑区域）。
4. 填进客户端即可。

> 登录也可以手动粘贴 `accessToken`（从桌面客户端抓或从浏览器开发者工具取），作为 WebView 登录失败时的后备路径。

## 三、客户端配置

### OpenAI 兼容客户端
（Cherry Studio / LobeChat / OpenWebUI / NextChat / ChatBox …）

| 项 | 值 |
| --- | --- |
| Base URL | `http://127.0.0.1:8788/v1`（手机本机）<br>`http://<手机局域网IP>:8788/v1`（同网设备） |
| API Key | 自己建的 `sk-wb-…`；建时选区域 |
| 模型 | 用 `/v1/models` 拉取，或手填 `gpt-5.5`、`glm-5.3` 等 |

### Claude Code

```bash
export ANTHROPIC_BASE_URL=http://<手机IP>:8788     # 注意：末尾不要带 /v1
export ANTHROPIC_AUTH_TOKEN=sk-wb-xxxxxxxx
claude
```

Claude Code 默认发的 `claude-sonnet-4-5` 这类模型名上游并不存在，网关会自动折到上游目录里的 GPT 系模型；你显式填上游模型名时则原样透传。

### curl 自测

```bash
curl http://<手机IP>:8788/v1/chat/completions \
  -H "Authorization: Bearer sk-wb-xxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-5.5","stream":true,"messages":[{"role":"user","content":"你好"}]}'
```

## 四、接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/chat/completions` | OpenAI 协议，支持 SSE 流式与 tool_calls 透传 |
| GET | `/v1/models` | 上游模型目录（无账号时返回内置清单） |
| POST | `/v1/messages` | Anthropic 协议，含事件流与 `tool_use` |
| POST | `/v1/messages/count_tokens` | 按字符粗估，供 Claude Code 裁剪上下文 |
| GET | `/` | 内置 Web 控制台 |
| GET | `/health` | 存活探测 |
| GET | `/api/accounts/{uid}/detail` | 账号积分与签到状态（面板用，`?refresh=1` 强制刷新） |
| POST | `/api/accounts/{uid}/checkin` | 触发 Buddy 加油站签到（仅国内版） |
| GET | `/api/keys` | 列出所有 Key（含区域与启停） |
| POST | `/api/keys` | 新建 Key：`{"label":"电脑端","realm":"intl\|cn\|any"}` |
| POST | `/api/keys/{id}/update` | 改名称与区域：`{"label":"…","realm":"intl\|cn\|any"}` |
| POST | `/api/keys/{id}/toggle` | 启停某把 Key |
| DELETE | `/api/keys/{id}` | 删除某把 Key（至少保留一把启用的） |

管理接口 `/api/*` 默认只允许 `127.0.0.1`；从局域网访问面板需带 `?key=<API Key>`。

## 五、和参考实现的对齐点

| 环节 | 取值 |
| --- | --- |
| 国际版对话 | `https://www.workbuddy.ai/v2/chat/completions` |
| 国内版对话 | `https://copilot.tencent.com/v2/chat/completions`（可切 `www.workbuddy.cn`） |
| 模型目录 | `/v2/enterprises/personal/models` → `data.agents[].models[]` |
| 登录 | `POST /v2/plugin/auth/state?platform=CLI` → 浏览器授权 → `GET /v2/plugin/auth/token?state=` |
| 续期 | `POST /v2/plugin/auth/token/refresh`（带 `X-Refresh-Token`） |
| 桌面端 UA | `WorkBuddy/5.8.4 workbuddy-ai/5.8.4 CLI/2.161.0`（国内版 5.8.3） |
| 身份头 | `X-Agent-Intent: craft`、`X-IDE-*`、`X-Product: SaaS`、`X-Domain`、`X-User-Id`、`X-Private-Data` |
| 会话头 | `X-Conversation-ID`、`X-Conversation-Request-ID`、`X-Root-Request-ID`、`X-Conversation-Message-ID`、`X-Request-ID`（32 位 hex） |
| 链路头 | `X-Trace-ID`、`traceparent`、`b3`、`X-B3-*` |

上游 `/v2/chat/completions` 本身就是 OpenAI Chat Completions 帧（`choices[].delta`、`reasoning_content`、`tool_calls`），所以转发层不做结构翻译，只做三件事：补齐 system 首条、按账号轮询、流式逐行透传并清掉空噪声字段。协议翻译只发生在 Anthropic 入口。

## 五之二、账号详情怎么取

两个区域的取数路径不同，网关里归一成同一份结构（`AccountDetail`）：

| | 国际版 | 国内版 |
| --- | --- | --- |
| 积分包 | `POST /v2/billing/meter/get-user-resource`<br>读 `data.Response.Data.Accounts[]` | `POST /billing/meter/get-user-resource-summary` → 免费包 / 付费包两个接口 |
| 加油站签到 | 无此活动 | 读状态 `POST /v2/billing/meter/checkin-activity-status`（copilot.tencent.com）<br>领积分 `POST /v2/billing/meter/daily-checkin`（codebuddy.cn） |
| 档位 | 取计费摘要的 `IsPaidUser` | 同左 |

积分字段按 `CycleCapacityRemainPrecise` → `CycleCapacityRemain` → `CapacityRemainPrecise` → `CapacityRemain` 依次回落；发放原因读 `AccountAttributes[].Key == "grantReason"`（如「Buddy 加油站签到」）。计费请求带的是轻量标头（`X-CodeBuddy-Request`、`X-Machine-ID`、`X-Session-ID` 按账号派生），不吃对话那套 `X-Agent-*` 身分。

## 六、设置项

内置控制台（App 首页 →「控制台」）可改：

- 监听端口在 App 的 API 页改；局域网开关、上游入口等在控制台改
- 外观（浅色/深色）、字号、卡片紧凑度在 App 的「我的 → 设置」里改
- System 注入策略：`auto`（缺 system 时补一句）/ `keep`（原样透传）/ `replace`（统一覆盖）
- 国内版入口切换、国际版上游基址覆盖（可指向自建中转）
- 读超时、失败换号次数、空 delta 清洗
- 重置 API Key

## 七、已知限制

- **后台保活**：国产 ROM 的深度省电会杀进程，建议在系统设置里给本 App 开「允许后台运行 / 忽略电池优化」，并保留常驻通知。
- **签名**：当前为 debug 签名，方便直接安装；正式分发请用自建 keystore 重新签名（见第八节）。
- **未实现** `/v1/responses`（Codex 走的协议）。OpenAI 兼容客户端用 `chat/completions` 即可；需要 Codex 时请用参考项目的桌面网关。
- **图片输入**未透传：Anthropic `image` 块与 OpenAI `image_url` 都会被丢弃，上游 vision 参数需要另一套映射。
- **静态模型清单**是兜底用的：只有账号池为空或上游目录取不到时才会返回。

## 八、源码与构建

```
wb2api-android/
├── app/src/main/java/com/workbuddy/proxy/
│   ├── App.kt                  Application / 通知渠道
│   ├── MainActivity.kt         首页：状态、账号池、接入信息
│   ├── LoginActivity.kt        内置 WebView OAuth
│   ├── ConsoleActivity.kt      控制台容器
│   ├── ProxyService.kt         前台服务，持有监听
│   ├── core/                   协议与账号内核
│   │   ├── Realms.kt           两区端点 / UA / 版本
│   │   ├── Identity.kt         设备与会话标识派生
│   │   ├── Upstream.kt         登录、续期、模型、chat 连接
│   │   ├── ProxyEngine.kt      转发、换号、SSE
│   │   ├── AnthropicRpc.kt     Messages ⇄ Chat 双向翻译
│   │   ├── Account*.kt         账号模型与轮询池
│   │   └── LoginWatcher.kt     服务端登录轮询
│   └── server/                 手写 HTTP/1.1 与路由
└── app/src/main/assets/console.html   移动端控制台
```

构建：

```bash
git clone https://github.com/<你的用户名>/WorkBuddyProxy.git
cd WorkBuddyProxy

# 指向本机 Android SDK（放在 local.properties，已被 .gitignore 排除）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

工具链：JDK 17+、Android SDK 34（compileSdk 34 / minSdk 24）、Gradle 8.9（wrapper 已带）、AGP 8.5.2、Kotlin 2.0.21。

工程刻意不使用任何第三方运行时依赖，`dependencies` 块是空的——构建只需要 Android SDK 和 Kotlin 标准库，不依赖外网仓库之外的包。

## 九、验证状态

**已通过**

| 项 | 结果 |
| --- | --- |
| 干净构建 | `clean assembleRelease` → BUILD SUCCESSFUL，产物 779 KB |
| 包结构 | `com.workbuddy.proxy` / minSdk 24 / targetSdk 34 / 启动 Activity 正确 |
| 签名 | `apksigner verify` 通过（debug 证书，可直接侧载） |
| dex 构成 | 12886 个方法引用，单 dex，**零 androidx、零第三方库** |
| 控制台 | 抽出内联 JS 做 `node --check`，语法通过 |
| 协议对拍 | 本机 mock 上游复刻了 `auth/state`、`auth/token`（含 11217 等待码）、`login/account`、`models`、`chat`（SSE 帧 + 「缺 system 就 400」的强校验），逐端点响应符合预期 |

**未做**

- 真机 / 模拟器运行验证：本机缺 Android 模拟器硬件加速驱动，纯软件模拟启动 20 分钟仍未完成；也没有连接真机可用。
- Robolectric JVM 测试栈在 AGP 8.5 + Kotlin 2.0 组合下存在测试 classpath 缺陷（`ClassNotFoundException`），绕开后又在 JDK 22 下卡死，最终未能跑通，已从工程移除以免污染发布构建。

**1.0.2 修复记录**

1.0.1 之后有两条反馈：连通性测试返回 `400 Non-stream chat request is currently not supported`；以及希望不要自动开服。

- 上游强制流式：参考实现的 `build_upstream_body` 里 `body["stream"] = True` 是写死的，我此前把客户端的 `stream` 原样传了上去。现在 `buildUpstreamBody` 统一覆写为 `stream: true` 并补 `stream_options.include_usage`，非流式请求由 `StreamAggregator` 读完整条 SSE 再拼成 `chat.completion`（含 tool_calls 按 index 合并、丢弃空函数名占位）。`/api/test` 与 Anthropic 非流式都改走这条路。
- 手动启动：删掉 `Prefs.autoStart` / `bootStart`、`BootReceiver` 与 `RECEIVE_BOOT_COMPLETED` 权限，`MainActivity` 不再在 `onCreate`/`onResume` 拉起服务。

**1.0.1 修复记录**

1.0.0 发布后用户反馈「点开就闪退」。根因是 `AndroidManifest.xml` 的 `<application>` 漏了 `android:name=".App"`：

1. Application 不实例化 → `App.onCreate` 里的通知渠道从未创建；
2. `ProxyService.onCreate` 用该渠道构造通知，`startForeground` 抛 `invalid channel`；
3. 五秒内没进入前台，系统按「前台服务未就绪」直接杀掉进程——表现就是闪退。

修复三处：补上 Application 注册；服务自己在 `onCreate` 建渠道，不再依赖 Application；`startForeground` 失败时主动 `stopSelf`，不把进程交给系统杀。另外加了崩溃堆栈落盘（`last_crash.txt`），下次再出问题能直接看到栈。

这正是「只做静态验证」的代价：`aapt2 badging` 看不出 manifest 少一个属性，只有真正跑一次启动链路才会暴露。

**装到手机后 1 分钟自检**

1. 打开 App，顶部应显示「服务运行中」，端口 8788。
2. 添加账号后进「控制台 → 概览 → 链路测试」，返回 `成功：<账号名> · …` 即上游链路通。
3. 同网电脑执行 `curl http://<手机IP>:8788/health`，应返回 `{"ok":true,"version":"1.0.2"}`。

## 十、崩溃了怎么排查

App 安装了一个未捕获异常的兜底：崩溃堆栈会写到应用私有目录的 `last_crash.txt`，下次启动时弹窗显示一次（看完自动删除）。把弹窗内容发出来就能直接定位。

如果连弹窗都看不到（崩在 Application 启动之前），用数据线抓日志：

```bash
adb logcat -d -s AndroidRuntime:E ActivityManager:E | tail -60
```

1.0.0 的闪退就是这样定位的：栈里会出现 `Bad notification for startForeground` / `invalid channel`，指向通知渠道缺失，而不是网络或账号问题。

## 十一、许可证与声明

仅供个人学习与自用，遵循参考项目的 MIT 许可精神。上游账号的使用须遵守 WorkBuddy / CodeBuddy 的服务条款，本项目不代管、不上传任何账号凭据——令牌只存在手机 App 私有目录里。
