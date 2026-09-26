package com.iamode.app.data.mail

import com.iamode.app.core.database.dao.MailWorkflowDao
import com.iamode.app.core.database.entity.CelebrationEntity
import com.iamode.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which celebration plays. [claim] is an atomic database compare-and-set, so the same email's
 * celebration plays exactly once, even across screens, restarts and re-syncs. Replays are explicit.
 */
@Singleton
class CelebrationCoordinator @Inject constructor(
    private val dao: MailWorkflowDao,
    settings: SettingsRepository,
) {
    val next = combine(dao.observeNextEligible(), settings.settings) { c, s -> if (s.celebrations) c else null }

    private val replayFlow = MutableSharedFlow<CelebrationEntity>(extraBufferCapacity = 1)
    val replays = replayFlow.asSharedFlow()

    /** True only for the one caller that moves ELIGIBLE -> SHOWN. */
    suspend fun claim(id: String): Boolean = dao.markShown(id, System.currentTimeMillis()) == 1

    suspend fun replay(emailId: String): Boolean {
        val c = dao.celebrationFor(emailId) ?: return false
        dao.countReplay(c.id)
        return replayFlow.tryEmit(c)
    }

    suspend fun forEmail(emailId: String) = dao.celebrationFor(emailId)
}
