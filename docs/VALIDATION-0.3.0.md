# 0.3.0 浏览器与媒体库重建验证

日期：2026-11-06（Asia/Shanghai）。

## 对应需求

1. 两列标签卡片：当前项高亮、原生内容缩略图、选择 / 关闭 / 新建；内存快照最大 256×400，32 个标签约 6.6MB，不落盘。进程重启后使用标题 / 域名占位，重新访问后更新。
2. 收藏夹复用历史页的原生记录视图，独立占据内容区；搜索、打开、复制和删除，保存所在页面与条件。
3. 下载中与已下载分为保留的独立实例，初始化后台预加载。移除 150ms 延迟刷新，切换立即用缓存过滤，仅数据变化才通知适配器；后台查询合并并发请求。
4. 右上菜单顶部为后退、前进、收藏、分享、刷新图标；正文有左侧图标。资源、重新检测与测试项目从菜单移除；重新检测仍属于资源面板。
5. 网页下方不再有操作栏，仅保留应用的三个底部导航入口。
6. 网页和内置播放器全屏显式请求 LANDSCAPE，退出恢复原方向；播放器原地处理旋转，不重建播放引擎。
7. 原生播放器和可访问的 HTML video 画面横向拖动显示定位时间，松手跳转；已知点播时长才可拖动。原生控制条保持独立，网页底部保留给站点控制器；跨源 iframe / 封装播放器不能保证受网页脚本控制。
8. 已下载仅视频 / 图片，默认视频，双列卡片只显示封面、名称、时长；长按进入原有管理操作。封面优先从本地媒体读取，HLS 尝试首片段，失败时回退页面缩略图 / 占位；未知时长显示 --:--，不伪造数值。
9. Release 专用签名，生成可安装 APK 与 SHA256SUMS.txt，GitHub 发布记录见下方结果。

## 验证方法与边界

- 独立 API 35 无窗口实例 pdownloader-ui-validation / emulator-5582；用 settings 关闭该测试实例的系统自动旋转，验证全屏仍请求并呈现横屏。
- Native 状态 / 生命周期 / 数据 API 检查，不注入鼠标、键盘或触摸。截图被动读取测试设备帧缓冲，未操作电脑窗口。
- 新增 BrowserRefreshTest 检查标签双列 / 当前预览 / 关闭后选择、收藏删除与历史隔离、菜单项目和图标、下载实例切换与不重复绑定。
- 新增 SeekSessionTest 检查相对进度、首尾边界、长视频灵敏度与不可用时长；MediaUiTest 继续验证真实 MP4 / HLS seek、暂停位置恢复和实际横屏。
- 帧缓冲截图来自本地测试网页、生成的四秒 MP4 / HLS、红色测试 PNG、example.com 测试收藏；不包含用户浏览或下载数据。
- 物理拖动手感、网页站点的播放器兼容性与其他设备布局仍需人工验收。功能状态测试不能替代实际手势体验。

## 执行结果

- JVM 单元测试 12 项通过。
- 完整 Android 集成测试 26 项通过（153.022 秒）；完整结果备份在 `app/build/verification/full-0.3.0`。
- 截图检查后为预览添加布局完成检查和背景填充，避免快速切换出现黑预览；随后 BrowserRefreshTest 4 项再次通过（28.799 秒）。
- 首轮新用例发现标签关闭后适配器刷新需要统一到关闭 API，已修正；另两处断言调整为正确处理空网格测量与首页标签。最终检查通过后再发布。
- 最终联合命令 `:app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest :app:assembleRelease -Pandroid.testInstrumentationRunnerArguments.class=com.pdownloader.browser.BrowserRefreshTest` 退出码 0；Debug 测试页横屏文案校正后再次 assembleDebug，退出码 0。
- Lint 为 0 errors、11 warnings、1 hint；已有目标 / 依赖版本建议、Debug 上下文静态引用及图标资源建议仍保留。手势层通过父容器分发保留原点击，并提供可访问的前后跳转动作。
- Release 使用 RSA 3072 位专用签名，apksigner verify 通过（v2）；证书 SHA-256 为 `4ca6f02c7edb8b181f76c738d1aea59a37f5a6b2620f8df12e92afc48775e3c8`。
- 签名 APK 在独立测试实例安装成功，冷启动返回 Status: ok / COLD，versionCode=6 / versionName=0.3.0，进程在检查后保持存活，包 flags 不含 DEBUGGABLE。ZIP 检查确认没有 Debug 媒体和测试 HTML。
- Release APK 大小 1,342,202 字节，SHA-256：`535fe176ed1d4752c49c0dc5b7803f6f917570aecadae19467fbd843f44bd069`。
- Debug APK SHA-256：`c0d9c89b024e56c1a99068301e14fe4a4c1e8a5b4167bb47f1a41282613324ec`。
- GitHub Release：[v0.3.0](https://github.com/xcymm3/PDownloader/releases/tag/v0.3.0)，附件为 PDownloader-0.3.0.apk 与 SHA256SUMS.txt；附件可用性将通过 GitHub API 和下载后哈希校验核对。
- 原用户 AVD 在本轮开始安装时已离线，随后无窗口启动尚在等待 package 服务；未卸载应用或清理数据。Release 已在独立实例完成安装与冷启动校验。

## 原始截图

- [标签页两列预览](screenshots/0.3.0/tabs-grid.png)
- [收藏夹独立页面](screenshots/0.3.0/bookmarks-page.png)
- [菜单顶部操作图标](screenshots/0.3.0/browser-menu.png)
- [已下载视频](screenshots/0.3.0/download-grid-video.png)
- [已下载图片](screenshots/0.3.0/download-grid-image.png)
- [关闭自动旋转后全屏横屏](screenshots/0.3.0/video-player.png)
