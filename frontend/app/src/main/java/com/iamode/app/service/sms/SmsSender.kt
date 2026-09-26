package com.iamode.app.service.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.iamode.app.domain.model.SendResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends missed-call replies. Note: Google Play restricts SEND_SMS; declare the use case in the
 * Play Console permissions declaration before publishing.
 */
@Singleton
class SmsSender @Inject constructor(@ApplicationContext private val context: Context) {

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()

    fun send(number: String, text: String): SendResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SendResult.Failed("SMS permission isn't granted")
        }
        return try {
            val sms = smsManager()
            val destination = if (number.startsWith("+")) number else "+$number"
            sms.sendMultipartTextMessage(destination, null, sms.divideMessage(text), null, null)
            SendResult.Sent
        } catch (e: Exception) {
            SendResult.Failed("The SMS couldn't be sent")
        }
    }
}
