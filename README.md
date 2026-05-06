# note笔记 · VoceChat Android

一个轻量 Android 客户端，包名/命名空间 `com.note.notebook`，用于连接用户自己的 VoceChat 服务端。

## 核心能力

- 自定义 VoceChat 服务器地址
- 邮箱密码登录与基础注册入口
- 兼容 VoceChat REST API：`/api/token/login`、`/api/user/contacts`、`/api/user/{uid}/send`、`/api/group/{gid}/send`
- 兼容 VoceChat WebSocket 事件：`/api/user/events_ws?api-key=...`
- 同步联系人、群组/频道、实时消息
- 邀请分享：`notechat://join?server=...`
- 微信风格绿色主题、小工具/笔记图标、通知频道 `note笔记消息`
- GitHub Actions 打 tag 自动构建并发布 APK 到 Releases

## 签名

CI 支持以下 GitHub Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

如果未配置，会生成临时 CI 证书；正式分发建议配置自己的固定证书。

## 构建

```bash
gradle :app:assembleRelease
```

推送 tag：

```bash
git tag v1.0.0
git push origin v1.0.0
```
