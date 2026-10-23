# mcpywrap-android

纯安卓手机上的源码 Mod 工作台。使用官方 Jetpack Compose Material 3 1.4.0，提供本地文件编辑、运行环境安装和独立游戏进程。

## 当前功能

应用版本：**0.5.1**；内置工程核心：**0.6.2**。

- 在 **内部存储 → mcpy**（`/sdcard/mcpy`）创建项目、运行版本、世界、草稿、导出、日志和回收站。需要 Android 的“所有文件访问”授权，不回退到私有工作区。
- 新建作品时填写作品名称和 Mod 标识，按需展开版本、脚本目录、客户端/服务端系统名；查看真实结构预览后创建。生成逻辑复用桌面 mcpy 核心。欢迎语示例保留为独立入口，创建后直接改源码。
- 导入 ZIP、mcpack、mcaddon、系统选择的文件夹，以及公开的 **HTTPS Git 仓库**。Git 使用 JGit 在设备上克隆默认分支，保留 `.git`，不执行安装/构建脚本。
- 文件夹面包屑、文件筛选、新建文件/文件夹、重命名/移动、回收站恢复、作品重命名与导出。可将单个文件交给外部编辑器。
- **Sora Editor**：Python / JSON 语法高亮、行号、等宽字体、自动缩进、撤销/重做、查找替换、换行切换。保存使用原子替换和外部修改检测；未保存草稿放在公共 `build/drafts`。
- 官方运行环境后台安装、暂停续传、摘要与签名校验、损坏分段修复、原生库提取。支持版本固定为已适配的 `3.10.100.299889`，不会自动执行未知新版。
- 在作品内新建、删除和恢复测试实例；每次运行使用当前作品代码；实际游戏画面通过系统画中画展示，默认启用。应用新代码前先请求引擎保存退出。
- 内置正式发布的 **mcpywrap-core 0.6.2**：使用同一 Python 核心解析依赖、组装与导出；支持已准备好的本地 Addon 依赖。设置中可检查核心更新、下载验证并回滚，无需重新安装 APK。

普通源码组件使用**游戏自带的 Python 环境**。App 内置的 CPython 3.13 仅运行工程核心，二者位于不同进程。Git/pip 和工程脚本由已配对开发端的工具环境执行，App 核心决定准备顺序并完成解析和组装。手动开发不需要工具环境；需要脚本的工程会提示通过开发端操作。地图工程尚未接入，不跳过验证或回退到另一套合并实现。

生成模组支持客户端和服务端源码初始化，游戏进程提供双端 Python 调试、源码热更、端侧日志和完整 Surface 截图。

「开发工具」提供分步安装指引，直接显示地址、配对码与连接命令；AI 说明是可选入口，普通终端也可完成配置。连接可通过临时确认卡或通知授权，不显示常驻悬浮图标。作品由开发环境中的标准命令选择。Minis、Termux 和其他工具共用标准 CLI 与 HTTP/WebSocket 协议，详见[本机接口](docs/local-host-api.md)。本机服务默认核心版本由 `gradle.properties` 中的 `mcpyCoreVersion` 确定；Nightly 从 PyPI 安装该版本，因此发布顺序为两个 Python 包完成发布后再构建 Android。本机服务需要支持 `prepare_run` 的核心版本；开发构建可通过 `-PmcpyCoreWheel=<已构建的核心wheel>` 指定待发布核心。

## 构建与运行

需要 JDK 17、构建机 Python 3.13、Android SDK 36、NDK `25.1.8937393`，以及 Rust 1.88.0 的 `aarch64-linux-android` target（构建 JSON Schema 验证依赖）。设置 `local.properties` 中的 `sdk.dir`，或 `ANDROID_HOME`。没有对相邻实验项目或 mcpy 源码 checkout 的构建依赖；核心从 PyPI 安装固定版本。

```sh
sh tools/build.sh
sh tools/run-product.sh
```

应用 ID：`dev.mcpywrap.android`；输出：`app/build/outputs/apk/debug/app-debug.apk`。这是开发签名的可安装 APK。0.5.1 起 Nightly 使用仓库 Actions Secret 中的专用固定签名；私钥不进入源码仓库。

GitHub Actions 会在分支推送、PR、`v*` 标签推送及手动触发时自动构建，上传可直接下载的 ARM64 Debug APK 和检查报告。默认主分支构建成功后自动更新固定的 `nightly` Release，APK 地址保持不变。依赖安装、缓存、签名兼容与下载方式见 [CI 说明](docs/ci.md)。

首次打开：允许文件访问 → 新建作品 → 设置中下载安装运行环境 → 打开作品并测试。下载约 2.16 GiB，建议保留 7 GB 可用空间。官方 APK 和原生库属于系统加载缓存，按 Android 要求保存在应用内部；用户项目、存档与日志全部使用公共目录。

## 验证

```sh
sh tools/verify-device.sh
# 单独运行需要联网和大容量下载的真实内核验收：
sh tools/verify-runtime.sh
python3 tools/check-template.py
```

默认测试设备 `emulator-5564`；可通过 `ANDROID_SERIAL` 覆盖。测试创建 `QA-*` 作品，输出到 `/sdcard/mcpy/logs/product-evidence`。验证脚本不会卸载应用，以保留已安装的运行环境。请使用专用测试设备。

## 文档

- [本机开发接口与授权](docs/local-host-api.md)
- [自动构建与 Nightly 下载](docs/ci.md)
- [Android 分层图标与生成方式](design/icons/README.md)
- [实现架构与模块职责](docs/architecture.md)
- [第三方来源和许可证](THIRD_PARTY_NOTICES.md)
