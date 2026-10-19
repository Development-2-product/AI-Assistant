package com.iamode.app.domain.policy

import com.iamode.app.domain.model.Alert
import com.iamode.app.domain.model.AlertKind

/**
 * Privacy-safe presentation for the encrypted activity feed. Raw alert bodies can contain
 * draft text from older app versions, so they are never used in this surface.
 */
object AutomationAudit {
    data class Item(val title: String, val summary: String, val needsAttention: Boolean, val actionLabel: String?)

    fun present(alert: Alert): Item = when (alert.kind) {
        AlertKind.APPROVAL -> Item(alert.title, "A draft is ready for your review.", true, "Review")
        AlertKind.CRISIS -> Item(alert.title, "IA Mode did not send a reply. Your attention is needed.", true, "Open")
        AlertKind.CALLBACK -> Item(alert.title, "A callback needs your attention.", true, "Open")
        AlertKind.FOLLOW_UP -> Item(alert.title, "Review the conversation before taking any follow-up action.", true, "Review")
        AlertKind.ERROR -> Item(alert.title, "This action was not completed. Open the conversation to recover.", true, "Recover")
        AlertKind.POLICY -> Item(alert.title, "A contact safety rule prevented an automatic reply.", true, "Review")
        AlertKind.AUTOMATION -> Item(alert.title, "IA Mode recorded an automatic action. You can review it in the conversation.", false, "View")
        AlertKind.SENT -> Item(alert.title, "The action was completed.", false, "View")
        AlertKind.NEW_MAIL -> Item(alert.title, "New email activity was detected.", true, alert.conversationId?.let { "Open" })
        AlertKind.ENDED -> Item(alert.title, "No further automatic reply is planned for this conversation.", false, "View")
    }
}
