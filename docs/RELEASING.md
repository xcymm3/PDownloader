# 发布 APK

仓库只包含签名配置读取逻辑；私钥与密码不上传 GitHub。

1. 维护者在本机项目根目录准备被忽略的 `signing.properties`，包含 `storeFile`（绝对路径）、`storePassword`、`keyAlias`、`keyPassword` 四项。
2. 执行 `gradlew.bat :app:assembleRelease`。有配置时产物为 `app/build/outputs/apk/release/app-release.apk`；无配置时只会得到 unsigned APK，不能作为可安装发布附件。
3. 使用 Android SDK 的 apksigner verify 检查签名，以 aapt2 dump badging 检查版本，确认未包含 Debug assets。
4. 完成验证后推送同一提交与版本标签，将签名 APK 和 SHA256SUMS.txt 上传到 GitHub Release。

当前发布密钥保存在维护者本机 `E:/mydoc/dev-tools/signing/PDownloader/release.p12`；密码文件在同目录。请将该目录安全备份，后续正式版本沿用同一密钥，丢失后将无法覆盖升级现有正式安装。

首次正式版本与之前本机调试包的签名不同。模拟器继续覆盖安装 Debug 包以保留既有数据；用户设备从 Debug 转为 Release 时需先自行备份需要保留的下载并卸载 Debug 包。之后的正式版本可直接覆盖升级。
