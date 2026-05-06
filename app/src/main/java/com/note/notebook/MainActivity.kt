package com.note.notebook

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
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
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
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
            setPadding(dp(28), dp(80), dp(28), dp(28))
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
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
        root.addView(nativeSettingsButton(), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.RIGHT))

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
        AlertDialog.Builder(this)
            .setTitle("VoceChat 服务器")
            .setView(input)
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
}
