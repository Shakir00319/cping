package com.oldplace.mikrotikwatchdog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

class MonitorService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val samples = ConcurrentHashMap<String, ArrayDeque<Long?>>()
    private val lastApiCheck = ConcurrentHashMap<String, Long>()
    private val apiStates = ConcurrentHashMap<String, Boolean>()
    private val lastKnownOnline = ConcurrentHashMap<String, Boolean>()
    private val failures = ConcurrentHashMap<String, Int>()
    private var started = false

    override fun onCreate() { super.onCreate(); createChannels() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            startForeground(1, buildOngoing("Starting router checks…"))
            started = true
            executor.scheduleWithFixedDelay({ runChecks() }, 0, 15, TimeUnit.SECONDS)
        }
        return START_STICKY
    }

    private fun runChecks() {
        try {
            val routers = SecureConfigStore.load(this)
            if (routers.isEmpty()) {
                stopSelf(); return
            }
            var onlineCount = 0
            routers.forEach { router ->
                val endpoint = try { HostParser.parse(router.host) } catch (_: Exception) { return@forEach }
                val ping = PingProbe.ping(endpoint.host)
                val queue = samples.getOrPut(router.id) { ArrayDeque() }
                synchronized(queue) {
                    queue.addLast(if (ping.reachable) ping.latencyMs else null)
                    while (queue.size > 20) queue.removeFirst()
                }
                val values = synchronized(queue) { queue.toList() }
                val lost = values.count { it == null }
                val loss = if (values.isEmpty()) 0 else (lost * 100 / values.size)
                val failCount = if (ping.reachable) 0 else (failures[router.id] ?: 0) + 1
                failures[router.id] = failCount
                val wasOnline = lastKnownOnline[router.id]
                if (failCount >= 3 && wasOnline != false) {
                    lastKnownOnline[router.id] = false
                    sendAlert(router.host, "Router offline", "No ping response for 3 consecutive checks.", router.id.hashCode())
                } else if (ping.reachable && wasOnline == false) {
                    lastKnownOnline[router.id] = true
                    sendAlert(router.host, "Router recovered", "Ping response restored.", router.id.hashCode())
                } else if (wasOnline == null && ping.reachable) lastKnownOnline[router.id] = true
                if (ping.reachable) onlineCount++

                val now = System.currentTimeMillis()
                if (now - (lastApiCheck[router.id] ?: 0L) >= 300_000L) {
                    val apiOk = try { RouterOsApi.checkLogin(endpoint.host, endpoint.port, router.username, router.password) }
                        catch (_: Exception) { false }
                    apiStates[router.id] = apiOk
                    lastApiCheck[router.id] = now
                }
                val latencies = values.filterNotNull()
                val average = if (latencies.isEmpty()) null else latencies.average().toLong()
                StatusStore.put(this, RouterStatus(router.id, ping.reachable, ping.latencyMs ?: average,
                    loss, values.size, lost, apiStates[router.id], lastApiCheck[router.id] ?: 0L,
                    now, failCount))
            }
            val count = routers.size
            getSystemService(NotificationManager::class.java).notify(1, buildOngoing("$onlineCount of $count routers responding · checks every 15 sec"))
        } catch (_: Exception) { }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("monitor", "Monitoring", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("alerts", "Router alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    @Suppress("DEPRECATION")
    private fun buildOngoing(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "monitor") else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("MikroTik Watchdog")
            .setContentText(text).setContentIntent(open).setOngoing(true).build()
    }

    @Suppress("DEPRECATION")
    private fun sendAlert(host: String, title: String, message: String, id: Int) {
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "alerts") else Notification.Builder(this)
        n.setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("$title: $host").setContentText(message).setAutoCancel(true)
        getSystemService(NotificationManager::class.java).notify(id, n.build())
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val i = Intent(context, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && SecureConfigStore.load(context).isNotEmpty()) MonitorService.start(context)
    }
}
