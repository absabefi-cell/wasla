package com.absabefi.wasla

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.util.concurrent.TimeUnit

/**
 * "Fausse camera" pour Wasla 3 : envoie une image de test animee (bandes de couleur
 * qui defilent + carre blanc qui bouge) au lieu de la vraie camera.
 * Marche meme quand l'app est en arriere-plan, donc on peut tester la video
 * entre deux apps sur le meme telephone.
 */
class MireCapturer : VideoCapturer {
    private var observer: CapturerObserver? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var w = 320
    private var h = 240
    private var periodMs = 66L
    private var frame = 0
    @Volatile private var running = false

    override fun initialize(helper: SurfaceTextureHelper?, ctx: Context?, obs: CapturerObserver?) {
        observer = obs
    }

    override fun startCapture(width: Int, height: Int, fps: Int) {
        w = 320; h = 240
        periodMs = 1000L / (if (fps in 1..30) fps else 15)
        val t = HandlerThread("mire").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        running = true
        observer?.onCapturerStarted(true)
        handler?.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { pushFrame() } catch (e: Exception) {}
            handler?.postDelayed(this, periodMs)
        }
    }

    private fun pushFrame() {
        val buf = JavaI420Buffer.allocate(w, h)
        val y = buf.dataY; val u = buf.dataU; val v = buf.dataV
        val sy = buf.strideY; val su = buf.strideU; val sv = buf.strideV
        val shift = frame * 4
        // Carre blanc qui fait des allers-retours
        val box = 60
        val span = w - box
        val pos = (frame * 5) % (2 * span)
        val bx = if (pos < span) pos else 2 * span - pos
        val by = (h - box) / 2
        for (row in 0 until h) {
            for (col in 0 until w) {
                val inBox = col >= bx && col < bx + box && row >= by && row < by + box
                y.put(row * sy + col, (if (inBox) 235 else 60 + ((col + shift) % w) * 120 / w).toByte())
            }
        }
        val cw = (w + 1) / 2; val ch = (h + 1) / 2
        for (row in 0 until ch) {
            for (col in 0 until cw) {
                val band = (((col * 2 + shift) % w) * 6 / w)
                val inBox = col * 2 >= bx && col * 2 < bx + box && row * 2 >= by && row * 2 < by + box
                val uu = if (inBox) 128 else intArrayOf(90, 200, 60, 160, 230, 128)[band]
                val vv = if (inBox) 128 else intArrayOf(240, 90, 60, 200, 130, 30)[band]
                u.put(row * su + col, uu.toByte())
                v.put(row * sv + col, vv.toByte())
            }
        }
        frame++
        val ts = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())
        val vf = VideoFrame(buf, 0, ts)
        observer?.onFrameCaptured(vf)
        vf.release()
    }

    override fun stopCapture() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, fps: Int) {}
    override fun dispose() { running = false }
    override fun isScreencast(): Boolean = false
}
