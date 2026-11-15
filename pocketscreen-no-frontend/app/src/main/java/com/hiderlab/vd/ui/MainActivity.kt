package com.hiderlab.vd.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PocketTheme { Root(model) } }
    }

    override fun onResume() {
        super.onResume()
        model.sync()
    }
}

@Composable
private fun Root(model: AppModel) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { model.messages.collect { snack.showSnackbar(it) } }

    // Slow heartbeat while the app is on screen: connection state, session clocks, running list.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(5_000); model.sync() }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snack, Modifier.navigationBarsPadding()) },
    ) { _ ->
        Crossfade(model.link, label = "link", modifier = Modifier.fillMaxSize()) { link ->
            if (link == Link.Ready) Main(model) else SetupScreen(model)
        }
    }
}

@Composable
private fun Main(model: AppModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Header(tab, model.running.size) { tab = it }
        Box(Modifier.fillMaxSize()) {
            if (tab == 0) AppsScreen(model) else RunningScreen(model, onBrowse = { tab = 0 })
        }
    }
}

/** The two sections are the title: the active one is dark, the other is quiet. */
@Composable
private fun Header(tab: Int, runningCount: Int, onTab: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderTab("Apps", tab == 0, badge = 0) { onTab(0) }
        Box(Modifier.padding(end = 24.dp))
        HeaderTab("Running", tab == 1, badge = runningCount) { onTab(1) }
    }
}

@Composable
private fun HeaderTab(label: String, active: Boolean, badge: Int, onClick: () -> Unit) {
    val color by animateColorAsState(
        if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        label = "tab",
    )
    Row(Modifier.clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.headlineMedium, color = color)
        if (badge > 0) {
            Box(
                Modifier
                    .padding(start = 6.dp, top = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            ) {
                Text(badge.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
