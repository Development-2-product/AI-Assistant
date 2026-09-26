package com.iamode.app.service.auto

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class CalendarEvent(val id: Long, val title: String, val begin: Long, val end: Long)

/** Timed calendar events marked busy (all-day and "free" events are ignored). */
@Singleton
class CalendarReader @Inject constructor(@ApplicationContext private val context: Context) {

    fun eventsBetween(from: Long, to: Long): List<CalendarEvent> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.AVAILABILITY,
        )
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, CalendarContract.Instances.BEGIN)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val allDay = c.getInt(4) == 1
                        val free = c.getInt(5) == CalendarContract.Events.AVAILABILITY_FREE
                        if (!allDay && !free) {
                            add(CalendarEvent(c.getLong(0), c.getString(1)?.ifBlank { null } ?: "Event", c.getLong(2), c.getLong(3)))
                        }
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun current(now: Long = System.currentTimeMillis()): CalendarEvent? =
        eventsBetween(now, now).firstOrNull { it.begin <= now && it.end > now }

    /** Next event start or end within 24 hours. */
    fun nextBoundary(now: Long = System.currentTimeMillis()): Long? =
        eventsBetween(now, now + DAY).flatMap { listOf(it.begin, it.end) }.filter { it > now }.minOrNull()

    private companion object { const val DAY = 24 * 60 * 60 * 1000L }
}
