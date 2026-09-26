package com.iamode.app.domain.repository

/** Looks up names in the phone's own contacts (READ_CONTACTS). */
interface Phonebook {
    suspend fun nameForNumber(number: String): String?
}

/** Android-side work that starts and stops with IA Mode (activity recognition, Gmail sync). */
interface ModeLifecycleHooks {
    suspend fun onModeStarted()
    suspend fun onModeStopped()
}

/** Remembers an automatic trigger the user overrode by switching IA Mode off, so it doesn't turn back on. */
interface AutoModeStateStore {
    suspend fun suppressedKey(): String?
    suspend fun setSuppressedKey(key: String?)
}

/** Re-runs the AI for a conversation later (e.g. when the network comes back). */
interface AiRetryScheduler {
    fun scheduleRetry(conversationId: String)
}
