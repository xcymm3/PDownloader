# 底部导航 SVG 预览

此目录仅保存设计稿，尚未替换 Android 应用图标。

- `browse.svg`：罗盘，代表浏览网页。
- `downloading.svg`：环形进度与向下箭头，代表下载中。
- `downloaded.svg`：带勾选的文件夹，代表已下载。
- `preview.svg` / `preview.png`：浅色、深色主题下的三种选中状态预览。实际底栏仅显示图标。

图标使用 24×24 网格、1.8px 圆角描边和 `currentColor`，便于主题着色。

使用 `pip install resvg-py` 安装绘图依赖后，在项目根目录运行 `python docs/design/bottom-navigation/generate_preview.py` 可重新生成预览。脚本使用 Windows 微软雅黑字体。
