package com.iamode.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.iamode.app.core.AppVisibility
import com.iamode.app.ui.celebration.CelebrationHost
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamode.app.ui.navigation.AppNavHost
import com.iamode.app.ui.navigation.DeepLink
import com.iamode.app.ui.navigation.RootViewModel
import com.iamode.app.ui.theme.IAModeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val root: RootViewModel by viewModels()
    private var deepLink by mutableStateOf<DeepLink?>(null)

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.iamode.app.core.i18n.I18n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Picks up a language changed in Android 13+ system settings while the app was running.
        com.iamode.app.core.i18n.I18n.init(this)
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash until settings are read, so the first screen appears fully formed.
        splash.setKeepOnScreenCondition { root.state.value == null }
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(220L)
                .withEndAction { provider.remove() }.start()
        }
        enableEdgeToEdge()
        deepLink = DeepLink.from(intent)

        setContent {
            val state by root.state.collectAsStateWithLifecycle()
            val s = state ?: return@setContent
            IAModeTheme(dynamic = s.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CelebrationHost(onOpenEmail = { deepLink = DeepLink.Mail(it) }) { modifier ->
                        Box(modifier) { AppNavHost(s.onboardingDone, deepLink, onDeepLinkHandled = { deepLink = null }) }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.foreground = true
        com.iamode.app.data.mail.MailSyncWorker.syncNow(this) // fresh mail understanding when the user opens the app
    }

    override fun onStop() {
        AppVisibility.foreground = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DeepLink.from(intent)?.let { deepLink = it }
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_EMAIL_ID = "email_id"
        const val EXTRA_APPLICATION_ID = "application_id"
    }
}
