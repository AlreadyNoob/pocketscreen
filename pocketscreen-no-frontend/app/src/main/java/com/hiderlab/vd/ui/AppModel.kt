package com.hiderlab.vd.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.hiderlab.vd.Apps
import com.hiderlab.vd.Vd
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

data class Running(
    val pkg: String,
    val label: String,
    val disp: Vd.Disp,
    val startedAt: Long?,
    val muted: Boolean,
)

enum class Link { NoShizuku, NeedsPermission, Connecting, Ready }

class AppModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val io = Executors.newSingleThreadExecutor()   // keeps backend calls in order
    private val main = Handler(Looper.getMainLooper())

    var shizukuRunning by mutableStateOf(false); private set
    var permitted by mutableStateOf(false); private set
    var connected by mutableStateOf(false); private set
    /** True when Android will no longer show the Shizuku permission prompt. */
    var denied by mutableStateOf(false); private set

    var apps by mutableStateOf<List<Apps.App>>(Apps.list); private set
    var appsReady by mutableStateOf(Apps.ready); private set
    var running by mutableStateOf<List<Running>>(emptyList()); private set
    var busy by mutableStateOf<Set<String>>(emptySet()); private set
    var now by mutableLongStateOf(System.currentTimeMillis()); private set

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val link: Link
        get() = when {
            !shizukuRunning -> Link.NoShizuku
            !permitted -> Link.NeedsPermission
            !connected -> Link.Connecting
            else -> Link.Ready
        }

    private val onChange = Runnable { main.post { sync() } }
    private val onBinder = Shizuku.OnBinderReceivedListener { main.post { sync() } }
    private val onDead = Shizuku.OnBinderDeadListener { main.post { sync() } }
    private val onPerm = Shizuku.OnRequestPermissionResultListener { _, _ -> main.post { sync() } }

    init {
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onPerm)
        Vd.listeners.add(onChange)
        Apps.load(ctx) { apps = Apps.list; appsReady = true }
        sync()
    }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeBinderDeadListener(onDead)
        Shizuku.removeRequestPermissionResultListener(onPerm)
        Vd.listeners.remove(onChange)
    }

    /** Re-reads connection state. Cheap; called on events and on a slow tick. */
    fun sync() {
        now = System.currentTimeMillis()
        shizukuRunning = Vd.shizukuRunning()
        permitted = Vd.permitted()
        connected = Vd.service != null
        if (shizukuRunning && !permitted) {
            denied = try { Shizuku.shouldShowRequestPermissionRationale() } catch (_: Throwable) { false }
        }
        if (permitted && !connected) Vd.connect(ctx)
        if (connected) refresh() else running = emptyList()
    }

    fun requestPermission() {
        try { Shizuku.requestPermission(0) } catch (_: Throwable) { say("Shizuku isn’t responding") }
    }

    fun retryConnect() { Vd.connect(ctx, force = true); sync() }

    fun reloadApps() { Apps.load(ctx, force = true) { apps = Apps.list; appsReady = true } }

    fun refresh() {
        io.execute {
            if (Vd.service == null) return@execute
            Vd.refreshInfo()
            val list = Vd.displays.flatMap { d ->
                Vd.tasks(d.id).map { (_, pkg) ->
                    Running(pkg, Apps.label(ctx, pkg), d, Vd.sessionStart(ctx, pkg), Vd.isMuted(ctx, pkg))
                }
            }.distinctBy { it.pkg }
            main.post { running = list }
        }
    }

    fun isRunning(pkg: String) = running.any { it.pkg == pkg }

    fun launch(app: Apps.App, landscape: Boolean) {
        val pkg = app.pkg
        if (pkg in busy) return
        busy = busy + pkg
        io.execute {
            try {
                val id = Vd.freeDisplay(ctx, landscape, Vd.appCfg(ctx, pkg), Vd.refreshFor(ctx, pkg))
                if (id < 0) {
                    say("Couldn’t create a hidden display")
                } else {
                    val out = Vd.launch(ctx, pkg, id)
                    Thread.sleep(1200)
                    Vd.syncAudio(ctx, pkg)
                    if (Vd.displayOf(pkg) == null) {
                        say("${app.label} didn’t open. ${out.lineSequence().firstOrNull().orEmpty().take(90)}".trim())
                        if (Vd.tasks(id).isEmpty()) Vd.releaseOnly(ctx, id)
                    }
                }
            } finally {
                main.post { busy = busy - pkg }
                refresh()
            }
        }
    }

    fun stop(pkg: String) {
        busy = busy + pkg
        io.execute {
            try { Vd.stopAppAndDisplay(ctx, pkg) } finally {
                main.post { busy = busy - pkg }
                refresh()
            }
        }
    }

    fun stopAll() {
        io.execute { Vd.stopAll(ctx); refresh() }
    }

    fun toggleMute(r: Running) {
        io.execute { Vd.setMuted(ctx, r.pkg, !r.muted); refresh() }
    }

    private fun say(text: String) { messages.tryEmit(text) }
}
