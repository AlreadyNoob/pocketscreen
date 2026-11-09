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
    }

    @Volatile private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watching = false
        super.onDestroy()
    }

    /** Checks the auto-stop limits from Settings every 30 seconds while a display is up. */
    private fun startWatchdog() {
        if (watching) return
        watching = true
        val ctx = applicationContext
        Thread {
            while (watching) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) {}
                if (!watching) break
                try { checkLimits(ctx) } catch (t: Throwable) { Debug.write("watchdog: $t") }
            }
        }.start()
    }

    private fun checkLimits(ctx: android.content.Context) {
        if (Vd.service == null) return
        Vd.refreshInfo()
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

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HostService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("PocketScreen")
            .setContentText("Hidden display is running")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop all", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        startWatchdog()
        return START_NOT_STICKY
    }
}
