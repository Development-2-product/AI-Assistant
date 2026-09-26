package com.iamode.app.domain

import com.iamode.app.domain.model.AutoSchedule
import com.iamode.app.domain.model.AutoTrigger
import com.iamode.app.domain.model.Session
import com.iamode.app.domain.model.StartSource
import com.iamode.app.domain.policy.AutoModePolicy
import com.iamode.app.domain.policy.AutoModePolicy.Action
import com.iamode.app.domain.util.MentionDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AutoModeTest {
    private val driving = AutoTrigger("driving", "You started driving")
    private val meeting = AutoTrigger("meeting:7", "Calendar: Standup")
    private fun auto(key: String) = Session("s", 0, startedBy = StartSource.AUTO, autoKey = key)

    @Test fun `turns on when a trigger starts`() =
        assertEquals(Action.TurnOn(driving), AutoModePolicy.decide(listOf(driving), null, null).action)

    @Test fun `never turns off a session the user started`() =
        assertEquals(Action.None, AutoModePolicy.decide(emptyList(), Session("s", 0), null).action)

    @Test fun `turns off when its trigger ends`() =
        assertEquals(Action.TurnOff, AutoModePolicy.decide(emptyList(), auto("driving"), null).action)

    @Test fun `hands over to another active trigger instead of turning off`() =
        assertEquals(Action.SwitchTrigger(meeting), AutoModePolicy.decide(listOf(meeting), auto("driving"), null).action)

    @Test fun `user switched it off, same trigger does not turn it back on`() =
        assertEquals(Action.None, AutoModePolicy.decide(listOf(driving), null, "driving").action)

    @Test fun `suppression clears once that trigger is over`() {
        val r = AutoModePolicy.decide(listOf(meeting), null, "driving")
        assertTrue(r.clearSuppression)
        assertEquals(Action.TurnOn(meeting), r.action)
    }

    // 2026-09-24 is a Thursday
    private val weekdays = AutoSchedule(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        LocalTime.of(10, 0), LocalTime.of(18, 0))
    private val bedtime = AutoSchedule(DayOfWeek.entries.toSet(), LocalTime.of(23, 0), LocalTime.of(7, 0))

    @Test fun `day schedule window`() {
        assertEquals(LocalDate.of(2026, 9, 24), weekdays.activeWindowStart(LocalDateTime.of(2026, 9, 24, 12, 0)))
        assertEquals(null, weekdays.activeWindowStart(LocalDateTime.of(2026, 9, 24, 18, 0)))
        assertEquals(null, weekdays.activeWindowStart(LocalDateTime.of(2026, 9, 26, 12, 0))) // Saturday
    }

    @Test fun `overnight schedule belongs to the evening it started`() {
        assertEquals(LocalDate.of(2026, 9, 23), bedtime.activeWindowStart(LocalDateTime.of(2026, 9, 24, 2, 0)))
        assertEquals(LocalDate.of(2026, 9, 24), bedtime.activeWindowStart(LocalDateTime.of(2026, 9, 24, 23, 30)))
        assertEquals(null, bedtime.activeWindowStart(LocalDateTime.of(2026, 9, 24, 8, 0)))
    }

    @Test fun `next boundary`() {
        assertEquals(LocalDateTime.of(2026, 9, 24, 18, 0), weekdays.nextBoundary(LocalDateTime.of(2026, 9, 24, 12, 0)))
        assertEquals(LocalDateTime.of(2026, 9, 28, 10, 0), weekdays.nextBoundary(LocalDateTime.of(2026, 9, 25, 19, 0)))
    }

    @Test fun `schedule encoding round trip and labels`() {
        assertEquals(weekdays, AutoSchedule.decode(weekdays.encode()))
        assertEquals("Weekdays 10:00–18:00", weekdays.label)
        assertEquals("Every day 23:00–07:00", bedtime.label)
    }

    @Test fun mentions() {
        assertTrue(MentionDetector.isMentioned("@Kasi are you coming?", listOf("Kasi")))
        assertTrue(MentionDetector.isMentioned("kasi, reply ra", listOf("Kasi")))
        assertTrue(!MentionDetector.isMentioned("Kasim is here", listOf("Kasi")))
        assertTrue(MentionDetector.isMentioned("anna em chestunnav", listOf("Kasi", "Anna")))
    }
}
