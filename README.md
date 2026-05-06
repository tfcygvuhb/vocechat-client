# note笔记 · VoceChat Web Android

这是 `com.note.notebook` 包名的 VoceChat Android APK。当前版本采用 WebView 方案：直接加载用户自己的 VoceChat 服务器网页版，因此聊天、联系人、群组、图片/文件上传、邀请链接、管理员控制、禁言/权限策略等能力与官方 VoceChat Web 客户端保持一致。

## 功能

- 用户首次启动可填写自己的 VoceChat 服务器地址
- `notechat://join?server=...` 深链可写入服务器地址
- 使用 Android WebView 加载 VoceChat 官方网页端
- 支持网页端登录、发送消息、图片/文件选择上传、下载附件
- 支持服务器管理员控制，因为所有功能都由服务器和官方网页端决定
- 自定义应用名：`note笔记`
- 自定义包名 / namespace：`com.note.notebook`
- 自定义小工具风格图标
- GitHub Actions 打 tag 自动生成 signed release APK

## 签名

CI 支持以下 GitHub Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

未配置时会生成 CI 临时证书；正式分发建议配置固定证书。
