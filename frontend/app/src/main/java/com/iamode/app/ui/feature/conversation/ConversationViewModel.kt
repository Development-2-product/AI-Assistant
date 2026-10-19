package com.iamode.app.ui.feature.conversation

import com.iamode.app.core.i18n.tr

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.Message
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.model.ReplyStyle
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.usecase.ConversationActionsUseCase
import com.iamode.app.core.database.dao.GmailAccountDao
import com.iamode.app.core.database.dao.OutlookAccountDao
import com.iamode.app.core.database.dao.ChatCommitmentDao
import com.iamode.app.core.database.entity.ChatCommitmentEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationUiState(
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val emailAccounts: List<String> = emptyList(),
    val commitments: List<ChatCommitmentEntity> = emptyList(),
)

@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    conversations: ConversationRepository,
    private val actions: ConversationActionsUseCase,
    gmailAccounts: GmailAccountDao,
    outlookAccounts: OutlookAccountDao,
    commitments: ChatCommitmentDao,
) : ViewModel() {

    private val id: String = checkNotNull(savedState["id"])
    val conversationId: String get() = id

    /** Passed in the route so the header (and its shared-element animation) is ready on the first frame. */
    val initialName: String = savedState.get<String>("name").orEmpty()
    val initialRelationship: Relationship =
        Relationship.entries.firstOrNull { it.name == savedState.get<String>("rel") } ?: Relationship.UNKNOWN
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val coreState = combine(
        conversations.observe(id), conversations.observeMessages(id), busy.asStateFlow(), error.asStateFlow(),
    ) { c, m, b, e -> ConversationUiState(c, m, b, e) }
    private val emailAccounts = combine(gmailAccounts.observeAll(), outlookAccounts.observeAll()) { gmail, outlook ->
        (gmail.map { it.email } + outlook.map { it.email }).distinct()
    }
    val state: StateFlow<ConversationUiState> = combine(coreState, emailAccounts, commitments.byConversation(id)) { core, accounts, items ->
        core.copy(emailAccounts = accounts, commitments = items.filter { it.status !in setOf("COMPLETED", "DISMISSED") })
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    fun approve(text: String, handleChat: Boolean) = launchAction { actions.approve(id, text.trim(), handleChat) }
    fun dontReply() = launchAction { actions.dontReply(id) }
    fun endChat() = launchAction { actions.endChat(id) }
    fun undo() = launchAction { actions.undoSend(id) }
    fun sendNow() = launchAction { actions.sendNow(id) }
    fun takeOver() = launchAction { actions.takeOver(id) }
    fun letIAModeHandle() = launchAction { actions.letIAModeHandle(id) }
    fun markCalledBack() = launchAction { actions.markCalledBack(id) }
    fun restyle(style: ReplyStyle) = launchAction {
        actions.restyle(id, style).onFailure { error.value = tr("Couldn't rewrite the reply. Check your connection.") }
    }
    fun clearError() { error.value = null }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try { block() } finally { busy.value = false }
        }
    }
}
