# 媒体发现、下载与界面决策

依据用户提供的《WDownloader-资源嗅探与界面重建交接.md》（2026-11-06）独立编写。其静态证据用于参考信息结构；本项目的实现选择不冒充原版源码或完整行为。

## 本版默认规则

| 项目 | 选择 |
| --- | --- |
| UI | 保留现有原生 View 浏览壳；红色主操作、深浅系统主题、三个底部入口；不引入广告 / 会员 / 云盘 |
| 资源通信 | Native 每 1.2 秒读取结构化 DOM 结果；不注册 addJavascriptInterface，不给网页原生代理、剪贴板或文件读取能力 |
| 导航 | 每标签每轮导航一个 ProbeSession；主导航及 SPA URL 变化换代，晚到结果先校验所属会话 |
| 分组 | DOM 视频元素的多个 source 为同组；HLS master 的子清晰度为同组；按完整 URL 保留签名；相同标题不合并 |
| 默认选择 | 面板首次打开选择已有可用视频组，每组一个可用版本；新发现的内容不自动加入已选批次；图片由用户选择 |
| HLS | 支持未加密、带 ENDLIST 的点播；保存分片与相对本地播放清单；不将媒体字节直接拼成 MP4 |
| 复杂格式 | 加密、DRM、直播、分离音轨离线下载、低延迟 HLS 特殊扩展明确拒绝；不请求 DRM 授权或加密密钥 |
| 站点 | 通用探测为主。B 站分享适配器使用 B 站公开网页接口获取单文件版本和封面。YouTube 提示不支持下载 |
| 请求上下文 | 每版本 UA、来源与观察到的 Authorization；Cookie 通过 CookieManager 为每个实际目标地址获取，跨源不搬运 Authorization |
| 下载 | 一次一个任务，持久化、最多两次瞬时网络重试；暂停 / 取消不会被晚到的传输状态覆盖 |
| 恢复 | 根据失败原因重试或回原网页。更换地址沿用任务 ID 并清理旧进度；原地址续传核对 Range、If-Range 与文件大小，HLS 根据清单指纹验证旧分片 |
| 后台 | 用户操作启动 dataSync 前台服务；有通知进度与暂停入口；进程死亡后的任务转暂停，由用户继续 |
| 保存 | 应用私有目录，完成后发布文件；普通文件原样导出，HLS 导出 ZIP；仅删记录与删除文件分开 |
| 播放 | Media3，在线播放及离线 HLS / 文件；竖屏默认，播放器后台暂停；网页全屏继续沿用原有方向恢复逻辑 |

## 资源上限

- DOM 每轮最多 40 个 video、每 video 12 个版本、200 张图片，遍历开放 Shadow DOM 的元素数有上限。
- 每会话最多 240 个归一内容组、60 个网络候选，其中无明显媒体特征的地址最多验证 12 个。最多 4 个候选处理中，两个 HTTP 验证线程，其余候选有限排队。
- 后台 iframe WebView 同时一个，每轮最多 6 个辅助任务、12 个去重地址、递归深度 2，每任务 10 秒释放。
- 后台页面不拥有外部应用入口，禁止文件访问，需要用户手势才能播放媒体；探测后还会静音、暂停媒体。
- HLS 清单最大 1 MB、最多 5000 个片段；下载以 64 KB 缓冲区流式传输。
- 缩略图单次读取最多约 6 MB，按尺寸采样到不超过 1280px，内存缓存 12 MB；不预解码所有原图。

## 归属与来源边界

shouldInterceptRequest 只观察可见的 URL / 请求头，不代理网页加载、读取所有响应或改写 Cookie 策略。ServiceWorker 只在 Referer 能关联到当前页或已知 iframe 时归属；没有来源的共享请求不猜测所属标签。普通资源 MIME 验证优先 HEAD，不支持 HEAD 时用小范围 GET。并不保证发现所有无扩展名地址、封闭 Shadow DOM、MSE / blob 内部数据或复杂跨源播放器。

跨源 iframe 辅助探测给播放器自己的页面环境，不绕过同源规则读取其 DOM。网站可能阻止独立加载、要求用户动作、使用已过期签名或改变验证方式，需要回到来源页登录 / 播放 / 重新检测。

每个版本独立保留观察上下文；没有观察到完整 Referer 时，跨源默认使用来源 origin。重定向限制为 6 跳，每跳重新校验 HTTP(S) 与目标 IP；它不是网页可调用的任意原生网络代理。真实站点的授权和媒体分离格式可能需要后续专门适配。

## 界面与未完成项

已实现本轮核心布局：网页及悬浮入口、固定操作按钮的资源面板、视频卡片和版本行、三列图片网格及预览、下载列表、完成分类与播放器。

尚未实现相册分组、封闭影子树解析、专用站点适配器、独立音轨下载合并、单 MP4 重封装、搜索建议独立编辑页、保险箱、投屏、Wi-Fi 限制或自定义下载目录设置。这些不在本轮真实下载闭环中冒充已完成。

通知权限被拒绝仍可启动符合系统规则的前台传输，但通知抽屉可见性受系统控制。导出大资源包时请保持应用在前台，当前没有独立的后台 ZIP 导出服务。

## 官方接口参考

- [WebViewClient 请求观察的能力与边界](https://developer.android.com/reference/android/webkit/WebViewClient)
- [Media3 离线媒体与下载](https://developer.android.com/media/media3/exoplayer/downloading-media)
- [前台服务类型与 dataSync](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android 15 前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [PlayerView 全屏由宿主调整布局](https://developer.android.com/reference/androidx/media3/ui/PlayerView.FullscreenButtonClickListener)
