package com.absabefi.wasla

import android.app.Activity
import android.app.NotificationManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : Activity() {
    private var with = ""
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val refresh: () -> Unit = { render() }
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        with = intent.getStringExtra("with") ?: run { finish(); return }
        actionBar?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(DARK))
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFECE5DD.toInt())
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Message"
            background = rounded(Color.WHITE, 22)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 5
        }
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "➤"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = rounded(GREEN, 24)
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    Hub.send(with, t)
                    input.setText("")
                }
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(6) })
        root.addView(bar)
        setContentView(root)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, "🎥").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 0, "📞").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    private fun call(video: Boolean) {
        if (CallManager.busy) {
            CallManager.openScreen(this)
            return
        }
        startActivity(
            android.content.Intent(this, CallActivity::class.java)
                .putExtra("out_peer", with).putExtra("out_video", video)
        )
    }

    override fun onMenuItemSelected(featureId: Int, item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        if (item.itemId == 1) { call(true); return true }
        if (item.itemId == 2) { call(false); return true }
        return super.onMenuItemSelected(featureId, item)
    }

    override fun onResume() {
        super.onResume()
        Hub.openChat = with
        visible = true
        Hub.listeners.add(refresh)
        Hub.history(with)
        getSystemService(NotificationManager::class.java).cancel(1000 + (with.hashCode() and 0xffff))
        render()
    }

    override fun onPause() {
        super.onPause()
        if (Hub.openChat == with) Hub.openChat = null
        visible = false
        Hub.listeners.remove(refresh)
    }

    private fun render() {
        val online = Hub.contacts.firstOrNull { it.user == with }?.online == true
        title = with + if (online) "  🟢" else ""
        list.removeAllViews()
        val msgs = Hub.chats[with] ?: emptyList<Msg>()
        for (m in msgs) {
            val mine = m.from == Hub.me
            val ticks = if (!mine) "" else when (m.state) { 0 -> "  🕓"; 1 -> "  ✓"; else -> "  ✓✓" }
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = rounded(if (mine) 0xFFDCF8C6.toInt() else Color.WHITE, 10)
                addView(TextView(this@ChatActivity).apply {
                    text = m.text
                    textSize = 16f
                    setTextColor(Color.BLACK)
                })
                addView(TextView(this@ChatActivity).apply {
                    text = fmt.format(Date(m.ts)) + ticks
                    textSize = 11f
                    setTextColor(Color.GRAY)
                    gravity = Gravity.END
                })
            }
            val row = LinearLayout(this).apply {
                gravity = if (mine) Gravity.END else Gravity.START
                addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (mine) leftMargin = dp(48) else rightMargin = dp(48)
                })
            }
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        var visible = false
    }
}
