# PDownloader

使用 Kotlin + Android WebView 开发的原生安卓浏览器，当前版本 0.2.2，最低支持 Android 8.0（API 26）。依据用户提供的《WDownloader-资源嗅探与界面重建交接》独立实现媒体发现、资源选择、下载与播放；使用自己的名称、图标和代码，不依赖原应用的解析服务器。红色主操作、浅灰 / 深色背景跟随系统。

图标采用已确认的黑底、红色空心方框与白色 P 下载箭头，提供矢量资源和自适应桌面图标。[图标设计稿](docs/branding/icon-approved.png)

## 当前功能

- 地址栏输入网址或搜索词；搜索使用 Google，网络可达性取决于所在环境。
- 多标签：新建、切换、逐个关闭；最多 32 个标签，同时最多保留 4 个活跃 WebView。回收的标签会在进程内尽量恢复导航状态。
- 收藏：按网址去重、本地保存、打开和删除。
- 历史记录：按网址合并为最近访问记录，最多 2000 条，支持单条删除及清空。
- 视频全屏：网页请求全屏后自动切换为竖屏，隐藏浏览器工具栏和系统栏；提供退出按钮和返回键退出，并恢复此前的方向设置。横向视频在竖屏容器内的画面比例由网页播放器决定。
- 前进、后退、刷新、分享、首页、错误重试、用户触发的新窗口、外部链接确认。
- 普通标签网址和标题在重启后恢复；重启不会恢复播放进度、表单或完整后退栈。
- 底部三个真实入口：浏览、下载中、已下载。
- 通用资源发现：video/currentSrc/source、图片实际地址、延后插入内容、开放 Shadow DOM、可归属的 ServiceWorker 请求、受限的跨源 iframe 辅助探测；每个标签与导航代次隔离。
- 右下统一资源图标：徽标显示去重资源数量，可拖动并保存相对位置；长按查看视频 / 图片数量说明。
- 紧凑资源面板：视频分组与版本选择；重命名、预览、重新检测、全选 / 取消使用图标，图片角标为透明点击区域上的方形勾选框；底部下载图标加数量。
- 图片共用半透明全屏预览：轻点图片或遮罩关闭，关闭图标、左右切换、双指缩放和双击缩放；资源选择与预览同步，已下载图片可在预览中分享和导出。
- 普通媒体流式下载、队列去重、暂停 / 继续、校验 ETag/Last-Modified 的断点续传；临时网络错误最多重试两次。
- 未加密、已结束的点播 HLS：主清单版本、TS / fMP4、初始化片段、字节范围和 discontinuity；已完成分片可复用。保存为离线资源包，不拼接成伪 MP4。
- 前台下载服务与通知进度、暂停全部；任务持久化，浏览页销毁后可继续。进程或系统服务停止后保留为暂停，用户再次点击继续。
- 已下载：全部 / 视频 / 图片分类以背景色、加粗和勾号显示当前选项，切换与重建后保持状态；名称搜索、图标排序、Media3 播放 / 图片查看、分享、导出。
- 视频使用明确的 48dp 进度触摸区域，可拖动显示预期时间并在松手后跳转，支持十秒快进 / 后退；暂停和播放位置在播放器重建后恢复。
- 普通文件分享 / 导出为原文件；HLS 分享 / 导出为 ZIP，解压后通过 offline.m3u8 播放，**不是单个 MP4**。
- 支持 Android 分享网页链接进入浏览器，沿用前台网页登录上下文，不读取账号密码。

## 快速体验下载

1. 安装最新 Debug APK，打开右上角菜单 → **本地下载功能测试**。
2. 等待页面资源出现；页面还会延后插入视频，并从跨源 iframe 提供播放器。
3. 点击右下方叠层形状的资源图标，选择视频版本或图片后点击下载图标。
4. 到“下载中”查看进度，到“已下载”播放、查看、分享或导出。

测试页和四秒钟 AVC/AAC 测试媒体全部在本地生成，不依赖外部网站。服务仅监听设备回环地址，只在 Debug 版本存在；Release 不包含服务器、测试媒体或该菜单。重启应用后测试服务器端口可能改变，请重新从菜单进入测试页。

## 构建与安装

需要 JDK 17 或 21、Android SDK Platform 36 和网络连接。项目内含 Gradle Wrapper，无需全局安装 Gradle。

1. 安装 SDK Platform 36，并准备 JDK 17 或 21。Android Studio 可选，也可直接使用命令行工具链。
2. 在本机 `local.properties` 中填写 SDK 路径；Windows 示例为 `sdk.dir=C\:/Android/Sdk`（使用正斜杠，并转义盘符冒号）。
3. 执行：

```powershell
$env:JAVA_HOME = '你的 JDK 17 或 21 路径'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

生成 APK：`app/build/outputs/apk/debug/app-debug.apk`。这是调试签名，发布版本需要自行配置签名；不要将密钥提交到仓库。

## 验证

JVM 测试覆盖网址解析、清单解析、签名参数保留、文件命名、私网地址拒绝、跨源重定向的 Cookie / Authorization 隔离。Android 集成测试使用独立本地服务器和真实生成的视频，覆盖浏览回归、DOM 与 iframe 探测、文件字节一致性、暂停续传、取消、HLS 分片失败恢复、离线播放器准备及服务生命周期。具体执行结果见 [docs/VALIDATION.md](docs/VALIDATION.md)。

```powershell
# 连接一台专门用于测试的设备或模拟器后执行；数据库测试会清除测试安装中的收藏和历史。
.\gradlew.bat :app:connectedDebugAndroidTest
```

人工验收步骤见 [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)。旧版全屏测试地址 `https://pdownloader.test/video` 仍保留；新的完整下载测试应从菜单进入本地测试页。测试媒体可通过 `python tools/generate-fixtures.py` 重建，需要 `imageio-ffmpeg`。

## 项目结构

```text
app/src/main/java/com/pdownloader/browser/
  MainActivity.kt      浏览器页面、标签、菜单、会话恢复与全屏生命周期
  BrowserUi.kt         原生界面组件与图标
  BrowserStore.kt      SQLite 收藏与历史仓库
  AddressResolver.kt   网址校验与搜索词解析
  MediaSheet.kt        视频 / 图片选择、稳定选择与图片预览
  DownloadsView.kt     下载列表、过滤、排序与任务动作
  PlayerActivity.kt    Media3 在线与离线播放器
  ImageGalleryDialog.kt 统一图片预览、透明遮罩与缩放 / 切换
  media/               会话探测、独立脚本、HLS 解析、请求上下文与受限 HTTP
  download/            SQLite 任务与本地媒体库、执行引擎、前台服务
app/src/main/res/values[-night]/  跟随系统的颜色与主题
app/src/test/                    纯 JVM 测试
app/src/androidTest/             Android 数据库集成测试
```

当前使用 Android 原生 View，下载传输由进程内串行执行器和前台服务持有，列表与浏览记录查询放在 IO 执行器。应用启动会同步执行少量数据库初始化与中断任务恢复。

范围和默认规则见 [docs/MEDIA_DESIGN.md](docs/MEDIA_DESIGN.md)。本版不支持 YouTube 下载、直播录制、加密 HLS、DRM、独立音轨合并、MP4 重封装、站点私有 API、云盘 / 会员 / 广告业务。文件上传、网站设备权限、无痕、密码同步、搜索建议编辑页、广告过滤和设置扩展仍未实现。网站可用性与系统 WebView / 设备解码器有关，不能保证所有站点可下载。

## 浏览安全设置

不暴露 JavaScript 原生桥；禁止网页直接访问本地 file/content 资源；启用 Safe Browsing；不放行证书错误；禁止 HTTPS 页面加载 HTTP 混合资源。为支持用户主动打开普通 HTTP 网站，应用允许明文 HTTP；HTTP 流量不受 TLS 保护。收藏和历史仅保存在应用私有目录，不上传到服务器，关闭 Android 自动备份。

下载网络请求只允许 HTTP(S)，拒绝解析为本机、内网等地址的目标；Debug 仅为本地测试放行回环地址和模拟器宿主地址。每一跳重定向重新取目标 Cookie；Authorization 绑定观察到的源，媒体版本分别保留请求上下文。不将 Cookie、令牌或完整签名地址输出到应用日志，不把凭据加入导出的资源包。

下载文件先保存在应用私有目录；用户选择导出后才复制到公开位置，卸载应用会移除尚未导出的媒体。仅删下载记录会保留已下载文件及本地媒体库入口；未完成任务删除时清理临时文件。“同时删文件”才清理完成媒体。Android 15 的 dataSync 前台服务存在系统时限，超时会转为暂停；不承诺无限后台运行。

WebView 内核由设备提供，请保持其更新。外部应用链接必须由用户触发并经确认后打开。真实视频渲染、手势、网站登录和主题布局仍需人工验收。
