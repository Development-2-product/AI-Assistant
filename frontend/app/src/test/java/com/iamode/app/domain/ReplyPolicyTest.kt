package com.iamode.app.domain

import com.iamode.app.domain.model.Analysis
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.ConversationState
import com.iamode.app.domain.model.Language
import com.iamode.app.domain.model.LanguageCode
import com.iamode.app.domain.model.MoneyMode
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.model.ReplyStyle
import com.iamode.app.domain.model.Script
import com.iamode.app.domain.model.UserSettings
import com.iamode.app.domain.policy.ReplyPolicy
import com.iamode.app.domain.policy.ReplyPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyPolicyTest {

    private val settings = UserSettings()

    private fun analysis(
        crisis: Boolean = false, needsReply: Boolean = true, state: ConversationState = ConversationState.ONGOING,
        money: Boolean = false,
    ) = Analysis(Language(LanguageCode.TE, Script.ROMAN), "casual", "", money, false, crisis, needsReply, state, ReplyStyle.CASUAL)

    private fun input(r: Relationship, autopilot: Boolean = false, turns: Int = 0, channel: Channel = Channel.WHATSAPP) =
        ReplyPolicy.Input(r, channel, "Ravi Kumar", autopilot, turns, hasOurReplies = turns > 0)

    @Test fun `client gets automatic reply`() {
        assertTrue(ReplyPolicy.decide(input(Relationship.CLIENT), analysis(), settings) is Decision.AutoSend)
    }

    @Test fun `friend needs approval`() {
        assertTrue(ReplyPolicy.decide(input(Relationship.FRIEND), analysis(), settings) is Decision.NeedsApproval)
    }

    @Test fun `unknown always needs approval even if set to auto`() {
        val s = settings.copy(replyModes = settings.replyModes + (Relationship.UNKNOWN to com.iamode.app.domain.model.ReplyMode.AUTO))
        assertTrue(ReplyPolicy.decide(input(Relationship.UNKNOWN), analysis(), s) is Decision.NeedsApproval)
    }

    @Test fun `approved friend chat continues on autopilot`() {
        assertTrue(ReplyPolicy.decide(input(Relationship.FRIEND, autopilot = true, turns = 1), analysis(), settings) is Decision.AutoSend)
    }

    @Test fun `autopilot pauses on money`() {
        val d = ReplyPolicy.decide(input(Relationship.PARTNER, autopilot = true), analysis(money = true), settings)
        assertTrue(d is Decision.NeedsApproval && d.paused)
    }

    @Test fun `business money in safe mode sends with follow up`() {
        val d = ReplyPolicy.decide(input(Relationship.BUSINESS), analysis(money = true), settings)
        assertTrue(d is Decision.AutoSend && d.followUp)
    }

    @Test fun `business money in ask mode needs approval`() {
        val d = ReplyPolicy.decide(input(Relationship.BUSINESS), analysis(money = true), settings.copy(moneyMode = MoneyMode.ASK))
        assertTrue(d is Decision.NeedsApproval)
    }

    @Test fun `crisis is never answered`() {
        assertTrue(ReplyPolicy.decide(input(Relationship.CLIENT), analysis(crisis = true), settings) is Decision.Crisis)
    }

    @Test fun `ended conversation stops without reply`() {
        val d = ReplyPolicy.decide(input(Relationship.CLIENT, turns = 2), analysis(state = ConversationState.ENDED), settings)
        assertTrue(d is Decision.End)
        assertTrue(d.reason.startsWith("Ravi closed"))
    }

    @Test fun `auto reply limit pauses`() {
        val d = ReplyPolicy.decide(input(Relationship.CLIENT, turns = 6), analysis(), settings)
        assertTrue(d is Decision.NeedsApproval)
    }

    @Test fun `email always needs explicit approval`() {
        val d = ReplyPolicy.decide(input(Relationship.CLIENT, channel = Channel.GMAIL), analysis(), settings.copy(emailAutoReply = true))
        assertTrue(d is Decision.NeedsApproval)
    }

    @Test fun `groups always need approval even on autopilot`() {
        assertTrue(ReplyPolicy.decide(input(Relationship.GROUP, autopilot = true), analysis(), settings) is Decision.NeedsApproval)
    }

    @Test fun `newsletter is skipped`() {
        assertEquals(Decision.Skip::class, ReplyPolicy.decide(input(Relationship.UNKNOWN), analysis(needsReply = false), settings)::class)
    }
}
