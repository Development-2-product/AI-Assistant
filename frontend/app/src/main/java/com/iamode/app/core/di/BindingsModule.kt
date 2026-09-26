package com.iamode.app.core.di

import com.iamode.app.core.notifications.AppNotifier
import com.iamode.app.data.ai.AiRepositoryImpl
import com.iamode.app.data.repository.AlertRepositoryImpl
import com.iamode.app.data.repository.AutoModeStateStoreImpl
import com.iamode.app.data.repository.ContactRepositoryImpl
import com.iamode.app.data.repository.ConversationRepositoryImpl
import com.iamode.app.data.repository.MessageSenderImpl
import com.iamode.app.data.repository.SessionRepositoryImpl
import com.iamode.app.data.repository.SettingsRepositoryImpl
import com.iamode.app.domain.repository.AiRepository
import com.iamode.app.domain.repository.AiRetryScheduler
import com.iamode.app.domain.repository.AlertRepository
import com.iamode.app.domain.repository.AutoModeStateStore
import com.iamode.app.domain.repository.ContactRepository
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.MessageSender
import com.iamode.app.domain.repository.ModeLifecycleHooks
import com.iamode.app.domain.repository.Notifier
import com.iamode.app.domain.repository.Phonebook
import com.iamode.app.domain.repository.ReplyScheduler
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.domain.repository.SituationProvider
import com.iamode.app.service.situation.AndroidPhonebook
import com.iamode.app.service.situation.SituationDetector
import com.iamode.app.service.worker.ModeLifecycle
import com.iamode.app.service.worker.SendScheduler
import com.iamode.app.service.worker.WorkManagerAiRetryScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun conversations(impl: ConversationRepositoryImpl): ConversationRepository
    @Binds abstract fun contacts(impl: ContactRepositoryImpl): ContactRepository
    @Binds abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun sessions(impl: SessionRepositoryImpl): SessionRepository
    @Binds abstract fun alerts(impl: AlertRepositoryImpl): AlertRepository
    @Binds abstract fun ai(impl: AiRepositoryImpl): AiRepository
    @Binds abstract fun sender(impl: MessageSenderImpl): MessageSender
    @Binds abstract fun notifier(impl: AppNotifier): Notifier
    @Binds abstract fun situation(impl: SituationDetector): SituationProvider
    @Binds abstract fun scheduler(impl: SendScheduler): ReplyScheduler
    @Binds abstract fun phonebook(impl: AndroidPhonebook): Phonebook
    @Binds abstract fun hooks(impl: ModeLifecycle): ModeLifecycleHooks
    @Binds abstract fun aiRetry(impl: WorkManagerAiRetryScheduler): AiRetryScheduler
    @Binds abstract fun autoState(impl: AutoModeStateStoreImpl): AutoModeStateStore
}
