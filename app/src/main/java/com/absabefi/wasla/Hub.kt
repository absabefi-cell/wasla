package com.absabefi.wasla

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class Contact(val user: String, val online: Boolean)

// state : 0 = en attente, 1 = envoye (✓), 2 = recu (✓✓)
data class Msg(val id: String, val from: String, val to: String, val text: String, var ts: Long, var state: Int)

/** Connexion unique au serveur, partagee par le service et les ecrans. */
object Hub {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ctx: Context? = null
    private var ws: WebSocket? = null
    private var retry = 1000L
    private var running = false
    private var pendingLogin: Pair<String, String>? = null
    private val outbox = ArrayList<JSONObject>()

    val listeners = CopyOnWriteArrayList<() -> Unit>()
    var onIncoming: ((Msg) -> Unit)? = null
    var onLoginError: ((String) -> Unit)? = null

    var connected = false
        private set
    var invisible = false
        private set
    var contacts: List<Contact> = emptyList()
        private set
    val chats = HashMap<String, MutableList<Msg>>()
    var openChat: String? = null

    fun init(c: Context) {
        if (ctx == null) ctx = c.applicationContext
    }

    private fun prefs(): SharedPreferences = ctx!!.getSharedPreferences("wasla", Context.MODE_PRIVATE)

    val me: String? get() = prefs().getString("user", null)
    val server: String? get() = prefs().getString("server", null)
    fun hasToken(): Boolean = prefs().getString("token", null) != null
    val loggingIn: Boolean get() = pendingLogin != null

    fun login(serverUrl: String, user: String, pass: String) {
        var s = serverUrl.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        prefs().edit().putString("server", s).apply()
        pendingLogin = user.trim() to pass
        running = true
        ws?.cancel()
        ws = null
        retry = 1000
        connect()
        notifyUi()
    }

    fun logout() {
        prefs().edit().remove("token").remove("user").apply()
        running = false
        ws?.close(1000, null)
        ws = null
        connected = false
        chats.clear()
        contacts = emptyList()
        outbox.clear()
        notifyUi()
    }

    fun start() {
        running = true
        if (ws == null) connect()
    }

    /** Force une reconnexion immediate (ex. retour du reseau). */
    fun kick() {
        if (!running) return
        if (!connected) {
            ws?.cancel()
            ws = null
            retry = 1000
            connect()
        }
    }

    private fun connect() {
        val p = prefs()
        val s = p.getString("server", null) ?: return
        if (p.getString("token", null) == null && pendingLogin == null) return
        val url = s.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
        val req = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            pendingLogin = null
            onLoginError?.invoke("Adresse du serveur invalide")
            notifyUi()
            return
        }
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val pl = pendingLogin
                val o = if (pl != null)
                    JSONObject().put("t", "login").put("user", pl.first).put("pass", pl.second)
                else
                    JSONObject().put("t", "auth").put("token", p.getString("token", ""))
                webSocket.send(o.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { handle(webSocket, text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { dropped(webSocket) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { dropped(webSocket) }
            }
        })
    }

    private fun dropped(w: WebSocket) {
        if (w !== ws) return
        ws = null
        connected = false
        if (pendingLogin != null) {
            pendingLogin = null
            running = false
            onLoginError?.invoke("Serveur injoignable. Verifie l'adresse.")
            notifyUi()
            return
        }
        notifyUi()
        if (running) {
            main.postDelayed({ if (ws == null && running) connect() }, retry)
            retry = minOf(retry * 2, 30000L)
        }
    }

    private fun handle(w: WebSocket, text: String) {
        if (w !== ws) return
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        when (o.optString("t")) {
            "login_ok", "auth_ok" -> {
                val e = prefs().edit()
                if (o.has("token")) e.putString("token", o.getString("token"))
                e.putString("user", o.getString("user")).apply()
                pendingLogin = null
                connected = true
                retry = 1000
                invisible = o.optBoolean("invisible")
                for (m in outbox) w.send(m.toString())
                outbox.clear()
                openChat?.let { history(it) }
                notifyUi()
            }
            "err" -> {
                val msg = o.optString("msg", "Erreur")
                if (o.optBoolean("fatal")) {
                    pendingLogin = null
                    if (hasToken()) prefs().edit().remove("token").apply()
                    running = false
                    ws = null
                    connected = false
                    w.close(1000, null)
                    onLoginError?.invoke(msg)
                    notifyUi()
                }
            }
            "contacts" -> {
                val a = o.getJSONArray("list")
                contacts = (0 until a.length()).map {
                    val c = a.getJSONObject(it)
                    Contact(c.getString("user"), c.optBoolean("online"))
                }
                notifyUi()
            }
            "msg" -> {
                val m = parseMsg(o)
                val isNew = addMsg(m)
                if (isNew && m.from != me) onIncoming?.invoke(m)
                notifyUi()
            }
            "sent" -> {
                findMsg(o.getString("id"))?.let {
                    if (it.state < 1) it.state = 1
                    val ts = o.optLong("ts")
                    if (ts > 0) it.ts = ts
                }
                notifyUi()
            }
            "delivered" -> {
                findMsg(o.getString("id"))?.state = 2
                notifyUi()
            }
            "history" -> {
                val with = o.getString("with")
                val a = o.getJSONArray("msgs")
                val l = (0 until a.length()).map { parseMsg(a.getJSONObject(it)) }.toMutableList()
                chats[with]?.filter { it.state == 0 && l.none { x -> x.id == it.id } }?.let { l.addAll(it) }
                chats[with] = l
                notifyUi()
            }
            "invisible" -> {
                invisible = o.optBoolean("on")
                notifyUi()
            }
        }
    }

    private fun parseMsg(o: JSONObject) = Msg(
        o.getString("id"), o.getString("from"), o.getString("to"), o.getString("text"),
        o.optLong("ts"), if (o.optBoolean("delivered")) 2 else 1
    )

    private fun peer(m: Msg) = if (m.from == me) m.to else m.from

    private fun addMsg(m: Msg): Boolean {
        val l = chats.getOrPut(peer(m)) { mutableListOf() }
        if (l.any { it.id == m.id }) return false
        l.add(m)
        return true
    }

    private fun findMsg(id: String): Msg? {
        for (l in chats.values) for (m in l) if (m.id == id) return m
        return null
    }

    fun send(to: String, text: String) {
        val id = UUID.randomUUID().toString()
        addMsg(Msg(id, me ?: "", to, text, System.currentTimeMillis(), 0))
        val o = JSONObject().put("t", "msg").put("to", to).put("text", text).put("id", id)
        val w = ws
        if (connected && w != null) w.send(o.toString()) else outbox.add(o)
        notifyUi()
    }

    fun history(with: String) {
        if (connected) ws?.send(JSONObject().put("t", "history").put("with", with).toString())
    }

    fun setInvisible(on: Boolean) {
        invisible = on
        ws?.send(JSONObject().put("t", "invisible").put("on", on).toString())
        notifyUi()
    }

    private fun notifyUi() {
        for (l in listeners) l()
    }
}
