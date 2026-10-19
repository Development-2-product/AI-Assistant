package com.iamode.app.domain.policy

import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Contact
import com.iamode.app.domain.model.ContactAutoReplyControl
import java.time.Instant
import java.time.ZoneId

/** Pure, local-only contact restrictions applied before a reply can be queued. */
object ContactReplyRules {
    fun restriction(contact: Contact?, channel: Channel, atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        contact ?: return null
        when (contact.autoReplyControl) {
            ContactAutoReplyControl.ALWAYS_APPROVE -> return "${contact.displayName} always requires your approval"
            ContactAutoReplyControl.NEVER_AUTO_REPLY -> return "Automatic replies are disabled for ${contact.displayName}"
            ContactAutoReplyControl.DEFAULT -> Unit
        }
        if (channel !in contact.allowedAutoReplyChannels) return "Automatic replies are disabled for ${channel.label} with ${contact.displayName}"
        val start = contact.autoReplyStartHour
        val end = contact.autoReplyEndHour
        if (start != null && end != null && start != end) {
            val hour = Instant.ofEpochMilli(atMillis).atZone(zone).hour
            val inWindow = if (start < end) hour in start until end else hour >= start || hour < end
            if (!inWindow) return "${contact.displayName}'s automatic replies are outside their allowed hours"
        }
        return null
    }
}
