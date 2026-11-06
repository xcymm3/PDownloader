# 0.2.0 验证记录

日期：2026-11-06（Asia/Shanghai）。Debug / Release 构建与下列验证通过；未进行交互式视觉验收。

## 验证方法

- 独立无窗口 Android 15 / API 35 x86_64 测试实例 `pdownloader-media-validation`，与用户体验使用的模拟器分开。
- JDK 21.0.11、Gradle 8.14.3、AGP 8.13.0、Kotlin 2.2.10、compileSdk 36 / targetSdk 35 / minSdk 26。
- 测试服务器只监听回环地址，媒体由 FFmpeg 本地生成。四秒 AVC/AAC MP4、TS 点播、fMP4 单文件字节范围点播均不依赖外网媒体。
- 测试直接调用引擎、生命周期与回调，不模拟电脑窗口、鼠标、键盘或触摸，不进行桌面自动化。

## 自动化覆盖

JVM：网址 / 搜索词解析、危险 scheme 拒绝；master 版本及分离音轨标记；相对地址与签名参数；初始化片段、字节范围、discontinuity；拒绝假清单 / 直播 / 加密；文件名、未知大小；原生 HTTP 私网目标拒绝、重定向 Cookie 重新取值与跨源 Authorization 移除。

Android：原浏览器收藏 / 历史 / 重建 / 网页全屏回归；下载去重及文件逐字节一致性；下载图片的本地 Bitmap 解码；删除下载记录后本地媒体保留；普通 Range 续传及服务器忽略 Range 后从头覆盖；暂停 / 继续 / 取消清理；HLS 分片失败不标完成、重试复用已完成分片；TS 和 fMP4 字节范围离线媒体由 Media3 准备成功且时长符合四秒样本；受保护媒体按目标获取浏览器 Cookie；Activity 销毁后前台服务继续完成下载；DOM 延后内容 / 图片 / HLS 版本 / 跨源 iframe 发现与旧会话丢弃；原生播放器全屏竖屏及工具栏恢复。补充定向验证了没有 .m3u8 后缀、由 MIME 明确声明的 HLS 地址。

播放器自动化检查的边界是媒体准备成功及有效时长，并不等同于人工观察画面、声音和旋转动画。

## 交接场景对应

| 场景 | 覆盖情况 |
| --- | --- |
| F01–F06 | 本地直接媒体、多个 source、延迟插入、HLS 请求及主清单版本；DOM / 网络共同进入归一流程 |
| F07–F08 | JVM 验证无分辨率保留、分离音轨禁用；假 HLS 下载失败 |
| F09 | 本地受控跨源 iframe 的 preload=none 视频，由辅助环境发现 |
| F10–F11 | 实现后台队列 / 超时 / 深度上限及可归属 SW 入口；任意循环页面与真实 SW 网站未完整集成测试 |
| F12–F16 | URL 合并、签名保留、独立内容组、Cookie 与重定向隔离、旧会话拒绝；复杂登录网站需人工验证 |
| F17–F18 | 已实现图片增量、稳定选择与预览同步；触摸操作和大图视觉需人工验收 |
| F19–F20 | 暂不提供专用站点解析器；YouTube 与不支持格式有说明，外部站点兼容性未声明通过 |
| D01–D03 | 用户选定版本入队、批次快照及未知大小逻辑；实际面板交互需人工验收 |
| D04 / D06–D08 | 范围续传、暂停 / 取消、分片失败恢复与离线准备、浏览页销毁后继续；真实断网、磁盘耗尽、系统强杀需人工验收 |
| D05 | HTTP 401/403/404/410 显示原因及打开来源；不自动替换旧任务 URL，真实过期站点需人工验收 |
| U01–U04 | 实现稳定面板与分层返回；深浅色、长标题、窄屏、安全区、拖动、横屏需用户人工验收 |

## 结果

全量命令退出码 0：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest :app:assembleRelease --console=plain --quiet
```

- 9 项 JVM 测试通过（3 个网址测试、4 个 HLS / 命名测试、2 个 HTTP 隔离测试）。
- 16 项 Android 全量测试通过，0 failures / errors / skipped。
- 随后修正了显式 HLS MIME 的无后缀候选入口，重跑 JVM、Lint、两个构建及对应探测集成用例，全部通过。该定向用例不重复计入 25 项总数。
- Debug APK 可安装，Release APK 经 R8 优化构建成功、未配置发布签名。
- 检查 Release ZIP：测试媒体 / 旧测试页资源数为 0。
- Lint：0 errors，8 warnings，1 hint。警告包括目标 API 版本、6 个固定依赖有新版本，以及仅 Debug 本地测试服务器持有 applicationContext 的静态引用提示；提示为可选 core-ktx 扩展。
- Git 空白检查通过。没有执行电脑控制或触摸 / 键盘模拟。

定向命令在同一基础命令后加：

```text
-Pandroid.testInstrumentationRunnerArguments.class=com.pdownloader.browser.MediaDownloadTest#domLateInsertionHlsAndImagesAreDiscoveredAndNavigationRetiresCallbacks
```

完整 Android 报告保存在 `app/build/verification/full-0.2.0/debug/index.html`，原始全量 XML 位于 `app/build/verification/full-0.2.0-results/`；最近一次定向报告位于 `app/build/reports/androidTests/connected/debug/index.html`。JVM 与 Lint 报告位于 `app/build/reports/`。这些构建产物未提交到 Git。

最终 APK SHA-256：

```text
Debug   E2368D778ADFD51D0F97EC97D4293C33CCADA9AD7759662713F809F9FE504BF2
Release 3C2B53A040BF614A83DF3343A21EF96AEAB04D1584002AC120800929504D2BA4
```

回归中修正的问题包括：旧测试的地址栏定位被下载搜索框干扰；本地 PNG 测试数据校验错误；长中文文件名可能超出字节长度；空 src 被错误解析为当前网页；无来源请求需等待 DOM / Performance 归属；服务结束与新任务入队竞态；删除记录后保留文件的访问入口。

## 发布边界

未进行交互式视觉 / 截图验收、真实外部站点登录验证、生产签名或商店发布。Release APK 为 unsigned，Debug APK 可安装体验。项目未配置远程仓库，不能推送。
