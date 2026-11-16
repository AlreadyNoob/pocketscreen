package com.hiderlab.vd

import android.content.Context

/**
 * Pauses an app's processes while the screen is off and resumes them when it comes back on.
 *
 * The work is done by the Shizuku shell. Two ways are tried, in this order:
 *  1. SIGSTOP / SIGCONT on every process of the app (needs Shizuku running as root: the shell user may
 *     not signal other apps).
 *  2. The cgroup freezer (`cgroup.freeze`), where the phone has one and lets the shell write to it.
 * Each attempt is checked afterwards (process state / cgroup.events), so "frozen" only ever means it
 * really is. If neither works for an app, [lastFailure] says why and nothing is left half-done.
 */
object Freezer {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("freeze", Context.MODE_PRIVATE)

    /** Why the last freeze attempt did not work on this phone, or empty. Shown next to the setting. */
    fun lastFailure(c: Context): String = prefs(c).getString("failure", "") ?: ""

    /** Apps we froze and have not resumed yet. Kept on disk so a crash of this app cannot leave them stuck. */
    private fun frozen(c: Context): Set<String> = prefs(c).getStringSet("frozen", emptySet()) ?: emptySet()
    private fun setFrozen(c: Context, s: Set<String>) { prefs(c).edit().putStringSet("frozen", s).apply() }

    /** (pid, uid) for every process of [pkg], including its `pkg:service` processes. */
    private fun processes(pkg: String): List<Pair<Int, Int>> {
        val out = Vd.exec("ps -A -o PID,NAME")
        if (out.startsWith("ERR")) return emptyList()
        val pids = out.lineSequence().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size == 2 && (p[1] == pkg || p[1].startsWith("$pkg:"))) p[0].toIntOrNull() else null
        }.toList()
        return pids.map { pid ->
            val st = Vd.exec("cat /proc/$pid/status")
            val uid = Regex("Uid:\\s+(\\d+)").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            pid to uid
        }
    }

    private fun stateStopped(pid: Int): Boolean =
        Vd.exec("grep State /proc/$pid/status").contains("stopped", ignoreCase = true)

    private fun cgroupDir(pid: Int, uid: Int) = "/sys/fs/cgroup/uid_$uid/pid_$pid"

    private fun cgroupFrozen(pid: Int, uid: Int): Boolean =
        uid >= 0 && Vd.exec("cat ${cgroupDir(pid, uid)}/cgroup.events").contains("frozen 1")

    /** Returns null when the process was frozen, otherwise the reason it was not. */
    private fun freezeProcess(pid: Int, uid: Int): String? {
        val sig = Vd.exec("kill -STOP $pid 2>&1").trim()
        if (stateStopped(pid)) return null
        val cg = if (uid >= 0) Vd.exec("echo 1 > ${cgroupDir(pid, uid)}/cgroup.freeze 2>&1").trim() else "no uid"
        if (cgroupFrozen(pid, uid)) return null
        return "kill: ${sig.ifEmpty { "no effect" }}; cgroup: ${cg.ifEmpty { "no effect" }}"
    }

    private fun thawProcess(pid: Int, uid: Int) {
        Vd.exec("kill -CONT $pid 2>&1")
        if (uid >= 0) Vd.exec("echo 0 > ${cgroupDir(pid, uid)}/cgroup.freeze 2>&1")
    }

    /** Called when the screen goes off: freezes every running app that has the option on. */
    @Synchronized
    fun onScreenOff(c: Context, apps: List<String>) {
        val done = frozen(c).toMutableSet()
        for (pkg in apps) {
            if (!AppSettings.freezeWhenOff(c, pkg) || pkg in done) continue
            val procs = processes(pkg)
            if (procs.isEmpty()) continue
            val errors = procs.mapNotNull { (pid, uid) -> freezeProcess(pid, uid) }
            if (errors.size == procs.size) {
                // Nothing could be frozen: this phone / Shizuku mode does not allow it.
                val why = errors.first()
                prefs(c).edit().putString("failure", why).apply()
                Debug.write("freeze $pkg failed: $why")
            } else {
                prefs(c).edit().remove("failure").apply()
                done += pkg
                Debug.write("froze $pkg (${procs.size - errors.size}/${procs.size} processes)")
                if (errors.isNotEmpty()) Debug.write("freeze $pkg partly failed: ${errors.first()}")
            }
        }
        setFrozen(c, done)
    }

    /** Called when the screen is back on (and whenever this app starts): resumes everything we froze. */
    @Synchronized
    fun onScreenOn(c: Context) {
        val done = frozen(c)
        if (done.isEmpty()) return
        val left = done.toMutableSet()
        for (pkg in done) {
            // Only processes that are still the app's (a dead pid may have been reused).
            val procs = processes(pkg)
            procs.forEach { (pid, uid) -> thawProcess(pid, uid) }
            if (procs.isEmpty() || procs.none { stateStopped(it.first) }) left -= pkg
            Debug.write("resumed $pkg")
        }
        setFrozen(c, left)
    }

    /** Resume one app (it is being stopped or its option was switched off). */
    @Synchronized
    fun forget(c: Context, pkg: String) {
        val s = frozen(c)
        if (pkg !in s) return
        processes(pkg).forEach { (pid, uid) -> thawProcess(pid, uid) }
        setFrozen(c, s - pkg)
    }
}
