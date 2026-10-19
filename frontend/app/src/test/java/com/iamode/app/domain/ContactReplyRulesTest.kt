package com.iamode.app.domain

import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Contact
import com.iamode.app.domain.model.ContactAutoReplyControl
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.policy.ContactReplyRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ContactReplyRulesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(hour: Int) = ZonedDateTime.of(2026, 1, 2, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun contact(control: ContactAutoReplyControl = ContactAutoReplyControl.DEFAULT, channels: Set<Channel> = Channel.entries.toSet(), start: Int? = null, end: Int? = null) =
        Contact("c", "Anita", relationship = Relationship.CLIENT, autoReplyControl = control,
            allowedAutoReplyChannels = channels, autoReplyStartHour = start, autoReplyEndHour = end)

    @Test fun `always approve blocks automatic reply`() {
        assertEquals("Anita always requires your approval", ContactReplyRules.restriction(contact(ContactAutoReplyControl.ALWAYS_APPROVE), Channel.WHATSAPP, at(10), zone))
    }

    @Test fun `channel block is contact specific`() {
        val reason = ContactReplyRules.restriction(contact(channels = setOf(Channel.TELEGRAM)), Channel.WHATSAPP, at(10), zone)
        assertEquals("Automatic replies are disabled for WhatsApp with Anita", reason)
    }

    @Test fun `overnight allowed hours are handled locally`() {
        val c = contact(start = 22, end = 7)
        assertNull(ContactReplyRules.restriction(c, Channel.WHATSAPP, at(23), zone))
        assertEquals("Anita's automatic replies are outside their allowed hours", ContactReplyRules.restriction(c, Channel.WHATSAPP, at(12), zone))
    }
}
