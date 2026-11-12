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
    private val alarmAction = "com.hiderlab.vd.WATCHDOG"
    private var wakeLock: PowerManager.WakeLock? = null
    private var alarmRegistered = false
    /** Recent battery readings, to guess how soon a battery limit could be reached. */
    @Volatile private var lastPct = -1
    @Volatile private var lastPctAt = 0L
    @Volatile private var drainPerHour = 0f
    /** Apps found on the hidden displays by the last scan. The battery receiver reuses it instead of scanning again. */
    @Volatile private var cachedApps: List<String> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watching = false
        stopAlarm()
        Vd.listeners.remove(changeListener)
        if (batteryRegistered) { try { unregisterReceiver(batteryReceiver) } catch (_: Throwable) {}; batteryRegistered = false }
        super.onDestroy()
    }

    /**
     * The checks run from alarms, not from a timer: with the screen off the CPU suspends, and a plain
     * timer (or the battery broadcast) then waits until something else wakes the phone, so the limits
     * would only fire when you picked it up. An alarm wakes the phone, a short wake lock keeps it awake
     * while the pass runs, and the next alarm is set for when something could actually need attention:
     * every 30 s while the screen is on; with it off, as late as the limits allow (see [nextDelay]).
     * A time limit gets an alarm at the moment it runs out. Changes the app makes itself reach the
     * notification straight away through [changeListener].
     */
    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                alarmAction -> runPassWithWakeLock()
                I2.ACTION_SCREEN_ON, I2.ACTION_SCREEN_OFF -> Thread { scheduleNext() }.start()
            }
        }
    }

    private fun startWatchdog() {
        if (watching) return
        watching = true
        val f = IntentFilter(alarmAction).apply {
            addAction(I2.ACTION_SCREEN_ON); addAction(I2.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(alarmReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(alarmReceiver, f)
        alarmRegistered = true
        scheduleNext()
    }

    private fun stopAlarm() {
        try { getSystemService(android.app.AlarmManager::class.java).cancel(alarmIntent()) } catch (_: Throwable) {}
        if (alarmRegistered) { try { unregisterReceiver(alarmReceiver) } catch (_: Throwable) {}; alarmRegistered = false }
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 3, Intent(alarmAction).setPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    @Volatile private var lastPass = System.currentTimeMillis()

    private fun runPassWithWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketScreen:check").also { wakeLock = it }
        try { wl.acquire(60_000L) } catch (_: Throwable) {}
        val ctx = applicationContext
        Thread {
            try {
                lastPass = System.currentTimeMillis()
                Debug.write("check run (screen ${if (pm.isInteractive) "on" else "off"})")
                checkLimits(ctx, fresh = true)
            } finally {
                try { if (wl.isHeld) wl.release() } catch (_: Throwable) {}
                scheduleNext()
            }
        }.start()
    }

    /** Sets the next alarm. Safe to call at any time: it replaces the one already set. */
    private fun scheduleNext() {
        if (!watching) return
        val ctx = applicationContext
        val now = System.currentTimeMillis()
        val due = minOf(lastPass + nextDelay(ctx), nextTimeLimitAt(ctx, lastPass))
        val wait = (due - now).coerceAtLeast(1_000L)
        try {
            getSystemService(android.app.AlarmManager::class.java).setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + wait, alarmIntent()
            )
        } catch (t: Throwable) { Debug.write("alarm: $t") }
    }

    /**
     * How long until the next regular pass. The screen being on is cheap, so 30 s. With it off the
     * phone has to be woken for each pass, so go as long as the limits allow:
     * - a temperature limit can be crossed at any time: every 5 minutes;
     * - a battery limit: half the time it would take to drain down to it at the recent rate, but never
     *   less than 2 or more than 10 minutes; not at all while charging;
     * - otherwise (only the notification and crash check): every 10 minutes.
     */
    private fun nextDelay(ctx: Context): Long {
        val pm = getSystemService(PowerManager::class.java)
        if (pm == null || pm.isInteractive) return 30_000L
        var best = 10 * 60_000L
        var anyTemp = false
        var batEta = Long.MAX_VALUE
        for (pkg in cachedApps) {
            if (AppSettings.effectiveTemp(ctx, pkg) > 0) anyTemp = true
            val lim = AppSettings.effectiveBattery(ctx, pkg)
            if (lim > 0 && lastPct > lim && drainPerHour > 0.05f) {
                val eta = ((lastPct - lim) / drainPerHour * 3_600_000L).toLong()
                if (eta < batEta) batEta = eta
            } else if (lim > 0 && lastPct > lim) batEta = minOf(batEta, 20 * 60_000L) // rate unknown yet
        }
        if (anyTemp) best = minOf(best, 5 * 60_000L)
        if (batEta != Long.MAX_VALUE) best = minOf(best, (batEta / 2).coerceIn(2 * 60_000L, 10 * 60_000L))
        return best
    }

    /** Remembers battery readings, to estimate how fast it is draining (% per hour). */
    private fun noteBattery(pct: Int, charging: Boolean) {
        val now = System.currentTimeMillis()
        if (charging) { lastPct = pct; lastPctAt = now; drainPerHour = 0f; return }
        if (lastPct < 0 || pct > lastPct) { lastPct = pct; lastPctAt = now; return }
        if (pct < lastPct) {
            val hours = (now - lastPctAt) / 3_600_000f
            if (hours > 0.02f) drainPerHour = (lastPct - pct) / hours
            lastPct = pct; lastPctAt = now
        }
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
        Thread { scheduleNext() }.start() // the plan depends on which apps are running
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
        noteBattery(pct, charging)
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
