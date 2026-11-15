package com.hiderlab.vd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hiderlab.vd.ui.AppScreen
import com.hiderlab.vd.ui.MainScreen
import com.hiderlab.vd.ui.Page
import com.hiderlab.vd.ui.PocketTheme
import com.hiderlab.vd.ui.RelaunchDialog
import com.hiderlab.vd.ui.SettingsScreen
import com.hiderlab.vd.ui.UiState

/** Launcher activity: Compose UI on top of [AppController]. See FRONTEND_API.md. */
class MainActivity : ComponentActivity() {

    private lateinit var controller: AppController
    private val ui = UiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        controller = AppController(applicationContext).also { c ->
            c.onStatus = { ui.status = it; ui.rev++ }
            c.onSnapshot = { ui.snap = it; ui.rev++ }
            c.onEvent = { onEvent(it) }
            c.onUpdateAvailable = { ui.update = it }
        }
        controller.attach()

        // The foreground-service notification needs this on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)

        setContent {
            val dynamic = remember(ui.rev) { AppSettings.dynamicColor(this) }
            PocketTheme(dynamic) {
                var page by remember { mutableStateOf<Page>(Page.Main) }
                BackHandler(page != Page.Main) { page = Page.Main }
                when (val p = page) {
                    Page.Main -> MainScreen(
                        ui, controller,
                        onViewer = { id -> startActivity(Intent(this, ViewerActivity::class.java).putExtra("display", id)) },
                        onSettings = { page = Page.Settings },
                        onApp = { pkg -> page = Page.App(pkg) }
                    )
                    Page.Settings -> SettingsScreen(controller, onBack = { page = Page.Main })
                    is Page.App -> AppScreen(p.pkg, controller, onBack = { page = Page.Main })
                }
                ui.relaunch?.let { RelaunchDialog(it, controller) { ui.relaunch = null } }
            }
        }
    }

    private fun onEvent(e: AppController.Event) {
        fun name(pkg: String) = Apps.label(this, pkg)
        when (e) {
            is AppController.Event.Launching -> ui.message = "Starting ${name(e.pkg)}…"
            is AppController.Event.AlreadyLaunching -> ui.message = "${name(e.pkg)} is already starting"
            is AppController.Event.AlreadyRunning -> ui.message = "${name(e.pkg)} is already running"
            is AppController.Event.NeedsRelaunchConfirm -> ui.relaunch = e
            AppController.Event.Unsupported -> ui.message = "This phone can't run apps on extra displays"
            is AppController.Event.NotOnHiddenDisplay ->
                ui.message = "${name(e.pkg)} opened on the main screen instead of the hidden display"
        }
    }

    override fun onResume() {
        super.onResume()
        controller.start()
    }

    override fun onPause() {
        controller.stop()
        super.onPause()
    }

    override fun onDestroy() {
        controller.detach()
        super.onDestroy()
    }
}
