package com.note.notebook

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * note笔记 - VoceChat Web Android shell.
 *
 * This version intentionally delegates all chat behavior to the official VoceChat web UI
 * served by the user's own VoceChat server. That keeps message sending, file/image upload,
 * admin policies, invite links, contacts, groups, and future VoceChat protocol changes aligned
 * with the official client while preserving our own package name, signing, icon and launcher.
 */
class MainActivity : Activity() {
    private val green = Color.rgb(7, 193, 96)
    private val textDark = Color.rgb(36, 39, 43)
    private val subText = Color.rgb(118, 126, 139)
    private val prefs: SharedPreferences by lazy { getSharedPreferences("note_web_voce", MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var server: String = ""

    companion object {
        private const val FILE_REQUEST = 9201
        private const val PERMISSION_REQUEST = 9202
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        KeepAliveService.ensureMessageChannel(this)
        runCatching { KeepAliveService.start(this) }
        server = prefs.getString("server", "") ?: ""
        handleDeepLink(intent)
        requestRuntimePermissions()
        if (server.isBlank()) showServerSetup() else showWebApp(server)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
        if (server.isNotBlank()) showWebApp(server)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_REQUEST) {
            val uris = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val s = uri.getQueryParameter("server") ?: uri.getQueryParameter("s")
        if (!s.isNullOrBlank()) saveServer(normalizeServer(s))
    }

    private fun saveServer(value: String) {
        server = normalizeServer(value)
        prefs.edit().putString("server", server).apply()
    }

    private fun showServerSetup() {
        val bg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(80) + statusBarHeight(), dp(28), dp(28) + navigationBarHeight())
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        setContentView(bg)
        bg.addView(TextView(this).apply {
            text = "note笔记"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textDark)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(58)))
        bg.addView(TextView(this).apply {
            text = "VoceChat 网页版 Android 客户端\n由你的服务器提供聊天、联系人、文件、图片和管理员控制"
            textSize = 16f
            setTextColor(subText)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(32))
        }, LinearLayout.LayoutParams(-1, -2))
        val input = EditText(this).apply {
            hint = "https://你的-vocechat-服务器.com"
            setSingleLine(true)
            textSize = 16f
            setTextColor(textDark)
            setHintTextColor(subText)
            setPadding(dp(16), 0, dp(16), 0)
            setText(prefs.getString("last_server", "") ?: "")
        }
        bg.addView(input, LinearLayout.LayoutParams(-1, dp(56)))
        val open = Button(this).apply {
            text = "连接服务器"
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            setBackgroundColor(green)
            setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isBlank()) {
                    toast("请填写 VoceChat 服务器地址")
                } else {
                    prefs.edit().putString("last_server", normalizeServer(value)).apply()
                    saveServer(value)
                    showWebApp(server)
                }
            }
        }
        val lp = LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(22) }
        bg.addView(open, lp)
        bg.addView(TextView(this).apply {
            text = "提示：这版直接加载 VoceChat 官方网页版，排版、消息发送、文件/图片按钮、管理员禁用/邀请/权限等都与服务器网页版保持一致。"
            textSize = 14f
            setTextColor(subText)
            setPadding(0, dp(22), 0, 0)
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebApp(target: String) {
        root = FrameLayout(this)
        setContentView(root)
        webView = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.VISIBLE
        }
        root.setBackgroundColor(Color.WHITE)
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 20) {
                webView.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                webView.clipToPadding = false
                val progressLp = progress.layoutParams as FrameLayout.LayoutParams
                progressLp.topMargin = insets.systemWindowInsetTop
                progress.layoutParams = progressLp
                val gearLp = (root.findViewWithTag<View>("settings_gear")?.layoutParams as? FrameLayout.LayoutParams)
                if (gearLp != null) {
                    gearLp.topMargin = insets.systemWindowInsetTop + dp(6)
                    gearLp.rightMargin = dp(8)
                    root.findViewWithTag<View>("settings_gear").layoutParams = gearLp
                }
            }
            insets
        }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
        root.addView(nativeSettingsButton(), FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = statusBarHeight() + dp(6)
            rightMargin = dp(8)
        })

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString note-vocechat-android/2.1"
        }
        WebView.setWebContentsDebuggingEnabled(false)
        webView.addJavascriptInterface(NativeNotifyBridge(), "NoteAndroid")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return handleExternalUrl(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleExternalUrl(url)

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progress.visibility = View.GONE
                CookieManager.getInstance().flush()
                injectNotificationBridge()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                progress.progress = newProgress
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return openFileChooser(fileChooserParams)
            }
        }
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadFile(url, userAgent, contentDisposition, mimeType)
        })
        loadServer(target)
    }

    private fun loadServer(target: String) {
        val normalized = normalizeServer(target)
        saveServer(normalized)
        if (!isNetworkAvailable()) toast("当前网络可能不可用，仍会尝试打开服务器")
        webView.loadUrl(normalized)
    }

    private fun openFileChooser(params: WebChromeClient.FileChooserParams): Boolean {
        return try {
            val intent = params.createIntent().apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(Intent.createChooser(intent, "选择图片或文件"), FILE_REQUEST)
            true
        } catch (_: ActivityNotFoundException) {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            toast("没有可用的文件选择器")
            false
        }
    }

    private fun downloadFile(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("已开始下载")
        } catch (e: Exception) {
            toast("下载失败：${e.message ?: "无法保存文件"}")
        }
    }

    private fun handleExternalUrl(url: String): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://")) return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun nativeSettingsButton(): TextView = TextView(this).apply {
        tag = "settings_gear"
        text = "⚙"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(128, 7, 193, 96))
        alpha = 0.78f
        setOnClickListener { showSettingsDialog() }
        setOnLongClickListener {
            if (::webView.isInitialized) webView.reload()
            toast("已刷新")
            true
        }
    }

    private fun showSettingsDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(server)
            selectAll()
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
            addView(Button(this@MainActivity).apply {
                text = "开启/检查后台保活"
                isAllCaps = false
                setOnClickListener {
                    runCatching { KeepAliveService.start(this@MainActivity) }
                    openBatteryOptimizationSettings()
                }
            }, LinearLayout.LayoutParams(-1, dp(48)))
            addView(Button(this@MainActivity).apply {
                text = "打开系统通知设置"
                isAllCaps = false
                setOnClickListener { openNotificationSettings() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
            addView(Button(this@MainActivity).apply {
                text = "打开自启动/后台管理设置"
                isAllCaps = false
                setOnClickListener { openAutoStartSettings() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
            addView(TextView(this@MainActivity).apply {
                text = "说明：安卓不允许普通应用被强行划掉或强行停止后仍永久运行。这里会开启前台服务、通知渠道、电池优化和厂商自启动设置；要做到完全可靠的新消息推送，后续需要接入 VoceChat 原生 WebSocket/FCM 推送。"
                textSize = 12f
                setTextColor(subText)
                setPadding(0, dp(8), 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("VoceChat 服务器")
            .setView(wrap)
            .setNegativeButton("取消", null)
            .setNeutralButton("清除登录/服务器") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                prefs.edit().clear().apply()
                server = ""
                showServerSetup()
            }
            .setPositiveButton("打开") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) showWebApp(normalizeServer(value))
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add("服务器设置").setOnMenuItemClickListener { showSettingsDialog(); true }
        menu.add("刷新").setOnMenuItemClickListener { if (::webView.isInitialized) webView.reload(); true }
        menu.add("浏览器打开").setOnMenuItemClickListener { if (server.isNotBlank()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(server))); true }
        return true
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            val permissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 33) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
            if (Build.VERSION.SDK_INT <= 28) {
                permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
            val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    private fun configureSystemBars() {
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.rgb(247, 247, 247)
        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
    }

    private fun injectNotificationBridge() {
        val js = """
            (function(){
              if (window.__noteAndroidNotifyInstalled) return;
              window.__noteAndroidNotifyInstalled = true;
              function send(title, options) {
                try {
                  var body = options && options.body ? String(options.body) : '';
                  window.NoteAndroid.showNotification(String(title || 'note笔记'), body);
                } catch(e) {}
              }
              var NativeNotification = function(title, options) { send(title, options || {}); };
              NativeNotification.permission = 'granted';
              NativeNotification.requestPermission = function(cb) {
                if (cb) cb('granted');
                return Promise.resolve('granted');
              };
              window.Notification = NativeNotification;
              window.dispatchEvent(new Event('note-android-notification-ready'));
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class NativeNotifyBridge {
        @JavascriptInterface
        fun showNotification(title: String?, body: String?) {
            runOnUiThread { showMessageNotification(title ?: "note笔记", body ?: "收到新消息") }
        }
    }

    private fun showMessageNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST)
            return
        }
        val pending = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, KeepAliveService.CHANNEL_MESSAGES)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        startActivity(intent)
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
                return
            }
        } catch (_: Exception) {
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openAutoStartSettings() {
        val candidates = listOf(
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        val opened = candidates.any {
            try {
                startActivity(it)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!opened) toast("请在系统设置里允许 note笔记 自启动、后台运行、通知和锁屏通知")
    }

    private fun normalizeServer(raw: String): String {
        val x = raw.trim().trimEnd('/')
        return if (x.startsWith("http://") || x.startsWith("https://")) x else "https://$x"
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnectedOrConnecting == true
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun statusBarHeight(): Int = resources.getIdentifier("status_bar_height", "dimen", "android")
        .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: 0
    private fun navigationBarHeight(): Int = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: 0
}
