# 0.3.9 底部导航图标

- 浏览使用地球经纬线，下载中使用闭合圆环与向下箭头，已下载使用勾选文件夹。
- 采用已确认 SVG 转换的 Android VectorDrawable，24×24 网格、1.8px 圆角描边。底栏仅显示图标，保留原点击区域、无障碍名称和页面切换逻辑。
- 选中项使用品牌红色与 56×36dp 胶囊背景，浅色和深色主题分别着色；其他位置的首页、下载、完成图标不受影响。

## 验证（2026-11-11）

- `assembleRelease lintDebug` 构建通过，静态检查无错误。
- 三个 Android 矢量路径重新渲染后与批准的 SVG 比较，边界一致，像素通道平均差小于 0.1/255（抗锯齿容差）。渲染结果位于被忽略的 `app/build/validation-0.3.9/`。
- 正式 APK 版本为 0.3.9，versionCode 为 15，应用 ID 保持 `com.pdownloader.browser`。
- `apksigner verify --print-certs` 通过，证书 SHA-256 为 `4ca6f02c7edb8b181f76c738d1aea59a37f5a6b2620f8df12e92afc48775e3c8`，与此前正式版本一致。
- 本次未运行设备交互测试；改动为导航绘图与版本号，不涉及数据结构或存储迁移。
