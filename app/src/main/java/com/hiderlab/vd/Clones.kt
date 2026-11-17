package com.hiderlab.vd

import android.content.Context

/**
 * The clones PocketScreen has made. One record per clone in prefs `clones`, key `list`, one line each,
 * fields separated by `|` (like `presets`): pkg, user, nickname, label, component, createdAt.
 *
 * The label and the launch component are saved when the clone is made, from the owner's copy. The app
 * can't look them up for the clone later (PackageManager doesn't see other profiles), and the clone has
 * to keep launching even if the original is uninstalled.
 */
object Clones {

    data class Clone(
        val pkg: String,
        val user: Int,
        val nickname: String,
        /** The app's own name, as it was when the clone was made. */
        val label: String,
        /** The launcher activity, as `flattenToShortString()`. */
        val component: String,
        val createdAt: Long
    ) {
        val app: AppRef get() = AppRef(pkg, user)
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("clones", Context.MODE_PRIVATE)

    private fun clean(s: String) = s.replace("|", " ").replace("\n", " ").replace("\r", " ").trim()

    fun list(ctx: Context): List<Clone> =
        (prefs(ctx).getString("list", "") ?: "").lines().mapNotNull { line ->
            val p = line.split("|")
            if (p.size != 6) return@mapNotNull null
            Clone(
                p[0].ifEmpty { return@mapNotNull null }, p[1].toIntOrNull() ?: return@mapNotNull null,
                p[2], p[3], p[4], p[5].toLongOrNull() ?: 0L
            )
        }

    private fun write(ctx: Context, l: List<Clone>) {
        prefs(ctx).edit().putString("list", l.joinToString("\n") {
            "${it.pkg}|${it.user}|${clean(it.nickname)}|${clean(it.label)}|${clean(it.component)}|${it.createdAt}"
        }).apply()
    }

    /** The record for a clone, or null for an original (or a clone we have no record of). */
    fun find(ctx: Context, app: AppRef): Clone? =
        if (!app.isClone) null else list(ctx).firstOrNull { it.pkg == app.pkg && it.user == app.user }

    /** The clone of this package, if there is one. */
    fun forPkg(ctx: Context, pkg: String): Clone? = list(ctx).firstOrNull { it.pkg == pkg }

    /** The launcher activity to start for [app]. For a clone it comes from its record, never from PackageManager. */
    fun launchComponent(ctx: Context, app: AppRef): String? =
        if (app.isClone) find(ctx, app)?.component
        else ctx.packageManager.getLaunchIntentForPackage(app.pkg)?.component?.flattenToShortString()

    /**
     * Makes a clone of an installed app: sets up the clone space if needed, copies the app into it and saves
     * the record. Blocks; [progress] gets short plain steps. Android allows one clone of an app.
     */
    fun create(ctx: Context, pkg: String, nickname: String? = null, progress: (String) -> Unit = {}): CloneSpace.Result {
        forPkg(ctx, pkg)?.let { return CloneSpace.Result(false, it.user, "This app already has a clone.") }
        val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            ?: return CloneSpace.Result(false, message = "This app has no launcher activity.")
        val label = Apps.label(ctx, AppRef(pkg))
        val nick = clean(nickname?.takeIf { it.isNotBlank() } ?: "$label (clone)")
        val space = CloneSpace.ensure(ctx, pkg, keep = true, progress = progress)
        if (!space.ok) return space
        progress("Copying $label")
        val added = CloneSpace.addApp(ctx, pkg, space.userId)
        if (!added.ok) return added
        write(ctx, list(ctx).filter { it.pkg != pkg } + Clone(pkg, space.userId, nick, clean(label), comp, System.currentTimeMillis()))
        Vd.changed()
        return CloneSpace.Result(true, space.userId, space.message)
    }

    /**
     * Stops the clone, removes its copy of the app from the clone space and forgets it. The original is not
     * touched. Returns text for the log. The record is kept if Android refused to remove the copy while the
     * space is still there, so nothing is left behind unseen.
     */
    fun remove(ctx: Context, app: AppRef): String {
        if (!app.isClone) return "Not a clone."
        val sb = StringBuilder()
        sb.append(Vd.stopAppAndDisplay(ctx, app)).append('\n')
        val r = CloneSpace.removeApp(ctx, app)
        val spaceGone = CloneSpace.status(ctx).state.let { it == CloneSpace.State.MISSING || it == CloneSpace.State.NOT_SET_UP }
        if (!r.ok && !spaceGone) return sb.append("Couldn't remove the copy: ${r.message}").toString()
        write(ctx, list(ctx).filter { !(it.pkg == app.pkg && it.user == app.user) })
        purge(ctx, app.key)
        Vd.changed()
        return sb.append("Removed ${app.pkg} from the clone space.").toString()
    }

    /**
     * Deletes everything saved for one app key: its running state, limits, size, orientation, mute, quick
     * launch entry, freeze note and per-app settings. Cancels its crash notification. After this a removed
     * clone leaves nothing behind.
     */
    fun purge(ctx: Context, key: String) {
        val app = ctx.applicationContext
        for (name in listOf("sessions", "deadlines", "app_size", "crashes", "orient", "autostops"))
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().remove(key).apply()
        val ref = AppRef.fromKey(key)
        Vd.setMutedPref(ctx, ref, false) // takes the key out of the `mute` set
        Vd.removePreset(ctx, ref)
        app.getSharedPreferences("freeze", Context.MODE_PRIVATE).edit().remove("status_$key").apply()
        val s = app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        for (p in listOf("back_", "kb_", "auto_bat_", "auto_temp_", "auto_min_", "auto_at_", "hz_", "freeze_off_")) s.remove(p + key)
        s.apply()
        HostService.cancelCrash(ctx, ref)
    }
}
