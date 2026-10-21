package com.iamode.app.data.mail

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small, local-only mailbox health metadata. It contains no email/account identifiers and
 * survives process death, so the inbox can honestly say when it last completed a sync.
 */
data class MailSyncStatus(
    val lastAttemptAt: Long? = null,
    val lastSuccessfulAt: Long? = null,
)

@Singleton
class MailSyncStatusStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(read())
    val status: StateFlow<MailSyncStatus> = _status

    fun markStarted(now: Long = System.currentTimeMillis()) = update { it.copy(lastAttemptAt = now) }
    fun markSucceeded(now: Long = System.currentTimeMillis()) = update { it.copy(lastSuccessfulAt = now) }

    private fun update(transform: (MailSyncStatus) -> MailSyncStatus) {
        val next = transform(_status.value)
        _status.value = next
        preferences.edit()
            .putLong(LAST_ATTEMPT, next.lastAttemptAt ?: 0L)
            .putLong(LAST_SUCCESS, next.lastSuccessfulAt ?: 0L)
            .apply()
    }

    private fun read() = MailSyncStatus(
        lastAttemptAt = preferences.getLong(LAST_ATTEMPT, 0L).takeIf { it > 0L },
        lastSuccessfulAt = preferences.getLong(LAST_SUCCESS, 0L).takeIf { it > 0L },
    )

    private companion object {
        const val PREFERENCES = "mail_sync_status"
        const val LAST_ATTEMPT = "last_attempt_at"
        const val LAST_SUCCESS = "last_success_at"
    }
}
