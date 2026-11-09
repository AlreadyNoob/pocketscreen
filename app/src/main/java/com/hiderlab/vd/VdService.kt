package com.hiderlab.vd

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log
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
        run("settings delete global force_resizable_activities")
        return "OK"
    }

    override fun exec(cmd: String): String = run(cmd)

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
