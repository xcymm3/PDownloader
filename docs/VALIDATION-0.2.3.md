# 0.2.3 首页与独立历史记录验证

日期：2026-11-06（Asia/Shanghai）。

## 实现

- 原生首页直接输入网址 / 搜索词，共用 AddressResolver；输入框箭头与键盘前往使用相同提交逻辑。首页隐藏重复地址栏和网页操作，进入网页恢复。
- 三列、两行网站入口：百度、Google、哔哩哔哩、YouTube、X、Instagram。彩色站点标记为随应用提供的简化绘制，无远程 favicon 请求。
- 收藏夹 / 历史记录为图标入口；下载帮助与收藏功能仍在浏览器菜单中。
- 历史页为主界面内容中的独立原生视图，不创建历史 Dialog。底部导航可用，返回恢复原标签与原 WebView 实例。
- 搜索匹配标题 / URL，忽略大小写，区分空历史与无匹配；行菜单提供打开、复制链接、删除。清空需确认且只操作 history 表。
- 历史页和搜索条件随 Activity 重建恢复；沿用系统字体及浅色 / 深色资源，不更换已有品牌主色。

## 自动检查方法

- 使用独立 API 35 无窗口 AVD pdownloader-ui-validation / emulator-5582；用户模拟器 emulator-5554 不执行数据库清理或测试数据插入。
- 新增 HomeHistoryTest 三项：四种首页宽度的子视图边界、首页提交后创建真实 WebView、历史返回保留原实例、筛选和删除 / 清空后的实际数据库结果、收藏保留、Activity 重建，以及两种主题截图。
- 测试仅调用原生状态 / 生命周期 / 配置 API；截图为被动读取测试设备帧缓冲。不注入触摸、鼠标或键盘，不使用电脑控制工具。
- 首页宽度检查为 320 / 375 / 414 / 768dp 的布局测量；整页截图为测试设备的实际 1080×2400、420dpi 配置。其他设备、横屏及大字体的实际交互需人工验收。

## 结果

- 新增三项集成检查首次执行均通过；9 项 JVM 单元测试通过。
- Debug APK 已覆盖安装到 pdownloader-validation / emulator-5554，`install -r` 返回 Success，`dumpsys package` 确认为 versionCode=5 / versionName=0.2.3；未卸载或清除应用数据。
- Debug APK SHA-256：`8f78d670dd62a17afb1162b429d474e40077abcfff1357c2d99cbac15fb866d7`。
- 最终全量 Android 集成测试：22 项通过（114.252 秒，0 failures / 0 errors）；报告为 `app/build/reports/androidTests/connected/debug/index.html`。
- Lint：0 errors、11 warnings、1 hint。警告为目标版本 / 依赖版本建议、已有 Debug 单例上下文引用与已有图标资源建议；新增视图没有绘制分配或布局构造器警告。
- 最终命令 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest :app:assembleRelease` 退出码为 0。Release APK 为未签名构建验证产物，模拟器安装的是 Debug 包。
- Release APK SHA-256：`ab45af597046a8807aaa40a0e1790e3eabdc3edb123f2e34018cf1d0aaeb92bb`。

## 测试截图

以下历史数据为独立测试实例中明确标记的界面测试记录，不是用户浏览记录。截图无任何人工修图。

- [浅色首页](screenshots/0.2.3/home-light.png)
- [深色首页](screenshots/0.2.3/home-dark.png)
- [浅色历史记录](screenshots/0.2.3/history-light.png)
- [深色历史记录](screenshots/0.2.3/history-dark.png)
