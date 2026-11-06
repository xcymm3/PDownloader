# PDownloader

使用 Kotlin + Android WebView 开发的原生安卓浏览器。最低支持 Android 8.0（API 26）。界面采用 Chrome 用户熟悉的地址栏、标签入口和菜单，浅色 / 深色跟随系统。

## 当前功能

- 地址栏输入网址或搜索词；搜索使用 Google，网络可达性取决于所在环境。
- 多标签：新建、切换、逐个关闭；最多 32 个标签，同时最多保留 4 个活跃 WebView。回收的标签会在进程内尽量恢复导航状态。
- 收藏：按网址去重、本地保存、打开和删除。
- 历史记录：按网址合并为最近访问记录，最多 2000 条，支持单条删除及清空。
- 视频全屏：网页请求全屏后自动切换为竖屏，隐藏浏览器工具栏和系统栏；提供退出按钮和返回键退出，并恢复此前的方向设置。横向视频在竖屏容器内的画面比例由网页播放器决定。
- 前进、后退、刷新、分享、首页、错误重试、用户触发的新窗口、外部链接确认。
- 普通标签网址和标题在重启后恢复；重启不会恢复播放进度、表单或完整后退栈。
- 底部固定三个入口：浏览、下载中、已下载。后两者为占位页，不会创建任何下载任务。

## 构建与安装

需要 JDK 17 或 21、Android SDK Platform 36 和网络连接。项目内含 Gradle Wrapper，无需全局安装 Gradle。

1. 使用 Android Studio 打开项目根目录并安装缺少的 SDK。
2. 在本机 `local.properties` 中填写 SDK 路径；Windows 示例为 `sdk.dir=C\:/Android/Sdk`（使用正斜杠，并转义盘符冒号）。
3. 执行：

```powershell
$env:JAVA_HOME = '你的 JDK 17 或 21 路径'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

生成 APK：`app/build/outputs/apk/debug/app-debug.apk`。这是调试签名，发布版本需要自行配置签名；不要将密钥提交到仓库。

## 验证

单元测试覆盖网址 / 搜索词解析与危险 scheme 拒绝；Android 集成测试覆盖收藏持久化、去重、历史清空隔离、数量上限、标签重建恢复以及全屏竖屏锁定和方向恢复。

```powershell
# 连接一台专门用于测试的设备或模拟器后执行；数据库测试会清除测试安装中的收藏和历史。
.\gradlew.bat :app:connectedDebugAndroidTest
```

人工验收步骤见 [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)。全屏测试页仅在 debug 版本可通过地址 `https://pdownloader.test/video` 访问，使用 MDN 的公开视频资源。

## 项目结构

```text
app/src/main/java/com/pdownloader/browser/
  MainActivity.kt      浏览器页面、标签、菜单、会话恢复与全屏生命周期
  BrowserUi.kt         原生界面组件与图标
  BrowserStore.kt      SQLite 收藏与历史仓库
  AddressResolver.kt   网址校验与搜索词解析
app/src/main/res/values[-night]/  跟随系统的颜色与主题
app/src/test/                    纯 JVM 测试
app/src/androidTest/             Android 数据库集成测试
```

当前使用 Android 原生 View 构建界面，数据库操作使用单线程 IO 执行器，避免在主线程读写数据库。项目尚未实现文件上传、网站摄像头 / 麦克风 / 定位授权、无痕模式、广告过滤、密码同步和视频下载。WebView 并不提供与完整 Chrome 相同的全部能力。

## 浏览安全设置

不暴露 JavaScript 原生桥；禁止网页直接访问本地 file/content 资源；启用 Safe Browsing；不放行证书错误；禁止 HTTPS 页面加载 HTTP 混合资源。为支持用户主动打开普通 HTTP 网站，应用允许明文 HTTP；HTTP 流量不受 TLS 保护。收藏和历史仅保存在应用私有目录，不上传到服务器，关闭 Android 自动备份。

WebView 内核由设备的系统 WebView 提供，请保持设备 WebView 组件更新。外部应用链接必须由用户触发并经确认后打开。网站自己的主题和视频全屏行为仍受网站实现及系统 WebView 版本影响。
