package com.absabefi.wasla

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Garde la connexion ouverte en permanence, meme telephone verrouille. */
class ChatService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private val refresh: () -> Unit = { updateStatus() }
    private var lastConnected: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("service", "Connexion", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel("msgs", "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("calls2", "Appels entrants", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel("missed", "Appels manques", NotificationManager.IMPORTANCE_HIGH))
        CallManager.init(this)
        goForeground()

        CallManager.onIncoming = { showIncoming() }
        CallManager.onFinished = { nm.cancel(CALL_NOTIF) }
        Hub.onMissed = { c -> showMissed(c) }

        Hub.onIncoming = { m -> if (Hub.openChat != m.from) notifyMsg(m) }
        Hub.listeners.add(refresh)

        // Wi-Fi <-> 4G : reconnexion immediate
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.postDelayed({ Hub.kick() }, 1500)
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCb = cb } catch (e: Exception) {}

        Hub.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "REJECT" -> {
                CallManager.reject()
                getSystemService(NotificationManager::class.java).cancel(CALL_NOTIF)
            }
            "CALLMODE" -> {
                inCall = intent.getBooleanExtra("on", false)
                inCallVideo = intent.getBooleanExtra("video", false)
            }
        }
        goForeground()
        if (!Hub.hasToken()) {
            stopSelf()
            return START_NOT_STICKY
        }
        Hub.start()
        return START_STICKY
    }

    override fun onDestroy() {
        Hub.listeners.remove(refresh)
        netCb?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (e: Exception) {} }
        super.onDestroy()
    }

    private fun openApp(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun statusNotif(): Notification {
        val txt = if (Hub.connected) "Connecte" + (if (Hub.invisible) " (invisible)" else "") else "Reconnexion..."
        return Notification.Builder(this, "service")
            .setContentTitle("Wasla")
            .setContentText(txt)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()
    }

    private var inCall = false
    private var inCallVideo = false

    private fun goForeground() {
        val n = statusNotif()
        if (Build.VERSION.SDK_INT >= 30) {
            var type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
            if (inCall && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (inCallVideo && checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            try {
                startForeground(1, n, type)
            } catch (e: Exception) {
                try {
                    startForeground(1, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
                } catch (e2: Exception) {
                }
            }
        } else {
            startForeground(1, n)
        }
    }

    private fun callIntent(action: String?, req: Int): PendingIntent {
        val i = Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) i.putExtra("do", action)
        return PendingIntent.getActivity(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showIncoming() {
        val who = CallManager.peer
        val video = CallManager.incomingVideo
        val reject = PendingIntent.getService(
            this, 50, Intent(this, ChatService::class.java).setAction("REJECT"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, "calls2")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(who)
            .setContentText(if (video) "Appel video entrant" else "Appel audio entrant")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(callIntent(null, 40))
            .setFullScreenIntent(callIntent(null, 41), true)
            .addAction(Notification.Action.Builder(null, "Refuser", reject).build())
            .addAction(Notification.Action.Builder(null, "Audio", callIntent("audio", 42)).build())
            .addAction(Notification.Action.Builder(null, "Video", callIntent("video", 43)).build())
        getSystemService(NotificationManager::class.java).notify(CALL_NOTIF, b.build())
        // Telephone deverrouille et app ouverte : afficher l'ecran directement
        try {
            if (MainActivity.visible || ChatActivity.visible) CallManager.openScreen(this)
        } catch (e: Exception) {
        }
    }

    private fun showMissed(c: CallLog) {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 60, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "missed")
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Appel manque")
            .setContentText((if (c.video) "Appel video de " else "Appel de ") + c.from)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        getSystemService(NotificationManager::class.java).notify(2000 + (c.id.hashCode() and 0xffff), n)
    }

    private fun updateStatus() {
        if (lastConnected == Hub.connected) return
        lastConnected = Hub.connected
        if (!Hub.hasToken()) {
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).notify(1, statusNotif())
    }

    private fun notifyMsg(m: Msg) {
        val i = Intent(this, ChatActivity::class.java)
            .putExtra("with", m.from)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, m.from.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "msgs")
            .setContentTitle(m.from)
            .setContentText(m.text)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        getSystemService(NotificationManager::class.java).notify(1000 + (m.from.hashCode() and 0xffff), n)
    }

    companion object {
        const val CALL_NOTIF = 77

        /** Passe le service en mode appel (micro/camera autorises en arriere-plan). */
        fun callMode(c: Context, on: Boolean, video: Boolean) {
            try {
                c.startForegroundService(Intent(c, ChatService::class.java).setAction("CALLMODE").putExtra("on", on).putExtra("video", video))
            } catch (e: Exception) {
            }
        }

        fun start(c: Context) {
            try {
                c.startForegroundService(Intent(c, ChatService::class.java))
            } catch (e: Exception) {
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ChatService::class.java))
        }
    }
}
