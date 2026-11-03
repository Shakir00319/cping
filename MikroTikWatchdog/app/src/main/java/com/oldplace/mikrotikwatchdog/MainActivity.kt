package com.oldplace.mikrotikwatchdog

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var hostInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { renderRouters(); handler.postDelayed(this, 5000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        buildUi()
        if (SecureConfigStore.load(this).isNotEmpty()) MonitorService.start(this)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(16)); setBackgroundColor(0xFFF4F7F6.toInt()) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = "MikroTik Watchdog"; textSize = 25f; setTextColor(0xFF102A24.toInt()); setTypeface(null, Typeface.BOLD) }
        content.addView(title)
        content.addView(text("Remote multi-router packet-loss monitor", 14f, 0xFF52645F.toInt()), margin(0, 4, 0, 18))

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundColor(0xFFFFFFFF.toInt()) }
        form.addView(text("ADD MIKROTIK", 13f, 0xFF176B55.toInt(), true))
        hostInput = edit("Remote IP / DNS  (default API-SSL port 8729)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        userInput = edit("MikroTik username", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        passInput = edit("MikroTik password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        form.addView(hostInput, margin(0, 12, 0, 0)); form.addView(userInput, margin(0, 8, 0, 0)); form.addView(passInput, margin(0, 8, 0, 0))
        val add = Button(this).apply { text = "ADD ROUTER & START MONITORING"; isAllCaps = false; setOnClickListener { addRouter() } }
        form.addView(add, margin(0, 12, 0, 0))
        content.addView(form, margin(0, 0, 0, 18))

        content.addView(text("YOUR ROUTERS", 13f, 0xFF176B55.toInt(), true), margin(0, 0, 0, 8))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)
        content.addView(text("How monitoring works", 16f, 0xFF102A24.toInt(), true), margin(0, 18, 0, 6))
        content.addView(text("• ICMP ping from this phone to each remote IP every 15 seconds.\n• RouterOS API-SSL login check every 5 minutes.\n• Alerts after 3 consecutive missed pings and when the router recovers.\n• Rolling packet-loss percentage uses the latest 20 ping checks.", 13f, 0xFF52645F.toInt()))
        content.addView(text("Requirements: enable RouterOS API-SSL (TCP 8729 by default) and allow it through your firewall from the phone's public IP. Use a valid TLS certificate trusted by Android. Do not expose API access to the entire internet.", 12f, 0xFF8A4B18.toInt()), margin(0, 10, 0, 12))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderRouters()
    }

    private fun edit(hintText: String, type: Int): EditText = EditText(this).apply {
        hint = hintText; inputType = type; textSize = 14f; singleLine = true
        setPadding(dp(12), dp(9), dp(12), dp(9)); setBackgroundColor(0xFFF0F4F2.toInt())
    }

    private fun addRouter() {
        val host = hostInput.text.toString().trim()
        val username = userInput.text.toString()
        val password = passInput.text.toString()
        if (host.isBlank() || username.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Enter the remote address, username and password.", Toast.LENGTH_LONG).show(); return
        }
        try { HostParser.parse(host) } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Invalid remote address", Toast.LENGTH_LONG).show(); return
        }
        val existing = SecureConfigStore.load(this)
        if (existing.any { it.host.equals(host, true) }) {
            Toast.makeText(this, "This remote address is already added. Remove it first to replace its login.", Toast.LENGTH_LONG).show(); return
        }
        SecureConfigStore.add(this, host, username, password)
        hostInput.text.clear(); userInput.text.clear(); passInput.text.clear()
        MonitorService.start(this)
        renderRouters()
        Toast.makeText(this, "Router added. Monitoring started.", Toast.LENGTH_SHORT).show()
    }

    private fun renderRouters() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        val routers = SecureConfigStore.load(this)
        if (routers.isEmpty()) {
            val empty = TextView(this).apply { text = "No routers added yet."; textSize = 14f; setTextColor(0xFF71817B.toInt()); setPadding(dp(4), dp(12), dp(4), dp(12)) }
            listContainer.addView(empty); return
        }
        routers.forEach { router ->
            val status = StatusStore.get(this, router.id)
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(0xFFFFFFFF.toInt()) }
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val name = TextView(this).apply { text = router.host; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF102A24.toInt()); maxLines = 1 }
            header.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            val online = status?.online == true
            val state = TextView(this).apply {
                text = if (status == null) "WAITING" else if (online) "ONLINE" else "NO PING"
                textSize = 11f; setTypeface(null, Typeface.BOLD)
                setTextColor(if (online) 0xFF16734D.toInt() else 0xFFB34B36.toInt())
            }
            header.addView(state)
            card.addView(header)
            val latency = status?.latencyMs?.let { "$it ms" } ?: "—"
            val details = if (status == null) "Waiting for first check…" else "Latency: $latency    Packet loss: ${status.lossPercent}% (last ${status.sent} checks)"
            card.addView(text(details, 13f, 0xFF52645F.toInt()), margin(0, 7, 0, 0))
            val apiText = when (status?.apiOk) {
                true -> "RouterOS API-SSL: authenticated"
                false -> "RouterOS API-SSL: login / port / TLS check failed"
                null -> "RouterOS API-SSL: checking…"
            }
            card.addView(text(apiText, 12f, if (status?.apiOk == true) 0xFF16734D.toInt() else 0xFF7A6B4A.toInt()), margin(0, 4, 0, 0))
            val remove = Button(this).apply {
                text = "Remove router"; isAllCaps = false; textSize = 12f
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Remove router?")
                        .setMessage("Remove ${router.host} and stop monitoring it?")
                        .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                            SecureConfigStore.remove(this@MainActivity, router.id)
                            StatusStore.clear(this@MainActivity, router.id)
                            if (SecureConfigStore.load(this@MainActivity).isEmpty()) stopService(android.content.Intent(this@MainActivity, MonitorService::class.java))
                            renderRouters()
                        }.show()
                }
            }
            card.addView(remove, margin(0, 6, 0, 0))
            listContainer.addView(card, margin(0, 0, 0, 10))
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun margin(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
