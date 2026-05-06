package com.note.notebook

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.text.InputType
import android.util.Base64
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * note笔记 - a lightweight Android client compatible with VoceChat server APIs.
 * API behavior follows the official VoceChat client source:
 * - /api/token/login body: { device, device_token, credential: { email, password, type } }
 * - /api/user/register body: { email, password, name, gender, language, device, device_token }
 * - auth header: x-api-key; event stream also supports ?api-key=
 * - sending text: POST /api/user/{uid}/send or /api/group/{gid}/send with content-type text/plain and x-properties base64({cid})
 */
class MainActivity : Activity() {
    private val green = Color.rgb(7, 193, 96)
    private val blue = Color.rgb(34, 150, 243)
    private val cyan = Color.rgb(0, 188, 212)
    private val pageBg = Color.rgb(244, 246, 248)
    private val textDark = Color.rgb(36, 39, 43)
    private val subText = Color.rgb(118, 126, 139)

    private val prefs by lazy { getSharedPreferences("note_voce", MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val streamClient = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    private var ws: WebSocket? = null
    private var sseJob: Job? = null
    private var reconnectJob: Job? = null
    private var server = ""
    private var token = ""
    private var refreshToken = ""
    private var myUid = -1
    private var myName = ""
    private var remember = true
    private var connectionStatus = "未连接"

    private val users = linkedMapOf<Int, String>()
    private val groups = linkedMapOf<Int, String>()
    private val messages = mutableMapOf<String, MutableList<ChatLine>>()
    private var activeHomeList: LinearLayout? = null
    private var activeChatKey: String? = null
    private var activeChatBox: LinearLayout? = null

    private lateinit var root: LinearLayout
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSession()
        createNotificationChannel()
        handleDeepLink(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        if (token.isBlank()) showLogin() else { showHome(); startRealtime() }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleDeepLink(intent); if (token.isBlank()) showLogin() }
    override fun onDestroy() { ws?.close(1000, "bye"); sseJob?.cancel(); reconnectJob?.cancel(); scope.cancel(); super.onDestroy() }

    private fun loadSession() {
        server = prefs.getString("server", "") ?: ""
        token = prefs.getString("token", "") ?: ""
        refreshToken = prefs.getString("refresh", "") ?: ""
        myUid = prefs.getInt("uid", -1)
        myName = prefs.getString("name", "") ?: ""
        remember = prefs.getBoolean("remember", true)
    }

    private fun handleDeepLink(i: Intent?) {
        val u = i?.data ?: return
        val s = u.getQueryParameter("server") ?: u.getQueryParameter("s")
        if (!s.isNullOrBlank()) { server = normalizeServer(s); prefs.edit().putString("server", server).apply() }
    }

    // ---------- Screens ----------
    private fun baseScaffold(t: String, light: Boolean = false) {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pageBg) }
        title = TextView(this).apply {
            text = t; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (light) textDark else Color.WHITE)
            setBackgroundColor(if (light) Color.WHITE else green)
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16),0,dp(16),0)
        }
        setContentView(root)
    }

    private fun showLogin() {
        activeHomeList = null; activeChatKey = null
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = gradientBg() }
        setContentView(root)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(34), dp(28), 0) }
        root.addView(outer, LinearLayout.LayoutParams(-1, -1))
        outer.addView(circleBack())
        val host = server.ifBlank { "your VoceChat" }.replace("https://", "").replace("http://", "").trimEnd('/')
        outer.addView(TextView(this).apply {
            text = "Login to ${host.substringBefore(':').substringBefore('/').take(18).ifBlank { "VoceChat" }}"
            textSize = 32f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cyan); setPadding(0, dp(28), 0, 0)
        })
        outer.addView(TextView(this).apply { text = server.ifBlank { "请填写你的聊天服务器" }; textSize = 18f; setTextColor(subText); setPadding(0, 0, 0, dp(52)) })

        val serverEt = loginEdit("Server URL", server.ifBlank { prefs.getString("last_server", "") ?: "" }, false)
        val emailEt = loginEdit("Email", prefs.getString("email", "") ?: "", false)
        val passWrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = round(Color.WHITE, 14f); setPadding(dp(12),0,dp(8),0) }
        val passEt = EditText(this).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; textSize = 18f; setSingleLine(true); background = null; setPadding(0,0,0,0) }
        val eye = TextView(this).apply {
            text = "👁"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(blue)
            setOnClickListener {
                val visible = (passEt.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) != 0
                passEt.inputType = if (visible) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                }
                passEt.setSelection(passEt.text.length)
            }
        }
        passWrap.addView(passEt, LinearLayout.LayoutParams(0, dp(64), 1f)); passWrap.addView(eye, LinearLayout.LayoutParams(dp(54), dp(64)))

        outer.addView(fieldLabel("Server")); outer.addView(serverEt, LinearLayout.LayoutParams(-1, dp(64)))
        outer.addView(fieldLabel("Email")); outer.addView(emailEt, LinearLayout.LayoutParams(-1, dp(64)))
        outer.addView(fieldLabel("Password")); outer.addView(passWrap, LinearLayout.LayoutParams(-1, dp(64)))

        val rememberRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(20), 0, dp(20)) }
        rememberRow.addView(TextView(this).apply { text = "Remember me"; textSize = 18f; setTextColor(textDark) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        val sw = Switch(this).apply { isChecked = remember; setOnCheckedChangeListener { _, b -> remember = b; prefs.edit().putBoolean("remember", b).apply() } }
        rememberRow.addView(sw); outer.addView(rememberRow)

        val loginBtn = bigButton("Log In", blue)
        loginBtn.setOnClickListener { login(serverEt.text.toString(), emailEt.text.toString(), passEt.text.toString(), loginBtn) }
        outer.addView(loginBtn, LinearLayout.LayoutParams(-1, dp(62)))
        val signup = TextView(this).apply {
            text = "Don't have an account?  Sign Up"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(subText); setPadding(0, dp(14), 0, 0)
            setOnClickListener { showSignup(serverEt.text.toString(), emailEt.text.toString()) }
        }
        outer.addView(signup)
    }

    private fun showSignup(serverHint: String = server, emailHint: String = "") {
        showLogin()
        val dialog = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val s = loginEdit("Server", serverHint, false)
        val e = loginEdit("Email", emailHint, false)
        val p = loginEdit("Password", "", true)
        val n = loginEdit("Name", "", false)
        dialog.addView(fieldLabel("Server")); dialog.addView(s)
        dialog.addView(fieldLabel("Email")); dialog.addView(e)
        dialog.addView(fieldLabel("Password")); dialog.addView(p)
        dialog.addView(fieldLabel("Display name")); dialog.addView(n)
        AlertDialog.Builder(this).setTitle("注册 note笔记账号").setView(dialog)
            .setNegativeButton("取消", null)
            .setPositiveButton("注册") { _, _ -> register(s.text.toString(), e.text.toString(), p.text.toString(), n.text.toString()) }
            .show()
    }

    private fun showHome() {
        activeChatKey = null
        baseScaffold("note笔记", light = true)
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(16), dp(10), dp(16), dp(12)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = "Chats"; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(textDark) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(iconText("＋") { toast("群组创建将在下一版补齐；当前可同步已有 VoceChat 群组") })
        row.addView(iconText("⋯") { shareInvite() })
        top.addView(row)
        top.addView(TextView(this).apply { text = "$myName · $connectionStatus · $server"; textSize = 13f; setTextColor(subText) })
        root.addView(top)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        actions.addView(pill("刷新") { refreshLists() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        actions.addView(pill("邀请") { shareInvite() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        actions.addView(pill("退出") { logout() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(actions)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(12)) }
        activeHomeList = list
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1,0,1f))
        renderHomeList()
        refreshLists()
    }

    private fun renderHomeList() {
        val list = activeHomeList ?: return
        list.removeAllViews()
        if (users.isEmpty() && groups.isEmpty()) {
            list.addView(emptyState("正在等待 VoceChat 服务端同步联系人/群组。\n如果一直为空，请确认账号已有联系人或已加入群组。"))
            return
        }
        groups.forEach { (gid, name) -> list.addView(chatRow("#", name, "VoceChat 群组 / 频道", "g$gid") { showChat("g$gid", name, true, gid) }) }
        users.filterKeys { it != myUid }.forEach { (uid, name) -> list.addView(chatRow(initial(name), name, "点击开始私聊", "u$uid") { showChat("u$uid", name, false, uid) }) }
    }

    private fun showChat(key: String, display: String, isGroup: Boolean, id: Int) {
        activeChatKey = key
        baseScaffold(display, light = true)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(10),0,dp(10),0) }
        header.addView(TextView(this).apply { text = "‹"; textSize = 36f; setTextColor(subText); gravity = Gravity.CENTER; setOnClickListener { showHome() } }, LinearLayout.LayoutParams(dp(44), dp(58)))
        header.addView(avatar(initial(display), 36), LinearLayout.LayoutParams(dp(44), dp(58)))
        header.addView(TextView(this).apply { text = display; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(textDark); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(58), 1f))
        header.addView(TextView(this).apply { text = "⋯"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(subText) }, LinearLayout.LayoutParams(dp(48), dp(58)))
        root.addView(header)
        val msgBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        activeChatBox = msgBox
        root.addView(ScrollView(this).apply { addView(msgBox) }, LinearLayout.LayoutParams(-1,0,1f))
        renderActiveChat()
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(7), dp(8), dp(7)); setBackgroundColor(Color.WHITE) }
        inputRow.addView(TextView(this).apply { text="▣"; textSize=24f; gravity=Gravity.CENTER; setTextColor(textDark) }, LinearLayout.LayoutParams(dp(42), dp(48)))
        inputRow.addView(TextView(this).apply { text="📁"; textSize=22f; gravity=Gravity.CENTER }, LinearLayout.LayoutParams(dp(42), dp(48)))
        val input = EditText(this).apply { hint = "Message @$display"; background = null; setSingleLine(false); maxLines = 3; textSize = 16f; setPadding(dp(8),0,dp(8),0) }
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        inputRow.addView(TextView(this).apply { text="➤"; textSize=30f; gravity=Gravity.CENTER; setTextColor(textDark); setOnClickListener { val text=input.text.toString().trim(); if(text.isNotEmpty()) sendMessage(isGroup,id,text,key){ input.setText("") } } }, LinearLayout.LayoutParams(dp(54), dp(48)))
        root.addView(inputRow)
    }

    private fun renderActiveChat() {
        val key = activeChatKey ?: return
        val box = activeChatBox ?: return
        box.removeAllViews()
        val list = messages[key].orEmpty()
        if (list.isEmpty()) box.addView(emptyState("暂无消息\n发送一条消息开始聊天"))
        list.takeLast(300).forEach { box.addView(messageView(it)) }
    }

    // ---------- VoceChat API ----------
    private fun login(s: String, email: String, pass: String, btn: Button) = scope.launch {
        val ns = normalizeServer(s)
        if (!validateInput(ns, email, pass)) return@launch
        btn.isEnabled = false; btn.text = "Connecting..."
        try {
            server = ns; prefs.edit().putString("last_server", server).putString("server", server).putString("email", email).apply()
            val body = JSONObject()
                .put("device", "Android")
                .put("device_token", "")
                .put("credential", JSONObject().put("email", email).put("password", pass).put("type", "password"))
                .toString()
            val r = api("/api/token/login", "POST", body, auth = false, contentType = "application/json")
            if (r.code == 200 && r.body.isNotBlank()) saveLogin(JSONObject(r.body)) else toast(humanError("登录失败", r))
        } catch (e: Exception) { toast("登录失败：${e.message ?: "网络错误"}") }
        finally { btn.isEnabled = true; btn.text = "Log In" }
    }

    private fun register(s:String, email:String, pass:String, name:String)=scope.launch{
        val ns = normalizeServer(s)
        if (!validateInput(ns, email, pass)) return@launch
        try {
            server = ns; prefs.edit().putString("server", server).putString("email", email).apply()
            val body = JSONObject()
                .put("email", email).put("password", pass)
                .put("name", name.ifBlank { email.substringBefore('@') })
                .put("gender", 0).put("language", "zh-CN")
                .put("device", "Android").put("device_token", "")
                .toString()
            val r = api("/api/user/register", "POST", body, auth = false, contentType = "application/json")
            if (r.code == 200 && r.body.isNotBlank()) saveLogin(JSONObject(r.body)) else toast(humanError("注册失败", r))
        } catch (e: Exception) { toast("注册失败：${e.message ?: "网络错误"}") }
    }

    private fun saveLogin(j:JSONObject){
        token = j.optString("token"); refreshToken = j.optString("refresh_token")
        val u = j.optJSONObject("user") ?: JSONObject()
        myUid = u.optInt("uid", -1); myName = u.optString("name", "用户$myUid")
        val edit = prefs.edit().putString("server", server).putString("email", prefs.getString("email", ""))
        if (remember) edit.putString("token", token).putString("refresh", refreshToken).putInt("uid", myUid).putString("name", myName) else edit.remove("token").remove("refresh")
        edit.apply()
        connectionStatus = "已登录"
        toast("登录成功")
        showHome(); startRealtime()
    }

    private fun refreshLists() = scope.launch {
        withContext(Dispatchers.IO) { runCatching { loadContactsBlocking() } }
        renderHomeList()
    }

    private fun loadContactsBlocking() {
        val r = apiBlocking("/api/user/contacts", "GET", null, auth = true, contentType = "application/json")
        if (r.code == 401 && renewTokenBlocking()) return loadContactsBlocking()
        if (r.code == 200 && r.body.isNotBlank()) {
            val arr = JSONArray(r.body)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val info = o.optJSONObject("target_info")
                val uid = o.optInt("target_uid", info?.optInt("uid", -1) ?: -1)
                if (uid > 0) users[uid] = info?.optString("name", "用户$uid") ?: "用户$uid"
            }
        }
    }

    private fun startRealtime() {
        if (token.isBlank() || server.isBlank()) return
        ws?.close(1000, "reconnect"); sseJob?.cancel(); reconnectJob?.cancel()
        connectionStatus = "连接中"; if (::title.isInitialized) title.text = "note笔记 · 连接中"
        val uri = Uri.parse(server); val scheme = if (uri.scheme == "https") "wss" else "ws"; val port = if (uri.port > 0) ":${uri.port}" else ""
        val url = "$scheme://${uri.host}$port/api/user/events_ws?api-key=${enc(token)}"
        ws = client.newWebSocket(Request.Builder().url(url).header("x-api-key", token).build(), object: WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { runOnUiThread { connectionStatus = "已连接"; if (::title.isInitialized) title.text = "note笔记"; refreshLists() } }
            override fun onMessage(webSocket: WebSocket, text: String) { runOnUiThread { handleEvent(text, true) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { runOnUiThread { connectionStatus = "离线，使用SSE重连"; startSseFallback() } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { runOnUiThread { scheduleReconnect() } }
        })
    }

    private fun startSseFallback() {
        sseJob?.cancel()
        sseJob = scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("$server/api/user/events?api-key=${enc(token)}").header("x-api-key", token).build()
                streamClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("SSE HTTP ${resp.code}")
                    withContext(Dispatchers.Main) { connectionStatus = "已连接(SSE)"; renderHomeList() }
                    val source = resp.body?.source()
                    if (source != null) {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: continue
                            if (line.startsWith("data:")) {
                                withContext(Dispatchers.Main) { handleEvent(line.removePrefix("data:").trim(), true) }
                            }
                        }
                    }
                }
            } catch (_: Exception) { withContext(Dispatchers.Main) { scheduleReconnect() } }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        connectionStatus = "离线，5秒后重连"; if(activeHomeList!=null) renderHomeList()
        reconnectJob = scope.launch { delay(5000); if (token.isNotBlank()) startRealtime() }
    }

    private fun handleEvent(raw:String, notify:Boolean){
        if (raw.isBlank()) return
        runCatching {
            val j = JSONObject(raw); when(j.optString("type")){
                "chat" -> onChat(j, notify)
                "related_groups" -> { val a = j.optJSONArray("groups") ?: JSONArray(); for(i in 0 until a.length()){ val g=a.getJSONObject(i); groups[g.optInt("gid")] = g.optString("name", "群组${g.optInt("gid")}") }; renderHomeList() }
                "users_snapshot" -> { val a = j.optJSONArray("users") ?: JSONArray(); for(i in 0 until a.length()){ val u=a.getJSONObject(i); users[u.optInt("uid")] = u.optString("name", "用户${u.optInt("uid")}") }; renderHomeList() }
                "ready", "heartbeat" -> { connectionStatus = "已连接" }
            }
        }
    }

    private fun onChat(j:JSONObject, notify:Boolean){
        val from = j.optInt("from_uid"); val target = j.optJSONObject("target") ?: JSONObject(); val detail = j.optJSONObject("detail") ?: JSONObject()
        val normalType = detail.optString("type")
        val content = if (normalType == "normal") detail.optString("content", "") else detail.toString()
        val gid = target.optInt("gid", -1); val targetUid = target.optInt("uid", -1)
        val key = if (gid > 0) "g$gid" else "u${if(from == myUid) targetUid else from}"
        messages.getOrPut(key){ mutableListOf() }.add(ChatLine(from == myUid, users[from] ?: if(from == myUid) myName else "用户$from", content, System.currentTimeMillis()))
        if (activeChatKey == key) renderActiveChat()
        if (notify && from != myUid) notify("note笔记新消息", "${users[from] ?: "用户$from"}: $content")
    }

    private fun sendMessage(isGroup:Boolean, id:Int, text:String, key:String, done:()->Unit)=scope.launch{
        val optimistic = ChatLine(true, myName, text, System.currentTimeMillis(), sending = true)
        messages.getOrPut(key){ mutableListOf() }.add(optimistic); renderActiveChat(); done()
        val ok = withContext(Dispatchers.IO) { sendBlocking(isGroup, id, text) || (renewTokenBlocking() && sendBlocking(isGroup, id, text)) }
        optimistic.sending = false; optimistic.failed = !ok; renderActiveChat()
        if (!ok) toast("发送失败：服务器未接受消息，请检查网络/权限/会话")
    }

    private fun sendBlocking(isGroup:Boolean, id:Int, text:String): Boolean {
        val cid = UUID.randomUUID().toString()
        val props = Base64.encodeToString(JSONObject().put("cid", cid).toString().toByteArray(), Base64.NO_WRAP)
        val path = if(isGroup) "/api/group/$id/send" else "/api/user/$id/send"
        val r = apiBlocking(path, "POST", text, auth = true, contentType = "text/plain", extra = mapOf("x-properties" to props, "referer" to server))
        return r.code in 200..299
    }

    private fun renewTokenBlocking(): Boolean {
        if (token.isBlank() || refreshToken.isBlank()) return false
        return try {
            val body = JSONObject().put("token", token).put("refresh_token", refreshToken).toString()
            val r = apiBlocking("/api/token/renew", "POST", body, false, "application/json")
            if (r.code == 200) {
                val j = JSONObject(r.body); token = j.optString("token", token); refreshToken = j.optString("refresh_token", refreshToken)
                prefs.edit().putString("token", token).putString("refresh", refreshToken).apply(); true
            } else false
        } catch (_: Exception) { false }
    }

    private suspend fun api(path:String, method:String, body:String?, auth:Boolean, contentType:String, extra:Map<String,String> = emptyMap()) = withContext(Dispatchers.IO) { apiBlocking(path, method, body, auth, contentType, extra) }
    private fun apiBlocking(path:String, method:String, body:String?, auth:Boolean, contentType:String, extra:Map<String,String> = emptyMap()): ApiResp {
        if (server.isBlank()) return ApiResp(0, "server is empty")
        val b = Request.Builder().url(server + path).header("accept", "application/json").header("referer", server)
        if (auth) b.header("x-api-key", token).header("api-key", token)
        extra.forEach { b.header(it.key, it.value) }
        val mt = contentType.toMediaType(); val rb = body?.toRequestBody(mt)
        when(method){ "POST" -> b.post(rb ?: ByteArray(0).toRequestBody(mt)); "PUT" -> b.put(rb ?: ByteArray(0).toRequestBody(mt)); "DELETE" -> b.delete(); else -> b.get() }
        client.newCall(b.build()).execute().use { return ApiResp(it.code, it.body?.string() ?: "") }
    }

    // ---------- UI helpers ----------
    private fun validateInput(s:String,email:String,pass:String): Boolean { if(s.isBlank()){toast("请填写聊天服务器");return false}; if(!email.contains("@")){toast("请填写正确邮箱");return false}; if(pass.length<3){toast("请填写密码");return false}; return true }
    private fun humanError(prefix:String, r:ApiResp): String = when(r.code){ 0 -> "$prefix：无法连接服务器"; 401 -> "$prefix：邮箱或密码错误"; 403 -> "$prefix：没有权限或服务器禁止登录"; 404 -> "$prefix：服务器地址不正确或不是 VoceChat"; 409 -> "$prefix：账号已存在或冲突"; 412 -> "$prefix：服务器需要邀请/魔法链接注册"; 423 -> "$prefix：账号被锁定"; else -> "$prefix：HTTP ${r.code} ${r.body.take(120)}" }
    private fun shareInvite(){ val text="我用 note笔记 邀请你加入聊天：notechat://join?server=${enc(server)}  服务器：$server"; startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"分享邀请")) }
    private fun logout(){ prefs.edit().remove("token").remove("refresh").remove("uid").remove("name").apply(); token=""; refreshToken=""; ws?.close(1000,"logout"); sseJob?.cancel(); showLogin() }
    private fun notify(t:String, m:String){ val n = if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,"messages") else Notification.Builder(this); n.setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle(t).setContentText(m).setAutoCancel(true); (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify((System.currentTimeMillis()%100000).toInt(),n.build()) }
    private fun createNotificationChannel(){ if(Build.VERSION.SDK_INT>=26)(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("messages","note笔记消息",NotificationManager.IMPORTANCE_DEFAULT)) }
    private fun normalizeServer(s:String): String { val x=s.trim().trimEnd('/'); return if(x.startsWith("http://")||x.startsWith("https://")) x else "https://$x" }
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun round(color:Int, radius:Float)=GradientDrawable().apply{ setColor(color); cornerRadius=dp(radius.toInt()).toFloat() }
    private fun gradientBg()=GradientDrawable(GradientDrawable.Orientation.TOP_RIGHT_BOTTOM_LEFT, intArrayOf(Color.rgb(35,155,238), Color.rgb(235,238,242), Color.rgb(244,245,247)))
    private fun fieldLabel(s:String)=TextView(this).apply{text=s; textSize=17f; setTextColor(textDark); setPadding(0, dp(18),0,dp(6))}
    private fun loginEdit(h:String,v:String,password:Boolean)=EditText(this).apply{
        hint=h; setText(v); textSize=18f; setSingleLine(true)
        inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or (if (h.contains("Email", ignoreCase = true)) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            } else {
                InputType.TYPE_TEXT_VARIATION_URI
            })
        }
        background=round(Color.WHITE,14f); setPadding(dp(18),0,dp(18),0)
    }
    private fun bigButton(s:String,c:Int)=Button(this).apply{text=s; textSize=18f; setTextColor(Color.WHITE); background=round(c,14f); isAllCaps=false}
    private fun pill(s:String,fn:()->Unit)=TextView(this).apply{text=s; gravity=Gravity.CENTER; textSize=15f; setTextColor(Color.WHITE); background=round(green,22f); setOnClickListener{fn()}}
    private fun iconText(s:String,fn:()->Unit)=TextView(this).apply{text=s; textSize=28f; gravity=Gravity.CENTER; setTextColor(textDark); setOnClickListener{fn()}; setPadding(dp(10),0,dp(10),0)}
    private fun circleBack()=TextView(this).apply{text="‹"; textSize=42f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); background=round(blue,28f); setOnClickListener{ server=""; prefs.edit().remove("server").apply() }}.also{ it.layoutParams=LinearLayout.LayoutParams(dp(56),dp(56)) }
    private fun emptyState(s:String)=TextView(this).apply{text=s; gravity=Gravity.CENTER; textSize=16f; setTextColor(subText); setPadding(dp(24),dp(80),dp(24),dp(24))}
    private fun avatar(txt:String,size:Int)=TextView(this).apply{text=txt; gravity=Gravity.CENTER; textSize=16f; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); background=round(cyan, size/2f)}
    private fun initial(s:String)=s.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "N"
    private fun chatRow(av:String,name:String,last:String,key:String,fn:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(14),dp(10),dp(14),dp(10)); setBackgroundColor(Color.WHITE); addView(avatar(av,48),LinearLayout.LayoutParams(dp(54),dp(54))); val col=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}; col.addView(TextView(context).apply{text=name; textSize=17f; typeface=Typeface.DEFAULT_BOLD; setTextColor(textDark)}); col.addView(TextView(context).apply{text=messages[key]?.lastOrNull()?.text ?: last; textSize=14f; setTextColor(subText); maxLines=1}); addView(col,LinearLayout.LayoutParams(0,dp(58),1f)); setOnClickListener{fn()} }
    private fun messageView(l:ChatLine)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL; gravity=if(l.self) Gravity.RIGHT else Gravity.LEFT; setPadding(0,dp(5),0,dp(5)); val col=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL; setPadding(dp(8),0,dp(8),0)}; col.addView(TextView(context).apply{text="${l.name}  ${SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(l.time))}${if(l.sending)"  发送中" else if(l.failed)"  失败" else ""}"; textSize=12f; setTextColor(if(l.self) green else cyan)}); col.addView(TextView(context).apply{text=l.text; textSize=16f; setTextColor(textDark); background=round(if(l.self) Color.rgb(220,248,228) else Color.WHITE,10f); setPadding(dp(12),dp(8),dp(12),dp(8)); maxWidth=dp(280)}); addView(col) }
    private fun hideKeyboard(){ if(::root.isInitialized)(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken,0) }

    data class ApiResp(val code:Int,val body:String)
    data class ChatLine(val self:Boolean, val name:String, val text:String, val time:Long, var sending:Boolean=false, var failed:Boolean=false)
}
