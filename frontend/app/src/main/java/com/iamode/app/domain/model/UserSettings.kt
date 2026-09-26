package com.iamode.app.domain.model

data class UserSettings(
    val onboardingDone: Boolean = false,
    val myName: String = "",
    val gender: Gender = Gender.MALE,
    val replyModes: Map<Relationship, ReplyMode> = DEFAULT_MODES,
    /** Email is approval-only by default.  Trusted automation is intentionally not implemented yet. */
    val emailAutoReply: Boolean = false,
    val moneyMode: MoneyMode = MoneyMode.SAFE_REPLY,
    val maxAutoReplies: Int = 6,
    val undoSeconds: Int = 8,
    val approvalNotifications: Boolean = true,
    val missedCallReplies: Boolean = true,
    val replyToUnknownCallers: Boolean = true,
    val defaultLanguage: LanguageCode = LanguageCode.TE,
    val defaultScript: Script = Script.ROMAN,
    val vehicleType: VehicleType = VehicleType.BIKE,
    val manualSituation: SituationStatus? = null,
    /** Relationship for saved phone contacts the user hasn't labelled yet. */
    val defaultSavedContactRelationship: Relationship = Relationship.FRIEND,
    /** Android 12+: take app colors from the wallpaper (Material You). */
    val useDynamicColor: Boolean = false,
    /** Show a silent "IA Mode is on" notification with a Turn off button. */
    val showStatusNotification: Boolean = true,
    /** Chat apps IA Mode reads and replies on. */
    val enabledApps: Set<Channel> = Channel.CHAT_APPS,
    /** Reply in group chats, only when someone mentions you. */
    val groupReplies: Boolean = true,
    /** Extra names people use for you in groups, comma separated (your first name is always included). */
    val groupNames: String = "",
    val autoMode: AutoModeSettings = AutoModeSettings(),
    /** Send anonymous crash reports (never message content). */
    val crashReports: Boolean = true,
    /** Understand incoming Gmail (categories, documents, interviews, opportunities). Replies always need approval. */
    val mailIntelligence: Boolean = true,
    /** The celebration animation for selections, offers and interviews. */
    val celebrations: Boolean = true,
    /** A short sound with the celebration. Off by default. */
    val celebrationSound: Boolean = false,
    /** Archive / move back in IA Mode also happens in Gmail. */
    val gmailMirror: Boolean = true,
    /** Tag understood mail with "IA Mode/…" labels in Gmail (never moves it). Off by default. */
    val gmailLabels: Boolean = false,
    /** Drafts sound like you: greeting, sign-off, length and tone learned from your sent mail. */
    val matchWritingStyle: Boolean = true,
    /** "auto" = reply in the email's language and script; otherwise an ISO code (en, te, hi, ta, kn, ml). */
    val mailReplyLanguage: String = "auto",
    /** Daily nudges for follow-ups, deadlines and offers in the job tracker. */
    val jobReminders: Boolean = true,
) {
    /** Every name that counts as a mention in a group. */
    val mentionNames: List<String>
        get() = (listOf(myName.trim().substringBefore(" ")) + groupNames.split(","))
            .map { it.trim() }.filter { it.length >= 2 }.distinctBy { it.lowercase() }

    fun modeFor(relationship: Relationship): ReplyMode =
        if (relationship == Relationship.UNKNOWN || relationship == Relationship.GROUP) ReplyMode.APPROVE
        else replyModes[relationship] ?: ReplyMode.APPROVE

    companion object {
        val DEFAULT_MODES = mapOf(
            Relationship.CLIENT to ReplyMode.AUTO,
            Relationship.BUSINESS to ReplyMode.AUTO,
            Relationship.PARTNER to ReplyMode.APPROVE,
            Relationship.FRIEND to ReplyMode.APPROVE,
            Relationship.FAMILY to ReplyMode.APPROVE,
        )
    }
}
