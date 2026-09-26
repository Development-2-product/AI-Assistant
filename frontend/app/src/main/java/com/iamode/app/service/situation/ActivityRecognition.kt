package com.iamode.app.service.situation

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.iamode.app.core.datastore.PreferenceKeys
import com.iamode.app.core.di.ApplicationScope
import com.iamode.app.service.auto.AutoModeEvaluator
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android can tell "in a vehicle" but not car vs bike, so the user's vehicle type setting
 * decides between DRIVING and RIDING.
 */
@Singleton
class ActivityRecognitionController @Inject constructor(@ApplicationContext private val context: Context) {

    private val pendingIntent: PendingIntent by lazy {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        PendingIntent.getBroadcast(context, 7, Intent(context, ActivityTransitionReceiver::class.java), flags)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        val types = listOf(DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE, DetectedActivity.STILL,
            DetectedActivity.WALKING)
        val transitions = types.map {
            ActivityTransition.Builder().setActivityType(it)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        }
        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent)
    }
}

@AndroidEntryPoint
class ActivityTransitionReceiver : BroadcastReceiver() {

    @Inject lateinit var store: DataStore<Preferences>
    @Inject lateinit var autoMode: dagger.Lazy<AutoModeEvaluator>
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val event = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull() ?: return
        val motion = when (event.activityType) {
            DetectedActivity.IN_VEHICLE -> "VEHICLE"
            DetectedActivity.ON_BICYCLE -> "BICYCLE"
            else -> "STILL"
        }
        val pending = goAsync()
        scope.launch {
            try {
                store.edit {
                    it[PreferenceKeys.MOTION] = motion
                    it[PreferenceKeys.MOTION_AT] = System.currentTimeMillis()
                }
                autoMode.get().evaluate() // may turn IA Mode on when driving starts, off when it ends
            } finally {
                pending.finish()
            }
        }
    }
}
