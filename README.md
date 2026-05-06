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

## v2.1.1 后台与状态栏修复

- 适配 Android 15/16 强制 edge-to-edge：WebView、进度条、悬浮设置按钮会避开状态栏和底部导航栏，不再和手机时间/电量图标重叠。
- 新增 Android 前台保活服务：安装打开后会显示“note笔记正在后台运行”的低优先级常驻通知，用于尽量保持 WebView 进程和网页连接。
- 新增消息通知通道和网页 Notification API 转发：VoceChat 网页在前台/后台仍存活时触发 Web Notification，会转成 Android 状态栏/锁屏通知。
- 设置弹窗新增“后台保活”“系统通知设置”“自启动/后台管理设置”入口，方便在 MIUI/ColorOS/vivo/Huawei 等系统里放行自启动、后台运行、锁屏通知。

限制说明：如果用户在系统最近任务里强行划掉、系统杀进程，或在应用信息里“强行停止”，普通 Android 应用无法保证继续运行；要做到微信/Telegram 那种完全可靠离线推送，需要接入 VoceChat 原生 WebSocket/FCM/厂商推送服务。
