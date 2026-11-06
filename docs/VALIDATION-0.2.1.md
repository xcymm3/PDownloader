# 0.2.1 界面与进度控制验证

日期：2026-11-06（Asia/Shanghai）。

## 改动对应

1. 双悬浮文字按钮合并为一个资源图标，显示总数徽标与分类说明，保留拖动位置。
2. 图片角标移除白色圆形背景，使用方形选择标记和透明点击区域。
3. 资源面板和下载列表的常用操作使用紧凑图标，支持长按说明与无障碍描述。
4. 共用半透明图片预览，关闭 / 切换 / 下载 / 分享 / 导出均用图标；轻点退出、双指缩放与缩放后移动。预览优先加载，不排在缩略图队列后。
5. 下载分类有唯一高亮、加粗与勾号，选项随 Activity 重建保存。
6. Media3 控制器提供 48dp 进度触摸区域，拖动中显示时间，松手跳转，支持十秒快进 / 后退；重建保留暂停状态和位置。

## 方法与边界

- 使用 E 盘中的独立、无窗口 API 35 测试实例 `pdownloader-ui-validation`，用户正在使用的模拟器不运行破坏性数据库测试。
- 新增集成检查覆盖分类实际过滤结果、唯一选中状态、图片本地与网络加载、翻页后的外部选择同步，以及 MP4 / HLS 的实际 seek 和暂停位置恢复。
- 应用测试直接调用生命周期、状态与播放 API。界面截图为测试设备被动帧缓冲读取，不注入鼠标、键盘或触摸，也未使用电脑控制插件。
- 拖动 / 缩放的物理手感、真实站点和其他设备上的布局仍需按验收清单人工体验。

## 执行结果

- 新增 `MediaUiTest` 三项集成检查已在最终界面代码上通过，包含真实 MP4 / HLS 跳转、播放器暂停重建与全屏安全区检查。
- JVM 单元测试：9 项通过。
- Android Lint：0 errors、8 warnings、1 hint；警告为目标版本 / 依赖版本建议，以及已有 Debug 测试服务器上下文静态引用，不包含本次控件的私有资源引用警告。
- Debug 0.2.1 已通过 `adb install -r` 覆盖安装到用户的 `pdownloader-validation`（emulator-5554），包信息确认为 versionCode=3 / versionName=0.2.1；未发送任何界面输入。
- Debug APK SHA-256：`7ad7832f38969bec2d0996952742bf670e0ee31e2a790cc53984eaf727d3e633`。
- 最终代码全量 Android 回归：19 项通过（146.444 秒，0 failures / 0 errors）；覆盖浏览生命周期、数据存储、媒体发现、下载与新增 UI 状态。报告位于 `app/build/reports/androidTests/connected/debug/index.html`。
- 最终联合命令 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest :app:assembleRelease` 退出码为 0；Release 为未签名产物，仅作构建验证。
- Release APK SHA-256：`973f5bf6758d4fb434a6e48d10359cc4741feac8b4abd8644b67b9ba5c48b0ac`。

## 原始截图

以下均为独立测试实例的被动截图。网页是应用内本地测试页面；资源面板截图额外构造一个视频与六张图片检查布局密度，图片为红色测试 PNG，视频为四秒测试图案；不代表任一真实网站的嗅探结果。

- [统一资源按钮](screenshots/0.2.1/resource-button.png)
- [紧凑资源面板与方形选择框](screenshots/0.2.1/resource-sheet.png)
- [半透明图片预览](screenshots/0.2.1/image-gallery.png)
- [下载分类选中提示](screenshots/0.2.1/download-filter.png)
- [视频进度条与系统栏安全区](screenshots/0.2.1/video-player.png)

截图确认了方形勾选框没有圆底、尺寸标签缩为半透明角标、下载图标和数量居中、播放器控件与系统导航键不重叠。首次新增预览测试发现初始选择状态要等到窗口显示回调才同步，已改为构造时同步并通过后续检查。
