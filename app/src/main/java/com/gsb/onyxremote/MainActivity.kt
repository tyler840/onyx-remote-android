package com.gsb.onyxremote

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gsb.onyxremote.RemoteViewModel.Screen
import com.gsb.onyxremote.model.AppConfig
import com.gsb.onyxremote.model.Orientation
import com.gsb.onyxremote.ui.ConsoleScreen
import com.gsb.onyxremote.ui.OnyxTheme
import com.gsb.onyxremote.ui.PanelBg
import com.gsb.onyxremote.ui.PanelScreen
import com.gsb.onyxremote.ui.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.config.collect { applyWindow(it) } }
        }

        setContent {
            OnyxTheme {
                val ui by vm.ui.collectAsState()
                Surface(Modifier.fillMaxSize(), color = PanelBg, contentColor = Color.White) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        when (ui.screen) {
                            Screen.PANEL -> PanelScreen(vm)
                            Screen.SETTINGS -> SettingsScreen(vm)
                            Screen.CONSOLE -> ConsoleScreen(vm)
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Bars come back after dialogs and swipes; hide them again.
        if (hasFocus) applyWindow(vm.config.value)
    }

    private fun applyWindow(c: AppConfig) {
        if (c.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val wanted = when (c.orientation) {
            Orientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        if (requestedOrientation != wanted) requestedOrientation = wanted

        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false
        if (c.fullscreen) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
