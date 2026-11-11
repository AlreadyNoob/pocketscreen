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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent as I2
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

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
    @Volatile private var lastSig = ""
    @Volatile private var watchThread: Thread? = null
    /** The watchdog waits on this, so a change in the running apps can make it re-plan its next wake-up. */
    private val replan = java.util.concurrent.Semaphore(0)
    /** Apps found on the hidden displays by the last scan. The battery receiver reuses it instead of scanning again. */
    @Volatile private var cachedApps: List<String> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watching = false
        watchThread?.interrupt()
        Vd.listeners.remove(changeListener)
        if (batteryRegistered) { try { unregisterReceiver(batteryReceiver) } catch (_: Throwable) {}; batteryRegistered = false }
        super.onDestroy()
    }

    /**
     * Runs a full pass (notification, crashed apps, limits) every 30 s (60 s while the phone's screen
     * is off) as a fallback, and also exactly when an app's time limit runs out. Changes the app makes
     * itself reach the notification straight away through [changeListener], and battery or temperature
     * changes through [batteryReceiver], so the timed pass only has to catch apps that quit on their own.
     */
    private fun startWatchdog() {
        if (watching) return
        watching = true
        val ctx = applicationContext
        val t = Thread {
            val me = Thread.currentThread()
            var lastPass = System.currentTimeMillis()
            while (watching && watchThread === me) {
                replan.drainPermits() // anything signalled before this point is already in cachedApps
                val pm = getSystemService(PowerManager::class.java)
                val base = if (pm != null && !pm.isInteractive) 60_000L else 30_000L
                val due = minOf(lastPass + base, nextTimeLimitAt(ctx, lastPass))
                val wait = due - System.currentTimeMillis()
                if (wait > 0) {
                    try { replan.tryAcquire(wait, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
                    if (!watching || watchThread !== me) break
                    // Woken early because the running apps changed: plan the next wake-up again.
                    if (System.currentTimeMillis() < due) continue
                }
                lastPass = System.currentTimeMillis()
                checkLimits(ctx, fresh = true)
            }
        }
        watchThread = t
        t.start()
    }

    /**
     * When the first not-yet-checked time limit among the running apps runs out (ms since epoch), or
     * Long.MAX_VALUE if none does. Deadlines at or before [checkedAt] were already looked at by a pass, so
     * an app that can't be stopped doesn't keep the watchdog spinning; the regular pass retries it.
     */
    private fun nextTimeLimitAt(ctx: Context, checkedAt: Long): Long {
        var next = Long.MAX_VALUE
        for (pkg in cachedApps) {
            val mins = AppSettings.effectiveMinutes(ctx, pkg)
            val start = Vd.sessionStart(ctx, pkg)
            if (mins <= 0 || start == null) continue
            val at = start + mins * 60_000L + 250L // a moment after the minute is reached
            if (at > checkedAt && at < next) next = at
        }
        return next
    }

    private val changeListener = Runnable { Thread { refreshNotification() }.start() }

    /** Apps currently running on the hidden displays. One task listing per display. */
    private fun scanApps(): List<String> =
        Vd.displays.toList().flatMap { d -> Vd.tasks(d.id).map { it.second } }.distinct()

    private fun statusText(apps: List<String>): String = when (apps.size) {
        0 -> "No apps running"
        1 -> "1 app running in the background"
        else -> "${apps.size} apps running in the background"
    }

    /** Reads the live display list and updates the notification. Runs when the app changes a display. */
    private fun refreshNotification() {
        if (!watching) return
        // Read the live display list first: the cached one can still hold a display that was just
        // removed, and while the service is unreachable nothing counts as running.
        val apps = try {
            if (Vd.service == null) emptyList() else { Vd.refreshInfo(); scanApps() }
        } catch (_: Throwable) { return }
        showApps(apps)
    }

    /** Remembers [apps] and rebuilds the ongoing notification if the set has changed. */
    private fun showApps(apps: List<String>) {
        cachedApps = apps
        replan.release() // the watchdog re-plans around the new list
        val sig = apps.sorted().toString()
        if (sig == lastSig) return
        lastSig = sig
        try { getSystemService(NotificationManager::class.java).notify(1, buildNotification(apps)) } catch (_: Throwable) {}
    }

    private fun buildNotification(apps: List<String>): Notification {
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
            .setContentText(statusText(apps))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop all", stop).build())
            .build()
    }

    private val checking = AtomicBoolean(false)
    @Volatile private var lastCheck = 0L
    private var batteryRegistered = false

    /** Battery level or temperature changed: check the limits right away instead of waiting for the next tick. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (System.currentTimeMillis() - lastCheck < 5_000L) return
            val ctx = applicationContext
            Thread { checkLimits(ctx, fresh = false) }.start()
        }
    }

    /**
     * [fresh] = true scans the displays, refreshes the notification and checks for crashed apps as well
     * (the timed pass). false only checks the limits against the last scan (battery or temperature changed).
     */
    private fun checkLimits(ctx: Context, fresh: Boolean) {
        if (!checking.compareAndSet(false, true)) return
        try {
            lastCheck = System.currentTimeMillis()
            if (fresh) doFullPass(ctx) else {
                val apps = cachedApps
                if (apps.isNotEmpty()) evaluateLimits(ctx, apps)
            }
        } catch (t: Throwable) {
            Debug.write("watchdog: $t")
        } finally {
            checking.set(false)
            lastCheck = System.currentTimeMillis()
        }
    }

    private fun doFullPass(ctx: Context) {
        if (Vd.service == null) { Vd.connect(ctx); showApps(emptyList()); return }
        Vd.refreshInfo()
        // One scan serves the notification, the crash check and the limits.
        val apps = scanApps()
        showApps(apps)
        // Note apps that died on their own, even if their display is already gone. A failure here
        // must not stop the limits below from being checked.
        try {
            for (c in Vd.checkCrashes(ctx, apps.toSet(), emptySet())) notifyCrash(ctx, c)
        } catch (t: Throwable) { Debug.write("crash check: $t") }
        if (Vd.displays.isEmpty()) return
        evaluateLimits(ctx, apps)
    }

    private fun evaluateLimits(ctx: Context, apps: List<String>) {
        val b = ctx.registerReceiver(null, IntentFilter(I2.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        // Reported in tenths of a degree Celsius.
        val tempC: Float? = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }

        // Each app uses its own limits, or the defaults from Settings if it has none. Whatever trips,
        // the result is the same: the app is force-stopped and its display is removed.
        for (pkg in apps) {
            val tempLimit = AppSettings.effectiveTemp(ctx, pkg)
            val batLimit = AppSettings.effectiveBattery(ctx, pkg)
            val maxMin = AppSettings.effectiveMinutes(ctx, pkg)
            val ran = Vd.sessionMinutes(ctx, pkg)
            // kind is what the user sees as the reason, detail has the numbers.
            var kind = ""
            var detail = ""
            val reason = when {
                tempLimit > 0 && tempC != null && tempC >= tempLimit -> {
                    kind = "Temperature"; detail = "Phone reached $tempC°C (limit $tempLimit°C)"
                    "phone overheating, $tempC°C >= $tempLimit°C"
                }
                batLimit > 0 && !charging && pct <= batLimit -> {
                    kind = "Battery"; detail = "Battery fell to $pct% (limit $batLimit%)"
                    "battery $pct% <= $batLimit%"
                }
                maxMin > 0 && ran != null && ran >= maxMin -> {
                    kind = "Timer"; detail = "Time limit of ${Vd.fmtMinutes(maxMin.toLong())} reached"
                    "ran ${ran}m >= ${maxMin}m"
                }
                else -> null
            } ?: continue
            // The scan can be a moment old: don't act on an app that has quit since.
            val pid = Vd.exec("pidof $pkg").trim()
            if (!pid.startsWith("ERR") && pid.firstOrNull()?.isDigit() != true) {
                Debug.write("Auto-stop skipped: $pkg is no longer running ($reason)")
                continue
            }
            Debug.write("Auto-stop: $pkg, $reason")
            Vd.recordAutoStop(ctx, pkg, kind, detail) // before the stop: it clears the session start
            Vd.stopAppAndDisplay(ctx, pkg)
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
        lastSig = ""
        val n = buildNotification(emptyList())
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        Vd.listeners.remove(changeListener)
        Vd.listeners.add(changeListener)
        if (!batteryRegistered) {
            val f = IntentFilter(I2.ACTION_BATTERY_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(batteryReceiver, f)
            batteryRegistered = true
        }
        startWatchdog()
        Thread { refreshNotification() }.start()
        return START_NOT_STICKY
    }
}
