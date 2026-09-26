package com.iamode.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.iamode.app.core.datastore.PreferenceKeys as K
import com.iamode.app.domain.model.AutoModeSettings
import com.iamode.app.domain.model.AutoSchedule
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.model.ReplyMode
import com.iamode.app.domain.model.SituationStatus
import com.iamode.app.domain.model.UserSettings
import com.iamode.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<UserSettings> = store.data.map { it.toSettings() }

    override suspend fun current(): UserSettings = settings.first()

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        store.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[K.ONBOARDING_DONE] = s.onboardingDone
            prefs[K.MY_NAME] = s.myName
            prefs[K.GENDER] = s.gender.name
            prefs[K.REPLY_MODES] = s.replyModes.entries.joinToString(",") { "${it.key.name}=${it.value.name}" }
            prefs[K.EMAIL_AUTO] = s.emailAutoReply
            prefs[K.MONEY_MODE] = s.moneyMode.name
            prefs[K.MAX_AUTO_REPLIES] = s.maxAutoReplies
            prefs[K.UNDO_SECONDS] = s.undoSeconds
            prefs[K.APPROVAL_NOTIFICATIONS] = s.approvalNotifications
            prefs[K.MISSED_CALL_REPLIES] = s.missedCallReplies
            prefs[K.REPLY_UNKNOWN_CALLERS] = s.replyToUnknownCallers
            prefs[K.DEFAULT_LANGUAGE] = s.defaultLanguage.name
            prefs[K.DEFAULT_SCRIPT] = s.defaultScript.name
            prefs[K.VEHICLE_TYPE] = s.vehicleType.name
            prefs[K.MANUAL_SITUATION] = s.manualSituation?.name.orEmpty()
            prefs[K.DEFAULT_SAVED_RELATIONSHIP] = s.defaultSavedContactRelationship.name
            prefs[K.DYNAMIC_COLOR] = s.useDynamicColor
            prefs[K.STATUS_NOTIFICATION] = s.showStatusNotification
            prefs[K.ENABLED_APPS] = s.enabledApps.joinToString(",") { it.name }
            prefs[K.GROUP_REPLIES] = s.groupReplies
            prefs[K.GROUP_NAMES] = s.groupNames
            prefs[K.AUTO_DRIVING] = s.autoMode.whenDriving
            prefs[K.AUTO_MEETINGS] = s.autoMode.duringMeetings
            prefs[K.AUTO_SCHEDULES] = s.autoMode.schedules.joinToString(";") { it.encode() }
            prefs[K.CRASH_REPORTS] = s.crashReports
            prefs[K.MAIL_INTELLIGENCE] = s.mailIntelligence
            prefs[K.CELEBRATIONS] = s.celebrations
            prefs[K.CELEBRATION_SOUND] = s.celebrationSound
            prefs[K.GMAIL_MIRROR] = s.gmailMirror
            prefs[K.GMAIL_LABELS] = s.gmailLabels
            prefs[K.MATCH_WRITING_STYLE] = s.matchWritingStyle
            prefs[K.MAIL_REPLY_LANGUAGE] = s.mailReplyLanguage
            prefs[K.JOB_REMINDERS] = s.jobReminders
        }
    }

    private fun Preferences.toSettings(): UserSettings {
        val d = UserSettings()
        val modes = this[K.REPLY_MODES]?.split(",")?.mapNotNull { pair ->
            val (rel, mode) = pair.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
            val r = Relationship.entries.firstOrNull { it.name == rel } ?: return@mapNotNull null
            val m = ReplyMode.entries.firstOrNull { it.name == mode } ?: return@mapNotNull null
            r to m
        }?.toMap()
        return UserSettings(
            onboardingDone = this[K.ONBOARDING_DONE] ?: d.onboardingDone,
            myName = this[K.MY_NAME] ?: d.myName,
            gender = enumOr(this[K.GENDER], d.gender),
            replyModes = if (modes.isNullOrEmpty()) d.replyModes else d.replyModes + modes,
            emailAutoReply = this[K.EMAIL_AUTO] ?: d.emailAutoReply,
            moneyMode = enumOr(this[K.MONEY_MODE], d.moneyMode),
            maxAutoReplies = this[K.MAX_AUTO_REPLIES] ?: d.maxAutoReplies,
            undoSeconds = this[K.UNDO_SECONDS] ?: d.undoSeconds,
            approvalNotifications = this[K.APPROVAL_NOTIFICATIONS] ?: d.approvalNotifications,
            missedCallReplies = this[K.MISSED_CALL_REPLIES] ?: d.missedCallReplies,
            replyToUnknownCallers = this[K.REPLY_UNKNOWN_CALLERS] ?: d.replyToUnknownCallers,
            defaultLanguage = enumOr(this[K.DEFAULT_LANGUAGE], d.defaultLanguage),
            defaultScript = enumOr(this[K.DEFAULT_SCRIPT], d.defaultScript),
            vehicleType = enumOr(this[K.VEHICLE_TYPE], d.vehicleType),
            manualSituation = this[K.MANUAL_SITUATION]?.let { v -> SituationStatus.entries.firstOrNull { it.name == v } },
            defaultSavedContactRelationship = enumOr(this[K.DEFAULT_SAVED_RELATIONSHIP], d.defaultSavedContactRelationship),
            useDynamicColor = this[K.DYNAMIC_COLOR] ?: d.useDynamicColor,
            showStatusNotification = this[K.STATUS_NOTIFICATION] ?: d.showStatusNotification,
            enabledApps = this[K.ENABLED_APPS]?.split(",")
                ?.mapNotNull { v -> Channel.entries.firstOrNull { it.name == v } }?.toSet() ?: d.enabledApps,
            groupReplies = this[K.GROUP_REPLIES] ?: d.groupReplies,
            groupNames = this[K.GROUP_NAMES] ?: d.groupNames,
            autoMode = AutoModeSettings(
                whenDriving = this[K.AUTO_DRIVING] ?: false,
                duringMeetings = this[K.AUTO_MEETINGS] ?: false,
                schedules = this[K.AUTO_SCHEDULES].orEmpty().split(";").filter { it.isNotBlank() }.mapNotNull(AutoSchedule::decode),
            ),
            crashReports = this[K.CRASH_REPORTS] ?: d.crashReports,
            mailIntelligence = this[K.MAIL_INTELLIGENCE] ?: d.mailIntelligence,
            celebrations = this[K.CELEBRATIONS] ?: d.celebrations,
            celebrationSound = this[K.CELEBRATION_SOUND] ?: d.celebrationSound,
            gmailMirror = this[K.GMAIL_MIRROR] ?: d.gmailMirror,
            gmailLabels = this[K.GMAIL_LABELS] ?: d.gmailLabels,
            matchWritingStyle = this[K.MATCH_WRITING_STYLE] ?: d.matchWritingStyle,
            mailReplyLanguage = this[K.MAIL_REPLY_LANGUAGE] ?: d.mailReplyLanguage,
            jobReminders = this[K.JOB_REMINDERS] ?: d.jobReminders,
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

}
