package com.hifilauncher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.hifilauncher.ui.HiFiTheme
import com.hifilauncher.ui.PlayerRoute
import com.hifilauncher.ui.PlayerViewModel

/**
 * The home screen. Two things to know about being a launcher:
 *
 * - The Home key never reaches onKeyDown. Pressing it while we are already in front
 *   re-delivers the HOME intent here (launchMode singleTask), so onNewIntent is where
 *   "Home" is handled: go back to the root screen.
 * - Back must never finish a launcher. PlayerRoute consumes it (it only closes the
 *   diagnostics screen).
 *
 * The status bar is deliberately left visible (no immersive mode): if this app ever
 * misbehaves, the notification shade still gives access to Settings, where the default
 * home app can be switched back.
 */
class LauncherActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HiFiTheme { PlayerRoute(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) vm.goHome()
    }
}
