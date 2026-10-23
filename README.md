# WorkBuddyProxy

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

在 Android 手机上跑一个网关，把 WorkBuddy 的账号变成 OpenAI / Anthropic 兼容接口。同一 Wi-Fi 下的电脑、平板直接连手机就能用，不用再开一台电脑挂着。

纯 Kotlin 写的，没有第三方依赖。APK 八百多 KB，Android 7.0 以上能装。

## 安装

 [Releases](https://github.com/123swxma/WorkBuddy-/releases)


## 支持的接口

- `/v1/chat/completions`，支持流式
- `/v1/messages`，给 Anthropic 的客户端用
- `/v1/models`

多个账号会轮流用，某个号失败了自动换下一个。

## 注意

- 无法使用Codex 用的那个协议`/v1/responses` 
- 上游只接受流式请求。如果是非流式的话，网关自己会把流读完拼成完整响应再返回，会比直连慢一点





## 许可

MIT
