package com.iamode.app.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannels {
    const val APPROVALS = "approvals"
    const val ACTIVITY = "activity"
    const val URGENT = "urgent"
    const val ACCOUNT = "account"
    const val STATUS = "status"

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(APPROVALS, "Replies waiting for approval", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Approve, edit or skip replies to friends, family and your partner"
                },
                NotificationChannel(ACTIVITY, "Sent replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Confirmation each time IA Mode sends a message for you"
                },
                NotificationChannel(URGENT, "Needs you personally", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Messages IA Mode will never answer for you"
                },
                NotificationChannel(ACCOUNT, "Account and connection", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(STATUS, "IA Mode is on", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "A silent reminder while IA Mode is replying for you, with a Turn off button"
                    setShowBadge(false)
                },
            )
        )
    }
}
