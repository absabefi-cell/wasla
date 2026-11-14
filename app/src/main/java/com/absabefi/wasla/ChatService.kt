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
        goForeground()

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

    private fun goForeground() {
        val n = statusNotif()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(1, n)
        }
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
