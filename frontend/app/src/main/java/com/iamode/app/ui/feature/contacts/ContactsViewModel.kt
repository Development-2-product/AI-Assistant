package com.iamode.app.ui.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamode.app.domain.model.Contact
import com.iamode.app.domain.repository.ContactRepository
import com.iamode.app.core.database.dao.ChatCommitmentDao
import com.iamode.app.core.database.dao.ConversationDao
import com.iamode.app.core.database.dao.MessageDao
import com.iamode.app.service.worker.CommitmentReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contacts: ContactRepository,
    private val commitments: ChatCommitmentDao,
    conversations: ConversationDao,
    messages: MessageDao,
    private val reminders: CommitmentReminderScheduler,
) : ViewModel() {
    data class CommitmentCounts(val open: Int = 0, val overdue: Int = 0, val completed: Int = 0)
    /** Derived locally from encrypted metadata. It deliberately never reads message text. */
    data class RelationshipInsight(
        val conversations: Int = 0, val received: Int = 0, val sent: Int = 0,
        val needsReview: Int = 0, val lastActivity: Long? = null,
    )
    val all: StateFlow<List<Contact>> = contacts.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val commitmentCounts: StateFlow<Map<String, CommitmentCounts>> = commitments.all().map { rows ->
        val now = System.currentTimeMillis()
        rows.filter { it.contactId != null }.groupBy { it.contactId!! }.mapValues { (_, items) ->
            CommitmentCounts(
                open = items.count { it.status in setOf("REVIEW_NEEDED", "CONFIRMED", "SNOOZED") },
                overdue = items.count { it.status in setOf("CONFIRMED", "SNOOZED") && it.dueAt?.let { due -> due < now } == true },
                completed = items.count { it.status == "COMPLETED" },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val relationshipInsights: StateFlow<Map<String, RelationshipInsight>> = combine(
        contacts.observeAll(), conversations.observeAll(), messages.observeCountsByConversation(),
    ) { people, chats, messageCounts ->
        val counts = messageCounts.associateBy { it.conversationId }
        people.associate { person ->
            val matched = chats.filter { chat -> matches(person, chat.address) }
            person.id to RelationshipInsight(
                conversations = matched.size,
                received = matched.sumOf { counts[it.id]?.received ?: 0 },
                sent = matched.sumOf { counts[it.id]?.sent ?: 0 },
                needsReview = matched.count { it.status == "PENDING_APPROVAL" },
                lastActivity = matched.maxOfOrNull { it.updatedAt },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun save(contact: Contact) = viewModelScope.launch { contacts.upsert(contact) }
    fun delete(id: String) = viewModelScope.launch {
        // Do not leave a future notification or private source excerpt after a contact is removed.
        commitments.byContact(id).first().forEach { reminders.cancel(it.id) }
        commitments.deleteForContact(id)
        contacts.delete(id)
    }

    private fun matches(contact: Contact, address: String): Boolean {
        val normalizedAddress = address.filter(Char::isLetterOrDigit).lowercase()
        val phone = contact.phone?.filter(Char::isDigit)
        val email = contact.email?.trim()?.lowercase()
        return (phone != null && normalizedAddress.endsWith(phone)) || (email != null && address.trim().lowercase() == email)
    }
}
