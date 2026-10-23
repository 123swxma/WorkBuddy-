# WorkBuddyProxy

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

在 Android 手机上跑一个网关，把 WorkBuddy 的账号变成 OpenAI / Anthropic 兼容接口。同一 Wi-Fi 下的电脑、平板直接连手机就能用，不用再开一台电脑挂着。

纯 Kotlin 写的，没有第三方依赖。APK 八百多 KB，Android 7.0 以上能装。

## 安装

 [Releases](https://github.com/123swxma/WorkBuddy-/releases)

## 怎么用

打开 app，在「代理」页点启动服务。默认只监听本机 8788 端口。

接着去「我的」页添加账号。国际版会跳到系统浏览器登录，Google 不让在 WebView 里完成授权；国内版可以直接在 app 里登。登录完自动进池子。

「API」页能看到 Base URL 和 Key。Key 要自己建，建的时候选走国际版还是国内版，这个选择决定这把 Key 只能用哪边的账号。

填进客户端：

- Base URL：`http://127.0.0.1:8788/v1`，别的设备用 `http://手机IP:8788/v1`
- API Key：自己建的那串

Claude Code 是这样：

```bash
export ANTHROPIC_BASE_URL=http://<手机IP>:8788
export ANTHROPIC_AUTH_TOKEN=sk-wb-xxxxxxxx
```

base url 后面不要带 `/v1`。

## 支持的接口

- `/v1/chat/completions`，支持流式
- `/v1/messages`，给 Anthropic 的客户端用
- `/v1/models`

多个账号会轮流用，某个号失败了自动换下一个。

## 做不到的

- `/v1/responses` 没实现，Codex 用的那个协议用不了
- 图片输入会被丢掉
- 上游只接受流式请求。客户端要非流式的话，是网关自己把流读完拼成完整响应再返回，会比直连慢一点

## 两个坑

手机厂商的省电策略会杀后台。最好给这个 app 开「忽略电池优化」，不然服务会莫名其妙停掉。

现在是 debug 签名，要正式发布得自己换个 keystore。

## 自己编译

```bash
./gradlew assembleRelease
```

需要 JDK 17 和 Android SDK 34。sdk 路径写在 `local.properties`，这个文件不进仓库。

协议细节参考了 [workbuddy2api-hub](https://github.com/ardeyouxipianyi/workbuddy2api-hub) 的抓包结论，代码是重新写的。

## 许可

MIT
