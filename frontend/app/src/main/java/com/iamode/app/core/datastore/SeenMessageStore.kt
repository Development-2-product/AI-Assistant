package com.iamode.app.core.datastore

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which WhatsApp/Gmail messages were already handled, across conversations.
 * WhatsApp re-posts old unread messages in every notification update, so this prevents
 * replying twice to the same message after a conversation has ended.
 */
@Singleton
class SeenMessageStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("seen_messages", Context.MODE_PRIVATE)
    private val lock = Any()
    private val ids: LinkedHashSet<String> =
        LinkedHashSet(prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() })

    /** Returns true the first time an id is seen. */
    fun markIfNew(id: String): Boolean = synchronized(lock) {
        if (!ids.add(id)) return false
        while (ids.size > MAX) ids.remove(ids.first())
        prefs.edit().putString(KEY, ids.joinToString("\n")).apply()
        true
    }

    fun clear() = synchronized(lock) {
        ids.clear()
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY = "ids"
        const val MAX = 1500
    }
}
