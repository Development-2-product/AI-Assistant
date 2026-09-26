package com.iamode.app.service.call

import android.telecom.Call
import android.telecom.CallScreeningService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Holds the "Caller ID & spam" role. It never blocks or silences calls; it only learns the
 * incoming number so IA Mode can text the caller if the call is missed.
 * Grant Contacts permission too, otherwise Android skips screening for saved contacts.
 */
@AndroidEntryPoint
class IACallScreeningService : CallScreeningService() {

    @Inject lateinit var tracker: CallTracker

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection == Call.Details.DIRECTION_INCOMING) {
            details.handle?.schemeSpecificPart?.let(tracker::onIncomingScreened)
        }
        respondToCall(details, CallResponse.Builder().build()) // allow the call normally
    }
}
