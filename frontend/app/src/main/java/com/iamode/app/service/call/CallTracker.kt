package com.iamode.app.service.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.iamode.app.core.di.ApplicationScope
import com.iamode.app.domain.usecase.HandleMissedCallUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Combines the caller's number (from [IACallScreeningService]) with call-state changes to
 * detect calls that rang and were never answered: RINGING -> IDLE without OFFHOOK.
 */
@Singleton
class CallTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val handleMissedCall: HandleMissedCallUseCase,
) {
    @Volatile private var incomingNumber: String? = null
    @Volatile private var ringing = false
    @Volatile private var answered = false

    private val telephony get() = context.getSystemService(TelephonyManager::class.java)
    private var callback: Any? = null

    fun onIncomingScreened(number: String) {
        incomingNumber = number
    }

    fun start() {
        if (callback != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) registerModern() else registerLegacy()
    }

    fun stop() {
        val cb = callback ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cb is TelephonyCallback) {
            telephony.unregisterTelephonyCallback(cb)
        } else if (cb is PhoneStateListener) {
            @Suppress("DEPRECATION")
            telephony.listen(cb, PhoneStateListener.LISTEN_NONE)
        }
        callback = null
    }

    @SuppressLint("MissingPermission") // checked in start()
    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerModern(): TelephonyCallback {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = onState(state)
        }
        telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
        return cb
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun registerLegacy(): PhoneStateListener {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state)
        }
        telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        return listener
    }

    private fun onState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> { ringing = true; answered = false }
            TelephonyManager.CALL_STATE_OFFHOOK -> if (ringing) answered = true
            TelephonyManager.CALL_STATE_IDLE -> {
                val number = incomingNumber
                if (ringing && !answered && number != null) scope.launch { handleMissedCall(number) }
                ringing = false
                answered = false
                incomingNumber = null
            }
        }
    }
}
