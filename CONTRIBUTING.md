# 贡献指南

这个项目刻意保持小：一个模块、零第三方运行时依赖、单 APK。改动请守住这条线。

## 先跑一遍

```bash
git clone <本仓库地址>
cd WorkBuddyProxy
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # 已被 .gitignore 排除
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。要求 JDK 17+、Android SDK 34。

## 提交前自查

1. `./gradlew assembleRelease` 通过，没有新增警告。
2. 没有新的第三方依赖进 `dependencies`——这是项目的核心约束，加库的 PR 会被要求改回平台 API。
3. 没有提交 `local.properties`、`*.jks`、`keystore.properties`、`build/`。
4. 用户可见的改动，在 `CHANGELOG.md` 的 `[Unreleased]` 下加一行。
5. 改动 `versionName` / `versionCode` 时两者一起改，`versionCode` 单调递增。

## 提交信息

```
<type>: <一句话说清改了什么>
```

`type` 取 `feat` / `fix` / `perf` / `refactor` / `docs` / `build` / `chore`。正文里如果有「为什么」，写在空行之后。

## 分支与 PR

- 从 `main` 切 `feat/xxx` 或 `fix/xxx`。
- PR 描述里写：改了什么、怎么验证的、有没有影响已安装用户的升级路径。
- CI 会跑 `assembleRelease`，绿了再看。

## 代码风格

- Kotlin 官方风格（`kotlin.code.style=official`），4 空格缩进。
- 源码一律 LF，`.gitattributes` 已约束，Windows 下别让编辑器改成 CRLF。
- 注释写「为什么」，不写「是什么」。现有代码里的注释密度可以照着来。

## 不要做的事

- 不要在仓库里放账号 token、抓包得到的凭据、真机日志里的用户信息。
- 不要为了「兼容更多客户端」引入协议猜测——上游行为变化请附上可复现的请求与响应片段（脱敏）。
- 不要把 debug 签名换成别人的 keystore。

## 报告问题

用 Issue 模板。必须带：App 版本、Android 版本与机型、复现步骤、`last_crash.txt` 内容（如果有）。日志里如果含 key 或 token，先替换成 `sk-wb-xxxx`。
