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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.system.exitProcess

/**
 * Runs as the shell user (started by Shizuku). Creates a virtual display that
 * lives as long as this process, and runs shell commands.
 */
class VdService : IVdService.Stub() {

    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var dm: Any? = null
    private var cb: Any? = null
    private var cbBinder: Binder? = null
    private var displayId = -1
    private var dispW = 0
    private var dispH = 0
    private var viewerSurface: Surface? = null
    private var inputIface: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null
    private var touchDownTime = 0L
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
        cleanup()
        exitProcess(0)
    }

    override fun currentDisplay(): Int = displayId

    override fun createDisplay(w: Int, h: Int, dpi: Int): String {
        cleanup()
        val errors = StringBuilder()
        for (flags in intArrayOf(FULL, MID, BASE)) {
            try {
                val id = create(w, h, dpi, flags)
                displayId = id
                dispW = w
                dispH = h
                // Let non-resizable apps open on a secondary display.
                run("settings put global force_resizable_activities 1")
                return "OK:$id (flags=$flags)"
            } catch (t: Throwable) {
                val real = (t as? InvocationTargetException)?.targetException ?: t
                errors.append("flags=").append(flags).append(": ")
                    .append(Log.getStackTraceString(real).take(900)).append('\n')
                cleanup()
            }
        }
        return "ERR:\n$errors"
    }

    override fun releaseDisplay(): String {
        cleanup()
        displayId = -1
        dispW = 0
        dispH = 0
        run("settings delete global force_resizable_activities")
        return "OK"
    }

    override fun exec(cmd: String): String = run(cmd)

    override fun info(): String = "$displayId,$dispW,$dispH,$lastError"

    override fun setSurface(s: Surface): String = try {
        swapSurface(s); "OK"
    } catch (t: Throwable) {
        "ERR: " + Log.getStackTraceString(unwrap(t)).take(700)
    }

    override fun clearSurface(): String = try {
        swapSurface(null); "OK"
    } catch (t: Throwable) {
        "ERR: " + Log.getStackTraceString(unwrap(t)).take(700)
    }

    private fun unwrap(t: Throwable): Throwable = (t as? InvocationTargetException)?.targetException ?: t

    private fun swapSurface(s: Surface?) {
        val d = dm ?: error("no display")
        val c = cb ?: error("no display")
        val target = s ?: reader?.surface ?: error("no fallback surface")
        val m = Class.forName("android.hardware.display.IDisplayManager").methods
            .firstOrNull { it.name == "setVirtualDisplaySurface" }
            ?: error("setVirtualDisplaySurface not found")
        m.invoke(d, c, target)
        val old = viewerSurface
        viewerSurface = s
        if (old != null && old !== s) try { old.release() } catch (_: Throwable) {}
    }

    // ---- input injection (targets the hidden display) ----

    override fun touch(action: Int, x: Float, y: Float) {
        if (displayId < 0) return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = now
        val ev = MotionEvent.obtain(touchDownTime, now, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        inject(ev)
        ev.recycle()
    }

    override fun key(keyCode: Int, meta: Int) {
        if (displayId < 0) return
        val now = SystemClock.uptimeMillis()
        for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val ev = KeyEvent(now, now, a, keyCode, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
            inject(ev)
        }
    }

    private fun inject(ev: InputEvent) {
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
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(ev, displayId)
            val m = injectMethod!!
            if (m.parameterCount == 2) m.invoke(inputIface, ev, 0) else m.invoke(inputIface, ev, 0, -1)
        } catch (t: Throwable) {
            lastError = unwrap(t).toString().take(200)
        }
    }

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
        res.entries.joinToString("\n") { "${it.key}|${it.value}" }
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

    private fun create(w: Int, h: Int, dpi: Int, flags: Int): Int {
        val t = HandlerThread("vd-reader")
        t.start()
        thread = t
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            try { rd.acquireLatestImage()?.close() } catch (_: Throwable) {}
        }, Handler(t.looper))
        val surface: Surface = r.surface

        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "display") as IBinder
        val dmClass = Class.forName("android.hardware.display.IDisplayManager")
        val dmI = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        dm = dmI

        val intT = Int::class.javaPrimitiveType
        val bClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = bClass.getConstructor(String::class.java, intT, intT, intT)
            .newInstance("HiderLab", w, h, dpi)
        bClass.getMethod("setFlags", intT).invoke(builder, flags)
        bClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        val config = bClass.getMethod("build").invoke(builder)

        val cbIface = Class.forName("android.hardware.display.IVirtualDisplayCallback")
        val binderObj = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = true
        }
        cbBinder = binderObj
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
        cb = cbProxy

        val m = dmClass.methods.firstOrNull { it.name == "createVirtualDisplay" }
            ?: error("IDisplayManager.createVirtualDisplay not found")
        if (m.parameterCount != 4) error("Unexpected signature: $m")
        val id = m.invoke(dmI, config, cbProxy, null, "com.android.shell") as Int
        if (id < 0) error("createVirtualDisplay returned $id")
        return id
    }

    private fun cleanup() {
        try {
            val d = dm
            val c = cb
            if (d != null && c != null) {
                Class.forName("android.hardware.display.IDisplayManager")
                    .getMethod("releaseVirtualDisplay", Class.forName("android.hardware.display.IVirtualDisplayCallback"))
                    .invoke(d, c)
            }
        } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { thread?.quitSafely() } catch (_: Throwable) {}
        reader = null; thread = null; dm = null; cb = null; cbBinder = null
    }
}
