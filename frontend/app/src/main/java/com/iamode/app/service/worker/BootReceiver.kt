package com.iamode.app.service.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.iamode.app.core.di.ApplicationScope
import com.iamode.app.service.auto.AutoModeEvaluator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity-recognition registrations and the status notification don't survive a reboot or app
 * update. If IA Mode was on, this turns everything back on without the user opening the app.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var lifecycle: ModeLifecycle
    @Inject lateinit var autoMode: AutoModeEvaluator
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        scope.launch {
            try {
                lifecycle.restoreIfOn()
                autoMode.refresh() // alarms and activity updates don't survive a reboot
            } finally {
                pending.finish()
            }
        }
    }
}
