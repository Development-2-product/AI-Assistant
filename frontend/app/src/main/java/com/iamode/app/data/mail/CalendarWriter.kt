package com.iamode.app.data.mail

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.iamode.app.domain.mail.IdempotencyKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

data class CalendarChoice(val id: Long, val name: String, val account: String, val primary: Boolean)

/** Writes approved events to the phone's calendar (which syncs to Google Calendar). */
@Singleton
class CalendarWriter @Inject constructor(@ApplicationContext private val context: Context) {

    sealed interface Result {
        data class Created(val eventId: Long, val existed: Boolean) : Result
        data class Failed(val reason: String) : Result
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Calendars the user can add events to, primary first. */
    suspend fun writableCalendars(): List<CalendarChoice> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val cols = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.IS_PRIMARY,
        )
        val where = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, where,
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { c ->
            buildList { while (c.moveToNext()) add(CalendarChoice(c.getLong(0), c.getString(1) ?: "Calendar", c.getString(2) ?: "", c.getInt(3) == 1)) }
        }.orEmpty().sortedByDescending { it.primary }
    }

    /**
     * Idempotent: the event carries a UID derived from the email + event details, so a retry finds the
     * existing event instead of creating a duplicate.
     */
    suspend fun create(
        calendarId: Long, eventHash: String, title: String, start: ZonedDateTime, end: ZonedDateTime,
        location: String?, description: String?, remindersMinutes: List<Int>,
    ): Result = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext Result.Failed("Calendar permission is needed to add the event")
        val uid = IdempotencyKeys.calendarUid(eventHash)
        existingEvent(uid)?.let { return@withContext Result.Created(it, existed = true) }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, start.zone.id)
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            put(CalendarContract.Events.UID_2445, uid)
            put(CalendarContract.Events.HAS_ALARM, if (remindersMinutes.isEmpty()) 0 else 1)
        }
        val uri = runCatching { context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
            ?: return@withContext Result.Failed("The calendar didn't accept the event")
        val eventId = uri.lastPathSegment?.toLongOrNull() ?: return@withContext Result.Failed("The calendar didn't return an event")
        remindersMinutes.distinct().forEach { minutes ->
            runCatching {
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, minutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }
        }
        Result.Created(eventId, existed = false)
    }

    private fun existingEvent(uid: String): Long? = runCatching {
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.UID_2445} = ? AND ${CalendarContract.Events.DELETED} = 0", arrayOf(uid), null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()
}
