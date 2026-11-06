# 0.2.2 图标替换验证

日期：2026-11-06（Asia/Shanghai）。

## 改动

- 按用户确认的设计制作矢量图标：黑底、倾斜的红色空心方框、白色 P，下端连接下载箭头。
- 设计参考原图保存于 [icon-approved.png](branding/icon-approved.png)。应用资源为独立矢量路径，去除展示稿外侧白边；自适应前景预留系统遮罩与动画空间。
- 应用与圆形图标统一引用 `mipmap-anydpi-v26/ic_launcher.xml`，由黑色背景与透明矢量前景组成；最低支持 API 26。
- 版本为 0.2.2 / versionCode 4。

## 验证

- `:app:assembleDebug :app:lintDebug` 退出码 0；Lint 为 0 errors、8 warnings、1 hint，未新增图标资源错误。
- 使用 aapt2 检查 APK：应用图标引用新的自适应资源，前景 / 背景引用完整，版本为 0.2.2。
- `adb -s emulator-5554 install -r` 返回 Success；目标 AVD 为 pdownloader-validation，安装后 dumpsys package 确认为 versionCode=4 / versionName=0.2.2。
- 使用覆盖安装，未卸载或清除应用数据；未操作模拟器窗口或注入界面输入。桌面图标的实际显示可由用户返回桌面查看。
- 本次仅修改图标、图标引用与版本信息，未重复运行浏览 / 下载集成测试。上一版行为验证见 [0.2.1 验证记录](VALIDATION-0.2.1.md)。
- Debug APK SHA-256：`70062f7ae77eca2745d87c5d858873dc4666065fdfd1089481ffd1c43b18ac05`。
