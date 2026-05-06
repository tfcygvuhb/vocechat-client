package com.note.notebook

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
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
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val green = Color.rgb(7, 193, 96)
    private val bg = Color.rgb(245, 245, 245)
    private val prefs by lazy { getSharedPreferences("note_voce", MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var server = ""
    private var token = ""
    private var refreshToken = ""
    private var myUid = -1
    private var myName = ""
    private val users = linkedMapOf<Int, String>()
    private val groups = linkedMapOf<Int, String>()
    private val messages = mutableMapOf<String, MutableList<ChatLine>>()
    private lateinit var root: LinearLayout
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = prefs.getString("server", "") ?: ""
        token = prefs.getString("token", "") ?: ""
        refreshToken = prefs.getString("refresh", "") ?: ""
        myUid = prefs.getInt("uid", -1)
        myName = prefs.getString("name", "") ?: ""
        createNotificationChannel()
        handleDeepLink(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        if (token.isBlank()) showLogin() else showHome(); if (token.isNotBlank()) connectEvents()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleDeepLink(intent) }
    override fun onDestroy() { super.onDestroy(); ws?.close(1000, "bye"); scope.cancel() }

    private fun handleDeepLink(i: Intent?) {
        val u = i?.data ?: return
        val s = u.getQueryParameter("server") ?: u.getQueryParameter("s")
        if (!s.isNullOrBlank()) { server = norm(s); prefs.edit().putString("server", server).apply() }
    }

    private fun baseScaffold(t: String) {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        title = TextView(this).apply { text = t; textSize = 20f; setTextColor(Color.WHITE); setBackgroundColor(green); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16),0,dp(16),0) }
        root.addView(title, LinearLayout.LayoutParams(-1, dp(56)))
        setContentView(root)
    }

    private fun showLogin() {
        baseScaffold("note笔记 · VoceChat")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), 0) }
        val serverEt = edit("聊天服务器，如 https://chat.example.com", server)
        val emailEt = edit("邮箱", prefs.getString("email", "") ?: "")
        val passEt = edit("密码", ""); passEt.inputType = 0x00000081
        val nameEt = edit("注册昵称（仅注册时填写）", "")
        box.addView(label("用户可指定自己的 VoceChat 服务端；支持 notechat://?server=... 邀请链接。")); box.addView(serverEt); box.addView(emailEt); box.addView(passEt); box.addView(nameEt)
        box.addView(button("登录", green) { login(serverEt.text.toString(), emailEt.text.toString(), passEt.text.toString()) })
        box.addView(button("注册新用户", Color.rgb(26,173,25)) { register(serverEt.text.toString(), emailEt.text.toString(), passEt.text.toString(), nameEt.text.toString()) })
        root.addView(box)
    }

    private fun showHome() {
        baseScaffold("note笔记")
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8),dp(8),dp(8),dp(8)) }
        bar.addView(button("刷新", green) { loadLists() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(button("邀请", green) { shareInvite() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(button("退出", Color.GRAY) { logout() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(bar)
        root.addView(label("当前：$myName  ·  $server"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1,0,1f))
        scope.launch { loadListsInto(list) }
    }

    private fun showChat(key: String, display: String, isGroup: Boolean, id: Int) {
        baseScaffold(display)
        title.setOnClickListener { showHome() }
        val msgBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10),dp(10),dp(10),dp(10)) }
        fun render() { msgBox.removeAllViews(); (messages[key] ?: mutableListOf()).takeLast(200).forEach { msgBox.addView(bubble(it)) } }
        root.addView(ScrollView(this).apply { addView(msgBox) }, LinearLayout.LayoutParams(-1,0,1f)); render()
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8),dp(6),dp(8),dp(6)); setBackgroundColor(Color.WHITE) }
        val input = edit("输入消息", "")
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(46), 1f))
        inputRow.addView(button("发送", green) { val text=input.text.toString(); if(text.isNotBlank()) sendMsg(isGroup,id,text,key){ input.setText(""); render() } }, LinearLayout.LayoutParams(dp(76), dp(46)))
        root.addView(inputRow)
    }

    private suspend fun loadListsInto(list: LinearLayout) {
        list.removeAllViews(); list.addView(label("联系人"))
        withContext(Dispatchers.IO) { runCatching { loadContacts(); loadInitialEventsOnce() } }
        users.forEach { (uid,n) -> list.addView(row("$n (#$uid)") { showChat("u$uid", n, false, uid) }) }
        list.addView(label("群组 / 频道")); groups.forEach { (gid,n) -> list.addView(row("$n (#$gid)") { showChat("g$gid", n, true, gid) }) }
        if (users.isEmpty() && groups.isEmpty()) list.addView(label("暂无联系人/群组。可等待服务端事件同步，或确认账号已加入群组。"))
    }
    private fun loadLists(){ showHome() }

    private fun login(s: String, email: String, pass: String) = scope.launch {
        server = norm(s); prefs.edit().putString("server",server).putString("email",email).apply()
        val body = JSONObject().put("device","Android").put("device_token","").put("credential", JSONObject().put("email",email).put("password",pass).put("type","password")).toString()
        val r = api("/api/token/login", "POST", body, false, "application/json")
        if (r.code == 200) saveLogin(JSONObject(r.body)); else toast("登录失败：HTTP ${r.code} ${r.body.take(100)}")
    }
    private fun register(s:String,email:String,pass:String,name:String)=scope.launch{
        server=norm(s); val body=JSONObject().put("email",email).put("password",pass).put("name", if(name.isBlank()) email.substringBefore('@') else name).toString()
        val r=api("/api/user/register","POST",body,false,"application/json"); if(r.code==200) saveLogin(JSONObject(r.body)) else toast("注册失败：HTTP ${r.code} ${r.body.take(140)}")
    }
    private fun saveLogin(j:JSONObject){ token=j.optString("token"); refreshToken=j.optString("refresh_token"); val u=j.optJSONObject("user"); myUid=u?.optInt("uid",-1)?:-1; myName=u?.optString("name","")?:("uid$myUid"); prefs.edit().putString("token",token).putString("refresh",refreshToken).putInt("uid",myUid).putString("name",myName).apply(); toast("登录成功"); showHome(); connectEvents() }

    private fun logout(){ prefs.edit().clear().apply(); token=""; ws?.close(1000,"logout"); showLogin() }

    private fun loadContacts(){ val r=apiBlocking("/api/user/contacts","GET",null,true,"application/json"); if(r.code==200){ val arr=JSONArray(r.body); for(i in 0 until arr.length()){ val o=arr.getJSONObject(i); val info=o.optJSONObject("target_info"); val uid=o.optInt("target_uid", info?.optInt("uid",-1)?:-1); if(uid>0) users[uid]=info?.optString("name","用户$uid") ?: "用户$uid" } } }
    private fun loadInitialEventsOnce(){ val path="/api/user/events?api-key=${enc(token)}"; val r=apiBlocking(path,"GET",null,false,"text/event-stream"); if(r.code in 200..299) r.body.lineSequence().filter{it.startsWith("data:")}.forEach{ handleEvent(it.removePrefix("data:").trim(), false) } }

    private fun connectEvents(){ ws?.close(1000,"reconnect"); val uri=Uri.parse(server); val scheme=if(uri.scheme=="https")"wss" else "ws"; val port=if(uri.port>0) ":${uri.port}" else ""; val url="$scheme://${uri.host}$port/api/user/events_ws?api-key=${enc(token)}"; val req=Request.Builder().url(url).build(); ws=client.newWebSocket(req, object:WebSocketListener(){ override fun onMessage(w:WebSocket,text:String){ runOnUiThread{ handleEvent(text,true) } }; override fun onFailure(w:WebSocket,t:Throwable,r:Response?){ runOnUiThread{ title.text="note笔记 · 已离线" }; Handler(Looper.getMainLooper()).postDelayed({ if(token.isNotBlank()) connectEvents() },5000) } }) }

    private fun handleEvent(raw:String, notify:Boolean){ runCatching{ val j=JSONObject(raw); when(j.optString("type")){ "chat" -> onChat(j,notify); "related_groups" -> { val a=j.optJSONArray("groups")?:JSONArray(); for(i in 0 until a.length()){ val g=a.getJSONObject(i); groups[g.optInt("gid")]=g.optString("name","群组${g.optInt("gid")}") } }; "users_snapshot" -> { val a=j.optJSONArray("users")?:JSONArray(); for(i in 0 until a.length()){ val u=a.getJSONObject(i); users[u.optInt("uid")]=u.optString("name","用户${u.optInt("uid")}") } } } } }
    private fun onChat(j:JSONObject, notify:Boolean){ val from=j.optInt("from_uid"); val target=j.optJSONObject("target")?:JSONObject(); val detail=j.optJSONObject("detail")?:JSONObject(); val content=detail.optString("content", detail.toString()); val gid=target.optInt("gid",-1); val uid=target.optInt("uid",-1); val key= if(gid>0) "g$gid" else "u${if(from==myUid) uid else from}"; messages.getOrPut(key){ mutableListOf() }.add(ChatLine(from==myUid, users[from]?:if(from==myUid)myName else "用户$from", content)); if(notify && from!=myUid) notify("note笔记新消息", "${users[from]?:"用户$from"}: $content") }
    private fun sendMsg(isGroup:Boolean,id:Int,text:String,key:String,done:()->Unit)=scope.launch{ val cid=UUID.randomUUID().toString(); val props=Base64.encodeToString(JSONObject().put("cid",cid).toString().toByteArray(), Base64.NO_WRAP); val path=if(isGroup)"/api/group/$id/send" else "/api/user/$id/send"; val r=api(path,"POST",text,true,"text/plain", mapOf("x-properties" to props, "referer" to server)); if(r.code in 200..299){ messages.getOrPut(key){ mutableListOf() }.add(ChatLine(true,myName,text)); done() } else toast("发送失败 HTTP ${r.code}") }

    private suspend fun api(path:String, method:String, body:String?, auth:Boolean, contentType:String, extra:Map<String,String> = emptyMap())=withContext(Dispatchers.IO){ apiBlocking(path,method,body,auth,contentType,extra) }
    private fun apiBlocking(path:String, method:String, body:String?, auth:Boolean, contentType:String, extra:Map<String,String> = emptyMap()): ApiResp { val b=Request.Builder().url(server+path).header("accept","application/json").header("referer",server); if(auth) b.header("x-api-key",token); extra.forEach{b.header(it.key,it.value)}; val mt=contentType.toMediaType(); val rb=body?.toRequestBody(mt); when(method){"POST"->b.post(rb ?: ByteArray(0).toRequestBody(mt)); "PUT"->b.put(rb ?: ByteArray(0).toRequestBody(mt)); else->b.get()}; client.newCall(b.build()).execute().use{ return ApiResp(it.code, it.body?.string() ?: "") } }

    private fun shareInvite(){ val text="我用 note笔记 邀请你加入聊天：notechat://join?server=${enc(server)}  服务器：$server"; startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"分享邀请")) }
    private fun notify(t:String, m:String){ val n=Notification.Builder(this,"messages").setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle(t).setContentText(m).setAutoCancel(true).build(); (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify((System.currentTimeMillis()%100000).toInt(),n) }
    private fun createNotificationChannel(){ if(Build.VERSION.SDK_INT>=26)(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("messages","note笔记消息",NotificationManager.IMPORTANCE_DEFAULT)) }
    private fun norm(s:String)= if(s.startsWith("http")) s.trim().trimEnd('/') else "https://${s.trim().trimEnd('/')}"
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun edit(h:String,v:String)=EditText(this).apply{hint=h; setText(v); setSingleLine(true); setPadding(dp(12),0,dp(12),0); setBackgroundColor(Color.WHITE)}
    private fun label(s:String)=TextView(this).apply{text=s; textSize=14f; setTextColor(Color.DKGRAY); setPadding(dp(12),dp(10),dp(12),dp(8))}
    private fun button(s:String,c:Int,fn:()->Unit)=Button(this).apply{text=s; setTextColor(Color.WHITE); setBackgroundColor(c); setOnClickListener{hideKeyboard(); fn()}}
    private fun row(s:String,fn:()->Unit)=TextView(this).apply{text=s; textSize=17f; setTextColor(Color.rgb(30,30,30)); setBackgroundColor(Color.WHITE); setPadding(dp(18),dp(16),dp(18),dp(16)); setOnClickListener{fn()}}
    private fun bubble(l:ChatLine)=TextView(this).apply{text="${l.name}: ${l.text}"; textSize=16f; setTextColor(Color.rgb(20,20,20)); setBackgroundColor(if(l.self) Color.rgb(224,255,235) else Color.WHITE); setPadding(dp(12),dp(10),dp(12),dp(10))}
    private fun hideKeyboard(){ (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken,0) }
    data class ApiResp(val code:Int,val body:String); data class ChatLine(val self:Boolean,val name:String,val text:String)
}
