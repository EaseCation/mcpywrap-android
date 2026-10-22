# mcpy Android 图标

来源：mcpywrap 的 `core/mcpywrap_core/ui/assets/mcpy.icon`。`source/` 保留完整 Icon Composer 图层源：`icon.json`、立方体 SVG、终端符号 SVG。逐文件 SHA-256 见 `source-receipt.json`。此项目不依赖相邻 mcpywrap checkout；升级源图标时显式同步并审阅生成结果。

本次同步自 mcpywrap `main` 提交 `14221768cee2da99398cc7bbbf07fd06ad754750`。

Android 使用源 SVG 的路径与源 JSON 的渐变配色。去除 macOS 特有的玻璃、外缘阴影、圆角和 Dock 留白，交给 Android launcher 处理外形裁切与动效。前景与背景均为 VectorDrawable，保持独立图层。

立方体与终端符号整体以画布中心缩放为原尺寸的 80%，增加视觉留白；同一比例用于彩色、单色、传统图标、商店素材和启动画面。源 SVG 保持原样，缩放由转换脚本控制。

- Android 8+：108dp 自适应图标。主图案保持在中心 66dp 安全圆内，四周给系统裁切和动效留白。
- Android 13+：单色层保留立方体轮廓、棱线和终端符号镂空，由系统按壁纸与深浅主题着色。原 SVG 的重叠路径按轮廓合并后转为镂空，避免 evenOdd 产生多余小三角。
- 圆形入口：Manifest 同时声明 `roundIcon`，现代系统依然用系统 mask，旧式资源提供单独圆形 PNG。
- 传统图标：mdpi / hdpi / xhdpi / xxhdpi / xxxhdpi 对应 48 / 72 / 96 / 144 / 192px。当前应用最低 Android 11，旧式图标用于资源读取器等兼容用途，不代表支持旧 Android 运行。
- Android 12+ 启动画面：无图标背景的 288dp 画布，图案在 192dp 安全圆内，底色沿用原图深蓝。
- 商店素材：512×512、完整实色背景、无预制圆角或外缘阴影。此文件仅备好，不自动上传发布。

生成命令：`python3 tools/build-icons.py`。只在更新图标时需要 `rsvg-convert`；正常 Gradle 构建直接使用已生成资源。`android-previews.png` 展示圆形、圆角方形、连续圆角和两种主题色模拟，主题色仅作效果预览，真实颜色由设备决定。

官方规格核对（2026-10-22）：

- https://developer.android.com/develop/ui/views/launch/icon_design_adaptive
- https://developer.android.com/develop/ui/views/launch/splash-screen
- https://developer.android.com/distribute/google-play/resources/icon-design-specifications
