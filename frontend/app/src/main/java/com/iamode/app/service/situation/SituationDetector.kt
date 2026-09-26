package com.iamode.app.service.situation

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.iamode.app.core.datastore.PreferenceKeys
import com.iamode.app.core.permissions.AppPermission
import com.iamode.app.core.permissions.AppPermissions
import com.iamode.app.domain.model.Situation
import com.iamode.app.domain.model.SituationStatus
import com.iamode.app.domain.model.VehicleType
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.domain.repository.SituationProvider
import com.iamode.app.service.auto.CalendarReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Works out what the user is doing, in priority order:
 * manual choice > moving (car/bike) > calendar event > game in foreground > night > available.
 * Everything is computed on the device; only the resulting status is sent to the AI.
 */
@Singleton
class SituationDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val store: DataStore<Preferences>,
    private val calendar: CalendarReader,
) : SituationProvider {

    override suspend fun current(): Situation = withContext(Dispatchers.IO) {
        val s = settings.current()
        s.manualSituation?.let { return@withContext Situation(it, "Set by you", manual = true) }

        val prefs = store.data.first()
        val motion = prefs[PreferenceKeys.MOTION]
        val motionAt = prefs[PreferenceKeys.MOTION_AT] ?: 0L
        if (System.currentTimeMillis() - motionAt < 30 * 60_000) {
            when (motion) {
                "VEHICLE" -> return@withContext if (s.vehicleType == VehicleType.BIKE)
                    Situation(SituationStatus.RIDING, "Phone detected you're on the move")
                else Situation(SituationStatus.DRIVING, "Phone detected you're driving")
                "BICYCLE" -> return@withContext Situation(SituationStatus.RIDING, "Phone detected you're riding")
            }
        }

        currentCalendarEvent()?.let { title ->
            val interview = Regex("interview", RegexOption.IGNORE_CASE).containsMatchIn(title)
            return@withContext Situation(if (interview) SituationStatus.INTERVIEW else SituationStatus.MEETING, "Calendar: $title")
        }

        foregroundGame()?.let { label -> return@withContext Situation(SituationStatus.GAMING, "$label is open") }

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val screenOn = context.getSystemService(PowerManager::class.java).isInteractive
        if ((hour >= 23 || hour < 6) && !screenOn) {
            return@withContext Situation(SituationStatus.SLEEPING, "Late night and phone is idle")
        }
        Situation(SituationStatus.AVAILABLE, "Nothing detected")
    }

    private fun currentCalendarEvent(): String? = calendar.current()?.title

    private fun foregroundGame(): String? {
        if (!AppPermissions.isGranted(context, AppPermission.USAGE_ACCESS)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10 * 60_000, now)
        val event = UsageEvents.Event()
        var foreground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> foreground = event.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (event.packageName == foreground) foreground = null
            }
        }
        val pkg = foreground ?: return null
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        val isGame = pkg in KNOWN_GAMES || info.category == ApplicationInfo.CATEGORY_GAME
        return if (isGame) pm.getApplicationLabel(info).toString() else null
    }

    private companion object {
        val KNOWN_GAMES = setOf(
            "com.dts.freefireth", "com.dts.freefiremax", "com.pubg.imobile", "com.tencent.ig",
            "com.activision.callofduty.shooter", "com.supercell.clashofclans",
        )
    }
}
