package com.iamode.app.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** When IA Mode should switch itself on (and back off when the reason ends). */
data class AutoModeSettings(
    val whenDriving: Boolean = false,
    val duringMeetings: Boolean = false,
    val schedules: List<AutoSchedule> = emptyList(),
) {
    val anyEnabled: Boolean get() = whenDriving || duringMeetings || schedules.isNotEmpty()
}

/** A weekly window, e.g. weekdays 10:00–18:00. If [end] is not after [start] it runs overnight. */
data class AutoSchedule(val days: Set<DayOfWeek>, val start: LocalTime, val end: LocalTime) {

    val overnight: Boolean get() = !end.isAfter(start)

    /** The date the currently active window started on, or null if [now] is outside every window. */
    fun activeWindowStart(now: LocalDateTime): LocalDate? {
        val today = now.toLocalDate()
        val t = now.toLocalTime()
        return if (!overnight) {
            today.takeIf { now.dayOfWeek in days && !t.isBefore(start) && t.isBefore(end) }
        } else when {
            now.dayOfWeek in days && !t.isBefore(start) -> today
            now.dayOfWeek.minus(1) in days && t.isBefore(end) -> today.minusDays(1)
            else -> null
        }
    }

    /** Next moment after [now] when this schedule starts or ends. */
    fun nextBoundary(now: LocalDateTime): LocalDateTime? {
        val candidates = (0L..7L).flatMap { offset ->
            val day = now.toLocalDate().plusDays(offset)
            if (day.dayOfWeek !in days) emptyList()
            else listOf(day.atTime(start), if (overnight) day.plusDays(1).atTime(end) else day.atTime(end))
        }
        return candidates.filter { it.isAfter(now) }.minOrNull()
    }

    val label: String get() = "${daysLabel(days)} ${start.hhmm()}–${end.hhmm()}"

    /** Stored as "MONDAY,TUESDAY@600-1080". */
    fun encode(): String = days.sortedBy { it.value }.joinToString(",") { it.name } +
        "@${start.toSecondOfDay() / 60}-${end.toSecondOfDay() / 60}"

    companion object {
        fun decode(raw: String): AutoSchedule? = runCatching {
            val (d, range) = raw.split("@")
            val (s, e) = range.split("-").map { LocalTime.ofSecondOfDay(it.toLong() * 60) }
            AutoSchedule(d.split(",").filter { it.isNotBlank() }.map { DayOfWeek.valueOf(it) }.toSet(), s, e)
        }.getOrNull()?.takeIf { it.days.isNotEmpty() }

        fun daysLabel(days: Set<DayOfWeek>): String = when (days) {
            DayOfWeek.entries.toSet() -> "Every day"
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) -> "Weekdays"
            setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
            else -> days.sortedBy { it.value }.joinToString(", ") { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
        }

        private fun LocalTime.hhmm() = "%02d:%02d".format(hour, minute)
    }
}

/** Something happening right now that can keep IA Mode on. [key] identifies this exact occurrence. */
data class AutoTrigger(val key: String, val reason: String)
