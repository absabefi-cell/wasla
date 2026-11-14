package com.absabefi.wasla

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Gere un appel audio/video (WebRTC) et la signalisation via le serveur. */
object CallManager {
    enum class State { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE, ENDED }

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null

    var state = State.IDLE
        private set
    var callId = ""
        private set
    var peer = ""
        private set
    var incomingVideo = false
        private set
    var caller = false
        private set
    var status = ""
        private set
    var startedAt = 0L
        private set
    var localVideoOn = false
        private set
    var remoteVideoOn = false
        private set
    var speaker = false
        private set
    var muted = false
        private set

    val listeners = CopyOnWriteArrayList<() -> Unit>()
    var onIncoming: (() -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    val egl: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var capturer: VideoCapturer? = null
    private var texHelper: SurfaceTextureHelper? = null
    private var videoTx: RtpTransceiver? = null
    var localVideoTrack: VideoTrack? = null
        private set
    var remoteVideoTrack: VideoTrack? = null
        private set

    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteSet = false
    private var wantVideo = false

    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null

    fun init(c: Context) {
        if (ctx == null) ctx = c.applicationContext
    }

    val busy: Boolean get() = state != State.IDLE && state != State.ENDED

    private fun notifyUi() {
        for (l in listeners) l()
    }

    // ------------------------------------------------------------------ actions

    /** Appel sortant. */
    fun startCall(to: String, video: Boolean) {
        if (busy) return
        reset()
        callId = UUID.randomUUID().toString()
        peer = to
        caller = true
        wantVideo = video
        incomingVideo = video
        state = State.OUTGOING
        status = "Appel..."
        val ok = Hub.sendRaw(JSONObject().put("t", "call").put("to", to).put("callId", callId).put("video", video))
        if (!ok) {
            finish("Pas de connexion au serveur")
            return
        }
        createPeer()
        if (video) setVideo(true, announce = false)
        setSpeaker(video)
        notifyUi()
    }

    /** Repondre a un appel entrant (audio ou video). */
    fun accept(video: Boolean) {
        if (state != State.INCOMING) return
        stopRinging()
        wantVideo = video
        state = State.CONNECTING
        status = "Connexion..."
        createPeer()
        setSpeaker(video)
        Hub.sendRaw(JSONObject().put("t", "accept").put("callId", callId).put("video", video))
        notifyUi()
    }

    fun reject() {
        if (state != State.INCOMING) return
        Hub.sendRaw(JSONObject().put("t", "reject").put("callId", callId))
        finish("Appel refuse")
    }

    fun hangup() {
        if (!busy) return
        Hub.sendRaw(JSONObject().put("t", "hangup").put("callId", callId))
        finish("Appel termine")
    }

    fun toggleMute() {
        muted = !muted
        audioTrack?.setEnabled(!muted)
        notifyUi()
    }

    fun setSpeaker(on: Boolean) {
        speaker = on
        val am = ctx?.getSystemService(AudioManager::class.java) ?: return
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = on
        notifyUi()
    }

    fun switchCamera() {
        (capturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    /** Active/desactive la camera pendant l'appel (passer de l'audio a la video). */
    fun setVideo(on: Boolean, announce: Boolean = true) {
        val f = factory ?: return
        val c = ctx ?: return
        if (on && localVideoTrack == null) {
            try {
                val cap: VideoCapturer = if (BuildConfig.MIRE) MireCapturer() else {
                    val en = Camera2Enumerator(c)
                    val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: return
                    en.createCapturer(name, null) ?: return
                }
                val helper = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
                val src = f.createVideoSource(false)
                cap.initialize(helper, c, src.capturerObserver)
                cap.startCapture(640, 480, 24)
                capturer = cap
                texHelper = helper
                videoSource = src
                localVideoTrack = f.createVideoTrack("v0", src)
            } catch (e: Exception) {
                status = "Camera indisponible"
                notifyUi()
                return
            }
        }
        if (on) {
            videoTx?.sender?.setTrack(localVideoTrack, false)
            if (!speaker) setSpeaker(true)
        } else {
            videoTx?.sender?.setTrack(null, false)
            stopCamera()
        }
        localVideoOn = on
        if (announce) Hub.sendRaw(JSONObject().put("t", "video").put("callId", callId).put("on", on))
        notifyUi()
    }

    // --------------------------------------------------------------- signalisation

    fun onSignal(o: JSONObject) {
        val t = o.optString("t")
        val id = o.optString("callId")
        if (t == "call") {
            if (busy) {
                Hub.sendRaw(JSONObject().put("t", "reject").put("callId", id))
                return
            }
            reset()
            callId = id
            peer = o.optString("from")
            caller = false
            incomingVideo = o.optBoolean("video")
            state = State.INCOMING
            status = if (incomingVideo) "Appel video entrant" else "Appel audio entrant"
            startRinging()
            onIncoming?.invoke()
            notifyUi()
            return
        }
        if (id != callId || !busy) return
        when (t) {
            "ringing" -> {
                status = "Ca sonne..."
                startRingback()
                notifyUi()
            }
            "accepted" -> {
                stopRinging()
                state = State.CONNECTING
                status = "Connexion..."
                notifyUi()
                makeOffer()
            }
            "sdp" -> onRemoteSdp(o.optString("type"), o.optString("sdp"))
            "ice" -> {
                val c = IceCandidate(o.optString("sdpMid"), o.optInt("sdpMLineIndex"), o.optString("candidate"))
                if (remoteSet) pc?.addIceCandidate(c) else pendingIce.add(c)
            }
            "video" -> {
                remoteVideoOn = o.optBoolean("on")
                notifyUi()
            }
            "call_end" -> {
                val msg = when (o.optString("reason")) {
                    "offline" -> "$peer n'est pas connecte. Appel manque enregistre."
                    "busy" -> "$peer est deja en appel"
                    "busy_me" -> "Tu es deja en appel"
                    "refused" -> "Appel refuse"
                    "timeout" -> "Pas de reponse"
                    "lost" -> "Connexion perdue"
                    "cancel" -> "Appel annule"
                    else -> "Appel termine"
                }
                finish(msg)
            }
        }
    }

    // ----------------------------------------------------------------- WebRTC

    private fun ensureFactory() {
        if (factory != null) return
        val c = ctx ?: return
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(c).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(c)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        val out = ArrayList<PeerConnection.IceServer>()
        val a = Hub.iceServers
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val u = o.opt("urls") ?: o.opt("url") ?: continue
            val urls = if (u is org.json.JSONArray) (0 until u.length()).map { u.getString(it) } else listOf(u.toString())
            val b = PeerConnection.IceServer.builder(urls)
            if (o.has("username")) b.setUsername(o.optString("username"))
            if (o.has("credential")) b.setPassword(o.optString("credential"))
            out.add(b.createIceServer())
        }
        if (out.isEmpty()) out.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        return out
    }

    private fun createPeer() {
        ensureFactory()
        val f = factory ?: return
        val cfg = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                main.post { onIceState(s) }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate) {
                main.post {
                    Hub.sendRaw(
                        JSONObject().put("t", "ice").put("callId", callId)
                            .put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
                    )
                }
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
            override fun onTrack(t: RtpTransceiver?) {
                val track = t?.receiver?.track()
                if (track is VideoTrack) main.post {
                    remoteVideoTrack = track
                    notifyUi()
                }
            }
        }) ?: return

        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("a0", audioSource)
        if (caller) {
            pc?.addTransceiver(audioTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("s")))
            videoTx = pc?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("s"))
            )
        }
    }

    private fun onIceState(s: PeerConnection.IceConnectionState?) {
        if (!busy) return
        when (s) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                if (state != State.ACTIVE) {
                    state = State.ACTIVE
                    startedAt = System.currentTimeMillis()
                    ChatService.callMode(ctx!!, true, localVideoOn || wantVideo)
                }
                status = ""
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> status = "Reconnexion..."
            PeerConnection.IceConnectionState.FAILED -> {
                hangup()
                status = "Connexion impossible (reseau)"
            }
            else -> {}
        }
        notifyUi()
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private fun sendSdp(d: SessionDescription) {
        Hub.sendRaw(
            JSONObject().put("t", "sdp").put("callId", callId)
                .put("type", d.type.canonicalForm()).put("sdp", d.description)
        )
    }

    private fun makeOffer() {
        val p = pc ?: return
        p.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                p.setLocalDescription(object : Sdp() {
                    override fun onSetSuccess() { main.post { sendSdp(d) } }
                }, d)
            }
        }, MediaConstraints())
    }

    private fun onRemoteSdp(type: String, sdp: String) {
        val p = pc ?: return
        val isOffer = type == "offer"
        val desc = SessionDescription(if (isOffer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER, sdp)
        p.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                main.post {
                    remoteSet = true
                    for (c in pendingIce) p.addIceCandidate(c)
                    pendingIce.clear()
                    if (isOffer) answer(p)
                }
            }
        }, desc)
    }

    private fun answer(p: PeerConnection) {
        for (t in p.transceivers) {
            when (t.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> {
                    t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                    t.sender.setTrack(audioTrack, false)
                }
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                    videoTx = t
                    val rt = t.receiver.track()
                    if (rt is VideoTrack) remoteVideoTrack = rt
                }
                else -> {}
            }
        }
        if (wantVideo) setVideo(true, announce = false)
        p.createAnswer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                p.setLocalDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        main.post {
                            sendSdp(d)
                            if (wantVideo) Hub.sendRaw(JSONObject().put("t", "video").put("callId", callId).put("on", true))
                        }
                    }
                }, d)
            }
        }, MediaConstraints())
        notifyUi()
    }

    // ------------------------------------------------------- sonnerie / fin

    private fun startRinging() {
        val c = ctx ?: return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(c, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        } catch (e: Exception) {
        }
        try {
            val v = c.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        } catch (e: Exception) {
        }
    }

    private fun startRingback() {
        if (tone != null) return
        try {
            tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        } catch (e: Exception) {
        }
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (e: Exception) {}
        ringtone = null
        try { ctx?.getSystemService(Vibrator::class.java)?.cancel() } catch (e: Exception) {}
        try { tone?.stopTone(); tone?.release() } catch (e: Exception) {}
        tone = null
    }

    private fun stopCamera() {
        try { capturer?.stopCapture() } catch (e: Exception) {}
        try { capturer?.dispose() } catch (e: Exception) {}
        capturer = null
        try { localVideoTrack?.dispose() } catch (e: Exception) {}
        localVideoTrack = null
        try { videoSource?.dispose() } catch (e: Exception) {}
        videoSource = null
        try { texHelper?.dispose() } catch (e: Exception) {}
        texHelper = null
    }

    private fun finish(msg: String) {
        stopRinging()
        val p = pc
        pc = null
        videoTx = null
        remoteVideoTrack = null
        try { p?.close() } catch (e: Exception) {}
        stopCamera()
        try { p?.dispose() } catch (e: Exception) {}
        try { audioTrack?.dispose() } catch (e: Exception) {}
        audioTrack = null
        try { audioSource?.dispose() } catch (e: Exception) {}
        audioSource = null
        ctx?.getSystemService(AudioManager::class.java)?.let {
            @Suppress("DEPRECATION")
            it.isSpeakerphoneOn = false
            it.mode = AudioManager.MODE_NORMAL
        }
        ctx?.let { ChatService.callMode(it, false, false) }
        state = State.ENDED
        status = msg
        localVideoOn = false
        remoteVideoOn = false
        onFinished?.invoke()
        notifyUi()
        main.postDelayed({ if (state == State.ENDED) { state = State.IDLE; notifyUi() } }, 2500)
    }

    private fun reset() {
        pendingIce.clear()
        remoteSet = false
        muted = false
        speaker = false
        localVideoOn = false
        remoteVideoOn = false
        startedAt = 0
        remoteVideoTrack = null
    }

    fun openScreen(c: Context) {
        c.startActivity(Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
