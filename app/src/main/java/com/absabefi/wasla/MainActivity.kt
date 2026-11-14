package com.absabefi.wasla

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private var mode = ""
    private val refresh: () -> Unit = { render() }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        actionBar?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(DARK))
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        Hub.listeners.add(refresh)
        CallManager.init(this)
        CallManager.listeners.add(refresh)
        Hub.onLoginError = { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            mode = ""
            render()
        }
        if (Hub.hasToken()) {
            ChatService.start(this)
            Hub.kick()
        }
        mode = ""
        render()
    }

    override fun onPause() {
        super.onPause()
        visible = false
        Hub.listeners.remove(refresh)
        CallManager.listeners.remove(refresh)
        Hub.onLoginError = null
    }

    private fun render() {
        if (Hub.hasToken() && Hub.me != null) {
            if (mode != "contacts") ChatService.start(this)
            mode = "contacts"
            showContacts()
        } else if (mode != "login" || Hub.loggingIn != loginBusy) {
            mode = "login"
            showLogin()
        }
    }

    // ---------------- Connexion ----------------
    private var loginBusy = false
    private var fServer: EditText? = null
    private var fUser: EditText? = null
    private var fPass: EditText? = null

    private fun showLogin() {
        title = "Wasla"
        val keepServer = fServer?.text?.toString() ?: Hub.server ?: ""
        val keepUser = fUser?.text?.toString() ?: ""
        val keepPass = fPass?.text?.toString() ?: ""
        loginBusy = Hub.loggingIn
        root.removeAllViews()

        root.addView(TextView(this).apply {
            text = "Wasla"
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(DARK)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        })

        fServer = field("Adresse du serveur (lien trycloudflare)", keepServer, InputType.TYPE_TEXT_VARIATION_URI)
        fUser = field("Nom d'utilisateur", keepUser, InputType.TYPE_CLASS_TEXT)
        fPass = field("Mot de passe", keepPass, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        root.addView(Button(this).apply {
            text = if (loginBusy) "Connexion..." else "Se connecter"
            isEnabled = !loginBusy
            setTextColor(Color.WHITE)
            background = rounded(GREEN, 10)
            setOnClickListener {
                val s = fServer!!.text.toString().trim()
                val u = fUser!!.text.toString().trim()
                val p = fPass!!.text.toString()
                if (s.isEmpty() || u.isEmpty() || p.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Remplis les 3 champs", Toast.LENGTH_SHORT).show()
                } else {
                    Hub.login(s, u, p)
                }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })

        root.addView(TextView(this).apply {
            text = "Tu te connectes une seule fois. Ensuite l'app reste connectee, meme en veille."
            setPadding(0, dp(16), 0, 0)
            setTextColor(Color.GRAY)
        })
    }

    private fun field(hint: String, value: String, type: Int): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            isSingleLine = true
            inputType = if (type == InputType.TYPE_TEXT_VARIATION_URI) InputType.TYPE_CLASS_TEXT or type else type
            if (type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) {
                transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            }
            setText(value)
        }
        root.addView(e, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        return e
    }

    // ---------------- Contacts ----------------
    private fun showContacts() {
        title = "Wasla : " + (Hub.me ?: "")
        root.removeAllViews()

        root.addView(TextView(this).apply {
            text = if (Hub.connected) "🟢 Connecte au serveur" else "🔄 Reconnexion..."
            setTextColor(if (Hub.connected) GREEN else Color.GRAY)
            setPadding(0, 0, 0, dp(8))
        })

        root.addView(Switch(this).apply {
            text = "Mode invisible (personne ne te voit en ligne)"
            isChecked = Hub.invisible
            setOnCheckedChangeListener { _, on -> if (on != Hub.invisible) Hub.setInvisible(on) }
            setPadding(0, dp(4), 0, dp(12))
        })

        val nm = getSystemService(android.app.NotificationManager::class.java)
        val msgChan = nm.getNotificationChannel("msgs")
        val notifOff = !nm.areNotificationsEnabled() ||
            (msgChan != null && msgChan.importance == android.app.NotificationManager.IMPORTANCE_NONE)
        if (notifOff) {
            root.addView(Button(this).apply {
                text = "⚠️ Activer les notifications"
                setOnClickListener {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    } else {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    }
                }
            })
        }

        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            root.addView(Button(this).apply {
                text = "⚠️ Autoriser l'ecran d'appel plein ecran"
                setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                    }
                }
            })
        }

        if (CallManager.busy) {
            root.addView(Button(this).apply {
                text = "📞 Appel en cours avec " + CallManager.peer + " : revenir"
                setTextColor(Color.WHITE)
                background = rounded(GREEN, 10)
                setOnClickListener { CallManager.openScreen(this@MainActivity) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(8) })
        }

        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            root.addView(Button(this).apply {
                text = "⚠️ Autoriser Wasla en veille (batterie)"
                setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            })
        }

        root.addView(TextView(this).apply {
            text = "Contacts"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        })

        val list = Hub.contacts.sortedWith(compareBy({ !it.online }, { it.user }))
        if (list.isEmpty()) {
            root.addView(TextView(this).apply {
                text = if (Hub.connected) "Aucun autre compte pour l'instant." else "Chargement..."
                setTextColor(Color.GRAY)
            })
        }
        for (c in list) {
            val last = Hub.chats[c.user]?.lastOrNull()
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(0xFFF2F2F2.toInt(), 10)
                addView(TextView(this@MainActivity).apply {
                    text = (if (c.online) "🟢 " else "⚪ ") + c.user
                    textSize = 18f
                })
                if (last != null) addView(TextView(this@MainActivity).apply {
                    text = (if (last.from == Hub.me) "Toi : " else "") + last.text
                    maxLines = 1
                    setTextColor(Color.GRAY)
                })
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, ChatActivity::class.java).putExtra("with", c.user))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }

        val recent = Hub.calls.sortedByDescending { it.ts }.take(15)
        if (recent.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = "Appels recents"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(20), 0, dp(4))
            })
            val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.FRENCH)
            for (c in recent) {
                val out = c.from == Hub.me
                val other = if (out) c.to else c.from
                val missed = !out && c.status == "missed"
                val what = when {
                    missed -> "📵 Manque"
                    c.status == "refused" -> if (out) "↗️ Refuse" else "↙️ Refuse"
                    c.status == "missed" -> "↗️ Sans reponse"
                    else -> (if (out) "↗️ " else "↙️ ") + String.format("%d:%02d", c.dur / 60, c.dur % 60)
                }
                root.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(if (missed && !c.seen) 0xFFFFE5E5.toInt() else 0xFFF2F2F2.toInt(), 10)
                    addView(TextView(this@MainActivity).apply {
                        text = (if (c.video) "🎥 " else "📞 ") + other + "\n" + what + "  ·  " + fmt.format(java.util.Date(c.ts))
                        setTextColor(if (missed) 0xFFD32F2F.toInt() else Color.DKGRAY)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Button(this@MainActivity).apply {
                        text = if (c.video) "🎥" else "📞"
                        setOnClickListener {
                            startActivity(Intent(this@MainActivity, CallActivity::class.java).putExtra("out_peer", other).putExtra("out_video", c.video))
                        }
                    }, LinearLayout.LayoutParams(dp(56), dp(48)))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
            if (Hub.unseenMissed > 0) main.postDelayed({ if (visible) Hub.callsSeen() }, 4000)
        }

        root.addView(Button(this).apply {
            text = "Se deconnecter"
            setOnClickListener {
                Hub.logout()
                ChatService.stop(this@MainActivity)
                mode = ""
                render()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(32) })
        root.visibility = View.VISIBLE
    }

    companion object {
        var visible = false
    }
}
