package com.hiderlab.vd

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * Runs as the shell user (started by Shizuku). Owns any number of virtual displays,
 * which live as long as this process, and injects input into them.
 */
class VdService : IVdService.Stub() {

    private class VD(
        val id: Int,
        val w: Int,
        val h: Int,
        val dpi: Int,
        val fps: Int,
        val reader: ImageReader,
        val thread: HandlerThread,
        val dm: Any,
        val cb: Any
    ) {
        var viewerSurface: Surface? = null
    }

    private val displays = ConcurrentHashMap<Int, VD>()
    private var inputIface: Any? = null
    private var injectMethod: Method? = null
    @Volatile private var touchDownTime = 0L
    @Volatile private var lastError = ""

    companion object {
        private const val PUBLIC = 1 shl 0
        private const val PRESENTATION = 1 shl 1
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val ROTATES_WITH_CONTENT = 1 shl 7
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val OWN_FOCUS = 1 shl 14
        private const val DEVICE_DISPLAY_GROUP = 1 shl 15

        private const val BASE = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or
            ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL
        private const val MID = BASE or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED
        private const val FULL = MID or OWN_FOCUS or DEVICE_DISPLAY_GROUP
    }

    override fun destroy() {
        displays.values.forEach { cleanup(it) }
        displays.clear()
        run("settings delete global force_resizable_activities")
        exitProcess(0)
    }

    override fun createDisplay(w: Int, h: Int, dpi: Int, fps: Int): String {
        val errors = StringBuilder()
        for (flags in intArrayOf(FULL, MID, BASE)) {
            try {
                val vd = create(w, h, dpi, fps, flags)
                displays[vd.id] = vd
                // Let non-resizable apps open on a secondary display.
                run("settings put global force_resizable_activities 1")
                return "OK:${vd.id} (flags=$flags, fps=${if (fps > 0) fps.toString() else "auto"}${if (fps > 0 && !rateApplied) " NOT APPLIED" else ""})"
            } catch (t: Throwable) {
                errors.append("flags=").append(flags).append(": ")
                    .append(Log.getStackTraceString(unwrap(t)).take(900)).append('\n')
            }
        }
        return "ERR:\n$errors"
    }

    override fun releaseDisplay(id: Int): String {
        displays.remove(id)?.let { cleanup(it) }
        // Spawning a shell takes a moment, so do it in the background and return right away.
        if (displays.isEmpty()) Thread { run("settings delete global force_resizable_activities") }.start()
        return "OK"
    }

    override fun exec(cmd: String): String = run(cmd)

    override fun info(): String =
        displays.values.sortedBy { it.id }.joinToString(";") { "${it.id},${it.w},${it.h},${it.dpi},${it.fps}" } + "#" + lastError

    override fun setSurface(id: Int, s: Surface): String = try {
        swapSurface(id, s); "OK"
    } catch (t: Throwable) {
        "ERR: " + Log.getStackTraceString(unwrap(t)).take(700)
    }

    override fun clearSurface(id: Int): String = try {
        swapSurface(id, null); "OK"
    } catch (t: Throwable) {
        "ERR: " + Log.getStackTraceString(unwrap(t)).take(700)
    }

    private fun unwrap(t: Throwable): Throwable = (t as? InvocationTargetException)?.targetException ?: t

    private fun swapSurface(id: Int, s: Surface?) {
        val vd = displays[id] ?: error("no such display")
        val target = s ?: vd.reader.surface
        val m = Class.forName("android.hardware.display.IDisplayManager").methods
            .firstOrNull { it.name == "setVirtualDisplaySurface" }
            ?: error("setVirtualDisplaySurface not found")
        m.invoke(vd.dm, vd.cb, target)
        val old = vd.viewerSurface
        vd.viewerSurface = s
        if (old != null && old !== s) try { old.release() } catch (_: Throwable) {}
    }

    // ---- input injection (targets one hidden display) ----

    override fun touch(id: Int, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = now
        val ev = MotionEvent.obtain(touchDownTime, now, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        inject(ev, id)
        ev.recycle()
    }

    override fun touchPointers(id: Int, action: Int, actionIndex: Int, pointerIds: IntArray, xs: FloatArray, ys: FloatArray) {
        val n = pointerIds.size
        if (n == 0 || xs.size < n || ys.size < n) return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = now
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().also { it.id = pointerIds[i]; it.toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().also { it.x = xs[i]; it.y = ys[i]; it.pressure = 1f; it.size = 1f }
        }
        val act = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val ev = MotionEvent.obtain(touchDownTime, now, act, n, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        inject(ev, id)
        ev.recycle()
    }

    override fun key(id: Int, keyCode: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val ev = KeyEvent(now, now, a, keyCode, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
            inject(ev, id)
        }
    }

    override fun keyEvent(id: Int, action: Int, keyCode: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        inject(KeyEvent(now, now, action, keyCode, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), id)
    }

    override fun typeText(id: Int, text: String) {
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        for (ch in text) {
            val evs = kcm.getEvents(charArrayOf(ch))
            if (evs != null) {
                for (e in evs) {
                    val now = SystemClock.uptimeMillis()
                    inject(KeyEvent(now, now, e.action, e.keyCode, 0, e.metaState, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), id)
                }
            } else {
                // Not on the virtual keyboard layout (accents, symbols): send it as a raw character.
                @Suppress("DEPRECATION")
                val ev = KeyEvent(SystemClock.uptimeMillis(), ch.toString(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
                inject(ev, id)
            }
        }
    }

    private val setDisplayIdMethod: Method by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    private fun inject(ev: InputEvent, displayId: Int) {
        try {
            if (injectMethod == null) {
                val binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java).invoke(null, "input") as IBinder
                val iface = Class.forName("android.hardware.input.IInputManager")
                inputIface = Class.forName("android.hardware.input.IInputManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                injectMethod = iface.methods.firstOrNull { it.name == "injectInputEvent" && it.parameterCount in 2..3 }
                    ?: error("injectInputEvent not found")
            }
            setDisplayIdMethod.invoke(ev, displayId)
            val m = injectMethod!!
            if (m.parameterCount == 2) m.invoke(inputIface, ev, 0) else m.invoke(inputIface, ev, 0, -1)
        } catch (t: Throwable) {
            lastError = unwrap(t).toString().take(200)
        }
    }

    override fun rotation(id: Int): Int = try {
        val c = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val g = c.getMethod("getInstance").invoke(null)
        val d = c.getMethod("getRealDisplay", Int::class.javaPrimitiveType).invoke(g, id) as android.view.Display
        d.rotation
    } catch (t: Throwable) {
        if (lastError.isEmpty()) lastError = "rotation: " + unwrap(t).toString().take(150)
        0
    }

    // ---- task listing ----

    private fun isRunning(pkg: String): Boolean = try {
        val p = ProcessBuilder("sh", "-c", "pidof $pkg").redirectErrorStream(true).start()
        val o = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        o.isNotEmpty() && o.all { it.isDigit() || it == ' ' }
    } catch (_: Throwable) { true }

    override fun tasksOn(displayId: Int): String = try {
        val p = ProcessBuilder("sh", "-c", "dumpsys activity activities").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val dispRe = Regex("^Display #(\\d+)")
        val taskRe = Regex("^\\s*\\* Task\\{\\w+ #(\\d+) type=standard A=\\d+:([\\w.]+)")
        var cur = -1
        val res = LinkedHashMap<Int, String>()
        for (line in out.lineSequence()) {
            val m = dispRe.find(line)
            if (m != null) cur = m.groupValues[1].toInt()
            else if (line.isNotEmpty() && !line[0].isWhitespace()) cur = -1 // a new top-level section (recents etc.)
            if (cur == displayId) taskRe.find(line)?.let { res.putIfAbsent(it.groupValues[1].toInt(), it.groupValues[2]) }
        }
        // Android keeps a task listed for a while after its app is killed; hide those.
        res.entries.filter { isRunning(it.value) }.joinToString("\n") { "${it.key}|${it.value}" }
    } catch (t: Throwable) {
        "ERR: $t"
    }

    private fun run(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.take(6000)
    } catch (t: Throwable) {
        "ERR: $t"
    }

    // ---- display creation ----

    @Volatile private var rateApplied = false

    private fun create(w: Int, h: Int, dpi: Int, fps: Int, flags: Int): VD {
        val thread = HandlerThread("vd-reader").also { it.start() }
        val reader = try {
            ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        } catch (e: Throwable) {
            thread.quitSafely()
            throw e
        }
        try {
            reader.setOnImageAvailableListener({ rd ->
                try { rd.acquireLatestImage()?.close() } catch (_: Throwable) {}
            }, Handler(thread.looper))
            val surface: Surface = reader.surface

            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "display") as IBinder
            val dmClass = Class.forName("android.hardware.display.IDisplayManager")
            val dmI = Class.forName("android.hardware.display.IDisplayManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!

            val intT = Int::class.javaPrimitiveType
            val bClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val builder = bClass.getConstructor(String::class.java, intT, intT, intT)
                .newInstance("HiderLab", w, h, dpi)
            bClass.getMethod("setFlags", intT).invoke(builder, flags)
            bClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
            // Refresh rate of the display itself (Android 14+). Android rounds it to a divisor of the
            // phone's own rate; 0 leaves it at the phone's rate.
            rateApplied = false
            if (fps > 0) {
                try {
                    bClass.getMethod("setRequestedRefreshRate", Float::class.javaPrimitiveType)
                        .invoke(builder, fps.toFloat())
                    rateApplied = true
                } catch (t: Throwable) {
                    Log.w("VdService", "setRequestedRefreshRate unavailable: $t")
                }
            }
            val config = bClass.getMethod("build").invoke(builder)

            val cbIface = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            val binderObj = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = true
            }
            val handler = InvocationHandler { _, m, _ ->
                when (m.name) {
                    "asBinder" -> binderObj
                    "hashCode" -> 1
                    "equals" -> false
                    "toString" -> "cb"
                    else -> null
                }
            }
            val cbProxy = Proxy.newProxyInstance(cbIface.classLoader, arrayOf(cbIface), handler)

            val m = dmClass.methods.firstOrNull { it.name == "createVirtualDisplay" }
                ?: error("IDisplayManager.createVirtualDisplay not found")
            if (m.parameterCount != 4) error("Unexpected signature: $m")
            val id = m.invoke(dmI, config, cbProxy, null, "com.android.shell") as Int
            if (id < 0) error("createVirtualDisplay returned $id")
            return VD(id, w, h, dpi, fps, reader, thread, dmI, cbProxy)
        } catch (e: Throwable) {
            try { reader.close() } catch (_: Throwable) {}
            thread.quitSafely()
            throw e
        }
    }

    private fun cleanup(vd: VD) {
        try {
            Class.forName("android.hardware.display.IDisplayManager")
                .getMethod("releaseVirtualDisplay", Class.forName("android.hardware.display.IVirtualDisplayCallback"))
                .invoke(vd.dm, vd.cb)
        } catch (_: Throwable) {}
        try { vd.viewerSurface?.release() } catch (_: Throwable) {}
        try { vd.reader.close() } catch (_: Throwable) {}
        try { vd.thread.quitSafely() } catch (_: Throwable) {}
    }
}
