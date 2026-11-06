# 验证记录

> 以下为 0.1.0 初始版本的历史记录；0.2.0 的当前结果见 [VALIDATION-0.2.0.md](VALIDATION-0.2.0.md)。本文旧 APK 哈希不适用于新版本。

验证日期：2026-11-06（Asia/Shanghai）。

## 环境

- Windows，JDK 21.0.11，Gradle Wrapper 8.14.3。
- Android Gradle Plugin 8.13.0，Kotlin 2.2.10。
- compileSdk 36 / targetSdk 35 / minSdk 26。
- 独立的无窗口 Android 15（API 35）x86_64 测试实例 `pdownloader-validation`。
- 未使用桌面控制、鼠标、键盘或触摸模拟；Android 测试直接调用生命周期和内核回调。

## 已通过

执行命令：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --console=plain
```

结果：`BUILD SUCCESSFUL`。

- Debug APK 构建成功：`app/build/outputs/apk/debug/app-debug.apk`。
- 3 项 JVM 测试通过：网页地址解析、搜索词编码、危险 scheme 和畸形网址拒绝。
- 5 项 Android 集成测试通过：收藏去重及重开数据库后的持久化、清空历史不影响收藏、历史条数上限、Activity 重建恢复选中标签地址、全屏竖屏锁定及退出恢复方向。
- 全屏测试也验证重复全屏请求会被拒绝，退出回调只调用一次。
- Lint：0 errors，3 warnings。

剩余 Lint 警告为 targetSdk 35 低于 compileSdk 36，以及两个已固定版本的 Android 测试依赖存在新版本。首次交付固定上述工具链；升至 targetSdk 36 前需要针对大屏方向策略和窗口行为做设备兼容性验证。

APK SHA-256：

```text
D8FB192F6583DC53B732D7E2244855FA4FD3D4BAF88AF200CA5F1D39A8BCED91
```

报告位置（构建产物，不提交到 Git）：

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/androidTests/connected/debug/index.html`
- `app/build/reports/lint-results-debug.html`

## 仍需人工验收

集成测试直接验证全屏回调的处理逻辑，未验证网站实际视频渲染、音频、系统旋转动画、网页自身全屏按钮或真实设备的方向策略。

未进行截图或交互式视觉验收。浅色 / 深色切换、不同屏幕尺寸、键盘和手势导航布局、多站点视频兼容性，应按 [ACCEPTANCE.md](ACCEPTANCE.md) 在手机上人工确认。系统或厂商在大屏、多窗口环境下可能覆盖方向请求。

未执行发布签名、商店发布或远程推送。项目为新建本地 Git 仓库，未配置远程地址。
