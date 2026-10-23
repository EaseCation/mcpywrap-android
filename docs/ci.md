# APK 自动构建与 Nightly 下载

工作流：`.github/workflows/build-apk.yml`。

## 触发与下载

| 触发方式 | 构建 APK | 更新固定 Nightly |
| --- | --- | --- |
| 推送默认主分支，例如 `main` | 是 | 是 |
| 在默认主分支手动运行 | 是 | 是 |
| 推送其他分支、PR、`v*` 标签 | 是 | 否 |

在 Actions → Build Android APK 中查看构建。成功后可从运行摘要或 Artifacts 下载 ARM64 Debug APK，检查报告与 SHA-256 一并提供，保留 14 天。手动运行入口需要工作流已提交到默认分支。

Nightly 固定地址，将 `<owner>/<repo>` 替换为 Android 仓库：

- Release：`https://github.com/<owner>/<repo>/releases/tag/nightly`
- APK：`https://github.com/<owner>/<repo>/releases/download/nightly/mcpywrap-android-nightly-arm64.apk`
- 校验：`https://github.com/<owner>/<repo>/releases/download/nightly/SHA256SUMS`

Nightly 保留同一预发布和下载文件名，标签指向对应源码提交。过期提交的重跑不会覆盖新版本，构建检查失败不会更新发布。应用版本号仍由 `app/build.gradle.kts` 管理。

## 仓库配置

启用 GitHub Actions，允许工作流使用 `GITHUB_TOKEN` 写入 Release 和移动 `nightly` 标签。固定 Nightly 必须保持可编辑，不能设为 immutable release；无需配置 PAT。

主分支发布必须配置 Actions Secret `ANDROID_DEBUG_KEYSTORE_BASE64`，内容为固定 Debug keystore 的 Base64 编码，别名 `androiddebugkey`，store/key 密码均为 `android`。如果需要覆盖本机安装包，应使用与原 APK 相同的 Debug keystore。不要使用正式发行密钥。

缺少该 Secret 时主分支构建会明确失败，不发布随机签名的 Nightly。PR 构建始终使用临时密钥，其他分支在未配置 Secret 时也可使用临时密钥。

## 安装要求

产物为 Android 11+ / ARM64 测试签名 APK。首次运行时在 App 内安装官方游戏环境。CI 不包含官方游戏资源，也不执行真实游戏设备验收。


## 0.5.1 签名迁移

0.5.1 是固定 Nightly 签名的起点。早期 Nightly 和本地 ADB 包可能使用其他签名，因此不能直接覆盖安装。切换前保留内部存储的 `mcpy` 文件夹，卸载旧 App 后安装 Nightly；公共作品与存档仍在该文件夹中，私有运行环境和配对需要重新配置。后续同签名 Nightly 可直接覆盖安装。
