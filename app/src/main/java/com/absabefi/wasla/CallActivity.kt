package com.absabefi.wasla

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Ecran d'appel : sonnerie entrante, appel sortant, appel en cours. */
class CallActivity : Activity() {
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var nameTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var buttons: LinearLayout
    private var remoteAttached: VideoTrack? = null
    private var localAttached: VideoTrack? = null
    private val main = Handler(Looper.getMainLooper())
    private val refresh: () -> Unit = { render() }
    private var pending: (() -> Unit)? = null
    private var lastButtonsKey = ""
    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        CallManager.init(this)
        actionBar?.hide()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(DARK) }
        remoteView = SurfaceViewRenderer(this).apply {
            init(CallManager.egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            visibility = View.GONE
        }
        root.addView(remoteView, FrameLayout.LayoutParams(-1, -1))

        localView = SurfaceViewRenderer(this).apply {
            init(CallManager.egl.eglBaseContext, null)
            setMirror(true)
            setZOrderMediaOverlay(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            visibility = View.GONE
        }
        root.addView(localView, FrameLayout.LayoutParams(dp(110), dp(160)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(40)
            rightMargin = dp(16)
        })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(80), dp(16), 0)
        }
        nameTv = TextView(this).apply {
            textSize = 32f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        statusTv = TextView(this).apply {
            textSize = 17f
            setTextColor(0xFFDDDDDD.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        info.addView(nameTv)
        info.addView(statusTv)
        root.addView(info, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP })

        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(40))
        }
        root.addView(buttons, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM })
        setContentView(root)

        handleIntent()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    private fun handleIntent() {
        val i = intent ?: return
        val outPeer = i.getStringExtra("out_peer")
        val action = i.getStringExtra("do")
        i.removeExtra("out_peer")
        i.removeExtra("do")
        if (outPeer != null) {
            val video = i.getBooleanExtra("out_video", false)
            withPerms(video) { CallManager.startCall(outPeer, video) }
        } else if (action != null) {
            getSystemService(NotificationManager::class.java).cancel(ChatService.CALL_NOTIF)
            when (action) {
                "audio" -> answer(false)
                "video" -> answer(true)
                "reject" -> CallManager.reject()
            }
        }
    }

    private fun answer(video: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) {
            val km = getSystemService(KeyguardManager::class.java)
            if (video && km.isKeyguardLocked) km.requestDismissKeyguard(this, null)
        }
        withPerms(video) { CallManager.accept(video) }
    }

    /** Demande micro (+ camera si video) avant d'agir. */
    private fun withPerms(video: Boolean, action: () -> Unit) {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (video) need.add(Manifest.permission.CAMERA)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
        } else {
            pending = action
            requestPermissions(missing.toTypedArray(), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 7) return
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val p = pending
        pending = null
        if (micOk && p != null) {
            p()
        } else {
            Toast.makeText(this, "Il faut autoriser le micro pour appeler", Toast.LENGTH_LONG).show()
            if (CallManager.state == CallManager.State.INCOMING) CallManager.reject()
            if (!CallManager.busy) finish()
        }
    }

    override fun onResume() {
        super.onResume()
        CallManager.listeners.add(refresh)
        main.post(tick)
        render()
    }

    override fun onPause() {
        super.onPause()
        CallManager.listeners.remove(refresh)
        main.removeCallbacks(tick)
    }

    override fun onDestroy() {
        remoteAttached?.let { try { it.removeSink(remoteView) } catch (e: Exception) {} }
        localAttached?.let { try { it.removeSink(localView) } catch (e: Exception) {} }
        remoteView.release()
        localView.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Retour = laisser l'appel continuer en arriere-plan
        if (CallManager.busy) moveTaskToBack(true) else super.onBackPressed()
    }

    private fun attach(track: VideoTrack?, view: SurfaceViewRenderer, old: VideoTrack?): VideoTrack? {
        if (track === old) return old
        old?.let { try { it.removeSink(view) } catch (e: Exception) {} }
        track?.let { try { it.addSink(view) } catch (e: Exception) {} }
        return track
    }

    private fun updateStatus() {
        val cm = CallManager
        statusTv.text = if (cm.state == CallManager.State.ACTIVE && cm.status.isEmpty()) {
            val s = (System.currentTimeMillis() - cm.startedAt) / 1000
            String.format("%02d:%02d", s / 60, s % 60)
        } else cm.status
    }

    private fun render() {
        val cm = CallManager
        if (cm.state == CallManager.State.IDLE && pending == null) {
            finish()
            return
        }
        nameTv.text = cm.peer
        updateStatus()

        remoteAttached = attach(if (cm.remoteVideoOn) cm.remoteVideoTrack else null, remoteView, remoteAttached)
        localAttached = attach(if (cm.localVideoOn) cm.localVideoTrack else null, localView, localAttached)
        remoteView.visibility = if (cm.remoteVideoOn && remoteAttached != null) View.VISIBLE else View.GONE
        localView.visibility = if (cm.localVideoOn && localAttached != null) View.VISIBLE else View.GONE

        val key = "${cm.state}|${cm.muted}|${cm.speaker}|${cm.localVideoOn}"
        if (key == lastButtonsKey) return
        lastButtonsKey = key
        buttons.removeAllViews()
        when (cm.state) {
            CallManager.State.INCOMING -> {
                round("🎥", "Video", GREEN) { answer(true) }
                round("📞", "Audio", GREEN) { answer(false) }
                round("✖", "Refuser", 0xFFE53935.toInt()) { CallManager.reject() }
            }
            CallManager.State.ENDED -> {
                main.postDelayed({ if (!CallManager.busy) finish() }, 2000)
            }
            else -> {
                round(if (cm.muted) "🔇" else "🎙", if (cm.muted) "Muet" else "Micro", if (cm.muted) Color.WHITE else 0x55FFFFFF) { CallManager.toggleMute() }
                round("🔊", if (cm.speaker) "HP oui" else "HP non", if (cm.speaker) Color.WHITE else 0x55FFFFFF) { CallManager.setSpeaker(!CallManager.speaker) }
                round("🎥", if (cm.localVideoOn) "Camera" else "Video", if (cm.localVideoOn) Color.WHITE else 0x55FFFFFF) {
                    if (CallManager.localVideoOn) CallManager.setVideo(false)
                    else withPerms(true) {
                        CallManager.setVideo(true)
                        ChatService.callMode(this, true, true)
                    }
                }
                if (cm.localVideoOn) round("🔄", "Tourner", 0x55FFFFFF) { CallManager.switchCamera() }
                round("✖", "Raccrocher", 0xFFE53935.toInt()) { CallManager.hangup() }
            }
        }
    }

    private fun round(icon: String, label: String, color: Int, onClick: () -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(Button(this).apply {
            text = icon
            textSize = 22f
            background = rounded(color, 32)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        col.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        buttons.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    }
}
