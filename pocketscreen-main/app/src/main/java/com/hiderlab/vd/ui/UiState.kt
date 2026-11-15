package com.hiderlab.vd.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiderlab.vd.AppController
import com.hiderlab.vd.Updater

/** What the controller last told us, as Compose state. [rev] bumps on every update so screens re-read preferences. */
class UiState {
    var status by mutableStateOf<AppController.Status?>(null)
    var snap by mutableStateOf(
        AppController.Snapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
    )
    var rev by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)
    var relaunch by mutableStateOf<AppController.Event.NeedsRelaunchConfirm?>(null)
    var update by mutableStateOf<Updater.Release?>(null)
}

sealed interface Page {
    object Main : Page
    object Settings : Page
    data class App(val pkg: String) : Page
}
