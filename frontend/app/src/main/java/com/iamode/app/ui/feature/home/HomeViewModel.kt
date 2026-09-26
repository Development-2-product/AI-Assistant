package com.iamode.app.ui.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.model.Session
import com.iamode.app.domain.model.SessionSummary
import com.iamode.app.domain.model.Situation
import com.iamode.app.domain.model.SituationStatus
import com.iamode.app.domain.repository.AlertRepository
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.domain.repository.SituationProvider
import com.iamode.app.domain.usecase.BuildSessionSummaryUseCase
import com.iamode.app.domain.usecase.ConversationActionsUseCase
import com.iamode.app.domain.usecase.ToggleIAModeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HomeTab(val label: String) { NEEDS_YOU("Needs you"), HANDLING("Handling"), FINISHED("Finished") }

data class HomeUiState(
    val session: Session? = null,
    val situation: Situation? = null,
    val manualSituation: SituationStatus? = null,
    val needsYou: List<Conversation> = emptyList(),
    val handling: List<Conversation> = emptyList(),
    val finished: List<Conversation> = emptyList(),
    val alertCount: Int = 0,
    /** Last finished session, shown as "While you were busy" while IA Mode is off. */
    val lastSummary: SessionSummary? = null,
) {
    val isOn get() = session != null

    fun listFor(tab: HomeTab): List<Conversation> = when (tab) {
        HomeTab.NEEDS_YOU -> needsYou
        HomeTab.HANDLING -> handling
        HomeTab.FINISHED -> finished
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    sessions: SessionRepository,
    conversations: ConversationRepository,
    alerts: AlertRepository,
    private val settings: SettingsRepository,
    private val situationProvider: SituationProvider,
    private val toggleMode: ToggleIAModeUseCase,
    private val actions: ConversationActionsUseCase,
    private val buildSummary: BuildSessionSummaryUseCase,
) : ViewModel() {

    private val lastSummary = sessions.lastEnded.map { s -> s?.let { buildSummary(it.id) } }

    private val situation = settings.settings.flatMapLatest {
        flow { while (true) { emit(situationProvider.current()); delay(30_000) } }
    }

    val state: StateFlow<HomeUiState> = sessions.activeSession.flatMapLatest { session ->
        if (session == null) {
            combine(situation, settings.settings, lastSummary) { sit, s, last ->
                HomeUiState(situation = sit, manualSituation = s.manualSituation, lastSummary = last)
            }
        } else {
            combine(
                conversations.observeForSession(session.id),
                alerts.observeSince(session.startedAt),
                situation,
                settings.settings,
            ) { list, alertList, sit, s ->
                HomeUiState(
                    session = session, situation = sit, manualSituation = s.manualSituation,
                    needsYou = list.filter { it.status in NEEDS_YOU },
                    handling = list.filter { it.status in HANDLING },
                    finished = list.filter { !it.status.isOpen },
                    alertCount = alertList.size,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun toggle() = viewModelScope.launch { toggleMode.toggle() }

    fun setSituation(status: SituationStatus?) = viewModelScope.launch {
        settings.update { it.copy(manualSituation = status) }
    }

    fun undo(id: String) = viewModelScope.launch { actions.undoSend(id) }

    private companion object {
        val NEEDS_YOU = setOf(ConversationStatus.PENDING_APPROVAL, ConversationStatus.CRISIS, ConversationStatus.CALLBACK)
        val HANDLING = setOf(ConversationStatus.ANALYZING, ConversationStatus.QUEUED, ConversationStatus.WAITING)
    }

}
