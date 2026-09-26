package com.iamode.app.core.permissions

import android.Manifest
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.net.Uri
import androidx.core.content.ContextCompat
import com.iamode.app.service.notification.IANotificationListener

/** What IA Mode needs, why, and how to check each one. */
enum class AppPermission(val title: String, val why: String, val required: Boolean) {
    NOTIFICATION_ACCESS("Notification access", "Read and reply to WhatsApp messages, and notice new Gmail.", true),
    POST_NOTIFICATIONS("Show notifications", "Ask you to approve replies and confirm what was sent.", true),
    CALL_SCREENING("Caller ID & spam app", "Know who is calling so IA Mode can text them if you can't pick up.", true),
    PHONE_STATE("Phone calls", "Notice when a call was missed.", true),
    SEND_SMS("Send SMS", "Text callers when you miss their call.", true),
    CONTACTS("Contacts", "Recognise saved contacts by name.", true),
    CALENDAR("Calendar", "Know when you're in an interview or meeting.", false),
    ACTIVITY_RECOGNITION("Physical activity", "Know when you're driving or riding.", false),
    USAGE_ACCESS("Usage access", "Know when you're playing a game like Free Fire or BGMI.", false),
    BATTERY("Unrestricted battery", "Stop Android from pausing IA Mode in the background. Strongly recommended.", false),
}

object AppPermissions {

    fun isGranted(context: Context, p: AppPermission): Boolean = when (p) {
        AppPermission.NOTIFICATION_ACCESS -> notificationAccess(context)
        AppPermission.POST_NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 || runtime(context, Manifest.permission.POST_NOTIFICATIONS)
        AppPermission.CALL_SCREENING -> context.getSystemService(RoleManager::class.java)
            .isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        AppPermission.PHONE_STATE -> runtime(context, Manifest.permission.READ_PHONE_STATE)
        AppPermission.SEND_SMS -> runtime(context, Manifest.permission.SEND_SMS)
        AppPermission.CONTACTS -> runtime(context, Manifest.permission.READ_CONTACTS)
        AppPermission.CALENDAR -> runtime(context, Manifest.permission.READ_CALENDAR)
        AppPermission.ACTIVITY_RECOGNITION -> runtime(context, Manifest.permission.ACTIVITY_RECOGNITION)
        AppPermission.USAGE_ACCESS -> usageAccess(context)
        AppPermission.BATTERY -> context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Runtime permission string, or null for special-access permissions handled via [settingsIntent]. */
    fun runtimePermission(p: AppPermission): String? = when (p) {
        AppPermission.POST_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        AppPermission.PHONE_STATE -> Manifest.permission.READ_PHONE_STATE
        AppPermission.SEND_SMS -> Manifest.permission.SEND_SMS
        AppPermission.CONTACTS -> Manifest.permission.READ_CONTACTS
        AppPermission.CALENDAR -> Manifest.permission.READ_CALENDAR
        AppPermission.ACTIVITY_RECOGNITION -> Manifest.permission.ACTIVITY_RECOGNITION
        else -> null
    }

    fun settingsIntent(context: Context, p: AppPermission): Intent? = when (p) {
        AppPermission.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        AppPermission.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        // This is initiated only after the user taps Allow. Android owns the final decision.
        AppPermission.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        AppPermission.CALL_SCREENING -> context.getSystemService(RoleManager::class.java)
            .createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
        else -> null
    }

    /** Phone makers that stop background apps unless "Autostart" is allowed in their own settings. */
    fun needsAutostartHint(): Boolean =
        Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco", "oppo", "realme", "vivo", "iqoo", "oneplus", "huawei", "honor")

    fun missingRequired(context: Context) = AppPermission.entries.filter { it.required && !isGranted(context, it) }

    private fun runtime(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun notificationAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, IANotificationListener::class.java).flattenToString()
        return flat.split(":").any { it == me }
    }

    @Suppress("DEPRECATION")
    private fun usageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
