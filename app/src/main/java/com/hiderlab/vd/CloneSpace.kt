package com.hiderlab.vd

import android.content.Context
import android.os.Build

/**
 * The "clone space": a profile of the phone's main user that holds a second copy of an app, with its
 * own empty data, so the copy can be signed in to another account. PocketScreen never creates a user or
 * profile: it uses one the phone already has (the phone maker's "Dual apps", say), after the user agrees.
 * Everything here goes through the
 * shell ([Vd.exec]) because the app can't see other profiles through PackageManager. Calls block, so
 * run them on a background thread.
 *
 * Safety: this only touches the user recorded in the `clones` prefs (key `space`), which is one the user
 * agreed to use (or, from an earlier build, one it created). It never uninstalls without `--user`, and
 * never touches the owner user.
 */
object CloneSpace {

    // ---------------------------------------------------------------- pure parsers

    const val FLAG_PROFILE = 0x1000

    /** One line of `pm list users`. */
    data class User(val id: Int, val name: String, val flags: Int, val running: Boolean) {
        val isProfile: Boolean get() = flags and FLAG_PROFILE != 0
    }

    /** `UserInfo{10:PocketScreen clones:1010} running` -> id 10, name, flags 0x1010, running. */
    fun parseUsers(out: String): List<User> {
        val re = Regex("UserInfo\\{(\\d+):(.*):([0-9a-fA-F]+)\\}(.*)")
        return out.lines().mapNotNull { line ->
            val m = re.find(line) ?: return@mapNotNull null
            val id = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val flags = m.groupValues[3].toLongOrNull(16)?.toInt() ?: 0
            User(id, m.groupValues[2], flags, m.groupValues[4].contains("running"))
        }
    }

    /** `Success: created user id 10` -> 10. */
    fun parseCreatedId(out: String): Int? =
        Regex("created user id (\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()

    /** `pm list packages` output: the package names, one per `package:` line. */
    fun parsePackages(out: String): Set<String> =
        out.lines().mapNotNull { l ->
            val t = l.trim()
            if (t.startsWith("package:")) t.removePrefix("package:").substringBefore(' ').trim().ifEmpty { null } else null
        }.toSet()

    /** A task from `dumpsys activity activities`: [rule] says how [user] was worked out (a, b, c or d). */
    data class TaskLine(val id: Int, val pkg: String, val user: Int, val rule: Char, val raw: String)

    /**
     * The standard tasks on display [displayId], with the Android user each belongs to. The user comes from,
     * in this order: (a) `U=<n>` on the Task line; (b) the `u<n>` in the first ActivityRecord line under
     * the task; (c) the digits after `A=`, which are probably the app's uid (user = uid / 100000);
     * (d) otherwise user 0.
     */
    fun parseTasks(dump: String, displayId: Int): List<TaskLine> {
        val dispRe = Regex("^Display #(\\d+)")
        val taskRe = Regex("^\\s*\\* Task\\{\\w+ #(\\d+) type=standard A=(\\d+):([\\w.]+)")
        val userRe = Regex("\\bU=(\\d+)")
        val recRe = Regex("ActivityRecord\\{\\w+ u(\\d+) ")
        var cur = -1
        var pending = -1 // a task whose line had no U=: the first ActivityRecord under it may still tell
        val res = LinkedHashMap<Int, TaskLine>()
        for (line in dump.lineSequence()) {
            val d = dispRe.find(line)
            if (d != null) { cur = d.groupValues[1].toInt(); pending = -1 }
            else if (line.isNotEmpty() && !line[0].isWhitespace()) { cur = -1; pending = -1 } // recents etc.
            if (cur != displayId) continue
            val m = taskRe.find(line)
            if (m != null) {
                val id = m.groupValues[1].toInt()
                pending = -1
                if (id in res) continue
                val pkg = m.groupValues[3]
                val u = userRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
                val uid = m.groupValues[2].toLongOrNull()
                res[id] = when {
                    u != null -> TaskLine(id, pkg, u, 'a', line)
                    uid != null -> { pending = id; TaskLine(id, pkg, (uid / 100000).toInt(), 'c', line) }
                    else -> { pending = id; TaskLine(id, pkg, 0, 'd', line) }
                }
            } else if (pending >= 0) {
                val r = recRe.find(line)
                if (r != null) {
                    val t = res[pending]
                    val u = r.groupValues[1].toIntOrNull()
                    if (t != null && u != null) res[pending] = t.copy(user = u, rule = 'b')
                    pending = -1
                }
            }
        }
        return res.values.toList()
    }

    /**
     * The entries of `dumpsys activity exit-info <pkg>`, newest first: the user each shows (from `user=`, or
     * from a uid as `uid / 100000`; null when the entry names none) and its reason, such as `CRASH`.
     */
    fun parseExitInfo(out: String): List<Pair<Int?, String>> {
        val reasonRe = Regex("reason=\\d+ \\(([A-Z_ ]+)\\)")
        val userRe = Regex("\\buser=(\\d+)")
        val uidRe = Regex("\\b(?:realUid|packageUid)=(\\d+)")
        val chunks = Regex("(?m)^\\s*ApplicationExitInfo #\\d+").split(out).let { if (it.size > 1) it.drop(1) else it }
        return chunks.mapNotNull { c ->
            val reason = reasonRe.find(c)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val user = userRe.find(c)?.groupValues?.get(1)?.toIntOrNull()
                ?: uidRe.find(c)?.groupValues?.get(1)?.toLongOrNull()?.let { (it / 100000).toInt() }
            user to reason
        }
    }

    /**
     * The display whose section of `dumpsys activity activities` lists [pkg] for [user], or null. The user of a
     * line comes from `U=<n>`, else the `u<n>` of an ActivityRecord, else the uid after `A=`, else user 0.
     */
    fun parseDisplayOf(out: String, pkg: String, user: Int): Int? {
        val dRe = Regex("Display #(\\d+)")
        val uRe = Regex("\\bU=(\\d+)")
        val rRe = Regex("ActivityRecord\\{\\w+ u(\\d+) ")
        val aRe = Regex("\\bA=(\\d+):")
        var cur: Int? = null
        for (line in out.lines()) {
            dRe.find(line)?.let { cur = it.groupValues[1].toInt() }
            val d = cur ?: continue
            if (!line.contains(pkg)) continue
            val u = uRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: rRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: aRe.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let { (it / 100000).toInt() }
                ?: 0
            if (u == user) return d
        }
        return null
    }

    // ---------------------------------------------------------------- processes and their users

    /** One process: the Android user it runs as, its pid and its name. */
    data class Proc(val user: Int, val pid: Int, val name: String)

    /**
     * The user a `ps` UID column belongs to. It is either a number (the uid; user = uid / 100000) or a name:
     * `u10_a123` is user 10, and system names (root, system, shell...) are user 0.
     */
    fun userOfUid(s: String): Int? {
        s.toLongOrNull()?.let { return (it / 100000).toInt() }
        Regex("^u(\\d+)_").find(s)?.let { return it.groupValues[1].toIntOrNull() }
        return if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '_' }) 0 else null
    }

    private val PKG_NAME = Regex("[A-Za-z0-9_.]+")

    /** Only plain package names go into a shell command. */
    fun safePkgs(pkgs: Collection<String>): List<String> = pkgs.filter { PKG_NAME.matches(it) }.distinct()

    /** `ps` for the processes of [pkgs] (and their `pkg:name` helpers when [children]); the header line is kept so a failure shows. */
    fun psCommand(pkgs: Collection<String>, children: Boolean = false): String {
        val names = safePkgs(pkgs).joinToString("|") { it.replace(".", "[.]") }
        return "ps -A -o UID,PID,NAME 2>&1 | grep -E '^ *UID|[ ]($names)${if (children) "(:[^ ]*)?" else ""}\$'"
    }

    /** Parses [psCommand] output. Null when the header is missing, which means `ps` didn't work here. */
    fun parsePs(out: String): List<Proc>? {
        if (out.lines().none { it.trim().startsWith("UID") }) return null
        return out.lines().mapNotNull { line ->
            val t = line.trim().split(Regex("\\s+"), limit = 3)
            if (t.size < 3) return@mapNotNull null
            val user = userOfUid(t[0]) ?: return@mapNotNull null
            Proc(user, t[1].toIntOrNull() ?: return@mapNotNull null, t[2].trim())
        }
    }

    /** The fallback when `ps` doesn't work: `pidof` for each package, then the owner of each pid. */
    fun pidCommand(pkgs: Collection<String>): String =
        "for n in ${safePkgs(pkgs).joinToString(" ")}; do for p in \$(pidof \$n); do echo \"\$n \$p \$(stat -c %u /proc/\$p)\"; done; done"

    /** Parses [pidCommand] output: lines of `name pid uid`. */
    fun parsePidLines(out: String): List<Proc> = out.lines().mapNotNull { line ->
        val t = line.trim().split(' ')
        if (t.size != 3) return@mapNotNull null
        val pid = t[1].toIntOrNull() ?: return@mapNotNull null
        val uid = t[2].toLongOrNull() ?: return@mapNotNull null
        Proc(((uid / 100000).toInt()), pid, t[0])
    }

    /**
     * The processes of [pkgs] with their users, using [run] to reach the shell. Null when neither way works,
     * so a caller can tell "nothing is running" from "couldn't tell".
     */
    fun processes(run: (String) -> String, pkgs: Collection<String>, children: Boolean = false): List<Proc>? {
        val safe = safePkgs(pkgs)
        if (safe.isEmpty()) return emptyList()
        parsePs(run(psCommand(safe, children)))?.let { found ->
            if (found.isNotEmpty()) return found
            // `ps` worked but showed none of them. Either nothing is running, or this phone prints process
            // names differently. `pidof` settles it, so an app is never hidden because of a naming difference.
            val alt = run(pidCommand(safe))
            return if (alt.startsWith("ERR")) found else parsePidLines(alt)
        }
        val out = run(pidCommand(safe))
        return if (out.startsWith("ERR")) null else parsePidLines(out)
    }

    // ---------------------------------------------------------------- the saved record

    private const val ADOPTED = "adopted"

    /** What we saved about the space: `id|name|method|origin`, origin being `adopted`, or `created` for a space an earlier build made. */
    data class Record(val id: Int, val name: String, val method: String, val origin: String) {
        val adopted: Boolean get() = origin == ADOPTED
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("clones", Context.MODE_PRIVATE)

    private fun clean(s: String) = s.replace("|", " ").replace("\n", " ").replace("\r", " ").trim()

    fun record(ctx: Context): Record? {
        val p = (prefs(ctx).getString("space", null) ?: return null).split("|")
        if (p.size != 4) return null
        return Record(p[0].toIntOrNull() ?: return null, p[1], p[2], p[3])
    }

    private fun saveRecord(ctx: Context, r: Record) {
        prefs(ctx).edit().putString("space", "${r.id}|${clean(r.name)}|${clean(r.method)}|${r.origin}").apply()
    }

    private fun clearRecord(ctx: Context) { prefs(ctx).edit().remove("space").apply() }

    // ---------------------------------------------------------------- notes (kept in memory for the report)

    private val trace = StringBuilder()

    private fun note(s: String) {
        Vd.log(s)
        synchronized(trace) {
            if (trace.length > 30_000) trace.setLength(0)
            trace.append(s).append('\n')
        }
    }

    private fun sh(cmd: String): String {
        val out = Vd.exec(cmd)
        note("$ $cmd\n${out.trim().take(1200)}")
        return out
    }

    // ---------------------------------------------------------------- status

    enum class State { NO_SERVICE, NOT_SET_UP, READY, STOPPED, MISSING }

    data class Status(val state: State, val id: Int, val text: String)

    /** `pm list users`, or null when the shell can't be reached. */
    fun listUsers(): List<User>? {
        val out = Vd.exec("pm list users")
        if (out.startsWith("ERR")) return null
        return parseUsers(out)
    }

    fun status(ctx: Context): Status {
        if (Vd.service == null) return Status(State.NO_SERVICE, -1, "Not connected to Shizuku")
        val users = listUsers() ?: return Status(State.NO_SERVICE, -1, "Couldn't read the phone's users")
        val rec = record(ctx) ?: return Status(State.NOT_SET_UP, -1, "No clone profile chosen yet")
        val u = users.firstOrNull { it.id == rec.id && it.name == rec.name }
            ?: return Status(State.MISSING, rec.id, "Clone space is missing")
        return if (u.running) Status(State.READY, u.id, "Ready (user ${u.id}, ${rec.origin})")
        else Status(State.STOPPED, u.id, "Not running (user ${u.id}, ${rec.origin})")
    }

    private fun unlocked(id: Int): Boolean = Vd.exec("am get-started-user-state $id").contains("RUNNING_UNLOCKED")

    /** Starts the space if it isn't running and unlocked. Call before every launch. */
    fun ensureRunning(id: Int): Boolean {
        if (id == AppRef.OWNER) return true
        if (unlocked(id)) return true
        sh("am start-user -w $id")
        return unlocked(id)
    }

    // ---------------------------------------------------------------- creating the space

    data class Result(
        val ok: Boolean,
        val userId: Int = -1,
        val message: String = "",
        /** A profile on the phone that we didn't create, which the user could agree to use instead. */
        val foreign: User? = null
    )

    private fun listed(id: Int, filter: String): Set<String> = parsePackages(Vd.exec("pm list packages --user $id $filter"))

    /**
     * Installs [pkg] for user [id] and checks that it is listed there. With [keep] false the app is removed
     * again, but only if this call is what put it there.
     */
    private fun probe(id: Int, pkg: String, keep: Boolean): Boolean {
        if (id == AppRef.OWNER) return false
        val was = pkg in listed(id, pkg)
        sh("pm install-existing --user $id $pkg")
        val ok = pkg in listed(id, pkg)
        note("probe $pkg in user $id: ${if (ok) "listed" else "NOT listed"}")
        if (ok && !keep && !was) sh("pm uninstall --user $id $pkg")
        return ok
    }

    /**
     * Makes sure the clone space is there and running. PocketScreen never creates a user or profile. When
     * there is no space yet, the result says so and carries, in [Result.foreign], a profile the phone already
     * has (the phone maker's "Dual apps", say) for the caller to offer to the user. Nothing is changed on the
     * phone here.
     */
    @Synchronized
    fun ensure(ctx: Context): Result {
        val st = status(ctx)
        when (st.state) {
            State.NO_SERVICE -> return Result(false, message = "PocketScreen isn't connected to Shizuku.")
            State.READY, State.STOPPED ->
                return if (ensureRunning(st.id)) Result(true, st.id)
                else Result(false, st.id, "The clone space is there but won't start.")
            State.MISSING -> {
                if (Clones.list(ctx).isNotEmpty())
                    return Result(false, st.id, "The clone space is missing. Remove the clones, then try again.")
                clearRecord(ctx)
            }
            State.NOT_SET_UP -> {}
        }
        val foreign = findForeign(ctx)
        val message = if (foreign != null)
            "PocketScreen doesn't create users or profiles. This phone already has \"${foreign.name}\", which can be used for clones."
        else
            "This phone has no profile to put clones in, and PocketScreen doesn't create users or profiles. " +
                "Turn on the phone's own dual apps or app cloner in Settings, then try again."
        return Result(false, message = message, foreign = foreign)
    }

    /** A profile of the main user that PocketScreen isn't using yet (the phone maker's "Dual apps", say), if there is one. */
    fun findForeign(ctx: Context): User? {
        val rec = record(ctx)
        return listUsers()?.firstOrNull { it.id != AppRef.OWNER && it.isProfile && !(rec != null && it.id == rec.id && it.name == rec.name) }
    }

    /**
     * Uses [u] as the clone space. Call this only after the user has agreed in a dialog that says what will
     * happen. Nothing is recorded unless [probe] can be copied into it.
     */
    @Synchronized
    fun adopt(ctx: Context, u: User, probe: String, keep: Boolean = true): Result {
        if (u.id == AppRef.OWNER || !u.isProfile) return Result(false, message = "That isn't a profile PocketScreen can use.")
        if (!ensureRunning(u.id)) return Result(false, u.id, "\"${u.name}\" won't start.")
        if (!probe(u.id, probe, keep)) return Result(false, u.id, "Couldn't copy an app into \"${u.name}\".")
        saveRecord(ctx, Record(u.id, u.name, "existing", ADOPTED))
        return Result(true, u.id, "Using \"${u.name}\".")
    }

    // ---------------------------------------------------------------- apps inside the space

    /** The recorded space id, or null if [id] isn't it. Everything that changes a user goes through this. */
    private fun ours(ctx: Context, id: Int): Record? =
        record(ctx)?.takeIf { it.id == id && id != AppRef.OWNER }

    /** Copies an app that is installed for the owner into the space. */
    @Synchronized
    fun addApp(ctx: Context, pkg: String, id: Int): Result {
        ours(ctx, id) ?: return Result(false, id, "That isn't the clone space.")
        if (!ensureRunning(id)) return Result(false, id, "The clone space won't start.")
        return if (probe(id, pkg, keep = true)) Result(true, id) else Result(false, id, "Android wouldn't copy the app into the clone space.")
    }

    /**
     * Removes the clone's copy only (`pm uninstall --user N`). The owner's app is not touched. In a profile the
     * user let us use but we didn't create, nothing is uninstalled: the app may have been there before us, and
     * the clone is only forgotten.
     */
    @Synchronized
    fun removeApp(ctx: Context, app: AppRef): Result {
        val rec = ours(ctx, app.user) ?: return Result(false, app.user, "That isn't the clone space.")
        if (rec.adopted) { note("not uninstalling ${app.pkg} from adopted user ${app.user}"); return Result(true, app.user) }
        val out = sh("pm uninstall --user ${app.user} ${app.pkg}")
        val gone = app.pkg !in listed(app.user, app.pkg)
        return if (gone || out.contains("Success")) Result(true, app.user) else Result(false, app.user, out.trim().take(200))
    }

    /** Deletes the space, only if we created it. A space we adopted is forgotten, not touched. */
    @Synchronized
    fun removeSpace(ctx: Context): Result {
        val rec = record(ctx) ?: return Result(true)
        if (!rec.adopted && rec.id != AppRef.OWNER) {
            val still = listUsers()?.any { it.id == rec.id && it.name == rec.name } == true
            if (still) {
                val out = sh("pm remove-user ${rec.id}")
                if (out.startsWith("ERR") || out.contains("Error")) return Result(false, rec.id, out.trim().take(200))
            }
        }
        clearRecord(ctx)
        return Result(true, rec.id)
    }

    // ---------------------------------------------------------------- report

    /** Facts for "Check support", also written to the log. [probe] is an app to test copying with. */
    @Synchronized
    fun report(ctx: Context, probe: String?): String {
        synchronized(trace) { trace.setLength(0) }
        note("PocketScreen clone report")
        note("phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        val before = status(ctx)
        note("space before: ${before.state} (${before.text})")
        note("recorded: ${record(ctx)}")
        sh("pm list users")
        val res = ensure(ctx)
        val worked = if (res.ok && probe != null) probe(res.userId, probe, keep = false) else res.ok
        note("verdict: " + when {
            res.ok && worked -> "supported (using user ${res.userId}, ${record(ctx)?.origin ?: "?"})"
            res.foreign != null -> "no clone space yet; the phone has a profile \"${res.foreign.name}\" (user ${res.foreign.id}) that could be used"
            else -> "not supported: ${res.message}"
        })
        val rec = record(ctx)
        note("packages for the owner: " + Vd.exec("pm list packages --user ${AppRef.OWNER} | wc -l").trim())
        if (rec != null) note("packages in the space (user ${rec.id}): " + Vd.exec("pm list packages --user ${rec.id} | wc -l").trim())
        return synchronized(trace) { trace.toString() }
    }

    /** One short line for the status line of the test sheet. */
    fun verdictLine(report: String): String =
        report.lines().firstOrNull { it.startsWith("verdict: ") }?.removePrefix("verdict: ") ?: "unknown"
}
