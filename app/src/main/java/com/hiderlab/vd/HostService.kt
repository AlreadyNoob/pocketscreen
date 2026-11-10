package com.hiderlab.vd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.content.Intent as I2
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder

/** Foreground service: keeps this app's process alive while the hidden display is up. */
class HostService : Service() {

    companion object {
        const val ACTION_STOP_ALL = "com.hiderlab.vd.STOP_ALL"
        private const val CHANNEL = "hidden_display"
        private const val ALERT_CHANNEL = "app_alerts"

        fun cancelCrash(ctx: android.content.Context, pkg: String) {
            try { ctx.getSystemService(NotificationManager::class.java).cancel(pkg.hashCode()) } catch (_: Throwable) {}
        }

        /** Tells the user an app closed unexpectedly. Silently skipped if notifications are not allowed. */
        fun notifyCrash(ctx: android.content.Context, c: Vd.Crash) {
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "App alerts", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(
                    ctx, 2, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val n = Notification.Builder(ctx, ALERT_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("${Apps.label(ctx, c.pkg)} closed unexpectedly")
                    .setContentText("Tap to open PocketScreen and relaunch it.")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                nm.notify(c.pkg.hashCode(), n)
            } catch (_: Throwable) {}
        }
    }

    @Volatile private var watching = false
    @Volatile private var lastCount = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watching = false
        Vd.listeners.remove(changeListener)
        super.onDestroy()
    }

    /** Keeps the notification's app count fresh (every 5 s) and checks the Settings limits (every 30 s). */
    private fun startWatchdog() {
        if (watching) return
        watching = true
        val ctx = applicationContext
        Thread {
            var ticks = 0
            while (watching) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) {}
                if (!watching) break
                ticks++
                if (ticks % 6 == 0) {
                    try { checkLimits(ctx) } catch (t: Throwable) { Debug.write("watchdog: $t") }
                }
                refreshNotification()
            }
        }.start()
    }

    private val changeListener = Runnable { Thread { refreshNotification() }.start() }

    /** Number of distinct apps currently running on the hidden displays. */
    private fun runningApps(): Int =
        Vd.displays.toList().flatMap { d -> Vd.tasks(d.id).map { it.second } }.distinct().size

    private fun statusText(n: Int): String = when (n) {
        0 -> "Hidden display is ready"
        1 -> "1 app running in the background"
        else -> "$n apps running in the background"
    }

    /** Rebuilds the ongoing notification when the number of running apps has changed. */
    private fun refreshNotification() {
        if (!watching || Vd.service == null) return
        val n = try { runningApps() } catch (_: Throwable) { return }
        if (n == lastCount) return
        lastCount = n
        try { getSystemService(NotificationManager::class.java).notify(1, buildNotification(n)) } catch (_: Throwable) {}
    }

    private fun buildNotification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HostService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pocketscreen)
            .setContentTitle("PocketScreen")
            .setContentText(statusText(count))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop all", stop).build())
            .build()
    }

    private fun checkLimits(ctx: android.content.Context) {
        if (Vd.service == null) return
        Vd.refreshInfo()
        // Note apps that died on their own, even if their display is already gone.
        val onDisplays = Vd.displays.flatMap { d -> Vd.tasks(d.id).map { it.second } }.toSet()
        for (c in Vd.checkCrashes(ctx, onDisplays, emptySet())) notifyCrash(ctx, c)
        if (Vd.displays.isEmpty()) return

        val b = ctx.registerReceiver(null, IntentFilter(I2.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100

        // Each app uses its own limits, or the defaults from Settings if it has none.
        for (d in Vd.displays.toList()) {
            for (pkg in Vd.tasks(d.id).map { it.second }.distinct()) {
                val batLimit = AppSettings.effectiveBattery(ctx, pkg)
                if (batLimit > 0 && !charging && pct <= batLimit) {
                    Debug.write("Auto-stop: $pkg, battery $pct% <= $batLimit%")
                    Vd.stopAppAndDisplay(ctx, pkg)
                    continue
                }
                val maxMin = AppSettings.effectiveMinutes(ctx, pkg)
                val m = Vd.sessionMinutes(ctx, pkg) ?: continue
                if (maxMin > 0 && m >= maxMin) {
                    Debug.write("Auto-stop: $pkg ran ${m}m >= ${maxMin}m")
                    Vd.stopAppAndDisplay(ctx, pkg)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            val ctx = applicationContext
            Thread { Vd.stopAll(ctx) }.start()
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hidden display", NotificationManager.IMPORTANCE_LOW))

        // No binder calls on the main thread: show a placeholder now, the background refresh fills in the count.
        lastCount = -1
        val n = buildNotification(0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        Vd.listeners.remove(changeListener)
        Vd.listeners.add(changeListener)
        startWatchdog()
        Thread { refreshNotification() }.start()
        return START_NOT_STICKY
    }
}
